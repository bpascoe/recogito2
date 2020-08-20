package services.document

import java.util.UUID
import javax.inject.{Inject, Singleton}
import services.BaseService
import services.generated.Tables.DOCUMENT
import scala.concurrent.ExecutionContext
import storage.db.DB
import storage.uploads.Uploads
import scala.concurrent.Future
import services.generated.tables.records.DocumentRecord
import services.generated.Tables.{FOLDER_ASSOCIATION}

@Singleton
class DocumentService @Inject() (
  implicit val uploads: Uploads, 
  implicit val db: DB
) extends BaseService 
  with create.CreateOps
  with read.DocumentReadOps
  with read.FilepartReadOps
  with read.ReadFromFolderOps
  with read.CollaboratorReadOps
  with read.SharedWithMeReadOps
  with read.AccessibleDocumentOps
  with search.SearchOps
  with network.NetworkOps
  with update.DocumentUpdateOps
  with update.CollaboratorUpdateOps
  with delete.DeleteOps {

  private val SORTABLE_FIELDS = 
    DOCUMENT.fields.toSeq.map(_.getName)

  /** Just make sure people don't inject any weird shit into the sort field **/
  protected def sanitizeField(name: String): Option[String] = {
    SORTABLE_FIELDS.find(_ == name)
  }

  /** Or into doc ID queries somehow **/
  protected def sanitizeDocId(id: String): Option[String] = {
    if (id.length == DocumentIdFactory.ID_LENGTH && // Ids have fixed length
        id.matches("[a-z0-9]+")) { // Ids are all lowercase & alphanumeric
      Some(id)
    } else {
      None
    }
  }

  def getDocIdByTitle(title: String, username: String, folder: Option[UUID])  = db.query { sql =>
    
    folder match {
      case Some(folderId) => 
        sql.select(DOCUMENT.ID).from(DOCUMENT)
          .join(FOLDER_ASSOCIATION).on(DOCUMENT.ID.equal(FOLDER_ASSOCIATION.DOCUMENT_ID))
          .where(DOCUMENT.FILENAME.equal(title)
            .and(DOCUMENT.OWNER.equal(username))
            .and(FOLDER_ASSOCIATION.FOLDER_ID.equal(folderId)))
          .limit(1)
          .fetchOne(0, classOf[String])

      case None => 
        sql.select(DOCUMENT.ID).from(DOCUMENT).where(DOCUMENT.FILENAME.equal(title).and(DOCUMENT.OWNER.equal(username))).limit(1).fetchOne(0, classOf[String])
    }
  }

  def getDocsInFolder(username: String, folder: Option[UUID])  = db.query { sql =>
    
    folder match {
      case Some(folderId) => 
        sql.select().from(DOCUMENT)
          .join(FOLDER_ASSOCIATION).on(DOCUMENT.ID.equal(FOLDER_ASSOCIATION.DOCUMENT_ID))
          .where(FOLDER_ASSOCIATION.FOLDER_ID.equal(folderId).and(DOCUMENT.OWNER.equal(username)))
          .fetchArray().toSeq.map{record=>record.into(classOf[DocumentRecord])}

      case None => 
        sql.selectFrom(DOCUMENT).where(DOCUMENT.OWNER.equal(username)).fetchArray().toSeq
    }
  }
  // def getDocsIdInFolder(documentId: String)(implicit ctx: ExecutionContext) = {
  //   getDocsId(documentId)
  // }
      

  /** Shorthand **/
  def listIds(folder: Option[UUID], loggedInAs: String)(implicit ctx: ExecutionContext) =
    folder match {
      case Some(folderId) => 
        listDocumentsInFolder(folderId, loggedInAs)
          .map { _.map { case (doc, _) => 
            doc.getId
          }}

      case None => 
        listRootIdsByOwner(loggedInAs)
    }
      
}

