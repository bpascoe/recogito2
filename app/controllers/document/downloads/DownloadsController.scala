package controllers.document.downloads

import akka.util.ByteString
import akka.stream.scaladsl.Source
import com.mohiva.play.silhouette.api.Silhouette
import com.mohiva.play.silhouette.api.actions.UserAwareRequest
import controllers.{BaseOptAuthController, Security, HasPrettyPrintJSON}
import controllers.document.downloads.serializers._
import javax.inject.{Inject, Singleton}
import services.{ContentType, RuntimeAccessLevel}
import services.annotation.AnnotationService
import services.document.{ExtendedDocumentMetadata, DocumentService}
import services.folder.FolderService
import services.entity.builtin.EntityService
import services.user.UserService
import org.apache.jena.riot.RDFFormat
import org.webjars.play.WebJarsUtil
import play.api.{Configuration, Logger}
import play.api.libs.json._
import play.api.libs.json.Reads._
import play.api.libs.functional.syntax._
import play.api.libs.iteratee.Enumerator
import play.api.libs.Files.TemporaryFileCreator
import play.api.i18n.I18nSupport
import play.api.mvc.{AnyContent, ControllerComponents, Result}
import play.api.http.{HttpEntity, FileMimeTypes}
import scala.concurrent.{ExecutionContext, Future}
import storage.uploads.Uploads
import storage.es.ES
import java.util.UUID
import scala.concurrent.Await
import scala.concurrent.duration._

case class FieldMapping(
  BASE_URL          : Option[String],
  FIELD_ID          : Int,
  FIELD_TITLE       : Int,
  FIELDS_NAME       : Option[Int],
  FIELD_DESCRIPTION : Option[Int],
  FIELD_COUNTRY     : Option[Int],
  FIELD_LATITUDE    : Option[Int],
  FIELD_LONGITUDE   : Option[Int])
  
object FieldMapping {
  
  implicit val fieldMappingReads: Reads[FieldMapping] = (
    (JsPath \ "base_url").readNullable[String] and
    (JsPath \ "id").read[Int] and
    (JsPath \ "title").read[Int] and
    (JsPath \ "name").readNullable[Int] and
    (JsPath \ "description").readNullable[Int] and
    (JsPath \ "country").readNullable[Int] and
    (JsPath \ "latitude").readNullable[Int] and
    (JsPath \ "longitude").readNullable[Int]
  )(FieldMapping.apply _)
  
}

