package controllers.document.downloads.serializers.places

import com.vividsolutions.jts.geom.Geometry
import controllers.document.downloads.serializers.{BaseSerializer, GeoJSONFeature}
import scala.concurrent.{ExecutionContext, Future}
import services.annotation.{Annotation, AnnotationService, AnnotationBody}
import services.entity.{Entity,EntityType, EntityRecord}
import services.entity.builtin.EntityService
import services.document.DocumentService
import storage.es.ES
import scala.concurrent.Await
import scala.concurrent.duration._

case class AnnotationPlaceFeature(
  // geometry: Geometry,
  // records: Seq[EntityRecord],
  // annotations: Seq[Annotation]
  records: Entity,
  annotations: Annotation
) extends GeoJSONFeature {

  // private val bodies = annotations.flatMap(_.bodies)
  private val bodies = annotations.bodies
  private def bodiesOfType(t: AnnotationBody.Type) = bodies.filter(_.hasType == t)

  // val titles = records.map(_.title.trim).distinct
  val titles = records.title
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
      // val sortAnnotationsByLocation = placeAnnotations.sortBy { annotation =>
      //   val a = annotation.anchor
      //   val startOffset = a.substring(12).toInt
      //   startOffset
      // }
      val sortAnnotationsByLocation = sortByCharOffset(placeAnnotations)
      
      // Each annotation in this document
      sortAnnotationsByLocation.flatMap { a=>
        val placeURIs = a.bodies.filter(_.hasType == AnnotationBody.PLACE).flatMap(_.uri)
        val placeOnThisAnnotation = places.items.filter {e=>
          val place = e._1.entity
          // val placeURIs = a.bodies.filter(_.hasType == AnnotationBody.PLACE).flatMap(_.uri)
          // !annotationURI.intersect(place.uris).isEmpty
          // val placeURIs = placeOnThisAnnotation.flatMap(_.bodies).filter(_.hasType == AnnotationBody.PLACE).flatMap(_.uri)
          // referencedRecords = place.isConflationOf.filter(g => placeURIs.contains(g.uri))
          !placeURIs.intersect(place.uris).isEmpty
        }
        // val referencedRecords = placeOnThisAnnotation.isConflationOf.filter(g => placeURIs.contains(g.uri))
        placeOnThisAnnotation.map { geom => 
          AnnotationPlaceFeature(placeOnThisAnnotation(0)._1.entity, a)
        }
        // AnnotationPlaceFeature(placeOnThisAnnotation(0)._1.entity, a)
      }
      // places.items.flatMap { e =>      
      //   val place = e._1.entity

      //   val annotationsOnThisPlace = placeAnnotations.filter { a =>
      //     // All annotations that include place URIs of this place
      //     val placeURIs = a.bodies.filter(_.hasType == AnnotationBody.PLACE).flatMap(_.uri)
      //     !placeURIs.intersect(place.uris).isEmpty
      //   }

      //   val placeURIs = annotationsOnThisPlace.flatMap(_.bodies).filter(_.hasType == AnnotationBody.PLACE).flatMap(_.uri)
      //   val referencedRecords = place.isConflationOf.filter(g => placeURIs.contains(g.uri))
        
      //   place.representativeGeometry.map { geom => 
      //     AnnotationPlaceFeature(geom, referencedRecords, annotationsOnThisPlace)
      //   }
      // }
    } 
  }

  def getAnnotationMappableFeaturesByIds(
    docIds: Seq[String]
  )(implicit 
      entityService: EntityService, 
      annotationService: AnnotationService, 
      ctx: ExecutionContext,
      documents: DocumentService
  ) = {
    // docIds.map { docId=>
    //   val doc = Await.result(documents.getDocumentById(docId),1.seconds)
    //   // val doc = documents.getDocumentById(docId)
    //   (doc, getAnnotationMappableFeatures(docId))
    // }
    
    // val fAnnotations = docIds.map { docId => 
    //   val annotations = annotationService.findByDocId(docId, 0, ES.MAX_SIZE)
    //   annotations.map(_._1)
    // }
    val fAnnotationsByDocs = Future.sequence {
      docIds.map { docId => 
        val doc = Await.result(documents.getDocumentById(docId),1.seconds)
        annotationService.findByDocId(docId).map { annotations => 
          (doc, annotations.map(_._1))
        }
      }
    }

    val fAnnotations = annotationService.findByDocIds(docIds, 0, ES.MAX_SIZE)
    val fPlaces = entityService.listEntitiesInDocuments(docIds, Some(EntityType.PLACE), 0, ES.MAX_SIZE)
        
    val f = for {
      annotations <- fAnnotationsByDocs
      places <- fPlaces
    } yield (annotations, places)
    
    f.map { case (annotationsByDocs, places) =>
      // All place annotations on this document
      // val (doc, annotations) = annotationsByDocs
      annotationsByDocs.map { case(doc, annotations) =>
        annotations.filter(_.bodies.map(_.hasType).contains(AnnotationBody.PLACE))
      val placeAnnotations = annotations.filter(_.bodies.map(_.hasType).contains(AnnotationBody.PLACE))  
      val sortAnnotationsByLocation = sortByCharOffset(placeAnnotations)
      
      // Each place in this document, along with all the annotations on this place and 
      // the specific entity records the annotations point to (within the place union record) 
      sortAnnotationsByLocation.flatMap { a=>
        val placeURIs = a.bodies.filter(_.hasType == AnnotationBody.PLACE).flatMap(_.uri)
        val placeOnThisAnnotation = places.items.filter {e=>
          val place = e._1.entity
          // val placeURIs = a.bodies.filter(_.hasType == AnnotationBody.PLACE).flatMap(_.uri)
          // !annotationURI.intersect(place.uris).isEmpty
          // val placeURIs = placeOnThisAnnotation.flatMap(_.bodies).filter(_.hasType == AnnotationBody.PLACE).flatMap(_.uri)
          // referencedRecords = place.isConflationOf.filter(g => placeURIs.contains(g.uri))
          !placeURIs.intersect(place.uris).isEmpty
        }
        // val referencedRecords = placeOnThisAnnotation.isConflationOf.filter(g => placeURIs.contains(g.uri))
        placeOnThisAnnotation.map { geom => 
          (doc,AnnotationPlaceFeature(placeOnThisAnnotation(0)._1.entity, a))
        }
        // AnnotationPlaceFeature(placeOnThisAnnotation(0)._1.entity, a)
      }
    }}     
  }

}