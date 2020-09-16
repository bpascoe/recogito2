package controllers.document.downloads.serializers.places

import com.vividsolutions.jts.geom.Geometry
import controllers.document.downloads.serializers.{BaseSerializer, GeoJSONFeature}
import scala.concurrent.{ExecutionContext, Future}
import services.annotation.{Annotation, AnnotationService, AnnotationBody}
import services.entity.{EntityType, EntityRecord}
import services.entity.builtin.EntityService
import storage.es.ES
import scala.concurrent.Await
import scala.concurrent.duration._

case class AnnotationPlaceFeature(
  geometry: Geometry,
  records: Seq[EntityRecord],
  annotations: Seq[Annotation]
) extends GeoJSONFeature {

  private val bodies = annotations.flatMap(_.bodies)
  private def bodiesOfType(t: AnnotationBody.Type) = bodies.filter(_.hasType == t)

  val titles = records.map(_.title.trim).distinct
  val quotes = bodiesOfType(AnnotationBody.QUOTE).flatMap(_.value)
  val comments = bodiesOfType(AnnotationBody.COMMENT).flatMap(_.value)
  val tags = bodiesOfType(AnnotationBody.TAG).flatMap(_.value)

}

trait BaseGeoAnnotationSerializer extends BaseSerializer {

  def getAnnotationMappableFeatures(
    documentId: String
  )(implicit 
      entityService: EntityService, 
      annotationService: AnnotationService, 
      ctx: ExecutionContext
  ): Future[Seq[AnnotationPlaceFeature]] = {
    val fAnnotations = annotationService.findByDocId(documentId, 0, ES.MAX_SIZE)
    val fPlaces = entityService.listEntitiesInDocument(documentId, Some(EntityType.PLACE), 0, ES.MAX_SIZE)
        
    val f = for {
      annotations <- fAnnotations
      places <- fPlaces
    } yield (annotations.map(_._1), places)
    
    f.map { case (annotations, places) =>
      // All place annotations on this document
      val placeAnnotations = annotations.filter(_.bodies.map(_.hasType).contains(AnnotationBody.PLACE))  

      // Each place in this document, along with all the annotations on this place and 
      // the specific entity records the annotations point to (within the place union record) 
      places.items.flatMap { e =>      
        val place = e._1.entity

        val annotationsOnThisPlace = placeAnnotations.filter { a =>
          // All annotations that include place URIs of this place
          val placeURIs = a.bodies.filter(_.hasType == AnnotationBody.PLACE).flatMap(_.uri)
          !placeURIs.intersect(place.uris).isEmpty
        }

        val placeURIs = annotationsOnThisPlace.flatMap(_.bodies).filter(_.hasType == AnnotationBody.PLACE).flatMap(_.uri)
        val referencedRecords = place.isConflationOf.filter(g => placeURIs.contains(g.uri))
        
        place.representativeGeometry.map { geom => 
          AnnotationPlaceFeature(geom, referencedRecords, annotationsOnThisPlace)
        }
      }
    } 
  }

  def getAnnotationMappableFeaturesByIds(
    docIds: Seq[String]
  )(implicit 
      entityService: EntityService, 
      annotationService: AnnotationService, 
      ctx: ExecutionContext
  ): Future[Seq[AnnotationPlaceFeature]] = {
    val fAnnotations = annotationService.findByDocIds(docIds, 0, ES.MAX_SIZE)
    val fPlaces = entityService.listEntitiesInDocuments(docIds, Some(EntityType.PLACE), 0, ES.MAX_SIZE)
        
    val f = for {
      annotations <- fAnnotations
      places <- fPlaces
    } yield (annotations.map(_._1), places)
    
    f.map { case (annotations, places) =>
      // All place annotations on this document
      val placeAnnotations = annotations.filter(_.bodies.map(_.hasType).contains(AnnotationBody.PLACE))  

      // Each place in this document, along with all the annotations on this place and 
      // the specific entity records the annotations point to (within the place union record) 
      places.items.flatMap { e =>      
        val place = e._1.entity

        val annotationsOnThisPlace = placeAnnotations.filter { a =>
          // All annotations that include place URIs of this place
          val placeURIs = a.bodies.filter(_.hasType == AnnotationBody.PLACE).flatMap(_.uri)
          !placeURIs.intersect(place.uris).isEmpty
        }

        val placeURIs = annotationsOnThisPlace.flatMap(_.bodies).filter(_.hasType == AnnotationBody.PLACE).flatMap(_.uri)
        val referencedRecords = place.isConflationOf.filter(g => placeURIs.contains(g.uri))
        
        place.representativeGeometry.map { geom => 
          AnnotationPlaceFeature(geom, referencedRecords, annotationsOnThisPlace)
        }
      }
    }        
  }

}