package services.folder.delete

import java.util.UUID
import scala.concurrent.Future
import services.folder.FolderService
import services.generated.Tables.{DOCUMENT_FILEPART,DOCUMENT,FOLDER, FOLDER_ASSOCIATION, SHARING_POLICY}

trait DeleteOps { self: FolderService => 

  def deleteFolder(id: UUID): Future[Boolean] = {
    db.withTransaction { sql => 
      // Delete sharing policies
      sql.deleteFrom(SHARING_POLICY)
         .where(SHARING_POLICY.DOCUMENT_ID.in(sql.select(FOLDER_ASSOCIATION.DOCUMENT_ID).from(FOLDER_ASSOCIATION)
          .where(FOLDER_ASSOCIATION.FOLDER_ID.equal(id))))
         .execute()
      // Delete filepart records
      sql.deleteFrom(DOCUMENT_FILEPART)
         .where(DOCUMENT_FILEPART.DOCUMENT_ID.in(sql.select(FOLDER_ASSOCIATION.DOCUMENT_ID).from(FOLDER_ASSOCIATION)
          .where(FOLDER_ASSOCIATION.FOLDER_ID.equal(id))))
         .execute()
      sql.deleteFrom(DOCUMENT)
         .where(DOCUMENT.ID.in(sql.select(FOLDER_ASSOCIATION.DOCUMENT_ID).from(FOLDER_ASSOCIATION)
          .where(FOLDER_ASSOCIATION.FOLDER_ID.equal(id))))
         .execute()
      sql.deleteFrom(FOLDER_ASSOCIATION)
         .where(FOLDER_ASSOCIATION.FOLDER_ID.equal(id))
         .execute
      sql.deleteFrom(FOLDER).where(FOLDER.ID.equal(id)).execute == 1
    }
  }

  def deleteReadme(id: UUID): Future[Boolean] =
    db.withTransaction { sql =>
      sql.update(FOLDER)
        .set(FOLDER.README, null.asInstanceOf[String])
        .where(FOLDER.ID.equal(id))
        .execute == 1
    }

  /** Deletes all associations for this document **/
  def removeDocumentFromFolder(documentId: String) = 
    db.withTransaction { sql => 
      sql.deleteFrom(FOLDER_ASSOCIATION)
         .where(FOLDER_ASSOCIATION.DOCUMENT_ID.equal(documentId))
         .execute
    }

  def deleteFolderByOwner(owner: String): Future[Boolean] = 
    db.withTransaction { sql => 
      sql.deleteFrom(FOLDER).where(FOLDER.OWNER.equal(owner)).execute == 1
    }

  def removeCollaborator(folderId: UUID, sharedWith: String) =
    db.query { sql => 
      sql.deleteFrom(SHARING_POLICY)
         .where(SHARING_POLICY.FOLDER_ID.equal(folderId)
           .and(SHARING_POLICY.SHARED_WITH.equal(sharedWith)))
         .execute == 1
    } 

}