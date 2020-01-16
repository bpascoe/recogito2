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
          <div id="{documentId}" class="tlcmap recogito2">
          <h1>{f.quotes.distinct.mkString(",")} </h1>
            <!-- <h3>Annotations</h3> {f.annotations.length}
            <h3>Place URIs</h3> {f.records.map(_.uri).mkString(", ")} 
            <h3>Names (Gazetteer)</h3> {f.titles.mkString(",")}
            <h3>Toponyms (Document)</h3> {f.quotes.distinct.mkString(",")}
          <ul class="annotation-links">
              {f.annotations.map (annotation=>
                 <li><a href={host + annotation.annotationId}>{host + annotation.annotationId}</a></li>
              )
              }
            </ul>-->
            <h3>Instances in Text:</h3>
              <p class="annotation-links">
              {f.annotations.zipWithIndex.map {
                case(annotation, count) => 
                  <a href={host + annotation.annotationId}> {count + 1} </a>
                  }
                }
              
              </p>
          </div>
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
