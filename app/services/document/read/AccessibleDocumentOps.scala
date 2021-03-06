package services.document.read

import collection.JavaConversions._
import java.util.UUID
import org.jooq.Record
import scala.concurrent.Future
import services.{ContentType, Page, PublicAccess, SortOrder}
import services.document.DocumentService
import services.document.read.results.AccessibleDocument
import services.generated.Tables.{DOCUMENT, FOLDER_ASSOCIATION, SHARING_POLICY}
import services.generated.tables.records.{DocumentRecord, SharingPolicyRecord}
import storage.db.DB

/** For convenience: wraps public and shared documents count **/
case class AccessibleDocumentsCount(public: Long, shared: Option[Long]) {

  lazy val total = public + shared.getOrElse(0l)

}

/** Read-operations related to accessible/public/shared documents **/
trait AccessibleDocumentOps { self: DocumentService =>

  /** Lists users who have at least one document with visibility set to PUBLIC
    * 
    * Recogito uses this query to build the sitemap.txt file.
    */
  def listOwnersWithPublicDocuments(
    offset: Int = 0, limit: Int = 10000
  ) = db.query { sql =>
    sql.select(DOCUMENT.OWNER).from(DOCUMENT)
      .where(DOCUMENT.PUBLIC_VISIBILITY
        .equal(PublicAccess.PUBLIC.toString))
      .groupBy(DOCUMENT.OWNER)
      .limit(limit)
      .offset(offset)
      .fetch().into(classOf[String])
      .toSeq
  }

  /** Lists IDs of root-level documents accessible to the given visitor **/
  private def listAccessibleIdsInRoot(owner: String, loggedInAs: Option[String]) = db.query { sql => 
    loggedInAs match {
      case Some(username) =>
        sql.select(DOCUMENT.ID)
          .from(DOCUMENT)
          .leftOuterJoin(FOLDER_ASSOCIATION)
            .on(FOLDER_ASSOCIATION.DOCUMENT_ID.equal(DOCUMENT.ID))
          .leftOuterJoin(SHARING_POLICY)
            .on(SHARING_POLICY.DOCUMENT_ID.equal(DOCUMENT.ID))
              .and(SHARING_POLICY.SHARED_WITH.equal(username))
          .where(
            DOCUMENT.OWNER.equalIgnoreCase(owner)
              .and(FOLDER_ASSOCIATION.FOLDER_ID.isNull)
              .and(
                DOCUMENT.PUBLIC_VISIBILITY.equal(PublicAccess.PUBLIC.toString)
                  .or(SHARING_POLICY.SHARED_WITH.equal(username))
              )
            )
          .fetch(0, classOf[String])
          .toSeq

      case None => 
        sql.select(DOCUMENT.ID)
          .from(DOCUMENT)
          .leftOuterJoin(FOLDER_ASSOCIATION)
            .on(FOLDER_ASSOCIATION.DOCUMENT_ID.equal(DOCUMENT.ID))
          .where(DOCUMENT.OWNER.equalIgnoreCase(owner)
            .and(DOCUMENT.PUBLIC_VISIBILITY.equal(PublicAccess.PUBLIC.toString))
            .and(FOLDER_ASSOCIATION.FOLDER_ID.isNull))
          .fetch(0, classOf[String])
          .toSeq
    }
  }

  /** Lists IDs of documents accessible to the given visitor in the given folder **/
  private def listAccessibleIdsInFolder(folder: UUID, loggedInAs: Option[String]) = db.query { sql => 
    loggedInAs match {
      case Some(username) => 
        sql.select(DOCUMENT.ID)
          .from(DOCUMENT)
          .leftOuterJoin(FOLDER_ASSOCIATION)
            .on(FOLDER_ASSOCIATION.DOCUMENT_ID.equal(DOCUMENT.ID))
          .leftOuterJoin(SHARING_POLICY)
            .on(SHARING_POLICY.DOCUMENT_ID.equal(DOCUMENT.ID))
          .where(FOLDER_ASSOCIATION.FOLDER_ID.equal(folder)
            .and(
              DOCUMENT.PUBLIC_VISIBILITY.equal(PublicAccess.PUBLIC.toString)
                .or(SHARING_POLICY.SHARED_WITH.equal(username))
            ))
          .fetch(0, classOf[String])
          .toSeq
      
      case None => 
        sql.select(DOCUMENT.ID)
          .from(DOCUMENT)
          .leftJoin(FOLDER_ASSOCIATION)
            .on(FOLDER_ASSOCIATION.DOCUMENT_ID.equal(DOCUMENT.ID))
          .where(DOCUMENT.PUBLIC_VISIBILITY.equal(PublicAccess.PUBLIC.toString)
            .and(FOLDER_ASSOCIATION.FOLDER_ID.equal(folder)))
          .fetch(0, classOf[String])
          .toSeq
    }
  }

