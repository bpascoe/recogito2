package services.entity.builtin

import com.vividsolutions.jts.geom.{Coordinate, Envelope}
import java.util.UUID
import scala.concurrent.Future
import services.Page
import services.entity.{Entity, EntityType}
import storage.es.ES

trait EntityService {

  def countEntities(eType: Option[EntityType] = None): Future[Long]

  def countByAuthority(eType: Option[EntityType] = None): Future[Seq[(String, Long)]]

  def upsertEntities(entities: Seq[IndexedEntity]): Future[Boolean]

  def deleteEntities(ids: Seq[UUID]): Future[Boolean]

  def findByURI(uri: String): Future[Option[IndexedEntity]]

  def listEntitiesInContribution(identifier: String): Future[Seq[IndexedEntity]]

  def listIndexedEntitiesInDocument(docId: String, eType: Option[EntityType] = None): Future[Seq[IndexedEntity]]

  def findConnected(uris: Seq[String]): Future[Seq[IndexedEntity]]

  def getEntitiesByUser(user: String): Future[Seq[IndexedEntity]]

  def getUserPlace(user: String, eType: Option[EntityType] = None,
    offset: Int = 0, limit: Int = ES.MAX_SIZE): Future[Page[(IndexedEntity, Long)]]

  def searchEntities(
    query       : String,
    eType       : Option[EntityType] = None,
    offset      : Int = 0,
    limit       : Int = ES.MAX_SIZE,
    sortFrom    : Option[Coordinate] = None,
    authorities : Option[Seq[String]] = None
  ): Future[Page[IndexedEntity]]

  def listEntitiesInDocument(
    docId  : String,
    eType  : Option[EntityType] = None,
    offset : Int = 0,
    limit  : Int = ES.MAX_SIZE
  ): Future[Page[(IndexedEntity, Long)]]

  def listEntitiesInDocuments(
    docIds  : Seq[String],
    eType  : Option[EntityType] = None,
    offset : Int = 0,
    limit  : Int = ES.MAX_SIZE
  ): Future[Page[(IndexedEntity, Long)]]
  

  def searchEntitiesInDocument(
    query  : String,
    docId  : String,
    eType  : Option[EntityType] = None,
    offset : Int = 0,
    limit  : Int = ES.MAX_SIZE
  ): Future[Page[IndexedEntity]]
  
  def getDocumentSpatialExtent(docId : String): Future[Envelope]

  def deleteBySourceAuthority(authority: String): Future[Boolean]

  def deleteEntityById(id: String): Future[Boolean]

  def upsertEntity(e: Entity,version: Long): Future[Boolean]

  def createEntity(e: Entity): Future[Boolean]
    
  def findById(id: String): Future[Option[IndexedEntity]]

}
