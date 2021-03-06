package controllers.my.settings

import com.mohiva.play.silhouette.api.Silhouette
import controllers.{ HasConfig, HasUserService, Security, HasPrettyPrintJSON }
import javax.inject.Inject
import services.user.UserService
import services.user.Roles._
import play.api.Configuration
import play.api.data.Form
import play.api.data.Forms._
import play.api.data.validation._
import play.api.i18n.{ I18nSupport, MessagesApi }
import play.api.mvc.{AbstractController, ControllerComponents}
import scala.concurrent.{ ExecutionContext, Future }
import play.api.libs.json.Json
import scala.concurrent.Await
import scala.concurrent.duration._
import services.annotation.{Annotation, AnnotationService,AnnotationBody}
import services.document.DocumentService
import org.webjars.play.WebJarsUtil
import services.entity.builtin.{EntityService,IndexedEntity}
import services.entity.{EntityType, EntityRecord,Entity,CountryCode}
import storage.es.ES
import services.contribution._
import org.joda.time.{DateTime,DateTimeZone}
import java.util.UUID
import services.entity.{TemporalBounds,Link,Description,Name}
import services.entity.builtin.importer.EntityImporterFactory
import com.vividsolutions.jts.geom.{Coordinate, GeometryFactory}

import kantan.csv.CsvConfiguration
import kantan.csv.CsvConfiguration.{Header, QuotePolicy}
import kantan.csv.ops._
import storage.TempDir
import play.api.{Configuration, Logger}
import play.api.http.FileMimeTypes
import play.api.libs.Files.TemporaryFileCreator
import java.nio.file.Paths
import com.sksamuel.elastic4s.Hit
import scala.language.postfixOps

class PlaceController @Inject() (
    val components: ControllerComponents,
    val users: UserService,
    val silhouette: Silhouette[Security.Env],
    val annotations: AnnotationService,
    implicit val documents: DocumentService,
    implicit val entities: EntityService,
    implicit val contributions: ContributionService,
    implicit val importerFactory: EntityImporterFactory,
    implicit val config: Configuration,
    implicit val mimeTypes: FileMimeTypes,
    implicit val tmpFile: TemporaryFileCreator,
    implicit val ctx: ExecutionContext,
    implicit val webjars: WebJarsUtil
  ) extends AbstractController(components) with HasConfig 
