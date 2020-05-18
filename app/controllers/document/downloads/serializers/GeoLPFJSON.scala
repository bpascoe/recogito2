package controllers.document.downloads.serializers

import com.vividsolutions.jts.geom.Geometry
import play.api.libs.json._
import play.api.libs.functional.syntax._
import services.annotation.{Annotation, AnnotationBody}
import services.entity.EntityRecord


trait GeoLPFJSONFeature

// TODO document metadata
case class GeoLPFJSONFeatureCollection[T <: GeoLPFJSONFeature](features: Seq[T])

object GeoLPFJSONFeatureCollection {
  
  implicit def featureCollectionLPFWrites[T <: GeoLPFJSONFeature](implicit w: Writes[T]) = new Writes[GeoLPFJSONFeatureCollection[T]] {
    def writes(fc: GeoLPFJSONFeatureCollection[T]) = Json.obj(
      "type" -> "FeatureCollection",
      "@context" -> "https://raw.githubusercontent.com/LinkedPasts/linked-places/master/linkedplaces-context-v1.jsonld",
      "features" -> Json.toJson(fc.features)
    )
  }

}