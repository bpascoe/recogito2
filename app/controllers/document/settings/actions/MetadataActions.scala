package controllers.document.settings.actions

import controllers.document.settings.SettingsController
import java.util.UUID
import services.document.PartOrdering
import services.user.Roles._
import services.generated.tables.records.DocumentRecord
import play.api.data.Form
import play.api.data.Forms._
import play.api.libs.json._
import play.api.libs.functional.syntax._
import scala.concurrent.Future
import services.annotation.AnnotationService
// import java.sql.Timestamp
// import play.api.data.format.Formats._

case class DocumentMetadata(
  filename    : String,
  title       : Option[String],
  author      : Option[String],
  description : Option[String],
  language    : Option[String],
  source      : Option[String],
  edition     : Option[String],
  license     : Option[String],
  attribution : Option[String],
  publicationPlace : Option[String],
  startDate   : Option[String],
  endDate     : Option[String],
  latitude    : Option[String],
  longitude   : Option[String])

case class FilepartMetadata(
  title : String,
  source: Option[String])

trait MetadataActions { self: SettingsController =>
  implicit val orderingReads: Reads[PartOrdering] = (
    (JsPath \ "id").read[UUID] and
    (JsPath \ "sequence_no").read[Int]
  )(PartOrdering.apply _)

  /** Sets the part sort order **/
  def setSortOrder(docId: String) = self.silhouette.SecuredAction.async { implicit request =>
    jsonDocumentAdminAction[Seq[PartOrdering]](docId, request.identity.username, { case (document, ordering) =>
      documents.setFilepartSortOrder(docId, ordering).map(_ => Status(200))
    })
  }

  val documentMetadataForm = Form(
    mapping(
      "filename" -> nonEmptyText,
      "title" -> optional(text),
      "author" -> optional(text),
      "description" -> optional(text(maxLength=1024)),
      "language" -> optional(text.verifying("2- or 3-digit ISO language code required", { t => t.size > 1 && t.size < 4 })),
      "source" -> optional(text),
      "edition" -> optional(text),
      "license" -> optional(text),
      "attribution" -> optional(text),
      "publication_place" -> optional(text),
      // "start_date" -> optional(of[Timestamp]),
      "start_date" -> optional(text),
      "end_date" -> optional(text),
      "latitude" -> optional(text),
      "longitude" -> optional(text)
    )(DocumentMetadata.apply)(DocumentMetadata.unapply)
  )

  protected def metadataForm(doc: DocumentRecord) = {
    documentMetadataForm.fill(DocumentMetadata(
      doc.getFilename,
      Option(doc.getTitle),
      Option(doc.getAuthor),
      Option(doc.getDescription),
      Option(doc.getLanguage),
      Option(doc.getSource),
      Option(doc.getEdition),
      Option(doc.getLicense),
      Option(doc.getAttribution),
      Option(doc.getPublicationPlace),
      Option(doc.getStartDate),
      Option(doc.getEndDate),
      Option(doc.getLatitude),
      Option(doc.getLongitude)))
  }

  def updateDocumentMetadata(docId: String) = self.silhouette.SecuredAction.async { implicit request =>
    documentAdminAction(docId, request.identity.username, { doc =>
      documentMetadataForm.bindFromRequest.fold(
        formWithErrors =>
          Future.successful(BadRequest(views.html.document.settings.metadata(formWithErrors, doc, request.identity))),

        f =>
          documents.updateMetadata(
            docId, f.filename, f.title, f.author, f.description, f.language, f.source, f.edition, f.license, f.attribution, f.publicationPlace, f.startDate, f.endDate, f.latitude, f.longitude
          ).map { success =>
           if (success){
            annotations.findByDocId(docId).map { result =>
              val annotation = result.map(_._1)
              annotation.map(e=>annotations.upsertAnnotation(e.copy(startDate=f.startDate,endDate=f.endDate)) )
            }
            Redirect(controllers.document.settings.routes.SettingsController.showDocumentSettings(docId, Some("metadata")))
              .flashing("success" -> "Your settings have been saved.")
           } else
              Redirect(controllers.document.settings.routes.SettingsController.showDocumentSettings(docId, Some("metadata")))
                .flashing("error" -> "There was an error while saving your settings.")
          }.recover { case t:Throwable =>
            t.printStackTrace()
            Redirect(controllers.document.settings.routes.SettingsController.showDocumentSettings(docId, Some("metadata")))
              .flashing("error" -> "There was an error while saving your settings.")
          }
      )
    })
  }

  def updateFilepartMetadata(docId: String, partId: UUID) = self.silhouette.SecuredAction.async { implicit request =>

    def bindFromRequest(): Either[String, FilepartMetadata] =
      getFormParam("title") match {
        case Some(title) if title.isEmpty => Left("Title required")
        case Some(title) => Right(FilepartMetadata(title, getFormParam("source")))
        case None => Left("Title required")
      }

    documentAdminAction(docId, request.identity.username, { doc =>
      // Make sure we're not updating a part that isn't in this document
      if (doc.fileparts.exists(_.getId == partId)) {
        bindFromRequest() match {
          case Right(partMetadata) =>
            documents.updateFilepartMetadata(doc.id, partId, partMetadata.title, partMetadata.source).map { success =>
              if (success) Ok
              else InternalServerError
            }

          case Left(error) =>
            Future.successful(BadRequest(error))
        }
      } else {
        Future.successful(BadRequest)
      }
    })
  }

}
