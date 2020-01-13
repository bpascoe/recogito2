package controllers.document.downloads.serializers.places

import com.vividsolutions.jts.geom.{Polygon, LineString}
import scala.concurrent.ExecutionContext
import services.annotation.{AnnotationService, AnnotationBody}
import services.entity.EntityType
import services.entity.builtin.EntityService
import storage.es.ES

trait PlacesToKMLByDescription extends BaseGeoSerializer {

  def placesToKMLByDescription(
    documentId: String
  )(implicit 
      entityService: EntityService, 
      annotationService: AnnotationService, 
      ctx: ExecutionContext
  ) = getMappableFeatures(documentId).map { features => 

    val kmlFeatures = features.map { f => 
      <Placemark>
        <name>{f.quotes.distinct.mkString(",")}</name>
        <description>{}
          <h1>Hello I am {f.quotes.distinct.mkString(",")} </h1>
          <div id={documentId} class="tlcmap">
            <h3>Annotations</h3> {f.annotations.length}
            <h3>Place URIs</h3> {f.records.map(_.uri).mkString(", ")} 
            <h3>Names (Gazetteer)</h3> {f.titles.mkString(",")}
            <h3>Toponyms (Document)</h3> {f.quotes.distinct.mkString(",")}
          </div>
          <ul class="annotation-links">
            { for (annotation <- f.annotations) {
                {annotation}
               <li><a href="#">Diogenes text 1 </a></li> 
            }}
          </ul>

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