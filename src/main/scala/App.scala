import Tracking.TrackingCommand
import akka.NotUsed
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, ActorSystem, SupervisorStrategy }
import akka.cluster.sharding.typed.scaladsl.{ ClusterSharding, Entity }
import akka.cluster.typed.{ ClusterSingleton, SingletonActor }
import akka.management.cluster.bootstrap.ClusterBootstrap
import akka.management.scaladsl.AkkaManagement
import com.typesafe.config.ConfigFactory

private object App extends App {

  implicit val system: ActorSystem[NotUsed] = ActorSystem[NotUsed](Behaviors.ignore, "wallet", systemConfig)

  AkkaManagement(system).start()
  ClusterBootstrap(system).start()
  val sharding = ClusterSharding(system)

  val singletonManager = ClusterSingleton(system)
  val tracking = initTrackingSingleton()

  Server.start(tracking, sharding)
  initWallets()

  private def initWallets(): Unit = {
    sharding.init(
      Entity(Wallet.TypeKey)(entityContext => {
        Wallet(entityContext.entityId, tracking, sharding)
      }).withStopMessage(Stop())
    )
  }

  private def initTrackingSingleton(): ActorRef[TrackingCommand] = {
    val supervisedTracking = Behaviors.supervise(Tracking()).onFailure[Exception](SupervisorStrategy.restart)

    singletonManager.init(
      SingletonActor(supervisedTracking, "tracking")
    )
  }

  private def systemConfig = {
    val envConfig = clusterBootstrappingConfig
    ConfigFactory.defaultApplication().withFallback(envConfig)
  }

  private def clusterBootstrappingConfig = {
    val isLocal = sys.env.get("LOCAL_CLUSTER").flatMap(_.toBooleanOption).getOrElse(false)
    val configName = if (isLocal) "local" else "k8s"
    ConfigFactory.load(configName)
  }
}

trait ClusterSerializable
