package controllers.document.downloads.serializers.places

import com.vividsolutions.jts.geom.{Polygon, LineString}
import scala.concurrent.ExecutionContext
import services.annotation.{AnnotationService, AnnotationBody}
import services.entity.EntityType
import services.entity.builtin.EntityService
import storage.es.ES
import java.net._

trait PlacesToKMLByDescription extends BaseGeoSerializer {

  def placesToKMLByDescription(
    documentId: String
  )(implicit 
      entityService: EntityService, 
      annotationService: AnnotationService, 
      ctx: ExecutionContext
  ) = getMappableFeatures(documentId).map { features => 
    val host = "http://" + InetAddress.getLocalHost.getHostName + ":9000/annotation2/"
    val cdatastart = "<![CDATA["
    val cdataend = "]]>"
    val kmlFeatures = features.map { f => 
      <Placemark>
        <name>{f.quotes.distinct.mkString(",")}</name>
        <description>
          {scala.xml.PCData("<div id=\""+{documentId}+"\""+" class=\"tlcmap recogito2\">"+
            <table>
              <tr><td>ID</td><td>{f.records.map(_.uri).distinct.mkString(", ")}</td></tr>
              <tr><td>Title</td><td>{f.titles.mkString(",")}</td></tr>
              <tr><td>Latitude</td><td>{f.geometry.getCentroid.getY}</td></tr>
              <tr><td>Longitude</td><td>{f.geometry.getCentroid.getX}</td></tr>
              <tr><td>CCode</td><td>{f.records.flatMap(_.countryCode).map(_.code).distinct.mkString(", ")}</td></tr>
              <tr><td>Description</td><td>{f.records.flatMap(_.descriptions).map(_.description).mkString(", ")}</td></tr>
              <tr><td>Contributor</td><td>{f.annotations.map(_.contributors.mkString(", ")).distinct.mkString(", ")}</td></tr>
              <tr><td>Source</td><td>{f.records.map(_.sourceAuthority).distinct.mkString(", ")}</td></tr>
            </table>+
            "</div>")}
        </description>
          
          { f.geometry match {
            case geom: Polygon => 
            <Polygon>
              <extrude>1</extrude>
              <altitudeMode>clampToGround</altitudeMode>
              <outerBoundaryIs>
                <LinearRing>
                  <coordinates>
                    { geom.getCoordinates.map { coord => 
                      s"${coord.x},${coord.y},0\n"
                    }}
                  </coordinates>
                </LinearRing>
              </outerBoundaryIs>
            </Polygon>
          case geom =>
            <Point>
              <coordinates>{f.geometry.getCentroid.getX},{f.geometry.getCentroid.getY},0</coordinates>
            </Point>
        }}
      </Placemark>
    }

    <kml xmlns="http://www.opengis.net/kml/2.2">
      <Document>
        { kmlFeatures }
      </Document>
    </kml>
  } 

}