@Singleton
class DownloadsController @Inject() (
  val components: ControllerComponents,
  val users: UserService,
  val silhouette: Silhouette[Security.Env],
  implicit val config: Configuration,
  implicit val mimeTypes: FileMimeTypes,
  implicit val tmpFile: TemporaryFileCreator,
  implicit val uploads: Uploads,
  implicit val annotations: AnnotationService,
  implicit val documents: DocumentService,
  implicit val folders: FolderService,
  implicit val entities: EntityService,
  implicit val webjars: WebJarsUtil,
  implicit val ctx: ExecutionContext
) extends BaseOptAuthController(components, config, documents, users)
    with annotations.csv.AnnotationsToCSV
    with annotations.csv.AnnotationsToCSVCorpus
    with annotations.oa.AnnotationsToOA
    with annotations.webannotation.AnnotationsToWebAnno
    with annotations.annotationlist.AnnotationsToAnnotationList
    with document.csv.DatatableToCSV
    with document.geojson.DatatableToGazetteer
    with document.tei.PlaintextToTEI
    with document.tei.TEIToTEI
    with document.iob.PlaintextToIOB
    with document.spacy.PlaintextToSpacy
    with places.PlacesToGeoJSON
    with places.AnnotationsToGeoJSON
    with places.PlacesToGeoLPFJSON
    with places.PlacesToKML
    with places.PlacesToKMLByDescription
    with places.CorpusPlacesToKMLByDescription
    with places.PlacesToKMLByAnnotation
    with places.CorpusPlacesToKMLByAnnotation
    with places.PlacesToGeoBrowser
    with relations.RelationsToTriplesCSV
    with relations.RelationsToGephi
    with HasPrettyPrintJSON
    with I18nSupport {
  
  private def download(
    documentId: String, 
    requiredAccessLevel: RuntimeAccessLevel, 
    export: ExtendedDocumentMetadata => Future[Result]
  )(implicit request: UserAwareRequest[Security.Env, AnyContent]) = 
    documentReadResponse(documentId, request.identity, { case (docInfo, userAccessLevel) =>
      if (userAccessLevel >= requiredAccessLevel)
        export(docInfo)
      else
        Future.successful(Forbidden)
    })

  def showDownloadOptions(documentId: String) = silhouette.UserAwareAction.async { implicit request =>
    documentReadResponse(documentId, request.identity, { case (doc, accesslevel) =>
      val fAnnotationCount = annotations.countByDocId(documentId)
      val fAnnotationBounds = entities.getDocumentSpatialExtent(documentId)
      val fHasRelations = annotations.hasRelations(documentId)
      
      val f = for {
        annotationCount <- fAnnotationCount
        annotationBounds <- fAnnotationBounds
        hasRelations <- fHasRelations
      } yield (annotationCount, annotationBounds, hasRelations)
      
      f.map { case (count, bounds, hasRelations) =>
        Ok(views.html.document.downloads.index(doc, request.identity, accesslevel, count, bounds, hasRelations))
      }
    })
  }

  /** Exports either 'plain' annotation CSV, or merges annotations with original DATA_* uploads, if any **/
  def downloadCSV(documentId: String, exportTables: Boolean) = silhouette.UserAwareAction.async { implicit request =>
    documentReadResponse(documentId, request.identity, { case (docInfo, userAccessLevel) =>
      if (exportTables)
        // Merged table export requires READ_ALL privileges
        if (userAccessLevel.canReadAll)
          exportMergedTables(docInfo).map { case (file, filename) =>
            Ok.sendFile(file).withHeaders(CONTENT_DISPOSITION -> { "attachment; filename=" + filename })
          }
        else
          Future.successful(Forbidden)
      else
        // Normal table export only requires READ_DATA privileges
        if (userAccessLevel.canReadData)
          annotationsToCSV(docInfo).map { csv =>
            Ok.sendFile(csv).withHeaders(CONTENT_DISPOSITION -> { "attachment; filename=" + docInfo.title + ".csv" })
          }
        else
          Future.successful(Forbidden)    
    })
  }

  def downloadCSVCorpus(documentId: String, folderId: String, exportTables: Boolean) = silhouette.UserAwareAction.async { implicit request => 
    if (folderId.isEmpty)
      documentReadResponse(documentId, request.identity, { case (docInfo, userAccessLevel) =>
      if (exportTables)
        // Merged table export requires READ_ALL privileges
        if (userAccessLevel.canReadAll)
          exportMergedTables(docInfo).map { case (file, filename) =>
            Ok.sendFile(file).withHeaders(CONTENT_DISPOSITION -> { "attachment; filename=" + filename })
          }
        else
          Future.successful(Forbidden)
      else
        // Normal table export only requires READ_DATA privileges
        if (userAccessLevel.canReadData)
          annotationsToCSV(docInfo).map { csv =>
            Ok.sendFile(csv).withHeaders(CONTENT_DISPOSITION -> { "attachment; filename=" + documentId + ".csv" })
          }
        else
          Future.successful(Forbidden) 
      })
    else {
      if (request.identity.map(_.username) == None) Future.successful(Forbidden)
      else {
      documentReadResponse(documentId, request.identity, { case (docInfo, userAccessLevel) =>
      if (exportTables)
        // Merged table export requires READ_ALL privileges
        if (userAccessLevel.canReadAll)
          exportMergedTables(docInfo).map { case (file, filename) =>
            Ok.sendFile(file).withHeaders(CONTENT_DISPOSITION -> { "attachment; filename=" + filename })
          }
        else
          Future.successful(Forbidden)
      else
        // Normal table export only requires READ_DATA privileges
        if (userAccessLevel.canReadData) {
          val loggedIn = request.identity.map(_.username).get
          var folderName = Await.result(folders.getFolderName(UUID.fromString(folderId)), 2.seconds)
          val docIds = Await.result(documents.listIds(Some(UUID.fromString(folderId)), loggedIn),2.seconds)
          annotationsToCSVCorpus(docIds,loggedIn).map { csv =>
            Ok.sendFile(csv).withHeaders(CONTENT_DISPOSITION -> { "attachment; filename=" + folderName + ".csv" })
          }
        }
        else
          Future.successful(Forbidden)})
      }
    }
  }
  
  private def downloadRDF(documentId: String, format: RDFFormat, extension: String) = silhouette.UserAwareAction.async { implicit request =>
    download(documentId, RuntimeAccessLevel.READ_DATA, { doc =>
      documentToRDF(doc, format).map { file => 
        Ok.sendFile(file).withHeaders(CONTENT_DISPOSITION -> { "attachment; filename=" + documentId + "." + extension })
      }
    })
  }
  
  def downloadTTL(documentId: String) = downloadRDF(documentId, RDFFormat.TTL, "ttl") 
  def downloadRDFXML(documentId: String) = downloadRDF(documentId, RDFFormat.RDFXML, "rdf.xml") 
  
  def downloadJSONLD(documentId: String, flavour: Option[String]) = silhouette.UserAwareAction.async { implicit request =>
    download(documentId, RuntimeAccessLevel.READ_DATA, { doc =>
      flavour match {
        case Some("iiif2") =>
          documentToIIIF2(doc).map { json =>
            Ok(Json.prettyPrint(json))
              // .withHeaders(CONTENT_DISPOSITION -> { "attachment; filename=" + documentId + ".jsonld" })
          }

        case _ =>
          documentToWebAnnotation(doc).map { json =>
            Ok(Json.prettyPrint(json))
              .withHeaders(CONTENT_DISPOSITION -> { "attachment; filename=" + documentId + ".jsonld" })
          }
      }
    })
  }

  def downloadKML(documentId: String, forGeoBrowser: Boolean) = silhouette.UserAwareAction.async { implicit request => 
    download(documentId, RuntimeAccessLevel.READ_DATA, { doc =>
      val fXml = if (forGeoBrowser) placesToGeoBrowser(documentId, doc) else placesToKML(documentId)
      fXml.map { xml =>
        Ok(xml).withHeaders(CONTENT_DISPOSITION -> { s"attachment; filename=${doc.filename.getOrElse(doc.title)}.kml" })
      }
    })
  }

  def downloadKMLByDescription(documentId: String, forGeoBrowser: Boolean) = silhouette.UserAwareAction.async { implicit request => 
    download(documentId, RuntimeAccessLevel.READ_DATA, { doc =>
      val fXml = if (forGeoBrowser) placesToGeoBrowser(documentId, doc) else placesToKMLByDescription(documentId)
      fXml.map { xml =>
        Ok(xml).withHeaders(CONTENT_DISPOSITION -> { s"attachment; filename=${doc.filename.getOrElse(doc.title)}.kml" })
      }
    })
  }

  def downloadKMLCorpusByDescription(documentId: String, folderId: String, forGeoBrowser: Boolean) = silhouette.UserAwareAction.async { implicit request => 
    if (folderId.isEmpty)
      download(documentId, RuntimeAccessLevel.READ_DATA, { doc =>
        val fXml = if (forGeoBrowser) placesToGeoBrowser(documentId, doc) else placesToKMLByDescription(documentId)
        fXml.map { xml =>
          Ok(xml).withHeaders(CONTENT_DISPOSITION -> { s"attachment; filename=${doc.filename.getOrElse(doc.title)}.kml" })
        }
      }) else {
      val loggedIn = request.identity.map(_.username).get
      val owner = loggedIn
      val docIds = Await.result(documents.listIds(Some(UUID.fromString(folderId)), loggedIn),10.seconds)
      download(documentId, RuntimeAccessLevel.READ_DATA, { doc =>
        val fXml = if (forGeoBrowser) placesToGeoBrowser(documentId, doc) else corpusPlacesToKMLByDescription(docIds)
        fXml.map { xml =>
          var folderName = Await.result(folders.getFolderName(UUID.fromString(folderId)), 2.seconds)
          Ok(xml).withHeaders(CONTENT_DISPOSITION -> { s"attachment; filename=${folderName}.kml" })
        }
      })
    }
  }

  def downloadKMLByAnnotation(documentId: String, forGeoBrowser: Boolean) = silhouette.UserAwareAction.async { implicit request => 
    download(documentId, RuntimeAccessLevel.READ_DATA, { doc =>
      val fXml = if (forGeoBrowser) placesToGeoBrowser(documentId, doc) else placesToKMLByAnnotation(documentId)
      fXml.map { xml =>
        Ok(xml).withHeaders(CONTENT_DISPOSITION -> { s"attachment; filename=${doc.filename.getOrElse(doc.title)}.kml" })
      }
    })
  }

  def downloadKMLCorpusByAnnotation(documentId: String, folderId: String, forGeoBrowser: Boolean) = silhouette.UserAwareAction.async { implicit request => 
    if (folderId.isEmpty)
      download(documentId, RuntimeAccessLevel.READ_DATA, { doc =>
        val fXml = if (forGeoBrowser) placesToGeoBrowser(documentId, doc) else placesToKMLByAnnotation(documentId)
        fXml.map { xml =>
          Ok(xml).withHeaders(CONTENT_DISPOSITION -> { s"attachment; filename=${doc.filename.getOrElse(doc.title)}.kml" })
        }
      }) else {
      val loggedIn = request.identity.map(_.username).get
      val owner = loggedIn
      val docIds = Await.result(documents.listIds(Some(UUID.fromString(folderId)), loggedIn),10.seconds)
      download(documentId, RuntimeAccessLevel.READ_DATA, { doc =>
        var folderName = Await.result(folders.getFolderName(UUID.fromString(folderId)), 2.seconds)
        val fXml = if (forGeoBrowser) placesToGeoBrowser(documentId, doc) else corpusPlacesToKMLByAnnotation(folderId, folderName, docIds)
        // Ok(fXml).withHeaders(CONTENT_DISPOSITION -> { s"attachment; filename=${folderName}.kml" })
        fXml.map { xml =>
          Ok(xml).withHeaders(CONTENT_DISPOSITION -> { s"attachment; filename=${folderName}.kml" })
        }
      })
    }
  }

  def downloadGeoJSON(documentId: String, asGazetteer: Boolean) = silhouette.UserAwareAction.async { implicit request =>
    
    // Standard GeoJSON download
    def downloadPlaces() = {
      val filename = Await.result(documents.getDocumentById(documentId),1.seconds).getFilename
      placesToGeoJSON(documentId).map { featureCollection =>
        Ok(Json.prettyPrint(featureCollection))
          .withHeaders(CONTENT_DISPOSITION -> { "attachment; filename=" + filename + ".json" })
      }}
    
    // Places + spreadsheet info, according to Pelagios gazetteer GeoJSON conventions
    def downloadGazetteer(doc: ExtendedDocumentMetadata) = request.body.asFormUrlEncoded.flatMap(_.get("json").flatMap(_.headOption)) match {
      case Some(str) =>
        Json.fromJson[FieldMapping](Json.parse(str)) match {
          case result: JsSuccess[FieldMapping] =>
            exportGeoJSONGazetteer(doc, result.get).map { featureCollection =>
              Ok(Json.prettyPrint(featureCollection))
                .withHeaders(CONTENT_DISPOSITION -> { "attachment; filename=" + documentId + ".json" })
            }
              
          case error: JsError =>
            Logger.warn("Attempt to download gazetteer but field mapping invalid: " + str + "\n" + error)
            Future.successful(BadRequest)
        }
          
      case None =>
        Logger.warn("Attempt to download gazetteer without field mapping payload")
        Future.successful(BadRequest)
    }

     documentReadResponse(documentId, request.identity, { case (docInfo, userAccessLevel) =>
       if (asGazetteer)
         // Download as gazetteer requires READ_ALL privileges
         if (userAccessLevel.canReadAll)
           downloadGazetteer(docInfo)
         else
           Future.successful(Forbidden)
       else
         // GeoJSON download only requires READ_DATA privileges
         if (userAccessLevel.canReadData)
           downloadPlaces
         else
           Future.successful(Forbidden)
     })     
  }


  def downloadGeoJSONAnnotation(documentId: String, asGazetteer: Boolean) = silhouette.UserAwareAction.async { implicit request =>
    
    // Standard GeoJSON download
    def downloadPlaces() = {
      val filename = Await.result(documents.getDocumentById(documentId),1.seconds).getFilename
      placesToGeoJSONAnnotation(documentId).map { featureCollection =>
        Ok(Json.prettyPrint(featureCollection))
          .withHeaders(CONTENT_DISPOSITION -> { "attachment; filename=" + filename + ".json" })
      }}
    
    // Places + spreadsheet info, according to Pelagios gazetteer GeoJSON conventions
    def downloadGazetteer(doc: ExtendedDocumentMetadata) = request.body.asFormUrlEncoded.flatMap(_.get("json").flatMap(_.headOption)) match {
      case Some(str) =>
        Json.fromJson[FieldMapping](Json.parse(str)) match {
          case result: JsSuccess[FieldMapping] =>
            exportGeoJSONGazetteer(doc, result.get).map { featureCollection =>
              Ok(Json.prettyPrint(featureCollection))
                .withHeaders(CONTENT_DISPOSITION -> { "attachment; filename=" + documentId + ".json" })
            }
              
          case error: JsError =>
            Logger.warn("Attempt to download gazetteer but field mapping invalid: " + str + "\n" + error)
            Future.successful(BadRequest)
        }
          
      case None =>
        Logger.warn("Attempt to download gazetteer without field mapping payload")
        Future.successful(BadRequest)
    }

     documentReadResponse(documentId, request.identity, { case (docInfo, userAccessLevel) =>
       if (asGazetteer)
         // Download as gazetteer requires READ_ALL privileges
         if (userAccessLevel.canReadAll)
           downloadGazetteer(docInfo)
         else
           Future.successful(Forbidden)
       else
         // GeoJSON download only requires READ_DATA privileges
         if (userAccessLevel.canReadData)
           downloadPlaces
         else
           Future.successful(Forbidden)
     })     
  }

  def downloadGeoJSONByCorpus(documentId: String, folderId: String, asGazetteer: Boolean) = silhouette.UserAwareAction.async { implicit request =>
    
    // Standard GeoJSON download
    def downloadPlaces() =
      if (folderId.isEmpty) {
        val filename = Await.result(documents.getDocumentById(documentId),1.seconds).getFilename
      placesToGeoJSON(documentId).map { featureCollection =>
        Ok(Json.prettyPrint(featureCollection))
          .withHeaders(CONTENT_DISPOSITION -> { "attachment; filename=" + filename + ".json" })
      }} else {
        val loggedIn = request.identity.map(_.username).get
        var folderName = Await.result(folders.getFolderName(UUID.fromString(folderId)), 1.seconds)
        val docIds = Await.result(documents.listIds(Some(UUID.fromString(folderId)), loggedIn),1.seconds)
        placesToGeoJSONCorpus(docIds).map { featureCollection =>
        Ok(Json.prettyPrint(featureCollection))
          .withHeaders(CONTENT_DISPOSITION -> { "attachment; filename=" + folderName + ".json" })
        }
      }
    
    // Places + spreadsheet info, according to Pelagios gazetteer GeoJSON conventions
    def downloadGazetteer(doc: ExtendedDocumentMetadata) = request.body.asFormUrlEncoded.flatMap(_.get("json").flatMap(_.headOption)) match {
      case Some(str) =>
        Json.fromJson[FieldMapping](Json.parse(str)) match {
          case result: JsSuccess[FieldMapping] =>
            exportGeoJSONGazetteer(doc, result.get).map { featureCollection =>
              Ok(Json.prettyPrint(featureCollection))
                .withHeaders(CONTENT_DISPOSITION -> { "attachment; filename=" + documentId + ".json" })
            }
              
          case error: JsError =>
            Logger.warn("Attempt to download gazetteer but field mapping invalid: " + str + "\n" + error)
            Future.successful(BadRequest)
        }
          
      case None =>
        Logger.warn("Attempt to download gazetteer without field mapping payload")
        Future.successful(BadRequest)
    }

     documentReadResponse(documentId, request.identity, { case (docInfo, userAccessLevel) =>
       if (asGazetteer)
         // Download as gazetteer requires READ_ALL privileges
         if (userAccessLevel.canReadAll)
           downloadGazetteer(docInfo)
         else
           Future.successful(Forbidden)
       else
         // GeoJSON download only requires READ_DATA privileges
         if (userAccessLevel.canReadData)
           downloadPlaces
         else
           Future.successful(Forbidden)
     })     
  }

  def downloadGeoJSONAnnotationByCorpus(documentId: String, folderId: String, asGazetteer: Boolean) = silhouette.UserAwareAction.async { implicit request =>
    
    // Standard GeoJSON download
    def downloadPlaces() =
      if (folderId.isEmpty) {
        val filename = Await.result(documents.getDocumentById(documentId),1.seconds).getFilename
      placesToGeoJSONAnnotation(documentId).map { featureCollection =>
        Ok(Json.prettyPrint(featureCollection))
          .withHeaders(CONTENT_DISPOSITION -> { "attachment; filename=" + filename + ".json" })
      }} else {
        val loggedIn = request.identity.map(_.username).get
        var folderName = Await.result(folders.getFolderName(UUID.fromString(folderId)), 1.seconds)
        val docIds = Await.result(documents.listIds(Some(UUID.fromString(folderId)), loggedIn),1.seconds)
        placesToGeoJSONAnnotationCorpus(docIds).map { featureCollection =>
        Ok(Json.prettyPrint(featureCollection))
          .withHeaders(CONTENT_DISPOSITION -> { "attachment; filename=" + folderName + ".json" })
        }
      }
    
    // Places + spreadsheet info, according to Pelagios gazetteer GeoJSON conventions
    def downloadGazetteer(doc: ExtendedDocumentMetadata) = request.body.asFormUrlEncoded.flatMap(_.get("json").flatMap(_.headOption)) match {
      case Some(str) =>
        Json.fromJson[FieldMapping](Json.parse(str)) match {
          case result: JsSuccess[FieldMapping] =>
            exportGeoJSONGazetteer(doc, result.get).map { featureCollection =>
              Ok(Json.prettyPrint(featureCollection))
                .withHeaders(CONTENT_DISPOSITION -> { "attachment; filename=" + documentId + ".json" })
            }
              
          case error: JsError =>
            Logger.warn("Attempt to download gazetteer but field mapping invalid: " + str + "\n" + error)
            Future.successful(BadRequest)
        }
          
      case None =>
        Logger.warn("Attempt to download gazetteer without field mapping payload")
        Future.successful(BadRequest)
    }

     documentReadResponse(documentId, request.identity, { case (docInfo, userAccessLevel) =>
       if (asGazetteer)
         // Download as gazetteer requires READ_ALL privileges
         if (userAccessLevel.canReadAll)
           downloadGazetteer(docInfo)
         else
           Future.successful(Forbidden)
       else
         // GeoJSON download only requires READ_DATA privileges
         if (userAccessLevel.canReadData)
           downloadPlaces
         else
           Future.successful(Forbidden)
     })     
  }
// download user-added places
  def downloadLPFContribution(identifier: String, asGazetteer: Boolean) = silhouette.UserAwareAction.async { implicit request =>
    val documentId = identifier //
    // Standard GeoJSON download
    def downloadPlaces() =
      placesToGeoLPFJSON(ES.CONTRIBUTION).map { featureCollection =>
        Ok(Json.prettyPrint(featureCollection))
          .withHeaders(CONTENT_DISPOSITION -> { "attachment; filename=contribution.lpf.json" })
      }
    
    // Places + spreadsheet info, according to Pelagios gazetteer GeoJSON conventions
    def downloadGazetteer(doc: ExtendedDocumentMetadata) = request.body.asFormUrlEncoded.flatMap(_.get("json").flatMap(_.headOption)) match {
      case Some(str) =>
        Json.fromJson[FieldMapping](Json.parse(str)) match {
          case result: JsSuccess[FieldMapping] =>
            exportGeoJSONGazetteer(doc, result.get).map { featureCollection =>
              Ok(Json.prettyPrint(featureCollection))
                .withHeaders(CONTENT_DISPOSITION -> { "attachment; filename=contribution.lpf.json" })
            }
              
          case error: JsError =>
            Logger.warn("Attempt to download gazetteer but field mapping invalid: " + str + "\n" + error)
            Future.successful(BadRequest)
        }
          
      case None =>
        Logger.warn("Attempt to download gazetteer without field mapping payload")
        Future.successful(BadRequest)
    }

     documentReadResponse(documentId, request.identity, { case (docInfo, userAccessLevel) =>
       if (asGazetteer)
         // Download as gazetteer requires READ_ALL privileges
         if (userAccessLevel.canReadAll)
           downloadGazetteer(docInfo)
         else
           Future.successful(Forbidden)
       else
         // GeoJSON download only requires READ_DATA privileges
         if (userAccessLevel.canReadData)
           downloadPlaces
         else
           Future.successful(Forbidden)
     })     
  }

  def downloadRelationTriples(documentId: String) = silhouette.UserAwareAction.async { implicit request => 
    relationsToTriplesCSV(documentId).map { file =>
      Ok.sendFile(file).withHeaders(CONTENT_DISPOSITION -> { "attachment; filename=" + documentId + "_relations.csv" })
    }
  }
  
  def downloadGephiNodes(documentId: String) = silhouette.UserAwareAction.async { implicit request =>
    relationsToGephiNodes(documentId).map { file =>
      Ok.sendFile(file).withHeaders(CONTENT_DISPOSITION -> { "attachment; filename=" + documentId + "_nodes.csv" })    
    }
  }
  
  def downloadGephiEdges(documentId: String) = silhouette.UserAwareAction.async { implicit request =>
    relationsToGephiEdges(documentId).map { file =>
      Ok.sendFile(file).withHeaders(CONTENT_DISPOSITION -> { "attachment; filename=" + documentId + "_edges.csv" })    
    }
  }
  
  def downloadTEI(documentId: String) = silhouette.UserAwareAction.async { implicit request =>
    download(documentId, RuntimeAccessLevel.READ_ALL, { doc =>
      val contentTypes = doc.fileparts.flatMap(pt => ContentType.withName(pt.getContentType)).distinct
      
      // At the moment, we only support TEI download for documents that are either plaintext- or TEI-only
      if (contentTypes.size != 1) {
        Future.successful(BadRequest("unsupported document content type(s)"))
      } else if (!contentTypes.head.isText) {
        Future.successful(BadRequest("unsupported document content type(s)"))
      } else {
        val f = contentTypes.head match {
          case ContentType.TEXT_PLAIN => plaintextToTEI(doc)
          case ContentType.TEXT_TEIXML => teiToTEI(doc)
          case _ => throw new Exception // Can't happen under current conditions (and should fail in the future if things go wrong)
        }
        
        f.map { xml => 
          Ok(xml).withHeaders(CONTENT_DISPOSITION -> { s"attachment; filename=${documentId}.tei.xml" })
        }
      }
    })
  }

  def downloadIOB(documentId: String) = silhouette.UserAwareAction.async { implicit request => 
    download(documentId, RuntimeAccessLevel.READ_ALL, { doc => 
      // At the moment we only support IOB for single-file plaintext documents
      if (doc.fileparts.size != 1) {
        Future.successful(BadRequest("not supported for multi-part documents"))
      } else if (doc.fileparts.head.getContentType != ContentType.TEXT_PLAIN.toString) {
        Future.successful(BadRequest("unsupported content type"))
      } else {
        plaintextToIOB(doc).map { f => 
          Ok.sendFile(f).withHeaders(CONTENT_DISPOSITION -> { s"attachment; filename=${documentId}.iob.txt" })
        }
      }
    })
  }

  def downloadSpacy(documentId: String) = silhouette.UserAwareAction.async { implicit request => 
    download(documentId, RuntimeAccessLevel.READ_ALL, { doc =>
      // At the moment we only support Spacy for single-file plaintext documents
      if (doc.fileparts.size != 1) {
        Future.successful(BadRequest("not supported for multi-part documents"))
      } else if (doc.fileparts.head.getContentType != ContentType.TEXT_PLAIN.toString) {
        Future.successful(BadRequest("unsupported content type"))
      } else {
        plaintextToSpacy(doc).map { json => 
          jsonOk(json)
        }
      }
    })
  }

}
