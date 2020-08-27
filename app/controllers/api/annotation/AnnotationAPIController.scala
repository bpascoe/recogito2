package controllers.api.annotation

import com.mohiva.play.silhouette.api.Silhouette
import com.mohiva.play.silhouette.api.actions.UserAwareRequest
import controllers._
import controllers.api.annotation.stubs._
import java.io.File
import java.util.UUID
import javax.inject.{Inject, Singleton}
import org.joda.time.{DateTime,DateTimeZone}
import play.api.{Configuration, Logger}
import play.api.http.FileMimeTypes
import play.api.mvc.{AnyContent, ControllerComponents, Request, Result}
import play.api.libs.Files.TemporaryFileCreator
import play.api.libs.json._
import play.api.libs.functional.syntax._
import scala.concurrent.{ExecutionContext, Future}
import services.{ContentType, HasDate, RuntimeAccessLevel}
import services.annotation._
import services.annotation.relation._
import services.contribution._
import services.document.{DocumentService, ExtendedDocumentMetadata}
import services.user.UserService
import services.image.ImageService
import services.generated.tables.records.{DocumentRecord, DocumentFilepartRecord}
import storage.uploads.Uploads

import services.entity.builtin.importer.EntityImporterFactory
import services.entity.{EntityType, EntityRecord, Name,CountryCode, LinkType, Link, Description,TemporalBounds}
import services.entity.builtin.importer.crosswalks.geojson.lpf.LPFCrosswalk
import services.annotation.Annotation
import storage.es.ES
import services.entity.builtin.EntityService
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import com.vividsolutions.jts.geom.{Coordinate, GeometryFactory}
import java.sql.Timestamp
import java.text.SimpleDateFormat
import java.time.format.DateTimeFormatter
import java.time.ZonedDateTime

import java.nio.file.Paths
import kantan.csv.CsvConfiguration
import kantan.csv.CsvConfiguration.{Header, QuotePolicy}
import kantan.csv.ops._
import play.api.Configuration
import storage.TempDir

