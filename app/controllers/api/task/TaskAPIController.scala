package controllers.api.task

import akka.actor.ActorSystem
import com.mohiva.play.silhouette.api.Silhouette
import controllers.{BaseAuthController, HasPrettyPrintJSON, Security}
import java.util.UUID
import javax.inject.{Inject, Singleton}
import play.api.Configuration
import play.api.libs.json.Json
import play.api.mvc.ControllerComponents 
import services.document.DocumentService
import services.task.{TaskType, TaskService, TaskRecordAggregate}
import services.user.UserService
import services.user.Roles._
import scala.concurrent.{ExecutionContext, Future}
import transform.JobDefinition
import transform.georesolution.{GeoresolutionService, TableGeoresolutionJobDefinition}
import transform.ner.{NERService, NERJobDefinition}

@Singleton
class TaskAPIController @Inject() (
  val components: ControllerComponents,
  val config: Configuration,
  val documents: DocumentService,
  val users: UserService,
  val ner: NERService,
  val georesolution: GeoresolutionService,
  val silhouette: Silhouette[Security.Env],
  val tasks: TaskService,
  implicit val system: ActorSystem,
  implicit val ctx: ExecutionContext
) extends BaseAuthController(components, config, documents, users) with HasPrettyPrintJSON {

  def spawnJob = silhouette.SecuredAction.async { implicit request =>
    request.body.asJson.map(json => Json.fromJson[JobDefinition](json)) match {
      case Some(result) if result.isSuccess =>
        val taskDefinition = result.get
        documentsResponse(taskDefinition.documents, request.identity, { case (docInfo, accesslevel) =>
          if (accesslevel.canWrite) {
            val documents = docInfo.map(doc=>doc.document)
            val fileparts = docInfo.map(doc=>doc.fileparts)
            taskDefinition.taskType match {  
              case TaskType("GEORESOLUTION") =>
                val definition = Json.fromJson[TableGeoresolutionJobDefinition](request.body.asJson.get).get
                georesolution.spawnJob2(documents, fileparts, definition)
                Ok

              case TaskType("NER") =>
                val definition = Json.fromJson[NERJobDefinition](request.body.asJson.get).get
                val jobId = ner.spawnJob2(documents, fileparts, definition)
                jsonOk(Json.obj("job_id" -> jobId))
                
              case t =>
                BadRequest(Json.parse("{ \"error\": \"unsupported task type: " + t + "\" }"))
            }}
          else
            Forbidden(Json.parse("{ \"error\": \"insufficient access privileges\"}"))
        })
        
      case Some(result) if result.isError => Future.successful(BadRequest(Json.parse("{ \"error\": \"JSON parse error\" }")))
      case None => Future.successful(BadRequest(Json.parse("{ \"error\": \"missing task definition\" }")))
    }
  }

  def getJobProgress(jobId: UUID) = silhouette.SecuredAction.async { implicit request => 
    tasks.findByJobId(jobId).map { _ match {
      case Some(progress) => jsonOk(Json.toJson(progress))
      case None => NotFound
    }}
  }

  def progressByDocument(id: String) = silhouette.SecuredAction.async { implicit request =>    
    documents.getExtendedMeta(id, Some(request.identity.username)).flatMap(_ match {
      case Some((doc, accesslevel)) =>
        if (accesslevel.canReadAll) {
          tasks.findByDocument(id).map {
            case Some(taskRecord) => jsonOk(Json.toJson(taskRecord))
            case None => NotFound
          }
        } else {
          Future.successful(Forbidden)
        }
        
      case None => Future.successful(NotFound)
    })
  }

  def isDocumentNERed(id: String) = silhouette.SecuredAction.async { implicit request =>    
    tasks.findByNERedDocument(id).map {
      case Some(taskRecord) => jsonOk(Json.toJson(taskRecord))
      case None => Ok("")
    }
  }
  
}
