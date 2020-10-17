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
import services.entity.builtin.EntityService
import services.entity.{EntityType, EntityRecord}
import storage.es.ES
import services.contribution._
import org.joda.time.{DateTime,DateTimeZone}
import java.util.UUID

class PlaceController @Inject() (
    val components: ControllerComponents,
    val config: Configuration,
    val users: UserService,
    val silhouette: Silhouette[Security.Env],
    val annotations: AnnotationService,
    implicit val documents: DocumentService,
    implicit val entities: EntityService,
    implicit val contributions: ContributionService,
    implicit val ctx: ExecutionContext,
    implicit val webjars: WebJarsUtil
  ) extends AbstractController(components) with HasConfig 
with HasUserService with I18nSupport with HasPrettyPrintJSON {

  def index() = silhouette.SecuredAction { implicit request =>
    Ok(views.html.my.settings.place(request.identity))
  }

  
  def getPlaces() = silhouette.SecuredAction.async { implicit request =>
    request.body.asJson match {
      case Some(json) => {
        // val username = request.identity.username
        val username = (json \ "username").as[String]
        val fAnnotations = annotations.getUserAnnotation(username)
        val fPlaces = annotations.getUserPlace(username, Some(EntityType.PLACE), 0, ES.MAX_SIZE)
        // val fPlaces = entities.listEntitiesInDocument("t0ikcct3t4yh9a")
        val f = for {
          annotations <- fAnnotations
          places <- fPlaces
        } yield (annotations.map(_._1), places)
        val annots = f.map { case (annotations, places) =>       
          // All place annotations on this document
          val placeAnnotations = annotations.filter(_.bodies.map(_.hasType).contains(AnnotationBody.PLACE))  

          // Each place in this document, along with all the annotations on this place and 
          // the specific entity records the annotations point to (within the place union record) 
          places.map { e =>      
            val place = e._1.entity

            val annotationsOnThisPlace = placeAnnotations.filter { a =>
              // All annotations that include place URIs of this place
              val placeURIs = a.bodies.filter(_.hasType == AnnotationBody.PLACE).flatMap(_.uri)
              !placeURIs.intersect(place.uris).isEmpty
            }
            val filenames = annotationsOnThisPlace.map {a=>
              Await.result(documents.getDocumentById(a.annotates.documentId),1.seconds).getFilename
            }
            // val docId = annotationsOnThisPlace(0).annotates.documentId
            
            // val placeURIs = annotationsOnThisPlace.flatMap(_.bodies).filter(_.hasType == AnnotationBody.PLACE).flatMap(_.uri)
            // val referencedRecords = place.isConflationOf.filter(g => placeURIs.contains(g.uri))
            var filename = "" 
            if (filenames.length > 0) filename = filenames(0)
            Json.obj("place"->place.title,"file_name"->filename,"union_id"->place.unionId)
          }
        } 
        // places.map { e =>      
        //     val place = e._1.entity
        //     Json.obj("place"->place.title,"union_id"->place.unionId)
        //   }
        // } 
        val places = Await.result(annots,5.seconds)
        Future.successful(jsonOk(Json.toJson(places)))
      }
      case None => {Future.successful(jsonOk(Json.toJson("")))}
    }
  }

  def updatePlace() = silhouette.SecuredAction { implicit request =>
    Ok(views.html.my.settings.place(request.identity))
  }

  def deletePlace() = silhouette.SecuredAction.async { implicit request =>
    request.body.asJson match {
      case Some(json) => {
        // val username = request.identity.username
        val username = (json \ "username").as[String]
        val unionId = (json \ "unionId").as[String]
        if (unionId.length > 0) {
          val response = Await.result(entities.deleteEntityById(unionId),1.seconds)
          val now = DateTime.now
          val annots = Await.result(annotations.getAnnotationsByUnionId(unionId),1.seconds)
          annots.flatMap {
            case (a,_)=>
            Await.result(annotations.deleteAnnotation(a.annotationId, username, now),1.seconds)
            // .flatMap {
            //   case Some(annotation) =>
            //     contributions.insertContribution(createDeleteContribution(annotation, document, username, now))
            // }
          }
        }
        Future.successful(Ok("test:" + unionId))
        // Future.successful(Ok("Success"))
      }
      case None => {Future.successful(Ok(""))}
    }
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
