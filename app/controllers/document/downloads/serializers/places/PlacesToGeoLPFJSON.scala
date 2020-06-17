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

import services.entity.{Description, Name}
import services.HasDate
import org.joda.time.DateTime

trait PlacesToGeoLPFJSON extends BaseGeoLPFSerializer 
  with HasCSVParsing 
  with HasNullableSeq 
  with HasDate
  with HasGeometry {
  
  implicit val geoLPFJsonFeatureWrites: Writes[AnnotatedPlaceFeatures] = (
    (JsPath \ "@id").write[String] and
    (JsPath \ "type").write[String] and
    (JsPath \ "properties").write[JsObject] and
    (JsPath \ "names").writeNullable[Seq[Name]] and
    (JsPath \ "geometry").write[JsObject] and
    // (JsPath \ "source_gazetteers").write[Seq[String]] and
    // (JsPath \ "tags").writeNullable[Seq[String]] and
    (JsPath \ "descriptions").writeNullable[Seq[Description]] and
    (JsPath \ "quotes").writeNullable[Seq[String]] and
    (JsPath \ "source_gazetteers").write[Seq[String]] and
    (JsPath \ "lastSyncedAt").write[Seq[DateTime]] and
    (JsPath \ "contributors").write[Seq[String]]
  )(f => (
      f.records.map(_.uri).mkString(", "),
      "Feature",
      Json.obj(
        "title" -> f.records.map(_.title).mkString(", "),
        "ccodes" -> f.records.flatMap(_.countryCode).map(_.code).mkString(", ")
      ),
      toOptSeq(f.records.flatMap(_.names)),
      // f.geometry,
      Json.obj(
        "type" -> "GeometryCollection",
        "geometries" -> f.geometry
      ),
      toOptSeq(f.records.flatMap(_.descriptions)),
      toOptSeq(f.quotes),
      f.records.map(_.sourceAuthority),
      f.records.map(_.lastSyncedAt),
      f.annotations.map(_.contributors.mkString(", "))
    )
  )

  def placesToGeoLPFJSON(identifier: String)(implicit entityService: EntityService, annotationService: AnnotationService, ctx: ExecutionContext) = {
    getLPFMappableFeatures(identifier).map { features => 
      Json.toJson(GeoLPFJSONFeatureCollection(features))
    }        
  }
    
}



  
