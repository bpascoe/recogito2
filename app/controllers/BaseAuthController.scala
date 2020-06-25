package controllers

import services.RuntimeAccessLevel
import services.document.{ExtendedDocumentMetadata,ExtendedDocumentMetadata2, DocumentService}
import services.generated.tables.records.{DocumentFilepartRecord, DocumentRecord}
import services.user.{User, UserService}
import play.api.Configuration
import play.api.mvc.{ControllerComponents, Result}
import scala.concurrent.ExecutionContext

abstract class BaseAuthController(
    components: ControllerComponents,
    config: Configuration,
    documents: DocumentService,
    users: UserService
  ) extends BaseController(components, config, users) {
  
  /** Helper that covers the boilerplate for all document views
    *
    * Just hand this method a function that produces an HTTP OK result for a document, while
    * the method handles ForbiddenPage/Not Found error cases.
    */
  protected def documentResponse(
    docId: String,
    user: User,
    response: (ExtendedDocumentMetadata, RuntimeAccessLevel) => Result
  )(implicit ctx: ExecutionContext) = {
    documents.getExtendedMeta(docId, Some(user.username)).map(_ match {
      case Some((doc, accesslevel)) => {
        if (accesslevel.canReadData)
          // As long as there are read rights we'll allow access here - the response
          // method must handle more fine-grained access by itself
          response(doc, accesslevel)
        else
          ForbiddenPage
      }

      case None =>
        // No document with that ID found in DB
        NotFoundPage
    }).recover { case t =>
      t.printStackTrace()
      InternalServerError(t.getMessage)
    }
  }

  protected def documentsResponse(
    docIds: Seq[String],
    user: User,
    response: (Seq[ExtendedDocumentMetadata2], RuntimeAccessLevel) => Result
  )(implicit ctx: ExecutionContext) = {
    documents.getExtendedMetas(docIds, Some(user.username)).map(_ match {
      case Some((doc, accesslevel)) => {
        if (accesslevel.canReadData)
          // As long as there are read rights we'll allow access here - the response
          // method must handle more fine-grained access by itself
          response(doc, accesslevel)
        else
          ForbiddenPage
      }

      case None =>
        // No document with that ID found in DB
        NotFoundPage
    }).recover { case t =>
      t.printStackTrace()
      InternalServerError(t.getMessage)
    }
  }

  /** Helper that covers the boilerplate for all document part views **/
  protected def documentPartResponse(
    docId: String,
    partNo: Int,
    user: User,
    response: (ExtendedDocumentMetadata, DocumentFilepartRecord, RuntimeAccessLevel) => Result
  )(implicit ctx: ExecutionContext) = {
    documentResponse(docId, user, { case (doc, accesslevel) =>
      doc.fileparts.find(_.getSequenceNo == partNo) match {
        case None => NotFoundPage
        case Some(part) => response(doc, part, accesslevel)
      }
    })
  }
  
}