  /** Delegate to the appropriate private method, based on folder value **/
  def listAccessibleIds(
    owner: String, 
    folder: Option[UUID], 
    loggedInAs: Option[String]
  ): Future[Seq[String]] = folder match {
    case Some(folderId) => listAccessibleIdsInFolder(folderId, loggedInAs)
    case None => listAccessibleIdsInRoot(owner, loggedInAs)
  }

  /** Lists the root-level documents accessible to the given visitor **/
  private def listAccessibleDocumentsInRoot(
    owner: String, 
    loggedInAs: Option[String],
    offset: Int,
    limit: Int,
    maybeSortBy: Option[String],
    maybeSortOrder: Option[SortOrder]
  ): Future[Page[AccessibleDocument]] = db.query { sql => 
    val startTime = System.currentTimeMillis

    val sortBy = maybeSortBy.flatMap(sanitizeField).getOrElse("document.uploaded_at")
    val sortOrder = maybeSortOrder.map(_.toString).getOrElse("desc")

    val query = loggedInAs match {
      case Some(username) =>
        val query = 
          s"""
           SELECT 
             document.*,
             sharing_policy.*,
             file_count,
             content_types,
             folder_sharing_policy.shared_with AS folder_shared
           FROM document
             LEFT OUTER JOIN sharing_policy 
               ON sharing_policy.document_id = document.id AND
                  sharing_policy.shared_with = ?
             LEFT OUTER JOIN folder_association 
               ON folder_association.document_id = document.id
             LEFT OUTER JOIN sharing_policy folder_sharing_policy 
               ON folder_sharing_policy.folder_id = folder_association.folder_id
             JOIN (
               SELECT
                 count(*) AS file_count,
                 array_agg(DISTINCT content_type) AS content_types,
                 document_id
               FROM document_filepart
               GROUP BY document_id
             ) AS parts ON parts.document_id = document.id
           WHERE document.owner = ?
             AND (
               document.public_visibility = 'PUBLIC' 
                 OR sharing_policy.shared_with = ?
             ) AND (
               folder_association.folder_id IS NULL
                 OR folder_sharing_policy.shared_with IS NULL
             )
           ORDER BY ${sortBy} ${sortOrder}
           OFFSET ${offset} LIMIT ${limit};
           """
        sql.resultQuery(query, username, owner, username)

      case None =>
        val query = 
          s"""
           SELECT 
             document.*,
             file_count,
             content_types
           FROM document
             LEFT OUTER JOIN folder_association 
               ON folder_association.document_id = document.id
             JOIN (
               SELECT
                 count(*) AS file_count,
                 array_agg(DISTINCT content_type) AS content_types,
                 document_id
               FROM document_filepart
               GROUP BY document_id
             ) AS parts ON parts.document_id = document.id
           WHERE document.owner = ?
             AND document.public_visibility = 'PUBLIC'
             AND folder_association.folder_id IS NULL
           ORDER BY ${sortBy} ${sortOrder}
           OFFSET ${offset} LIMIT ${limit};
           """
        sql.resultQuery(query, owner)
       
    }

    val records = query.fetchArray.map(AccessibleDocument.build)
    Page(System.currentTimeMillis - startTime, records.size, 0, records.size, records)
  }
  
