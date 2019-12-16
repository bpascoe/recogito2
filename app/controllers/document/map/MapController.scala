package controllers.document.map

import com.mohiva.play.silhouette.api.Silhouette
import controllers.{BaseOptAuthController, HasVisitLogging, Security}
import controllers.document.annotation.AnnotationController
import javax.inject.{Inject, Singleton}
import services.document.{ExtendedDocumentMetadata, DocumentService}
import services.annotation.AnnotationService
import services.user.UserService
import services.annotation.{Annotation, AnnotationService}
import services.user.Roles._
import services.visit.VisitService
import services.generated.tables.records.{DocumentFilepartRecord, DocumentRecord, DocumentPreferencesRecord, UserRecord}
import org.webjars.play.WebJarsUtil
import play.api.Configuration
import play.api.mvc.ControllerComponents
import play.api.i18n.I18nSupport
import scala.concurrent.{ExecutionContext, Future}
import play.api.mvc.{ControllerComponents, RequestHeader, Result}
import storage.uploads.Uploads

@Singleton
class MapController @Inject() (
    val components: ControllerComponents,
    val config: Configuration,
    val annotations: AnnotationService,
    val document: DocumentService,
    val users: UserService,
    val silhouette: Silhouette[Security.Env],
    val annotation: AnnotationController,
    val uploads: Uploads,
    val currentPart: DocumentFilepartRecord,
    implicit val visitService: VisitService,
    implicit val ctx: ExecutionContext,
    implicit val webjars: WebJarsUtil
  ) extends BaseOptAuthController(components, config, document, users) with HasVisitLogging with I18nSupport {

  def showMap(documentId: String) = silhouette.UserAwareAction.async { implicit request =>
    documentReadResponse(documentId, request.identity,  { case (doc, accesslevel) =>
      logDocumentView(doc.document, None, accesslevel)
      annotations.countByDocId(documentId).map { documentAnnotationCount =>
        Ok(views.html.document.map.index(doc, request.identity, accesslevel, documentAnnotationCount))
      }
    })
  }
  
  def textToMap(documentId: String) = silhouette.UserAwareAction.async { implicit request =>
    documentReadResponse(documentId, request.identity,  { case (doc, accesslevel) =>
      // logDocumentView(doc.document, Some(currentPart), accesslevel)
      logDocumentView(doc.document, None, accesslevel)
      val content = uploads.readTextfile(doc.ownerName, documentId, "02056dd3-cb88-400c-9b47-1ad7019041e1.txt")
      // val content = uploads.getDocumentDir(doc.ownerName, documentId)
      annotations.countByDocId(documentId).map { documentAnnotationCount =>
        Ok(views.html.document.map.text2map(doc, request.identity, accesslevel, documentAnnotationCount,content))
      }
    })
    // documentReadResponse(documentId, request.identity,  { case (doc, accesslevel) =>
    //   logDocumentView(doc.document, None, accesslevel)
    //   // content = uploads.readTextfile(doc.ownerName, doc.id, doc.title)
    //   // annotations.countByDocId(documentId).map { documentAnnotationCount =>
    //   //   // annotations.findById(UUID.fromString(documentId)).map { _.map(_._1) }
    //   //   Ok(views.html.document.map.text2map(doc, request.identity, accesslevel, documentAnnotationCount))
    //   //   // annotation.showAnnotationView(documentId, 1)
    //   // }
    // })
  }
  /*def textToMap(documentId: String, seqNo: Int) = silhouette.UserAwareAction.async { implicit request =>
    val loggedIn = request.identity
    
    val fPreferences = documents.getDocumentPreferences(documentId)
    
    val fRedirectedVia = request.flash.get("annotation") match {
      case Some(annotationId) => annotations.findById(UUID.fromString(annotationId)).map { _.map(_._1) }
      case None => Future.successful(None)
    }
    
    def fResponse(prefs: Seq[DocumentPreferencesRecord], via: Option[Annotation]) = 
      documentPartResponse(documentId, seqNo, loggedIn, { case (doc, currentPart, accesslevel) =>
        if (accesslevel.canReadData)
          renderResponse(doc, currentPart, loggedIn, accesslevel, prefs, via.map(AnnotationSummary.from))
        else if (loggedIn.isEmpty) // No read rights - but user is not logged in yet
          Future.successful(Redirect(controllers.landing.routes.LoginLogoutController.showLoginForm(None)))
        else
          Future.successful(ForbiddenPage)
      })
    
    for {
      preferences <- fPreferences
      redirectedVia <- fRedirectedVia
      response <- fResponse(preferences, redirectedVia)
    } yield response
  }

  private def renderResponse(
    doc: ExtendedDocumentMetadata,
    currentPart: DocumentFilepartRecord,
    loggedInUser: Option[User],
    accesslevel: RuntimeAccessLevel,
    prefs: Seq[DocumentPreferencesRecord],
    redirectedVia: Option[AnnotationSummary]
  )(implicit request: RequestHeader) = {

    logDocumentView(doc.document, Some(currentPart), accesslevel)

    // Needed in any case - start now (val)
    val fCountAnnotations = annotations.countByDocId(doc.id)
    val fGetClonedFrom = doc.clonedFrom.map(origId => documents.getDocumentRecordById(origId))
      .getOrElse(Future.successful(None))
    val fClones = documents.listClones(doc.id)

    val f = for {
      annotationCount <- fCountAnnotations
      clonedFrom <- fGetClonedFrom // Source doc this document was cloned from (if any)
      clones <- fClones // Documents that were cloned from this document (if any)
    } yield (annotationCount, clonedFrom.map(_._1), clones)

    // Needed only for Text and TEI - start on demand (def)
    def fReadTextfile() = uploads.readTextfile(doc.ownerName, doc.id, currentPart.getFile)

    // Generic conditional: is the user authorized to see the content? Render 'forbidden' page if not.
    def ifAuthorized(result: Result, annotationCount: Long) =
      if (accesslevel.canReadAll) result else Ok(views.html.document.annotation.forbidden(doc, currentPart, loggedInUser, annotationCount))

    ContentType.withName(currentPart.getContentType) match {

      case Some(ContentType.IMAGE_UPLOAD) | Some(ContentType.IMAGE_IIIF) =>
        f.map { case (count, clonedFrom, clones) =>
          ifAuthorized(Ok(views.html.document.annotation.image(
            doc, 
            currentPart, 
            loggedInUser, 
            accesslevel, 
            clonedFrom,
            clones,
            prefs, 
            count, 
            redirectedVia)), count)
        }

      case Some(ContentType.TEXT_PLAIN) =>
        fReadTextfile() flatMap {
          case Some(content) =>
            f.map { case (count, clonedFrom, clones) =>
              ifAuthorized(Ok(views.html.document.annotation.text(
                doc, 
                currentPart, 
                loggedInUser, 
                accesslevel, 
                clonedFrom,
                clones,
                prefs, 
                count, 
                content, 
                redirectedVia)), count)
            }

          case None =>
            // Filepart found in DB, but not file on filesystem
            Logger.error(s"Filepart recorded in the DB is missing on the filesystem: ${doc.ownerName}, ${doc.id}")
            Future.successful(InternalServerError)
        }

      case Some(ContentType.TEXT_TEIXML) =>
        fReadTextfile flatMap {
          case Some(content) =>
            f.map { case (count, clonedFrom, clones) =>
              val preview = previewFromTEI(content)
              ifAuthorized(Ok(views.html.document.annotation.tei(
                doc, 
                currentPart, 
                loggedInUser, 
                accesslevel, 
                clonedFrom,
                clones,
                prefs, 
                preview, 
                count, 
                redirectedVia)), count)
            }

          case None =>
            // Filepart found in DB, but not file on filesystem
            Logger.error(s"Filepart recorded in the DB is missing on the filesystem: ${doc.ownerName}, ${doc.id}")
            Future.successful(InternalServerError)
        }

      case Some(ContentType.DATA_CSV) =>
        f.map { case (count, clonedFrom, clones) =>
          ifAuthorized(Ok(views.html.document.annotation.table(
            doc, 
            currentPart, 
            loggedInUser, 
            accesslevel, 
            clonedFrom,
            clones, 
            prefs, 
            count)), count)
        }

      case _ =>
        // Unknown content type in DB, or content type we don't have an annotation view for - should never happen
        Future.successful(InternalServerError)
    }

  }*/

}
