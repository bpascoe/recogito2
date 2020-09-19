package controllers.document.downloads.serializers.places

import com.vividsolutions.jts.geom.{Polygon, LineString}
import scala.concurrent.ExecutionContext
import services.annotation.{AnnotationService, AnnotationBody}
import services.entity.EntityType
import services.entity.builtin.EntityService
import storage.es.ES
import java.net._

trait PlacesToKMLByAnnotation extends BaseGeoAnnotationSerializer {

  def placesToKMLByAnnotation(
    documentId: String
  )(implicit 
      entityService: EntityService, 
      annotationService: AnnotationService, 
      ctx: ExecutionContext
  ) = getAnnotationMappableFeatures(documentId).map { features => 
    val host = "http://" + InetAddress.getLocalHost.getHostName + ":9000/annotation2/"
    val cdatastart = "<![CDATA["
    val cdataend = "]]>"

    val kmlFeatures = features.map { f => 
      <Annotation>
        <UUID>{f.annotations.annotationId.toString}</UUID>
        <name>{f.quotes.mkString(", ")}</name>
        <anchor>{f.annotations.anchor}</anchor>
        <modifiedBy>{f.annotations.lastModifiedBy.getOrElse("")}</modifiedBy>
        <modifiedAt>{f.annotations.lastModifiedAt}</modifiedAt>
        <TimeSpan>
          <begin>{f.annotations.startDate.getOrElse("")}</begin>
          <end>{f.annotations.endDate.getOrElse("")}</end>
        </TimeSpan>
        <description>
			{scala.xml.PCData(buildAnnotationKMLString(f, host))}
        </description>
          
        {<Point>
          <coordinates>{f.records.representativeGeometry.get.getCentroid.getX},{f.records.representativeGeometry.get.getCentroid.getY},0</coordinates>
        </Point>}
      </Annotation>
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

  def buildAnnotationKMLString (f: AnnotationPlaceFeature, host: String) : String = {

	var outCdata: String = "";

		outCdata = outCdata + """
			<div class="tlcmap recogito2">
			<table>
              <tr><td>ID</td><td><a href="""" + f.records.isConflationOf.map(_.uri).distinct.mkString(", ") + """">""" + f.records.isConflationOf.map(_.uri).distinct.mkString(", ") + """</a></td></tr>
              <tr><td>Title</td><td>""" + f.records.isConflationOf.map(_.title).distinct.mkString(", ") + """</td></tr>
              <tr><td>Latitude</td><td>""" + f.records.representativeGeometry.get.getCentroid.getY + """</td></tr>
              <tr><td>Longitude</td><td>""" + f.records.representativeGeometry.get.getCentroid.getX + """</td></tr>
              <tr><td>CCode</td><td>""" + f.records.isConflationOf.flatMap(_.countryCode).map(_.code).distinct.mkString(", ") + """</td></tr>
              <tr><td>Description</td><td>""" + f.records.isConflationOf.flatMap(_.descriptions).map(_.description).mkString(", ") + """</td></tr>
              <tr><td>Contributor</td><td>""" + f.annotations.contributors.mkString(", ").distinct.mkString(", ") + """</td></tr>
              <tr><td>Source</td><td>""" + f.records.isConflationOf.map(_.sourceAuthority).distinct.mkString(", ") + """</td></tr>
            </table>
			<h3>Occurences in Text:</h3>
              <p class="annotation-links">
			  """
			  outCdata = outCdata + """<a href="""" + host + f.annotations.annotationId + """">""" + {f.titles} + """</a>, """
        
			outCdata = outCdata.replaceAll(", $", ""); // remove the trailing comma
			outCdata = outCdata + """
              </p>
			</div>
		"""
	return outCdata;
  }

}
