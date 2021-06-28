import Tracking.{ Add, Remove, TrackingCommand }
import akka.actor.Address
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, Behavior }
import akka.cluster.sharding.typed.scaladsl.{ ClusterSharding, EntityTypeKey }
import akka.cluster.typed.Cluster

trait WalletCommand extends ClusterSerializable

case class TransferMoney(targetWalletId: String) extends WalletCommand

case class GetBalance() extends WalletCommand

case class Stop() extends WalletCommand

case class EntityId(address: Address, shardId: String)

object Wallet {
  val TypeKey: EntityTypeKey[WalletCommand] = EntityTypeKey("wallet")

  def apply(entityId: String, tree: ActorRef[TrackingCommand], sharding: ClusterSharding): Behavior[WalletCommand] = Behaviors.setup { ctx =>

    val address = Cluster(ctx.system).selfMember.address.toString
    val selfPath = ctx.self.path.toString
    val shardId = selfPath.split("/").toList.takeRight(2).head

    val selfAsShardingEntity = ShardingEntity(address, shardId, entityId)

    tree.tell(Add(selfAsShardingEntity))

    Behaviors.receiveMessagePartial {
      case Stop() =>
        tree.tell(Remove(selfAsShardingEntity))
        Behaviors.stopped
      case msg =>
        tree.tell(Add(selfAsShardingEntity))
        msg match {
          case GetBalance() =>
          case TransferMoney(targetWalletId) =>
            sharding.entityRefFor(Wallet.TypeKey, targetWalletId)
        }
        Behaviors.same
    }
  }
}
