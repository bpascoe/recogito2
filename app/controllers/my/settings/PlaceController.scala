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

class PlaceController @Inject() (
    val components: ControllerComponents,
    val config: Configuration,
    val users: UserService,
    val silhouette: Silhouette[Security.Env],
    implicit val ctx: ExecutionContext
  ) extends AbstractController(components) with HasConfig with HasUserService with I18nSupport with HasPrettyPrintJSON {

  def index() = silhouette.SecuredAction { implicit request =>
    Ok(views.html.my.settings.place(request.identity))
  }

  def getPlaces() = silhouette.SecuredAction.async { implicit request =>
    request.body.asJson match {
      case Some(json) => {
        val username = request.identity.username
        val filename = (json \ "Filename").as[String]
        val places = ""
        Future.successful(jsonOk(Json.toJson(places)))
      }
      case None => {Future.successful(jsonOk(Json.toJson("")))}
    }
  }

  def updatePlace() = silhouette.SecuredAction { implicit request =>
    Ok(views.html.my.settings.place(request.identity))
  }

}
