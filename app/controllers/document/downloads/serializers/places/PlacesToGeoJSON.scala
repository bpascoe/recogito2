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

trait PlacesToGeoJSON extends BaseGeoSerializer 
  with HasCSVParsing 
  with HasNullableSeq 
  with HasGeometry {
  
  implicit val geoJsonFeatureWrites: Writes[AnnotatedPlaceFeature] = (
    (JsPath \ "type").write[String] and
    (JsPath \ "geometry").write[Geometry] and
    (JsPath \ "properties").write[JsObject] and
    (JsPath \ "uris").write[Seq[String]] and
    (JsPath \ "titles").write[Seq[String]] and
    (JsPath \ "names").writeNullable[Seq[String]] and
    (JsPath \ "place_types").writeNullable[Seq[String]] and
    (JsPath \ "source_gazetteers").write[Seq[String]] and
    (JsPath \ "time_span").write[JsObject] and
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
      Json.obj(
        "start" -> {val temporal = f.records(0).temporalBounds
          if (temporal != None) {temporal.get.from.toString} else {""}
        },
        "end" -> {val temporal = f.records(0).temporalBounds
          if (temporal != None) {temporal.get.to.toString} else {""}
        }
      ),
      toOptSeq(f.quotes),
      toOptSeq(f.tags),
      toOptSeq(f.comments)
    )
  )

  def placesToGeoJSON(documentId: String)(implicit entityService: EntityService, annotationService: AnnotationService, ctx: ExecutionContext) = {
    getMappableFeatures(documentId).map { features => 
      Json.toJson(GeoJSONFeatureCollection(features))
    }        
  }
  def placesToGeoJSONCorpus(docIds: Seq[String])(implicit entityService: EntityService, annotationService: AnnotationService, ctx: ExecutionContext,
      documents: DocumentService) = {
    getMappableFeaturesByIds(docIds).map { features => 
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
          "geometry" -> f.geometry,
          "properties" -> Json.obj(
            "titles" -> f.titles.mkString(", "),
            "annotations" -> f.annotations.size
          ),
          "uris" -> f.records.map(_.uri),
          "titles" -> f.records.map(_.title),
          "names" -> toOptSeq(f.records.flatMap(_.names.map(_.name))),
          "place_types" -> toOptSeq(f.records.flatMap(_.subjects)),
          "source_gazetteers" -> f.records.map(_.sourceAuthority),
          "time_span" -> Json.obj(
            "start" -> {val temporal = f.records(0).temporalBounds
              if (temporal != None) {temporal.get.from.toString} else {""}
            },
            "end" -> {val temporal = f.records(0).temporalBounds
              if (temporal != None) {temporal.get.to.toString} else {""}
            },
          "quotes" -> toOptSeq(f.quotes),
          "tags" -> toOptSeq(f.tags),
          "comments" -> toOptSeq(f.comments)
          ))}
      }))}}}
    ))
      // Json.toJson(GeoJSONFeatureCollection(f))
    }
  }
    
}



  
