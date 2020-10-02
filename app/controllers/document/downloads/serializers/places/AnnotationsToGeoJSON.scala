package controllers.document.downloads.serializers.places

import com.vividsolutions.jts.geom.{Coordinate, Geometry}
import controllers.HasCSVParsing
import controllers.document.downloads.FieldMapping
import controllers.document.downloads.serializers._
import java.io.File
import org.geotools.geometry.jts.JTSFactoryFinder
import play.api.libs.json._
import play.api.libs.functional.syntax._
import scala.concurrent.ExecutionContext
import scala.util.Try
import services.{ContentType, HasGeometry, HasNullableSeq}
import services.annotation.{Annotation, AnnotationBody, AnnotationService}
import services.entity.{Entity, EntityRecord, EntityType}
import services.entity.builtin.EntityService
import storage.es.ES 
import storage.uploads.Uploads
import services.document.DocumentService
// BaseGeoAnnotationSerializer
trait AnnotationsToGeoJSON extends BaseGeoSerializer 
  with HasCSVParsing 
  with HasNullableSeq 
  with HasGeometry {
  
  implicit val geoJsonAnnotationFeatureWrites: Writes[AnnotatedPlaceFeature] = (
    (JsPath \ "type").write[String] and
    (JsPath \ "geometry").write[Geometry] and
    (JsPath \ "properties").write[JsObject] and
    (JsPath \ "uris").write[Seq[String]] and
    (JsPath \ "titles").write[Seq[String]] and
    (JsPath \ "names").writeNullable[Seq[String]] and
    (JsPath \ "place_types").writeNullable[Seq[String]] and
    (JsPath \ "source_gazetteers").write[Seq[String]] and
    (JsPath \ "quotes").writeNullable[Seq[String]] and
    (JsPath \ "tags").writeNullable[Seq[String]] and
    (JsPath \ "comments").writeNullable[Seq[String]] 
  )(f => (
      "Feature",
      f.geometry,
      Json.obj(
        "titles" -> f.titles.mkString(", "),
        "annotations" -> f.annotations.size
      ),
      f.records.map(_.uri),
      f.records.map(_.title),
      toOptSeq(f.records.flatMap(_.names.map(_.name))),
      toOptSeq(f.records.flatMap(_.subjects)),
      f.records.map(_.sourceAuthority),
      toOptSeq(f.quotes),
      toOptSeq(f.tags),
      toOptSeq(f.comments)
    )
  )

  def placesToGeoJSONAnnotation(documentId: String)(implicit entityService: EntityService, annotationService: AnnotationService, ctx: ExecutionContext) = {
    getMappableFeatures(documentId).map { features =>
    // getAnnotationMappableFeatures(documentId).map { features => 
      Json.toJson(GeoJSONFeatureCollection(features))
    }        
  }
  def placesToGeoJSONAnnotationCorpus(docIds: Seq[String])(implicit entityService: EntityService, annotationService: AnnotationService, ctx: ExecutionContext,
      documents: DocumentService) = {
    getMappableFeatures(docIds(0)).map { features =>
    // getMappableFeaturesByIds(docIds).map { features => 
    // getAnnotationMappableFeaturesByIds(docIds).map { features => 
      Json.toJson(GeoJSONFeatureCollection(features))
    }        
  }
    
}



  
