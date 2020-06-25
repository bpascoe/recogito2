package services.upload

import collection.JavaConverters._
import java.io.File
import java.nio.file.{Files, Paths, StandardCopyOption}
import java.sql.Timestamp
import java.util.{Date, UUID}
import javax.inject.Inject
import services.{BaseService, ContentType, PublicAccess}
import services.document.DocumentIdFactory
import services.folder.FolderService
import services.generated.Tables._
import services.generated.tables.records._
import services.user.User
import play.api.libs.Files.TemporaryFile
import play.api.mvc.MultipartFormData.FilePart
import scala.collection.JavaConversions._
import scala.concurrent.{ExecutionContext, Future}
import storage.db.DB
import storage.uploads.Uploads

class QuotaExceededException(val remainingSpaceKb: Long, val filesizeKb: Double) extends RuntimeException

class UploadService @Inject() (
  folders: FolderService,
  uploads: Uploads,
  implicit val db: DB,
  implicit val ctx: ExecutionContext) extends BaseService {
  
  /** Admin-level method to fetch all pending uploads in the system **/
  def listPendingUploads(olderThan: Option[Timestamp] = None) = db.query { sql =>
    olderThan match {
      case Some(date) =>
        sql.selectFrom(UPLOAD)
          .where(UPLOAD.CREATED_AT.lessThan(date)).fetchArray()
        
      case _ =>
        sql.selectFrom(UPLOAD).fetchArray()
    }
  }

  def createPendingUpload(owner: String, title: String) =
    storePendingUpload(owner, title, "", "", "", "", "", "")
  
  /** Inserts a new upload, or updates an existing one if it already exists **/
  def storePendingUpload(
    owner: String, 
    title: String, 
    author: String = null, 
    dateFreeform: String = null, 
    description: String = null, 
    language: String = null, 
    source: String = null, 
    edition: String = null
  ) = db.withTransaction { sql =>
      val upload =
        Option(sql.selectFrom(UPLOAD).where(UPLOAD.OWNER.equal(owner)).fetchOne()) match {
          case Some(upload) => {
            // Pending upload exists - update
            upload.setCreatedAt(new Timestamp(new Date().getTime))
            upload.setTitle(title)
            upload.setAuthor(nullIfEmpty(author))
            upload.setDateFreeform(nullIfEmpty(dateFreeform))
            upload.setDescription(nullIfEmpty(description))
            upload.setLanguage(nullIfEmpty(language))
            upload.setSource(nullIfEmpty(source))
            upload.setEdition(nullIfEmpty(edition))
            upload
          }

          case None => {
            // No pending upload - create new
            val upload = new UploadRecord(null,
                owner,
                new Timestamp(new Date().getTime),
                nullIfEmpty(title),
                nullIfEmpty(author),
                nullIfEmpty(dateFreeform),
                nullIfEmpty(description),
                nullIfEmpty(language.toUpperCase),
                nullIfEmpty(source),
                nullIfEmpty(edition),
                null)

            sql.attach(upload)
            upload.changed(UPLOAD.ID, false);
            upload
          }
        }

      upload.store()
      upload
    }

  def insertUploadFilepart(uploadId: Int, owner: User, filepart: FilePart[TemporaryFile]):
    Future[Either[Exception, UploadFilepartRecord]] =
      insertUploadFilepart(uploadId, owner, filepart.ref, filepart.filename)

  /** Inserts a new locally stored filepart - metadata goes to the DB, content to the pending-uploads dir **/
  def insertUploadFilepart(uploadId: Int, owner: User, file: TemporaryFile, filename: String):
    Future[Either[Exception, UploadFilepartRecord]] = db.withTransaction { sql =>
     
    val filesizeKb = Files.size(file.path).toDouble / 1024
    
    val usedDiskspaceKb = uploads.getUsedDiskspaceKB(owner.username)
    val remainingDiskspaceKb = owner.quotaMb * 1024 - usedDiskspaceKb
    val isQuotaExceeded = remainingDiskspaceKb < filesizeKb
    
    if (isQuotaExceeded) {
      Left(new QuotaExceededException(remainingDiskspaceKb, filesizeKb))
    } else {
      val id = UUID.randomUUID
      val extension = filename.substring(filename.lastIndexOf('.'))
      val dest = new File(uploads.PENDING_UPLOADS_DIR, id.toString + extension)
      file.moveTo(dest)
      dest.setReadable(true, false)
      
      ContentType.fromFile(dest) match {
        case Right(contentType) => {
          val filepartRecord = new UploadFilepartRecord(id, uploadId, owner.username, filename, contentType.toString, dest.getName, filesizeKb, null, null)
          sql.insertInto(UPLOAD_FILEPART).set(filepartRecord).execute()
          Right(filepartRecord)
        }
  
        case Left(e) =>
          file.delete()
          Left(e)
      }
    }
  }
  
  /** Inserts a new remote filepart - metadata goes to the DB, content stays external **/
  def insertRemoteFilepart(uploadId: Int, owner: String, contentType: ContentType, 
     url: String, title: Option[String] = None, sequenceNo: Option[Int] = None) = db.withTransaction { sql =>

    val filepartRecord = 
      new UploadFilepartRecord(
        UUID.randomUUID, 
        uploadId, 
        owner, 
        title.getOrElse(url), 
        contentType.toString, 
        url, 
        null, null, optInt(sequenceNo))
    
    val rows = sql.insertInto(UPLOAD_FILEPART).set(filepartRecord).execute()
    rows == 1
  }
  
  /** Deletes all fileparts for the given upload Id **/
  def deleteFilePartsByUploadId(uploadId: Int) = db.withTransaction { sql =>
    sql.deleteFrom(UPLOAD_FILEPART).where(UPLOAD_FILEPART.UPLOAD_ID.equal(uploadId)).execute()
  }

  /** Deletes a filepart - record is removed from the DB, file from the data directory **/
  def deleteFilepartByUUIDAndOwner(id: UUID, owner: String) = db.withTransaction { sql =>
    // Note: the ID is unique, we're just using the owner as an additional verification measure
    Option(sql.selectFrom(UPLOAD_FILEPART)
              .where(UPLOAD_FILEPART.ID.equal(id))
              .and(UPLOAD_FILEPART.OWNER.equal(owner))
              .fetchOne()) match {

      case Some(filepartRecord) => {
        val file = new File(uploads.PENDING_UPLOADS_DIR, filepartRecord.getFile)
        file.delete()
        filepartRecord.delete() == 1
      }

      case None =>
        // Happens when someone clicks 'delete' on a failed upload - never mind
        false
    }
  }

  /** Retrieves the pending upload for a user (if any) **/
  def findPendingUpload(username: String) = db.query { sql =>
    Option(sql.selectFrom(UPLOAD).where(UPLOAD.OWNER.equal(username)).fetchOne())
  }

  /** Deletes a user's pending upload **/
  def deletePendingUpload(username: String) = db.query { sql =>
    val fileparts =
      sql.selectFrom(UPLOAD_FILEPART)
         .where(UPLOAD_FILEPART.OWNER.equal(username))
         .fetchArray

    fileparts.foreach { part =>
      val file = new File(uploads.PENDING_UPLOADS_DIR, part.getFile)
      file.delete()
    }

    sql.deleteFrom(UPLOAD_FILEPART).where(UPLOAD_FILEPART.OWNER.equal(username)).execute()
    sql.deleteFrom(UPLOAD).where(UPLOAD.OWNER.equal(username)).execute() == 1
  }
  
  /** Admin-level method to drop all pending uploads from the system **/
  def deleteAllPendingUploads() = db.withTransaction { sql =>
    // Delete files from 'pending' directory
    val fileparts = sql.selectFrom(UPLOAD_FILEPART).fetchArray()
    fileparts.foreach { part =>
      val file = new File(uploads.PENDING_UPLOADS_DIR, part.getFile)
      file.delete()
    }
    
    sql.deleteFrom(UPLOAD_FILEPART).execute()
    sql.deleteFrom(UPLOAD).execute()
  }

  /** Retrieves the pending upload for a user (if any) along with the filepart metadata records **/
  def findPendingUploadWithFileparts(username: String) = db.query { sql =>
    val result =
      sql.selectFrom(UPLOAD
        .leftJoin(UPLOAD_FILEPART)
        .on(UPLOAD.ID.equal(UPLOAD_FILEPART.UPLOAD_ID)))
      .where(UPLOAD.OWNER.equal(username))
      .orderBy(UPLOAD_FILEPART.SEQUENCE_NO)
      .fetchArray()

      // Convert to map (Upload -> Seq[Filepart]), filtering out null records returned as result of the join
      .map(record =>
        (record.into(classOf[UploadRecord]), record.into(classOf[UploadFilepartRecord])))
      .groupBy(_._1)
      .mapValues(_.map(_._2).filter(_.getId != null).toSeq)

    // Result map can have 0 or 1 key - otherwise DB integrity is compromised
    if (result.size > 1)
      throw new RuntimeException("DB contains multiple pending uploads for user " + username)

    if (result.isEmpty)
      None
    else
      Some(result.head)
  }

  /** Creates a new DocumentRecord from an UploadRecord **/
  private def createDocumentFromUpload(upload: UploadRecord) =
    new DocumentRecord(
          DocumentIdFactory.generateRandomID(),
          upload.getOwner,
          upload.getCreatedAt,
          upload.getTitle,
          upload.getAuthor,
          null, // TODO date_numeric
          upload.getDateFreeform,
          upload.getDescription,
          upload.getLanguage,
          upload.getSource,
          upload.getEdition,
          upload.getLicense,
          null, // attribution
          PublicAccess.PRIVATE.toString, // public_visibility
          null, // public_access_level
          null) // cloned_from

  private def importDocumentAndParts(
    upload: UploadRecord, 
    fileparts: Seq[UploadFilepartRecord]
  ) = db.withTransaction { sql =>
    var tmp = new DocumentRecord
    var ids = Seq.empty[String]
    val docFileparts = fileparts.zipWithIndex.map { case (part, idx) =>
      upload.setTitle(part.getTitle)
      val document = createDocumentFromUpload(upload)
      tmp = document
      sql.insertInto(DOCUMENT).set(document).execute()
      // val sequenceNo: Integer = Option(part.getSequenceNo).getOrElse(idx + 1)
      val p =new DocumentFilepartRecord(
        part.getId,
        document.getId,
        part.getTitle,
        part.getContentType,
        part.getFile,
        1,//sequenceNo
        part.getSource)
      sql.insertInto(DOCUMENT_FILEPART).set(p).execute()

      val isLocalFile = ContentType.withName(part.getContentType).map(_.isLocal).getOrElse(false)
      if (isLocalFile) {
        val source = new File(uploads.PENDING_UPLOADS_DIR, part.getFile).toPath
        val destination = new File(uploads.getDocumentDir(upload.getOwner, document.getId, true).get, part.getFile).toPath
        Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE)
      }
      ids = ids :+ document.getId
      p
    }
        
    // val inserts = docFileparts.map(p => sql.insertInto(DOCUMENT_FILEPART).set(p))    
    // sql.batch(inserts:_*).execute()
    
    // Move uploaded files from 'pending' to 'user-data' folder (disregard remote files)
    // fileparts.map(filepart => {
    //   val isLocalFile = ContentType.withName(filepart.getContentType).map(_.isLocal).getOrElse(false)
    //   if (isLocalFile) {
    //     val source = new File(uploads.PENDING_UPLOADS_DIR, filepart.getFile).toPath
    //     val destination = new File(uploads.getDocumentDir(upload.getOwner, document.getId, true).get, filepart.getFile).toPath
    //     Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE)
    //   }
    // })

    // Delete Upload and UploadFilepart records from the staging area tables
    sql.deleteFrom(UPLOAD_FILEPART).where(UPLOAD_FILEPART.UPLOAD_ID.equal(upload.getId)).execute()
    sql.deleteFrom(UPLOAD).where(UPLOAD.ID.equal(upload.getId)).execute()

    (tmp, docFileparts,ids)
    // (tmp, docFileparts)
    
  }

    /** Promotes a pending upload in the staging area to actual document **/
  def importPendingUpload(
    upload: UploadRecord, 
    fileparts: Seq[UploadFilepartRecord],
    folder: Option[UUID]
  ) = folder match {
    case Some(folderId) => for {
      t <- importDocumentAndParts(upload, fileparts)
      // _ <- folders.moveDocumentToFolder(t._1.getId, folderId)
      _ <- folders.moveDocumentsToFolder(t._3, folderId)
    } yield t

    case None => importDocumentAndParts(upload, fileparts)
  }

}