with HasUserService with I18nSupport with HasPrettyPrintJSON {

  def index() = silhouette.SecuredAction { implicit request =>
    Ok(views.html.my.settings.place(request.identity))
  }

  def formatDateString (date: String) = {
    val dateArray = date.split("-")
    if (date(0) == '-') {
      if (dateArray.size == 4) {
        (("-"+dateArray(1)).toInt,dateArray(2).toInt,dateArray(3).toInt)
      } else if (dateArray.size == 3) {
        (("-"+dateArray(1)).toInt,dateArray(2).toInt,1)
      } else {
        (("-"+dateArray(1)).toInt,1,1)
      }
    } else {
      if (dateArray.size == 3) {
        (dateArray(0).toInt,dateArray(1).toInt,dateArray(2).toInt)
      } else if (dateArray.size == 2) {
        (dateArray(0).toInt,dateArray(1).toInt,1)
      } else {
        (dateArray(0).toInt, 1, 1)
      }
    }
  }
  
  // def getPlaces() = silhouette.SecuredAction.async { implicit request =>
  //   request.body.asJson match {
  //     case Some(json) => {
  //       // val username = request.identity.username
  //       val username = (json \ "username").as[String]
  //       val fAnnotations = annotations.getUserAnnotation(username)
  //       val fPlaces = annotations.getUserPlace(username, Some(EntityType.PLACE), 0, ES.MAX_SIZE)
  //       // val fPlaces = entities.listEntitiesInDocument("t0ikcct3t4yh9a")
  //       val f = for {
  //         annotations <- fAnnotations
  //         places <- fPlaces
  //       } yield (annotations.map(_._1), places)
  //       val annots = f.map { case (annotations, places) =>       
  //         // All place annotations on this document
  //         val placeAnnotations = annotations.filter(_.bodies.map(_.hasType).contains(AnnotationBody.PLACE))  

  //         // Each place in this document, along with all the annotations on this place and 
  //         // the specific entity records the annotations point to (within the place union record) 
  //         places.map { e =>      
  //           val place = e._1.entity

  //           val annotationsOnThisPlace = placeAnnotations.filter { a =>
  //             // All annotations that include place URIs of this place
  //             val placeURIs = a.bodies.filter(_.hasType == AnnotationBody.PLACE).flatMap(_.uri)
  //             !placeURIs.intersect(place.uris).isEmpty
  //           }
  //           val filenames = annotationsOnThisPlace.map {a=>
  //             Await.result(documents.getDocumentById(a.annotates.documentId),1.seconds).getFilename
  //           }
  //           // val docId = annotationsOnThisPlace(0).annotates.documentId
            
  //           // val placeURIs = annotationsOnThisPlace.flatMap(_.bodies).filter(_.hasType == AnnotationBody.PLACE).flatMap(_.uri)
  //           // val referencedRecords = place.isConflationOf.filter(g => placeURIs.contains(g.uri))
  //           var filename = "" 
  //           val lat = place.representativeGeometry.get.getCentroid.getY
  //           val lon = place.representativeGeometry.get.getCentroid.getX
  //           if (filenames.length > 0) filename = filenames(0)
  //           val temporal = place.temporalBoundsUnion
  //           var from = ""
  //           var to = ""
  //           // val to = place.flatMap(_.temporalBoundsUnion.map(_.toString)).getOrElse(EMPTY)
  //           if (temporal != None) {
  //             from= temporal.get.from.toString
  //             if (from.length > 10) from = from.substring(0,10)
  //             to= temporal.get.to.toString
  //             if (to.length > 10) to = to.substring(0,10)
  //           }
  //           Json.obj("name"->place.title,"file"->filename,"id"->place.unionId,
  //             "startDate"->from,"endDate"->to,"lat"->lat,"lon"->lon)
  //         }
  //       } 
  //       // places.map { e =>      
  //       //     val place = e._1.entity
  //       //     Json.obj("place"->place.title,"union_id"->place.unionId)
  //       //   }
  //       // } 
  //       val places = Await.result(annots,5.seconds)
  //       Future.successful(jsonOk(Json.toJson(places)))
  //     }
  //     case None => {Future.successful(jsonOk(Json.toJson("")))}
  //   }
  // }
  
  // def getPlaces() = silhouette.SecuredAction.async { implicit request =>
  //   request.body.asJson match {
  //     case Some(json) => {
  //       // val username = request.identity.username
  //       val username = (json \ "username").as[String]
  //       // val fAnnotations = annotations.getUserAnnotation(username)
  //       // val fPlaces = entities.getUserPlace(username, Some(EntityType.PLACE), 0, ES.MAX_SIZE)
  //       val fPlaces = entities.getEntitiesByUser(username)
  //       val f = for {
  //         places <- fPlaces
  //       } yield (places)
  //       val annots = f.map { case ( places) =>       
  //         places.map { e =>      
  //           val place = e.entity
  //           // if (place.contributor == username) {
  //             val lat = place.representativeGeometry.get.getCentroid.getY
  //             val lon = place.representativeGeometry.get.getCentroid.getX
  //             val temporal = place.temporalBoundsUnion
  //             var from = ""
  //             var to = ""
  //             if (temporal != None) {
  //               from= temporal.get.from.toString
  //               if (from.length > 10) from = from.substring(0,10)
  //               to= temporal.get.to.toString
  //               if (to.length > 10) to = to.substring(0,10)
  //             }
  //             val record = place.isConflationOf(0)
  //             val descriptions = record.descriptions.map(d=>d.description)
  //             val names = record.names.map(n=>n.name)
  //             var ccode = ""
  //             if (record.countryCode != None) ccode = record.countryCode.get.code
  //             Json.obj("name"->place.title,"uri"->record.uri,"id"->place.unionId,"user"->record.contributor,
  //             "startDate"->from,"endDate"->to,"lat"->lat,"lon"->lon,"country"->ccode,"description"->descriptions.mkString(", "),"altNames"->names.mkString(", "))
  //           // }
  //         }
  //       } 
  //       val places = Await.result(annots,5.seconds)
  //       Future.successful(jsonOk(Json.toJson(places)))
  //     }
  //     case None => {Future.successful(jsonOk(Json.toJson("")))}
  //   }
  // }
  
  // def getPlaces() = silhouette.SecuredAction.async { implicit request =>
  //   request.body.asJson match {
  //     case Some(json) => {
  //       // val username = request.identity.username
  //       val username = (json \ "username").as[String]
  //       // val fAnnotations = annotations.getUserAnnotation(username)
  //       // val fPlaces = entities.getUserPlace(username, Some(EntityType.PLACE), 0, ES.MAX_SIZE)
  //       val fPlaces = entities.getEntitiesByUser(username)
  //       val f = for {
  //         places <- fPlaces
  //       } yield (places)
  //       val annots = f.map { case ( places) =>       
  //         places.map { e =>      
  //           val records = e.entity.isConflationOf
  //           records.map {r =>
  //             val lat = r.geometry.get.getCentroid.getY
  //             val lon = r.geometry.get.getCentroid.getX
  //             val temporal = r.temporalBounds
  //             var from = ""
  //             var to = ""
  //             if (temporal != None) {
  //               from= temporal.get.from.toString
  //               if (from.length > 10) from = from.substring(0,10)
  //               to= temporal.get.to.toString
  //               if (to.length > 10) to = to.substring(0,10)
  //             }
  //             val descriptions = r.descriptions.map(d=>d.description)
  //             val names = r.names.map(n=>n.name)
  //             var ccode = ""
  //             if (r.countryCode != None) ccode = r.countryCode.get.code
  //             Json.obj("name"->r.title,"uri"->r.uri,"id"->r.uri,"user"->r.contributor,
  //               "startDate"->from,"endDate"->to,"lat"->lat,"lon"->lon,"country"->ccode,"description"->descriptions.mkString(", "),"altNames"->names.mkString(", "))
  //           }
  //         }
  //       } 
  //       val places = Await.result(annots,10.seconds)
  //       Future.successful(jsonOk(Json.toJson(places)))
  //     }
  //     case None => {Future.successful(jsonOk(Json.toJson("")))}
  //   }
  // }

  def getPlaces() = silhouette.SecuredAction.async { implicit request =>
    request.body.asJson match {
      case Some(json) => {
        // val username = request.identity.username
        val username = (json \ "username").as[String]
        // val fAnnotations = annotations.getUserAnnotation(username)
        // val fPlaces = entities.getUserPlace(username, Some(EntityType.PLACE), 0, ES.MAX_SIZE)
        val fPlaces = entities.getEntitiesByUser(username)
        val f = for {
          places <- fPlaces
        } yield (places)
        val annots = f.map { case ( places) =>       
          places.map { e =>      
            val place = e.entity
            val lat = place.representativeGeometry.get.getCentroid.getY
              val lon = place.representativeGeometry.get.getCentroid.getX
              val temporal = place.temporalBoundsUnion
              var from = ""
              var to = ""
              if (temporal != None) {
                from= temporal.get.from.toString
                if (from.length > 10) from = from.substring(0,10)
                to= temporal.get.to.toString
                if (to.length > 10) to = to.substring(0,10)
              }
              val record = place.isConflationOf(0)
              val descriptions = record.descriptions.map(d=>d.description)
              val names = record.names.map(n=>n.name)
              var ccode = ""
              if (record.countryCode != None) ccode = record.countryCode.get.code
              Json.obj("name"->place.title,"uri"->record.uri,"id"->place.unionId,"user"->place.contributor,
              "startDate"->from,"version"->e.version,"endDate"->to,"lat"->lat,"lon"->lon,"country"->ccode,"description"->descriptions.mkString(", "),"altNames"->names.mkString(", "))
          }
        } 
        val places = Await.result(annots,10.seconds)
        Future.successful(jsonOk(Json.toJson(places)))
      }
      case None => {Future.successful(jsonOk(Json.toJson("")))}
    }
  }

  def updatePlace() = silhouette.SecuredAction { implicit request =>
    request.body.asJson match {
      case Some(json) => {
        val username = (json \ "username").as[String]
        val id = (json \ "id").as[String]
        val title = (json \ "title").as[String]
        val lat = (json \ "lat").as[Double]
        val lon = (json \ "lon").as[Double]
        val coord = new Coordinate(lon, lat)
        val point = new GeometryFactory().createPoint(coord)
        val from  = (json \ "from").as[String]
        val to  = (json \ "to").as[String]
        val norURI = EntityRecord.normalizeURI((json \ "uri").as[String])
        val country  = (json \ "country").as[String]
        val ccode = if (country.length != 2 ) {None} 
        else {Some(CountryCode(country.toUpperCase))}
        
        val time = DateTime.now()
        val altNames  = Name((json \ "altNames").asOpt[String].getOrElse(""))
        val description  = Description((json \ "description").asOpt[String].getOrElse(""))
        
        val temporal_bounds = if (from == "" || to == "") {None} else { 
          val (fromYear, fromMonth, fromDay) = formatDateString(from) // yy-mm-dd
          val (toYear, toMonth, toDay) = formatDateString(to)
          Some(new TemporalBounds(new DateTime(DateTimeZone.UTC).withDate(fromYear, fromMonth, fromDay).withTime(0, 0, 0, 0), new DateTime(DateTimeZone.UTC).withDate(toYear, toMonth, toDay).withTime(0, 0, 0, 0)))
        }
        val record = Await.result(entities.findById(id),5.seconds)
        // val record = Await.result(entities.findByURI(norURI),5.seconds)
        // val referencedRecord = place.isConflationOf.map(g => uri)
        val entityRecord = EntityRecord(norURI,ES.CONTRIBUTION,time,Some(time),title,Seq(description),Seq(altNames),Some(point),Some(coord),ccode,temporal_bounds,Seq.empty[String],None,Some(username),Seq.empty[Link])
        if (record != None) {
          var version = 1.toLong
          if (record.get.version != None && record.get.version.get > 0) version = record.get.version.get
          val newRecord = record.get.entity.copy(title=title,temporalBoundsUnion=temporal_bounds,representativePoint=Some(coord),representativeGeometry=Some(point),isConflationOf=Seq(entityRecord))
          val indexRecord = new IndexedEntity(newRecord,Some(version))
          val response =Await.result(entities.upsertEntities(Seq(indexRecord)),5.seconds)
          // val response = Await.result(entities.upsertEntities(Seq(record.get.copy(entity=newRecord))),5.seconds)
          if (response != None)
            Ok("Success")
          else
            Ok("Fail")
        } else {
          // val newRecord = record.get.entity.copy(title=title,temporalBoundsUnion=temporal_bounds,representativePoint=Some(coord),representativeGeometry=Some(point),isConflationOf=Seq(entityRecord))
          // Await.result(entities.createEntity(newRecord),10.seconds)
          Ok("Success2")
        }
      }
      case None =>
        Ok("Success3")
    }
  }

  def createPlace() = silhouette.SecuredAction { implicit request =>
    request.body.asJson match {
      case Some(json) => {
        val importer = importerFactory.createImporter(EntityType.PLACE)
        val norURI = EntityRecord.normalizeURI((json \ "uri").as[String])
        val username = (json \ "username").as[String]
        val title = (json \ "title").as[String]
        val lat = (json \ "lat").as[Double]
        val lon = (json \ "lon").as[Double]
        val time = DateTime.now()
        val coord = new Coordinate(lon, lat)
        val point = new GeometryFactory().createPoint(coord)
        val from  = (json \ "from").as[String]
        val to  = (json \ "to").as[String]
        val country  = (json \ "country").as[String]
        val ccode = if (country.length != 2 ) {None} 
        else {Some(CountryCode(country.toUpperCase))}
        val altNames  = Name((json \ "altNames").asOpt[String].getOrElse(""))
        val description  = Description((json \ "description").asOpt[String].getOrElse(""))
        val temporal_bounds = if (from == "" || to == "") {None} else { 
          val (fromYear, fromMonth, fromDay) = formatDateString(from) // yy-mm-dd
          val (toYear, toMonth, toDay) = formatDateString(to)
          Some(new TemporalBounds(new DateTime(DateTimeZone.UTC).withDate(fromYear, fromMonth, fromDay).withTime(0, 0, 0, 0), new DateTime(DateTimeZone.UTC).withDate(toYear, toMonth, toDay).withTime(0, 0, 0, 0)))
        }
        val record = EntityRecord(norURI,ES.CONTRIBUTION,time,Some(time),title,Seq(description),Seq(altNames),Some(point),Some(coord),ccode,temporal_bounds,Seq.empty[String],None,Some(username),Seq.empty[Link])
        val response = importer.importRecord(record)
        // if (response != None) {
          val newRecord = Entity(UUID.randomUUID,EntityType.PLACE,title,Some(point),Some(coord),temporal_bounds,Seq(record),Some(username))//
          // Await.result(entities.createEntity(newRecord),10.seconds)
          val indexRecord = new IndexedEntity(newRecord,None)
          Await.result(entities.upsertEntities(Seq(indexRecord)),5.seconds)
          Ok("Success")
        // }
        // else
        //   Ok("Fail")
      }
      case None =>
        Ok("Success")
    }
  }

  def deletePlace() = silhouette.SecuredAction.async { implicit request =>
    request.body.asJson match {
      case Some(json) => {
        // val username = request.identity.username
        val username = (json \ "username").as[String]
        val unionId = (json \ "unionId").as[String]
        if (unionId.length > 0) {
          val response = Await.result(entities.deleteEntityById(unionId),5.seconds)
          val now = DateTime.now
          val annots = Await.result(annotations.getAnnotationsByUnionId(unionId),5.seconds)
          annots.flatMap {
            case (a,_)=>
            Await.result(annotations.deleteAnnotation(a.annotationId, username, now),5.seconds)
            // .flatMap {
            //   case Some(annotation) =>
            //     contributions.insertContribution(createDeleteContribution(annotation, document, username, now))
            // }
          }
        }
        // Future.successful(Ok("test:" + unionId))
        Future.successful(Ok("Success"))
      }
      case None => {Future.successful(Ok(""))}
    }
  }

  def importPlaces() = silhouette.UserAwareAction.async { implicit request =>
    request.body.asJson match {
      case Some(json) => {
        val username = request.identity.get.username
        val name = (json \ "Name").as[String]
        if (name.length>0) {
          val importer = importerFactory.createImporter(EntityType.PLACE)
          val norURI = EntityRecord.normalizeURI((json \ "URI").as[String])
          // val entity = Await.result(entities.findByURI(norURI).map { _ match {
          //   case Some(e) => e.entity
          // }},1.seconds)

          val from  = (json \ "StartDate").asOpt[String].getOrElse("")
          val to  = (json \ "EndDate").asOpt[String].getOrElse("")
          val temporal_bounds = if (from == "" || to == "") {None} else { 
            val (fromYear, fromMonth, fromDay) = formatDateString(from) // yy-mm-dd
            val (toYear, toMonth, toDay) = formatDateString(to)
            Some(new TemporalBounds(new DateTime(DateTimeZone.UTC).withDate(fromYear, fromMonth, fromDay).withTime(0, 0, 0, 0), new DateTime(DateTimeZone.UTC).withDate(toYear, toMonth, toDay).withTime(0, 0, 0, 0)))
          }
          val country  = (json \ "Country").asOpt[String].getOrElse("")
          val ccode = if (country.length != 2 ) {None} 
          else {Some(CountryCode(country.toUpperCase))}
          
          val altNames  = Name((json \ "AlternateNames").asOpt[String].getOrElse(""))
          val description  = Description((json \ "Description").asOpt[String].getOrElse(""))
          val latitude  = (json \ "Latitude").as[Double]
          val longitude  = (json \ "Longitude").as[Double]
          val time = DateTime.now()
          val coord = new Coordinate(longitude, latitude)
          val point = new GeometryFactory().createPoint(coord)
          val fromDate  = (json \ "StartDate").asOpt[String]
          val toDate  = (json \ "EndDate").asOpt[String]
          val ent = Await.result(entities.findByURI(norURI), 10 seconds)

          val record = EntityRecord(norURI,ES.CONTRIBUTION,time,Some(time),name,Seq(description),Seq(altNames),Some(point),Some(coord),ccode,temporal_bounds,Seq.empty[String],None,Some(username),Seq.empty[Link])
          if (ent != None) {
            var version = 1.toLong
            if (ent.get.version != None && ent.get.version.get > 0) version = ent.get.version.get
            val newRecord = ent.get.entity.copy(title=name,temporalBoundsUnion=temporal_bounds,representativePoint=Some(coord),representativeGeometry=Some(point),isConflationOf=Seq(record))
            val indexRecord = new IndexedEntity(newRecord,Some(version))
            Await.result(entities.upsertEntities(Seq(indexRecord)),5.seconds)
          } else { 
            val response = importer.importRecord(record)
            // if (response != None) {
              val newRecord = Entity(UUID.randomUUID,EntityType.PLACE,name,Some(point),Some(coord),temporal_bounds,Seq(record),Some(username))//
              // Await.result(entities.createEntity(newRecord),5.seconds)
              val indexRecord = new IndexedEntity(newRecord,None)
              Await.result(entities.upsertEntities(Seq(indexRecord)),5.seconds)
            // }
          }
          Future.successful(Ok("Success"))
        } else Future.successful(Ok("Fail"))
      }
      case None => {
        Future.successful(Ok("Empty CSV file"))}
    }
  }

  // def exportPlaces() = silhouette.UserAwareAction.async { implicit request =>
  //   val username = request.identity.get.username
  //   val file = scala.concurrent.blocking {
  //   val header = Seq("Name", "URI", "Latitude", "Longitude", "Country", "Description", "StartDate", "EndDate", "AlternateNames")
  //   val tmp = tmpFile.create(Paths.get(TempDir.get(), s"${username}.csv"))
  //   val underlying = tmp.path.toFile
  //   val configs = CsvConfiguration(',', '"', QuotePolicy.Always, Header.Explicit(header))
  //   def toStr (r: String) = {
  //     if (r == null) "" else r.asInstanceOf[String]
  //   }
  //   val writer = underlying.asCsvWriter[Seq[String]](configs)
  //   // val fPlaces = entities.getUserPlace(username, Some(EntityType.PLACE), 0, ES.MAX_SIZE)
  //   val fPlaces = entities.getEntitiesByUser(username)
  //   val places = Await.result(fPlaces,5.seconds)
  //   places.map { e =>      
  //     val place = e.entity
  //     // val place = e._1.entity
  //     if (place.contributor == username) {
  //       val lat = place.representativeGeometry.get.getCentroid.getY
  //       val lon = place.representativeGeometry.get.getCentroid.getX
  //       val temporal = place.temporalBoundsUnion
  //       var from = ""
  //       var to = ""
  //       if (temporal != None) {
  //         from= temporal.get.from.toString
  //         if (from.length > 10) from = from.substring(0,10)
  //         to= temporal.get.to.toString
  //         if (to.length > 10) to = to.substring(0,10)
  //       }
  //       val record = place.isConflationOf(0)
  //       val descriptions = record.descriptions.map(d=>d.description)
  //       val names = record.names.map(n=>n.name)
  //       var ccode = ""
  //       if (record.countryCode != None) ccode = record.countryCode.get.code
  //       val row = Seq(toStr(place.title),toStr(record.uri), toStr(lat.toString), toStr(lon.toString), toStr(ccode), toStr(descriptions.mkString(", ")), toStr(from), toStr(to), toStr(names.mkString(", ")))
  //       writer.write(row)
  //     }
  //   }
  //   writer.close() 
  //   underlying
  //  }
  //  Future.successful(Ok.sendFile(file).withHeaders(CONTENT_DISPOSITION -> { "attachment; filename=" + username + "-places.csv" }))
  // }
  def exportPlaces() = silhouette.UserAwareAction.async { implicit request =>
    val username = request.identity.get.username
    val file = scala.concurrent.blocking {
    val header = Seq("Name", "URI", "Latitude", "Longitude", "Country", "Description", "StartDate", "EndDate", "AlternateNames")
    val tmp = tmpFile.create(Paths.get(TempDir.get(), s"${username}.csv"))
    val underlying = tmp.path.toFile
    val configs = CsvConfiguration(',', '"', QuotePolicy.Always, Header.Explicit(header))
    def toStr (r: String) = {
      if (r == null) "" else r.asInstanceOf[String]
    }
    val writer = underlying.asCsvWriter[Seq[String]](configs)
    // val fPlaces = entities.getUserPlace(username, Some(EntityType.PLACE), 0, ES.MAX_SIZE)
    val fPlaces = entities.getEntitiesByUser(username)
    val places = Await.result(fPlaces,5.seconds)
    // val f = for {
    //   places <- fPlaces
    // } yield (places)
    // val annots = f.map { case ( places) =>    
      places.map { e =>      
        val place = e.entity
        var user = ""
        if (place.contributor != None ) user = place.contributor.get
        if (user == username) {
          val lat = place.representativeGeometry.get.getCentroid.getY
            val lon = place.representativeGeometry.get.getCentroid.getX
            val temporal = place.temporalBoundsUnion
            var from = ""
            var to = ""
            if (temporal != None) {
              from= temporal.get.from.toString
              if (from.length > 10) from = from.substring(0,10)
              to= temporal.get.to.toString
              if (to.length > 10) to = to.substring(0,10)
            }
            val record = place.isConflationOf(0)
            val descriptions = record.descriptions.map(d=>d.description)
            val names = record.names.map(n=>n.name)
            var ccode = ""
            if (record.countryCode != None) ccode = record.countryCode.get.code
            
            val row = Seq(toStr(place.title),toStr(record.uri), toStr(lat.toString), toStr(lon.toString), toStr(ccode), toStr(descriptions.mkString(", ")), toStr(from), toStr(to), toStr(names.mkString(", ")))
            writer.write(row)
        }
      }
    // } 
    // Await.result(annots,10.seconds)
    writer.close() 
    underlying
   }
   Future.successful(Ok.sendFile(file).withHeaders(CONTENT_DISPOSITION -> { "attachment; filename=" + username + "-places.csv" }))
  }

  // manage annotations
  def annotation() = silhouette.SecuredAction { implicit request =>
    Ok(views.html.my.settings.annotation(request.identity))
  }

  
  def getAnnotations() = silhouette.SecuredAction.async { implicit request =>
    request.body.asJson match {
      case Some(json) => {
        // val username = request.identity.username
        val username = (json \ "username").as[String]
        val response = Await.result(annotations.getUserAnnotation(username),5.seconds)
        val annots = response.map {
          case (a,_) =>
              val filename = Await.result(documents.getDocumentById(a.annotates.documentId),1.seconds).getFilename
              Json.obj("name"->a.bodies(0).value,"file"->filename,"id"->a.annotationId,"startDate"->a.startDate.getOrElse[String](""),"endDate"->a.endDate.getOrElse[String](""))
        }
        Future.successful(jsonOk(Json.toJson(annots)))
      }
      case None => {Future.successful(jsonOk(Json.toJson("")))}
    }
  }

  def updateAnnotation() = silhouette.SecuredAction { implicit request =>
    request.body.asJson match {
      case Some(json) => {
        val username = (json \ "username").as[String]
        val id = (json \ "id").as[String]
        val title = (json \ "title").as[String]
        val time = DateTime.now()
        val from  = (json \ "from").asOpt[String]
        val to  = (json \ "to").asOpt[String]
        // val temporal_bounds = if (from == "" || to == "") {None} else { 
        //   val (fromYear, fromMonth, fromDay) = formatDateString(from) // yy-mm-dd
        //   val (toYear, toMonth, toDay) = formatDateString(to)
        //   Some(new TemporalBounds(new DateTime(DateTimeZone.UTC).withDate(fromYear, fromMonth, fromDay).withTime(0, 0, 0, 0), new DateTime(DateTimeZone.UTC).withDate(toYear, toMonth, toDay).withTime(0, 0, 0, 0)))
        // }
        val response = Await.result(annotations.findById(UUID.fromString(id)),1.seconds)
        if (response != None) {
          val result = response.map {
            case (record,_) =>
              Await.result(annotations.upsertAnnotation(record.copy(lastModifiedAt=time,startDate=from,endDate=to)),1.seconds)
          }
          if (result != None)
            Ok("Success")
          else
            Ok("Fail")
        } else {
          Ok("Success")
        }
      }
      case None =>
        Ok("Success")
    }
  }

  def deleteAnnotation() = silhouette.SecuredAction.async { implicit request =>
    request.body.asJson match {
      case Some(json) => {
        // val username = request.identity.username
        val username = (json \ "username").as[String]
        val id = (json \ "id").as[String]
        val now = DateTime.now
        val response = Await.result(annotations.deleteAnnotation(UUID.fromString(id), username, now),1.seconds)
        if (response != None)
          Future.successful(Ok("Success"))
        else
          Future.successful(Ok("Fail"))
      }
      case None => {Future.successful(Ok("Success"))}
    }
  }

}