  /** Lists the documents accessible to the given visitor in the given folder **/
  private def listAccessibleDocumentsInFolder(
    folder: UUID, 
    loggedInAs: Option[String],
    offset: Int,
    limit: Int,
    maybeSortBy: Option[String],
    maybeSortOrder: Option[SortOrder]
  ): Future[Page[AccessibleDocument]] = db.query { sql => 
    val startTime = System.currentTimeMillis

    val sortBy = maybeSortBy.flatMap(sanitizeField).getOrElse("document.uploaded_at")
    val sortOrder = maybeSortOrder.map(_.toString).getOrElse("desc")

    val query = loggedInAs match {
      case Some(username) =>
        val query = 
          s"""
           SELECT 
             document.*,
             sharing_policy.*,
             file_count,
             content_types
           FROM document
             LEFT OUTER JOIN sharing_policy 
               ON sharing_policy.document_id = document.id AND
                  sharing_policy.shared_with = ?
             LEFT OUTER JOIN folder_association 
               ON folder_association.document_id = document.id
             JOIN (
               SELECT
                 count(*) AS file_count,
                 array_agg(DISTINCT content_type) AS content_types,
                 document_id
               FROM document_filepart
               GROUP BY document_id
             ) AS parts ON parts.document_id = document.id
           WHERE folder_association.folder_id = ?
             AND (
               document.public_visibility = 'PUBLIC' 
                 OR sharing_policy.shared_with = ?
             )
           ORDER BY ${sortBy} ${sortOrder}
           OFFSET ${offset} LIMIT ${limit};
           """
        sql.resultQuery(query, username, folder, username)

      case None => 
        val query = 
          s"""
           SELECT 
             document.*,
             file_count,
             content_types
           FROM document
             LEFT OUTER JOIN folder_association 
               ON folder_association.document_id = document.id
             JOIN (
               SELECT
                 count(*) AS file_count,
                 array_agg(DISTINCT content_type) AS content_types,
                 document_id
               FROM document_filepart
               GROUP BY document_id
             ) AS parts ON parts.document_id = document.id
           WHERE document.public_visibility = 'PUBLIC'
             AND folder_association.folder_id = ?
           ORDER BY ${sortBy} ${sortOrder}
           OFFSET ${offset} LIMIT ${limit};
           """
        sql.resultQuery(query, folder)
    }

    val records = query.fetchArray.map(AccessibleDocument.build)
    Page(System.currentTimeMillis - startTime, records.size, 0, records.size, records)
  }

  /** Delegate to the appropriate private method, based on folder value **/
  def listAccessibleDocuments(
    owner: String,
    folder: Option[UUID],
    loggedInAs: Option[String],
    offset: Int,
    limit: Int,
    maybeSortBy: Option[String],
    maybeSortOrder: Option[SortOrder]
  ): Future[Page[AccessibleDocument]] = folder match {
    case Some(folderId) => listAccessibleDocumentsInFolder(folderId, loggedInAs, offset, limit, maybeSortBy, maybeSortOrder)
    case None => listAccessibleDocumentsInRoot(owner, loggedInAs, offset, limit, maybeSortBy, maybeSortOrder)
  }

  /** Counts the total accessible documents that exist for the given owner. 
    * 
    * The result of this method depends on who's requesting the information. 
    * For anonymous visitors (accessibleTo = None), the count will cover public 
    * documents only. For a specific logged-in user, additional documents 
    * may be accessible, because they are shared with her/him.
    */
  def countAllAccessibleDocuments(
    owner: String, 
    accessibleTo: Option[String]
  ) = db.query { sql =>

    // Count all public documents
    val public =
      sql.selectCount()
         .from(DOCUMENT)
         .where(DOCUMENT.OWNER.equalIgnoreCase(owner)
           .and(DOCUMENT.PUBLIC_VISIBILITY.equal(PublicAccess.PUBLIC.toString)))
         .fetchOne(0, classOf[Long])  

    // If there's a logged-in user, count shared
    val shared = accessibleTo.map { username => 
      sql.selectCount()
         .from(DOCUMENT)
         .where(DOCUMENT.OWNER.equalIgnoreCase(owner)
           // Don't double-count public docs!
           .and(DOCUMENT.PUBLIC_VISIBILITY.notEqual(PublicAccess.PUBLIC.toString))
           .and(DOCUMENT.ID.in(
             sql.select(SHARING_POLICY.DOCUMENT_ID)
                .from(SHARING_POLICY)
                .where(SHARING_POLICY.SHARED_WITH.equal(username))
             ))
         ).fetchOne(0, classOf[Long])
    }

    AccessibleDocumentsCount(public, shared)
  }

}