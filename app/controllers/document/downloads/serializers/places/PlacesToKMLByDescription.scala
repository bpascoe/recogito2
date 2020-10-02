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
			{scala.xml.PCData(buildKMLString(f, host))}
        </description>
        <TimeSpan>
          <begin>{val temporal = f.records(0).temporalBounds
          if (temporal != None) {temporal.get.from.toString} else {""}
        }</begin>
          <end>{val temporal = f.records(0).temporalBounds
          if (temporal != None) {temporal.get.to.toString} else {""}
        }</end>
        </TimeSpan>
          
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
	
	//scala.xml.XML.loadString(kmlString);
  } 

// Scala xml handling within a map is deeply frustrating. 
// It seems this is a commonly experienced problem: https://github.com/scala/bug/issues/3368
// You try CDATA, you try quotes, you try interpolation, you try literal, you try adding a comma, whatever you do it breaks something else, you get it printing out names of objects, you get errors on quotes, you can't escape quotes, it encodes entities when you don't want it to, etc etc etc.
// Even if I try to skip all that and just loop the data and build a string, when I pass that back out, it still encodes the CDATA, and turns out unworkable.
// The only thing that seems to work is if ever you need CDATA, pass the required data to a method that is interpolated into the XML wrapped in the scala.xml.PCData() method.

  def buildKMLString (f: AnnotatedPlaceFeature, host: String) : String = {

	var outCdata: String = "";

		outCdata = outCdata + """
			<div class="tlcmap recogito2">
			<table>
              <tr><td>ID</td><td><a href="""" + f.records.map(_.uri).distinct.mkString(", ") + """">""" + f.records.map(_.uri).distinct.mkString(", ") + """</a></td></tr>
              <tr><td>Title</td><td>""" + f.titles.mkString(",") + """</td></tr>
              <tr><td>Latitude</td><td>""" + f.geometry.getCentroid.getY + """</td></tr>
              <tr><td>Longitude</td><td>""" + f.geometry.getCentroid.getX + """</td></tr>
              <tr><td>CCode</td><td>""" + f.records.flatMap(_.countryCode).map(_.code).distinct.mkString(", ") + """</td></tr>
              <tr><td>Description</td><td>""" + f.records.flatMap(_.descriptions).map(_.description).mkString(", ") + """</td></tr>
              <tr><td>Contributor</td><td>""" + f.annotations.map(_.contributors.mkString(", ")).distinct.mkString(", ") + """</td></tr>
              <tr><td>Source</td><td>""" + f.records.map(_.sourceAuthority).distinct.mkString(", ") + """</td></tr>
            </table>
			<h3>Occurences in Text:</h3>
              <p class="annotation-links">
			  """
			  
            f.annotations.zipWithIndex.map {
                case(annotation, count) => 
                  outCdata = outCdata + """<a href="""" + host + annotation.annotationId + """">""" + {count + 1} + """</a>, """
            }
			outCdata = outCdata.replaceAll(", $", ""); // remove the trailing comma
			outCdata = outCdata + """
              </p>
			</div>
		"""
	return outCdata;
  }

}
