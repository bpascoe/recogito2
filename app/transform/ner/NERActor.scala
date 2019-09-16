package transform.ner

import akka.actor.Props
import java.io.File
import java.net.URI
import java.util.UUID
import org.pelagios.recogito.sdk.ner._
import play.api.{Configuration, Logger}
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.io.Source
import services.ContentType
import services.annotation.{Annotation, AnnotationBody, AnnotationService}
import services.entity.builtin.EntityService
import services.task.TaskService
import services.generated.tables.records.{DocumentRecord, DocumentFilepartRecord}
import transform.georesolution.{Georesolvable, GeoresolutionJobDefinition, HasGeoresolution}
import transform.{WorkerActor, SpecificJobDefinition}

case class EntityResolvable(entity: Entity, val anchor: String, val uri: Option[URI]) extends Georesolvable {
  val toponym = entity.chars
  val coord = None
}

class NERActor(
  implicit val taskService: TaskService,
  implicit val annotationService: AnnotationService,
  implicit val entityService: EntityService,
  val config: Configuration
) extends WorkerActor(NERService.TASK_TYPE, taskService) with HasGeoresolution {
  
  type T = EntityResolvable
  
  private implicit val ctx = context.dispatcher
  
  override def doWork(
    doc: DocumentRecord, 
    part: DocumentFilepartRecord, 
    dir: File, 
    definition: Option[SpecificJobDefinition], 
    taskId: UUID
  ) = try {
    val jobDef = definition.map(_.asInstanceOf[NERJobDefinition])
    val engine = jobDef.map(_.engine)

    Logger.info(s"Starting NER on ${part.getId}")
    val phrases = parseFilepart(doc, part, dir, engine)
    
    Logger.info(s"NER completed on ${part.getId}")
    taskService.updateTaskProgress(taskId, 50)
    
    val places = phrases.filter(_.entity.entityType == EntityType.LOCATION).map(Some(_))
    val persons = phrases.filter(_.entity.entityType == EntityType.PERSON) 
    val other = phrases.filter(p => 
      p.entity.entityType != EntityType.LOCATION && p.entity.entityType != EntityType.PERSON)
    
    val resolutionDefinition: GeoresolutionJobDefinition = 
      jobDef.getOrElse(
        GeoresolutionJobDefinition.default(Seq(doc.getId), Seq(part.getId))
      )

    resolve(doc, part, places, resolutionDefinition, places.size, taskId, (50, 80))

    val fInsert = for {
      // People
      _ <- annotationService.upsertAnnotations(persons.map { p => 
        Annotation
          .on(part, p.anchor)
          .withBody(AnnotationBody.quoteBody(p.entity.chars))
          .withBody(AnnotationBody.personBody())
        })

      // Tags 
      _ <- annotationService.upsertAnnotations(other.map { p => 
          Annotation
            .on(part, p.anchor)
            .withBody(AnnotationBody.quoteBody(p.entity.chars))
            .withBody(AnnotationBody.tagBody(p.entity.entityType.toString))
        })
    } yield ()

    Await.result(fInsert, 20.minutes)
    taskService.setTaskCompleted(taskId)
  } catch { case t: Throwable =>
    t.printStackTrace()
    taskService.setTaskFailed(taskId, Some(t.getMessage))
  }
    
  /** Select appropriate parser for part content type **/
  private def parseFilepart(document: DocumentRecord, part: DocumentFilepartRecord, dir: File, engine: Option[String]) =
    ContentType.withName(part.getContentType) match {
      case Some(t) if t == ContentType.TEXT_PLAIN =>
        val text = Source.fromFile(new File(dir, part.getFile)).getLines.mkString("\n")
        val entities = NERService.parseText(text, engine, config)
        entities.map(e => EntityResolvable(e, s"char-offset:${e.charOffset}", Option(e.uri)))
        
      case Some(t) if t == ContentType.TEXT_TEIXML =>
        // For simplicity, NER results are inlined into the TEI document. They
        // will be extracted (together with all pre-existing tags) in a separate
        // step, anyway.
        val entitiesAndAnchors = NERService.parseTEI(new File(dir, part.getFile), engine, config)
        entitiesAndAnchors.map { case (e, anchor) => 
          EntityResolvable(e, anchor, Option(e.uri))
        }

      case _ =>
        Logger.info(s"Skipping NER for file of unsupported type ${part.getContentType}: ${dir.getName}${File.separator}${part.getFile}")
        Seq.empty[EntityResolvable]
    }
  
}

object NERActor {
  
  def props(
    taskService: TaskService, 
    annotationService: AnnotationService, 
    entityService: EntityService,
    config: Configuration 
  ) = Props(classOf[NERActor], taskService, annotationService, entityService, config)

}