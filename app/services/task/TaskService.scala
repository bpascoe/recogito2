package services.task

import akka.actor.ActorSystem
import javax.inject.{Inject, Singleton}
import java.sql.Timestamp
import java.util.UUID
import services.BaseService
import services.generated.Tables.TASK
import services.generated.tables.records.TaskRecord
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import storage.db.DB

case class TaskType(private val name: String) {
  
  override def toString = name
  
}

object TaskStatus extends Enumeration {

  val PENDING = Value("PENDING")

  val RUNNING = Value("RUNNING")
  
  val COMPLETED = Value("COMPLETED")
  
  val FAILED = Value("FAILED")

}

@Singleton
class TaskService @Inject() (val db: DB, implicit val ctx: ExecutionContext) extends BaseService {
  
  def findByTaskId(uuid: UUID) = db.query { sql =>
    Option(sql.selectFrom(TASK).where(TASK.ID.equal(uuid)).fetchOne())
  }

  def findByJobId(uuid: UUID) = db.query { sql =>
    val tasks = 
      sql.selectFrom(TASK)
         .where(TASK.JOB_ID.equal(uuid))
         .fetchArray.toSeq

    if (tasks.size > 0)
      Some(TaskRecordAggregate(tasks))
    else 
      None
  }
  
  /** @deprecated **/
  def findByDocument(documentId: String) = db.query { sql =>
    val records = 
      sql.selectFrom(TASK).where(TASK.DOCUMENT_ID.equal(documentId))
        .fetchArray().toSeq
    
    if (records.size > 0)
      Some(TaskRecordAggregate(records))
    else
      None
  }

  def findByNERedDocument(documentId: String) = db.query { sql =>
    val records = 
      sql.selectFrom(TASK).where(TASK.TASK_TYPE.equal("NER"))
      .and(TASK.DOCUMENT_ID.equal(documentId))
        .fetchArray().toSeq
    
    if (records.size > 0)
      Some(TaskRecordAggregate(records))
    else
      None
  }
  
  def deleteByJobId(id: UUID) = db.withTransaction { sql => 
    sql.deleteFrom(TASK)
       .where(TASK.JOB_ID.equal(id))
       .execute
  }
  
  /*
  def deleteByTypeAndDocument(taskType: TaskType, documentId: String) = db.withTransaction { sql =>
    sql.deleteFrom(TASK)
      .where(TASK.TASK_TYPE.equal(taskType.toString))
      .and(TASK.DOCUMENT_ID.equal(documentId)).execute()
  }
  */
  
  def scheduleTaskForRemoval(uuid: UUID, in: FiniteDuration = 10.minutes)(implicit system: ActorSystem) = {
    system.scheduler.scheduleOnce(in) {
      db.withTransaction { sql =>
        sql.deleteFrom(TASK).where(TASK.ID.equal(uuid)).execute()
      }
    }
  }
  
  def updateTaskProgress(uuid: UUID, progress: Int): Future[Unit] = db.withTransaction { sql => 
    sql.update(TASK)
      .set[Integer](TASK.PROGRESS, progress)
      .where(TASK.ID.equal(uuid))
      .execute()
  }
    
  def updateTaskStatus(uuid: UUID, status: TaskStatus.Value): Future[Unit] = db.withTransaction { sql => 
    sql.update(TASK)
      .set(TASK.STATUS, status.toString)
      .where(TASK.ID.equal(uuid))
      .execute()
  }
  
  def updateTaskStatusAndProgress(uuid: UUID, status: TaskStatus.Value, progress: Int): Future[Unit] = db.withTransaction { sql =>
    sql.update(TASK)
      .set(TASK.STATUS, status.toString)
      .set[Integer](TASK.PROGRESS, progress)
      .where(TASK.ID.equal(uuid))
      .execute()
  }
  
  def setTaskCompleted(uuid: UUID, completedWith: Option[String] = None): Future[Unit] = db.withTransaction { sql =>
    sql.update(TASK)
      .set(TASK.STATUS, TaskStatus.COMPLETED.toString)
      .set(TASK.STOPPED_AT, new Timestamp(System.currentTimeMillis))
      .set(TASK.STOPPED_WITH, optString(completedWith))
      .set[Integer](TASK.PROGRESS, 100)
      .where(TASK.ID.equal(uuid))
      .execute()
  }
  
  def setTaskFailed(uuid: UUID, failedWith: Option[String] = None): Future[Unit] = db.withTransaction { sql =>
    sql.update(TASK)
      .set(TASK.STATUS, TaskStatus.FAILED.toString)
      .set(TASK.STOPPED_AT, new Timestamp(System.currentTimeMillis))
      .set(TASK.STOPPED_WITH, optString(failedWith))
      .where(TASK.ID.equal(uuid))
      .execute()
  }
  
  def insertTask(
    taskType: TaskType,
    className: String,
    jobId: UUID,
    documentId: Option[String],
    filepartId: Option[UUID],
    spawnedBy: Option[String]
  ): Future[UUID] = db.withTransaction { sql =>
      
    val uuid = UUID.randomUUID
    
    val taskRecord = new TaskRecord(
      uuid,
      taskType.toString,
      className,
      jobId,
      optString(documentId),
      filepartId.getOrElse(null),
      optString(spawnedBy),
      new Timestamp(System.currentTimeMillis),
      null, // stopped_at
      null, // stopped_with
      TaskStatus.PENDING.toString,
      0
    )
    
    sql.insertInto(TASK).set(taskRecord).execute()
    
    uuid
  }

}