@Singleton
class AnnotationAPIController @Inject() (
  val components: ControllerComponents,
  val contributions: ContributionService,
  val documents: DocumentService,
  val silhouette: Silhouette[Security.Env],
  val users: UserService,
  val importerFactory: EntityImporterFactory,
  val entity: EntityService,
  implicit val annotationService: AnnotationService,
  implicit val config: Configuration,
  implicit val mimeTypes: FileMimeTypes,
  implicit val tmpFile: TemporaryFileCreator,
  implicit val uploads: Uploads,
  implicit val ctx: ExecutionContext
) extends BaseController(components, config, users)
    with HasPrettyPrintJSON
    with HasTextSnippets
    with HasTEISnippets
    with HasCSVParsing 
    with helpers.AnnotationValidator
    with helpers.ContributionHelper {

  // Frontent serialization format
  import services.annotation.FrontendAnnotation._

  def listAnnotationsInDocument(docId: String) = listAnnotations(docId, None)

  def listAnnotationsInPart(docId: String, partNo: Int) = listAnnotations(docId, Some(partNo))

  private def listAnnotations(docId: String, partNo: Option[Int]) = silhouette.UserAwareAction.async { implicit request =>
    // TODO currently annotation read access is unrestricted - do we want that?
    (docId, partNo) match {
      case (id, Some(seqNo)) =>
        // Load annotations for specific doc part
        documents.findPartByDocAndSeqNo(id, seqNo).flatMap {
          case Some(filepart) =>
            annotationService.findByFilepartId(filepart.getId)
              .map { annotations =>
                // Join in places, if requested
                jsonOk(Json.toJson(annotations.map(_._1)))
              }

          case None =>
            Future.successful(NotFoundPage)
        }

      case (id, None) =>
        // Load annotations for entire doc
        annotationService.findByDocId(id).map(annotations => jsonOk(Json.toJson(annotations.map(_._1))))
    }
  }

  /** Common boilerplate code for all API methods carrying JSON config payload **/
  private def jsonOp[T](op: T => Future[Result])(implicit request: UserAwareRequest[Security.Env, AnyContent], reads: Reads[T]) = {
    request.identity match {
      case Some(user) => 
        request.body.asJson match {
          case Some(json) =>
            Json.fromJson[T](json) match {
              case s: JsSuccess[T]  => op(s.get)
              case e: JsError =>
                Logger.warn("Call to annotation API but invalid JSON: " + e.toString)
                Future.successful(BadRequest)
            }
          case None =>
            Logger.warn("Call to annotation API but no JSON payload")
            Future.successful(BadRequest)
        }

      case None =>
        Future.successful(Forbidden)
    }
  }

  def createAnnotation() = silhouette.UserAwareAction.async { implicit request => jsonOp[AnnotationStub] { annotationStub =>
    val username = request.identity.get.username

    // Fetch the associated document to check access privileges
    documents.getDocumentRecordById(annotationStub.annotates.documentId, Some(username)).flatMap(_ match {
      case Some((document, accesslevel)) => {
        if (accesslevel.canWrite) {
          val annotation = annotationStub.toAnnotation(username)
          val f = for {
            previousVersion <- annotationService.findById(annotation.annotationId).map(_.map(_._1))

            isValidUpdate <- isValidUpdate(annotation, previousVersion)

            if (isValidUpdate)
            
            annotationStored <- annotationService.upsertAnnotation(annotation)

            // contributionStored <- contributions.upsertContriGaze(computeContributions(annotation, previousVersion, document))

            success <- if (annotationStored)
                         contributions.insertContributions(computeContributions(annotation, previousVersion, document))
                       else
                         Future.successful(false)
            
          } yield success
          // User add a place to Contribution gazetteer
          upsertContriGaze(annotation)
          f.map(success => if (success) Ok(Json.toJson(annotation)) else InternalServerError)
        } else {
          // No write permissions
          Future.successful(Forbidden)
        }
      }

      case None =>
        Logger.warn("POST to /annotations but annotation points to unknown document: " + annotationStub.annotates.documentId)
        Future.successful(NotFound)
    })
  }}

  def loadCsvMetadata() = silhouette.UserAwareAction.async { implicit request =>
    request.body.asJson match {
      case Some(json) => {
        val username = request.identity.get.username
        val filename = (json \ "Filename").as[String]
        if (filename.length>3) {
          val folderId = (json \ "folderId").as[String]
          val docId = if (folderId.length>3) {Await.result(documents.getDocIdByTitle(filename,username, Some(UUID.fromString(folderId))), 1.seconds)
          } else {Await.result(documents.getDocIdByTitle(filename,username, None), 1.seconds)}
          if (docId != null) {
            val fromVal  = (json \ "StartDate").asOpt[Int].getOrElse(99999)
            val toVal  = (json \ "EndDate").asOpt[Int].getOrElse(99999)
            val from = if (fromVal == 99999) {null} else { new DateTime(DateTimeZone.UTC).withDate(fromVal, 1, 1).withTime(0, 0, 0, 0)}
            val to = if (toVal == 99999) {null} else { new DateTime(DateTimeZone.UTC).withDate(toVal, 1, 1).withTime(0, 0, 0, 0)}
            val temporal_bounds = if (from == 99999 && to == 99999) {None} else { Some(new TemporalBounds(from, to))}
            val title  = (json \ "Title").asOpt[String]
            val author  = (json \ "Author").asOpt[String]
            val description  = (json \ "Description").asOpt[String]
            val language  = (json \ "Language").asOpt[String]
            val source  = (json \ "Source").asOpt[String]
            val edition  = (json \ "Edition").asOpt[String]
            val license  = (json \ "License").asOpt[String]
            val attribution  = (json \ "Attribution").asOpt[String]
            val pubPlace  = (json \ "PublicationPlace").asOpt[String]
            val startDate = (json \ "StartDate").asOpt[String]
            val endDate  = (json \ "EndDate").asOpt[String]
            val latitude  = (json \ "Latitude").asOpt[String]
            val longitude  = (json \ "Longitude").asOpt[String]
            // val format = DateTimeFormatter.ofPattern("dd/MM/yyyy")//' 'HH:mm:ss
            // val startDate = if ((json \ "StartDate").as[String].length<1) {null} else {Some(new Timestamp(new SimpleDateFormat("dd/MM/yyyy").parse((json \ "StartDate").as[String]).getTime()))}
            // val endDate = if ((json \ "EndDate").as[String].length<1) {null} else {Some(new Timestamp(new SimpleDateFormat("dd/MM/yyyy").parse((json \ "EndDate").as[String]).getTime()))}
            val row = documents.updateMetadata2(docId,title,author,description,language,source,edition,license,attribution,pubPlace,startDate,endDate,latitude,longitude)
            // val entities = entity.listIndexedEntitiesInDocument(docId, Some(EntityType.PLACE))
            val entities = entity.listIndexedEntitiesInDocument(docId, Some(EntityType.PLACE)).map { result =>
              result.map(e=>e.copy(e.entity.copy(temporalBoundsUnion=temporal_bounds)))
            }
            entity.upsertEntities(Await.result(entities, 10.seconds))
            Future.successful(Ok(filename+" success"))
          } else Future.successful(Ok(filename+" not in the current corpus"))
        } else Future.successful(Ok("Empty filename"))
      }
      case None => {
        Future.successful(Ok("Empty CSV file"))}
    }
  }
  def downloadCsvMetadata() = silhouette.UserAwareAction.async { implicit request =>
    val username = request.identity.get.username
    request.body.asJson match {
      case Some(json) => {
        val folderId = (json \ "folderId").as[String]
        val docs = if (folderId.length>3) {Await.result(documents.getDocsInFolder(username, Some(UUID.fromString(folderId))), 1.seconds)
        } else {Await.result(documents.getDocsInFolder(username, None), 1.seconds)}
        val file = scala.concurrent.blocking {
          val header = Seq("Filename", "Title", "Author", "Description", "Language", "Source", "Edition", "License", "Attribution", "StartDate", "EndDate", "PublicationPlace", "Latitude", "Longitude")
          val tmp = tmpFile.create(Paths.get(TempDir.get(), s"${username}.csv"))
          val underlying = tmp.path.toFile
          val configs = CsvConfiguration(',', '"', QuotePolicy.Always, Header.Explicit(header))
          // val toStr: String => String = str =>  if (s == null) "" else str
          def toStr (r: String) = {
            if (r == null) "" else r.asInstanceOf[String]
          }
          val writer = underlying.asCsvWriter[Seq[String]](configs)
          docs.map { doc => 
            // Future.successful(OK(item))
            // val doc = item.into(classOf[DocumentRecord])
            val row = Seq(toStr(doc.getFilename),toStr(doc.getTitle), toStr(doc.getAuthor), toStr(doc.getDescription), toStr(doc.getLanguage), toStr(doc.getSource), toStr(doc.getEdition), toStr(doc.getLicense), toStr(doc.getAttribution), toStr(doc.getStartDate), toStr(doc.getEndDate), toStr(doc.getPublicationPlace), toStr(doc.getLatitude), toStr(doc.getLongitude))
            // val row =Seq(toStr(doc.getFilename))
            writer.write(row)
          }
          writer.close() 
          underlying
         }

        Future.successful(Ok.sendFile(file).withHeaders(CONTENT_DISPOSITION -> { "attachment; filename=" + username + ".csv" }))
        }
      case None => {
        Logger.warn("Need necessary information in uploaded CSV file")
        Future.successful(Ok("nothing"))}
      }
  }
// add to user-added contribution gazetteer (search in the gazetteer list)
  def upsertContriGaze(annotation: Annotation) = {
    val importer = importerFactory.createImporter(EntityType.PLACE)
    val entityURIs = annotation.bodies.flatMap(_.uri).mkString(" ")
    if (entityURIs.trim.nonEmpty) {
      val place = Await.result(entity.findByURI(entityURIs), 1.seconds).get.entity
      place.isConflationOf.map(record=>
        importer.importRecord(record.copy(sourceAuthority = ES.CONTRIBUTION, title = annotation.bodies.flatMap(_.value).mkString(" "),lastSyncedAt=DateTime.now(),lastChangedAt=Some(DateTime.now()))))
    }
  }
// add new place to user-added contribution gazetteer (not exist ever)
  def createPlace() = silhouette.UserAwareAction.async { implicit request =>
    request.body.asJson match {
      case Some(json) => {
        val importer = importerFactory.createImporter(EntityType.PLACE)
        val norURI = EntityRecord.normalizeURI((json \ "uri").as[String])
        val title = (json \ "title").as[String]
        val lat = (json \ "lat").as[Double]
        val lon = (json \ "lon").as[Double]
        val coord = new Coordinate(lon, lat)
        val point = new GeometryFactory().createPoint(coord)
        val time = DateTime.now()
        val from  = (json \ "from").asOpt[String].getOrElse("")
        val to  = (json \ "to").asOpt[String].getOrElse("")
        val temporal_bounds = if (from == "" || to == "") {None} else { 
          val fromvalues = from.split("/") // dd/mm/yy
          val tovalues = to.split("/") 
          Some(new TemporalBounds(new DateTime(DateTimeZone.UTC).withDate(fromvalues(2).toInt, fromvalues(1).toInt, fromvalues(0).toInt).withTime(0, 0, 0, 0), new DateTime(DateTimeZone.UTC).withDate(tovalues(2).toInt, tovalues(1).toInt, tovalues(0).toInt).withTime(0, 0, 0, 0)))
        }
        val ccode  = (json \ "ccode").asOpt[String].getOrElse("")
        val ccode2 = if (ccode.size == 2) {Some(new CountryCode(ccode))} else {None}
        val description  = (json \ "description").asOpt[String].getOrElse("")
        val description2 = if (description.size > 0) {Seq(new Description(description))} else {Seq.empty[Description]}
        val altNames  = (json \ "altNames").asOpt[String].getOrElse("")
        val altNames2 = if (altNames.size > 0) {Seq(new Name(altNames))} else {Seq.empty[Name]}
        
        // val description = new Description((json \ "description").as[String])
        // Seq(description)
        val record = EntityRecord(norURI,ES.CONTRIBUTION,time,Some(time),title,description2,altNames2,Some(point),Some(coord),ccode2,temporal_bounds,Seq.empty[String],None,Seq.empty[Link])
        // val record = EntityRecord(norURI,ES.CONTRIBUTION,time,Some(time),title,Seq.empty[Description],Seq(Name(title)),Some(point),Some(coord),None,None,Seq.empty[String],None,Seq.empty[Link])
        importer.importRecord(record)
        Future.successful(Ok("Success"))
        // Future {Ok("success")}
      }
      case None =>
        // Logger.warn("Need to fill all necessary information")
        Future.successful(BadRequest)
    }
  }

  def bulkUpsert() = silhouette.UserAwareAction.async { implicit request => jsonOp[Seq[AnnotationStub]] { annotationStubs =>
    // We currently restrict to bulk upserts for a single document part only
    val username = request.identity.get.username
    val documentIds = annotationStubs.map(_.annotates.documentId).distinct
    val partIds = annotationStubs.map(_.annotates.filepartId).distinct

    if (documentIds.size == 1 || partIds.size == 1) {
      documents.getExtendedMeta(documentIds.head, Some(username)).flatMap {
        case Some((doc, accesslevel)) =>
          if (accesslevel.canWrite) {
            doc.fileparts.find(_.getId == partIds.head) match {
              case Some(filepart) =>
                val annotations = annotationStubs.map(_.toAnnotation(username))
                val ids = annotations.map(_.annotationId)

                def isValidBulkUpdate(previous: Seq[Option[Annotation]]) =
                  Future.sequence {
                    annotations.zip(previous)
                      .map { t => isValidUpdate(t._1, t._2) }
                  } map { ! _.exists(_ == false) }

                val f = for {
                  previousVersions <- annotationService.findByIds(ids)   

                  isValidBulkUpdate <- isValidBulkUpdate(previousVersions)               

                  if (isValidBulkUpdate)
                  
                  failed <- annotationService.upsertAnnotations(annotations)
                } yield failed
                // createPlace()
                // for (annotation <- annotations) upsertContriGaze(annotation)

                f.map { failed =>
                  if (failed.size == 0)
                    // TODO add username and timestamp
                    jsonOk(Json.toJson(annotations))
                  else
                    InternalServerError
                }

              case None =>
                Logger.warn("Bulk upsert with invalid content: filepart not in document: " + documentIds.head + "/" + partIds.head)
                Future.successful(BadRequest)
            }
          } else {
            // No write permissions
            Future.successful(Forbidden)
          }

        case None =>
          Logger.warn("Bulk upsert request points to unknown document: " + documentIds.head)
          Future.successful(NotFound)
      }
    } else {
      Logger.warn("Bulk upsert request for multiple document parts")
      Future.successful(BadRequest)
    }
  }}

  def getImage(id: UUID) = silhouette.UserAwareAction.async { implicit request =>
    val username = request.identity.map(_.username)
    annotationService.findById(id).flatMap {
      case Some((annotation, _)) =>
        val docId = annotation.annotates.documentId
        val partId = annotation.annotates.filepartId

        documents.getExtendedMeta(docId, username).flatMap {
          case Some((doc, accesslevel)) =>
            doc.fileparts.find(_.getId == partId) match {
              case Some(filepart) =>
                if (accesslevel.canReadAll)
                  ContentType.withName(filepart.getContentType) match {
                    case Some(ContentType.IMAGE_UPLOAD) =>
                      ImageService.cutout(doc, filepart, annotation).map { file =>
                        Ok.sendFile(file)
                      }     
                      
                    case Some(ContentType.IMAGE_IIIF) =>
                      val snippetUrl = ImageService.iiifSnippet(doc, filepart, annotation)
                      Future.successful(Redirect(snippetUrl))
                      
                    case _ => // Should never happen
                      Future.successful(InternalServerError)
                  }
                else
                  Future.successful(Forbidden)

              case None =>
                Logger.error(s"Attempted to render image for annotation $id but filepart $partId not found")
                Future.successful(InternalServerError)
            }

          case None =>
            // Annotation exists, but not the doc?
            Logger.error(s"Attempted to render image for annotation $id but document $docId not found")
            Future.successful(InternalServerError)
        }

      case None => Future.successful(NotFound)
    }
  }

  def getAnnotation(id: UUID, includeContext: Boolean) = silhouette.UserAwareAction.async { implicit request =>
    def getTextContext(doc: ExtendedDocumentMetadata, part: DocumentFilepartRecord, annotation: Annotation): Future[JsValue] =
      uploads.readTextfile(doc.ownerName, doc.id, part.getFile) map {
        case Some(text) =>
          val snippet = ContentType.withName(part.getContentType).get match {
            case ContentType.TEXT_TEIXML => snippetFromTEI(text, annotation.anchor)
            case ContentType.TEXT_PLAIN => snippetFromText(text, annotation)
            case _ => throw new RuntimeException("Attempt to retrieve text snippet for non-text doc part - should never happen")
          }

          Json.obj("snippet" -> snippet.text, "char_offset" -> snippet.offset, "fileName" -> part.getTitle)

        case None =>
          Logger.warn("No text content found for filepart " + annotation.annotates.filepartId)
          JsNull
      }

    def getDataContext(doc: ExtendedDocumentMetadata, part: DocumentFilepartRecord, annotation: Annotation): Future[JsValue] = {
      uploads.getDocumentDir(doc.ownerName, doc.id).map { dir =>
        extractLine(new File(dir, part.getFile), annotation.anchor.substring(4).toInt).map(snippet => Json.toJson(snippet))
      } getOrElse {
        Logger.warn("No file content found for filepart " + annotation.annotates.filepartId)
        Future.successful(JsNull)
      }
    }

    def getContext(doc: ExtendedDocumentMetadata, annotation: Annotation): Future[JsValue] = annotation.annotates.contentType match {
      case t if t.isImage => Future.successful(JsNull)

      case t if t.isText | t.isData => doc.fileparts.find(_.getId == annotation.annotates.filepartId) match {
        case Some(part) =>
          if (t.isText) getTextContext(doc, part, annotation)
          else getDataContext(doc, part, annotation)

        case None =>
          // Annotation referenced a part ID that's not in the database
          Logger.error("Annotation points to filepart " + annotation.annotates.filepartId + " but not in DB")
          Future.successful(JsNull)
      }

      case _ =>
        Logger.error("Annotation indicates unsupported content type " + annotation.annotates.contentType)
        Future.successful(JsNull)
    }

    annotationService.findById(id).flatMap {
      case Some((annotation, _)) => {
        if (includeContext) {
          documents.getExtendedMeta(annotation.annotates.documentId, request.identity.map(_.username)).flatMap {
            case Some((doc, accesslevel)) =>
              if (accesslevel.canReadData)
                getContext(doc, annotation).map(context =>
                  jsonOk(Json.toJson(annotation).as[JsObject] ++ Json.obj("context" -> context)))
              else
                Future.successful(ForbiddenPage)

            case _ =>
              Logger.warn("Annotation points to document " + annotation.annotates.documentId + " but not in DB")
              Future.successful(NotFoundPage)
          }
        } else {
          Future.successful(jsonOk(Json.toJson(annotation)))
        }
      }

      case None => Future.successful(NotFoundPage)
    }
  }

  private def createDeleteContribution(annotation: Annotation, document: DocumentRecord, user: String, time: DateTime) =
    Contribution(
      ContributionAction.DELETE_ANNOTATION,
      user,
      time,
      Item(
        ItemType.ANNOTATION,
        annotation.annotates.documentId,
        document.getOwner,
        Some(annotation.annotates.filepartId),
        Some(annotation.annotates.contentType),
        Some(annotation.annotationId),
        None, None, None
      ),
      annotation.contributors,
      getContext(annotation)
    )

  def deleteAnnotation(id: UUID) = silhouette.SecuredAction.async { implicit request =>
    val username = request.identity.username
    annotationService.findById(id).flatMap {
      case Some((annotation, version)) =>
        // Fetch the associated document
        documents.getDocumentRecordById(annotation.annotates.documentId, Some(username)).flatMap {
          case Some((document, accesslevel)) => {
            if (accesslevel.canWrite) {
              val now = DateTime.now
              annotationService.deleteAnnotation(id, username, now).flatMap {
                case Some(annotation) =>
                  contributions.insertContribution(createDeleteContribution(annotation, document, username, now)).map(success =>
                    if (success) Status(200) else InternalServerError)

                case None =>
                  Future.successful(NotFoundPage)
              }
            } else {
              Future.successful(ForbiddenPage)
            }
          }

          case None => {
            // Annotation on a non-existing document? Can't happen except DB integrity is broken
            Logger.warn(s"Annotation points to document ${annotation.annotates.documentId} but not in DB")
            Future.successful(InternalServerError)
          }
        }

      case None => Future.successful(NotFoundPage)
    }
  }
  
  def bulkDelete() = silhouette.UserAwareAction.async { implicit request => jsonOp[Seq[UUID]] { ids =>
    val username = request.identity.get.username
    val now = DateTime.now
    
    // Shorthand for readability
    def getDocumentIds(affectedAnnotations: Seq[Option[(Annotation, Long)]]) =
      affectedAnnotations.flatten.map(_._1.annotates.documentId)
      
    // Deletes one annotation after checking access permissions
    def deleteOne(annotation: Annotation, docsAndPermissions: Seq[(ExtendedDocumentMetadata, RuntimeAccessLevel)]) = {
      val hasWritePermissions = docsAndPermissions
        .find(_._1.id == annotation.annotates.documentId)
        .map(_._2.canWrite)
        .getOrElse(false)
        
      if (hasWritePermissions) {
        annotationService
          .deleteAnnotation(annotation.annotationId, username, now)
          .map(_ => true) // True if delete is successful
          .recover { case t: Throwable =>
            t.printStackTrace()
            Logger.error(s"Something went wrong while batch-deleting annotation ${annotation.annotationId}")
            Logger.error(t.toString)
            false // False in case stuff breaks 
          } 
      } else {
        Logger.warn(s"Prevented malicious batch-delete attempt by user ${username}")
        Future.successful(false)
      }
    }
    
    // Fetch affected annotations and documents
    val f = for {
      affectedAnnotations <- Future.sequence(ids.map(annotationService.findById))
      affectedDocuments <- Future.sequence { 
        getDocumentIds(affectedAnnotations).map { docId => 
          documents.getExtendedMeta(docId, Some(username))
        }
      }
    } yield (affectedAnnotations.flatten.map(_._1), affectedDocuments.flatten)
    
    // Bulk delete loop
    val fBulkDelete = f.flatMap { case (annotations, docsAndPermissions) =>
      // Delete annotations one by one, keeping a list of those that failed
      annotations.foldLeft(Future.successful(Seq.empty[Annotation])) { case (f, next) =>
        f.flatMap { failed =>          
          deleteOne(next, docsAndPermissions).map { success =>
            if (success) failed else failed :+ next
          }
        }
      }
    } map { failed =>
      if (!failed.isEmpty)
        Logger.error(s"Failed to bulk-delete the following annotations: ${failed.map(_.annotationId).mkString}") 
      failed.isEmpty
    }
    
    fBulkDelete.map(success => if (success) Ok else InternalServerError)
  }}

}
