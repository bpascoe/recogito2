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
import services.annotation.AnnotationService
import services.document.DocumentService
import org.webjars.play.WebJarsUtil

class PlaceController @Inject() (
    val components: ControllerComponents,
    val config: Configuration,
    val users: UserService,
    val silhouette: Silhouette[Security.Env],
    val annotations: AnnotationService,
    implicit val documents: DocumentService,
    implicit val ctx: ExecutionContext,
    implicit val webjars: WebJarsUtil
  ) extends AbstractController(components) with HasConfig with HasUserService with I18nSupport with HasPrettyPrintJSON {

  def index() = silhouette.SecuredAction { implicit request =>
    Ok(views.html.my.settings.place(request.identity))
  }

  def getPlaces() = silhouette.SecuredAction.async { implicit request =>
    request.body.asJson match {
      case Some(json) => {
        // val username = request.identity.username
        val username = (json \ "username").as[String]
        val response = Await.result(annotations.getUserAnnotation(username),1.seconds)
        val annots = response.map {
          case (annotation,_) =>
            val docId = annotation.annotates.documentId
            val filename = Await.result(documents.getDocumentById(docId),1.seconds).getFilename
            ("place"->annotation.bodies(0).value,"file_name"->filename)
        }
        // val places = ""
        Future.successful(jsonOk(Json.toJson(annots)))
      }
      case None => {Future.successful(jsonOk(Json.toJson("")))}
    }
  }

  def updatePlace() = silhouette.SecuredAction { implicit request =>
    Ok(views.html.my.settings.place(request.identity))
  }

}
