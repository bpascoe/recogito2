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
trait AnnotationsToGeoJSON extends BaseGeoAnnotationSerializer 
  with HasCSVParsing 
  with HasNullableSeq 
  with HasGeometry {

  def placesToGeoJSONAnnotation(documentId: String)(implicit entityService: EntityService, annotationService: AnnotationService, ctx: ExecutionContext) = {
    getAnnotationMappableFeatures(documentId).map { features => 
      Json.toJson(Json.obj(
      "type" -> "FeatureCollection",
          "features" -> Json.toJson(features.map{f=>{
          Json.obj("type" -> "Feature",
          "geometry" -> f.records.representativeGeometry,
          "properties" -> Json.obj(
            "uuid" -> f.annotations.annotationId.toString,
            "name" -> f.quotes.mkString(", "),
            "time_span" -> Json.obj(
              "start" -> f.annotations.startDate,
              "end" -> f.annotations.endDate
            ),
            "modified_by" -> f.annotations.lastModifiedBy,
            "modified_at" -> f.annotations.lastModifiedAt.toString
          ),
          "uri" -> f.annotations.bodies(0).uri,
          "title" -> f.records.title,
          "name" -> f.quotes.mkString(", "),
          "annotation_type" -> f.records.entityType,
          "source_gazetteer" -> f.records.isConflationOf(0).sourceAuthority,
          "quotes" -> toOptSeq(f.quotes),
          "tags" -> toOptSeq(f.tags),
          "comments" -> toOptSeq(f.comments)
          )}
      })))
    }        
  }
  def placesToGeoJSONAnnotationCorpus(docIds: Seq[String])(implicit entityService: EntityService, annotationService: AnnotationService, ctx: ExecutionContext,
      documents: DocumentService) = {
    getAnnotationMappableFeaturesByIds(docIds).map { features => 
      Json.toJson(Json.obj(
      "type" -> "FeatureCollection",
      "folder" -> {var i = -1
        features.map{feature=>{
        i = i + 1
        val (d, _) = feature(i)
        Json.obj("id"->d.getId,
          "name" -> d.getFilename,
          "features" -> Json.toJson(feature.map{case (doc, f)=>{
          Json.obj("type" -> "Feature",
          "geometry" -> f.records.representativeGeometry,
          "properties" -> Json.obj(
            "uuid" -> f.annotations.annotationId.toString,
            "name" -> f.quotes.mkString(", "),
            "time_span" -> Json.obj(
              "start" -> f.annotations.startDate,
              "end" -> f.annotations.endDate
            ),
            "modified_by" -> f.annotations.lastModifiedBy,
            "modified_at" -> f.annotations.lastModifiedAt.toString
          ),
          "uri" -> f.annotations.bodies(0).uri,
          "title" -> f.records.title,
          "name" -> f.quotes.mkString(", "),
          "annotation_type" -> f.records.entityType,
          "source_gazetteer" -> f.records.isConflationOf(0).sourceAuthority,
          "quotes" -> toOptSeq(f.quotes),
          "tags" -> toOptSeq(f.tags),
          "comments" -> toOptSeq(f.comments)
          )}
      }))}}}
    ))
  }}
    
}