import ClusterState.ActorMap
import Hierarchy.hierarchyJsonFormat
import akka.actor.Address
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, Behavior }
import akka.cluster.ClusterEvent.{ MemberDowned, MemberEvent, MemberExited, MemberUp }
import akka.cluster.typed.{ Cluster, Down, Subscribe }
import spray.json.DefaultJsonProtocol.{ IntJsonFormat, StringJsonFormat, listFormat, tuple2Format }
import spray.json.{ JsValue, RootJsonFormat, enrichAny }

import scala.concurrent.duration.DurationInt

object Tracking {

  trait TrackingProtocol extends ClusterSerializable

  object PrintTracking extends TrackingProtocol

  case class AddNode(str: String) extends TrackingProtocol

  case class RemoveNode(address: String) extends TrackingProtocol

  trait TrackingCommand extends TrackingProtocol

  case class Add(shardingEntity: ShardingEntity) extends TrackingCommand

  case class Remove(shardingEntity: ShardingEntity) extends TrackingCommand

  case class GetCluster(replyTo: ActorRef[ClusterState]) extends TrackingCommand

  case class Terminate(node: Address) extends TrackingCommand


  def apply(): Behavior[TrackingCommand] = Behaviors.setup[TrackingProtocol] { ctx =>
    Behaviors.withTimers { timer =>

      timer.startTimerWithFixedDelay(PrintTracking, 5.seconds)

      val addressToString = (event: MemberEvent) => event.member.address.toString

      val memberUpAdapter = ctx.messageAdapter[MemberUp](event => AddNode(addressToString(event)))
      val memberExitedAdapter = ctx.messageAdapter[MemberExited](event => RemoveNode(addressToString(event)))
      val memberDownedAdapter = ctx.messageAdapter[MemberDowned](event => RemoveNode(addressToString(event)))

      val cluster = Cluster(ctx.system)
      val selfNode = cluster.selfMember

      var state = ClusterState(selfNode.address.toString)

      cluster.subscriptions.tell(Subscribe(memberUpAdapter, classOf[MemberUp]))
      cluster.subscriptions.tell(Subscribe(memberExitedAdapter, classOf[MemberExited]))
      cluster.subscriptions.tell(Subscribe(memberDownedAdapter, classOf[MemberDowned]))

      type PartialCommandHandler = PartialFunction[TrackingProtocol, Behavior[TrackingProtocol]]

      val handlePrint: PartialCommandHandler = {
        case PrintTracking =>
          ctx.log.info(state.clusterCount.toJson.prettyPrint)
          Behaviors.same
      }

      val handleClientCommands: PartialCommandHandler = {
        case GetCluster(replyTo) =>
          replyTo.tell(state)
          Behaviors.same
        case Terminate(node) =>
          if (cluster.state.members.exists(member => member.address == node))
            cluster.manager.tell(Down(node))
          Behaviors.same
      }

      val handleShards: PartialCommandHandler = {
        case Add(shardingEntity) =>
          state = state.trackEntity(shardingEntity)
          Behaviors.same
        case Remove(shardingEntity) =>
          state = state.removeEntity(shardingEntity)
          Behaviors.same
      }

      val handleNodes: PartialCommandHandler = {
        case AddNode(node) =>
          state = state.trackNode(node)
          Behaviors.same
        case RemoveNode(node) =>
          state = state.removeNode(node)
          Behaviors.same
      }

      Behaviors.receiveMessagePartial { msg =>
        handlePrint
          .orElse(handleShards)
          .orElse(handleNodes)
          .orElse(handleClientCommands)
          .apply(msg)
      }
    }
  }.narrow
}

case class ShardingEntity(node: String, shardId: String, entityId: String)

object ClusterState {

  import spray.json.DefaultJsonProtocol._

  implicit val actorMapJsonFormat: RootJsonFormat[ActorMap] = mapFormat

  type ActorMap = Map[String, Map[String, Set[String]]]

  def apply(self: String): ClusterState = ClusterState(self, Set(), Map())

}

case class ClusterState(self: String, active: Set[String], hierarchy: ActorMap) extends ClusterSerializable {

  import ClusterState._

  def trackEntity(shardingEntity: ShardingEntity): ClusterState = {
    if (active.contains(shardingEntity.entityId))
      this
    else
      copy(active = active + shardingEntity.entityId, hierarchy = addActor(shardingEntity))
  }

  def removeEntity(shardingEntity: ShardingEntity): ClusterState =
    copy(active = active - shardingEntity.entityId, hierarchy = removeActor(shardingEntity))

  def trackNode(node: String): ClusterState =
    copy(hierarchy = hierarchy.updatedWith(node)(found => Some(found.getOrElse(Map()))))

  def removeNode(nodeName: String): ClusterState =
    hierarchy.get(nodeName)
      .map(node => copy(active = active.removedAll(node.values.flatten), hierarchy = hierarchy - nodeName))
      .getOrElse(this)

  private def addActor(shardingEntity: ShardingEntity): ActorMap = {
    val ShardingEntity(node, shardId, entityId) = shardingEntity
    hierarchy.updatedWith(node) {
      case Some(node) =>
        Some(node.updatedWith(shardId)(found =>
          Some(found.getOrElse(Set()) + entityId)))
      case None =>
        Some(Map(shardId -> Set(entityId)))
    }
  }

  private def removeActor(shardingEntity: ShardingEntity): ActorMap = {
    val ShardingEntity(node, shardId, entityId) = shardingEntity
    hierarchy.updatedWith(node) {
      case Some(existingNode) =>
        Some(existingNode.updatedWith(shardId) {
          case Some(shardRegion) =>
            val updatedRegion = shardRegion - entityId
            if (updatedRegion.isEmpty) None else Some(updatedRegion)
          case _ => None
        })
      case _ => None
    }
  }

  def asJson: JsValue = hierarchy.toJson

  def toHierarchy: JsValue = {
    val nodes = hierarchy.map { case (node, shardMap) =>
      val shards = shardMap.map { case (shard, entities) =>
        Hierarchy(shard, entities.map(Hierarchy(_, Set(), "entity")), "shard")
      }
      val entityType = if (node == self) "singleton" else "node"
      Hierarchy(node, shards.toSet, entityType)
    }
    Hierarchy("cluster", nodes.toSet, "cluster")
  }.toJson

  def clusterCount: List[(String, Int)] = hierarchy.view.mapValues(_.values.flatten.size).toList
}

object Hierarchy {

  import spray.json.DefaultJsonProtocol._
  import spray.json._

  /*_*/
  implicit val hierarchyJsonFormat: RootJsonFormat[Hierarchy] = rootFormat(lazyFormat(jsonFormat(Hierarchy.apply, "name", "children", "entityType")))
  /*_*/
}

case class Hierarchy(name: String, children: Set[Hierarchy], entityType: String)
