package controllers.document.downloads.serializers.places

import com.vividsolutions.jts.geom.Geometry
import controllers.document.downloads.serializers.{BaseSerializer, GeoLPFJSONFeature}
import scala.concurrent.{ExecutionContext, Future, Await}
import services.annotation.{Annotation, AnnotationService, AnnotationBody}
import services.entity.{EntityType, EntityRecord}
import services.entity.builtin.EntityService
import storage.es.ES
import scala.concurrent.duration._

case class AnnotatedPlaceFeatures(
  geometry: Geometry,
  records: Seq[EntityRecord],
  annotations: Seq[Annotation]
) extends GeoLPFJSONFeature {

  private val bodies = annotations.flatMap(_.bodies)
  private def bodiesOfType(t: AnnotationBody.Type) = bodies.filter(_.hasType == t)

  val titles = records.map(_.title.trim).distinct
  val quotes = bodiesOfType(AnnotationBody.QUOTE).flatMap(_.value)
  val comments = bodiesOfType(AnnotationBody.COMMENT).flatMap(_.value)
  val tags = bodiesOfType(AnnotationBody.TAG).flatMap(_.value)

}

trait BaseGeoLPFSerializer extends BaseSerializer {

  def getLPFMappableFeatures(
    identifier: String
  )(implicit 
      entityService: EntityService, 
      annotationService: AnnotationService, 
      ctx: ExecutionContext
  ): Future[Seq[AnnotatedPlaceFeatures]] = {
    // val fAnnotations = annotationService.findByDocId(documentId, 0, ES.MAX_SIZE)
    val fPlaces = entityService.listEntitiesInContribution(identifier)
    // val fAnnotations = annotationService.findByUnionIds(fPlaces)
        
    val f = for {
      // annotations <- fAnnotations
      places <- fPlaces
    } yield (places)
    
    f.map { case (places) =>
      // Each place in this document, along with all the annotations on this place and 
      // the specific entity records the annotations point to (within the place union record) 
      places.flatMap { e =>      
        val place = e.entity
        // val annotationsOnThisPlace = annotations
        val annotation = Await.result(annotationService.findByUnionId(place.unionId.toString), 10.seconds)

        val annotationsOnThisPlace = annotation.map(_._1)
        // val placeURIs = annotationsOnThisPlace.flatMap(_.bodies).filter(_.hasType == AnnotationBody.PLACE).flatMap(_.uri)
        val referencedRecords = place.isConflationOf//.filter(g => placeURIs.contains(g.uri))
        
        place.representativeGeometry.map { geom => 
          AnnotatedPlaceFeatures(geom, referencedRecords, annotationsOnThisPlace)
        }
      }
    }        
  }

}