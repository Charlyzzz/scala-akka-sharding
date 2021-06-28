import Tracking.{ GetCluster, Terminate, TrackingCommand }
import akka.NotUsed
import akka.actor.AddressFromURIString
import akka.actor.typed.{ ActorRef, ActorSystem }
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshalling.sse.EventStreamMarshalling._
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.server.Directives.{ _segmentStringToPathMatcher, complete, concat, delete, get, getFromResource, path, pathEndOrSingleSlash }
import akka.http.scaladsl.server.PathMatchers.Remaining
import akka.stream.scaladsl.{ BroadcastHub, Flow, Source }
import akka.stream.typed.scaladsl.ActorFlow
import akka.util.Timeout

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Random, Try }

object Server {

  def start(tracking: ActorRef[TrackingCommand], sharding: ClusterSharding)(implicit system: ActorSystem[_]): Future[Http.ServerBinding] = {
    implicit val executionContext: ExecutionContext = system.executionContext

    val routes = makeRoutes(tracking, sharding)

    Http().newServerAt("0.0.0.0", 8080)
      .bind(routes)
      .map(_.addToCoordinatedShutdown(hardTerminationDeadline = 10.seconds))
  }

  private def makeRoutes(tracking: ActorRef[TrackingCommand], sharding: ClusterSharding)(implicit system: ActorSystem[_]) = {
    implicit val timeout: Timeout = 4.second

    val clusterStateFlow: Flow[NotUsed, ClusterState, _] =
      ActorFlow.ask(tracking)((_, replyTo) => GetCluster(replyTo))

    val broadcastSink = BroadcastHub.sink[NotUsed]

    val clusterStateSource = Source.tick(0.seconds, 2.seconds, NotUsed)
      .runWith(broadcastSink)
      .via(clusterStateFlow)
      .map(state => ServerSentEvent(state.toHierarchy.toString()))

    concat(
      pathEndOrSingleSlash {
        getFromResource("index.html")
      },
      path("create") {
        get {
          val (op, id) = randomOp
          val wallet = sharding.entityRefFor(Wallet.TypeKey, id)
          wallet.tell(op)
          complete(StatusCodes.OK)
        }
      },
      path("cluster") {
        get {
          complete(clusterStateSource)
        }
      },
      path("terminate" / Remaining) { node =>
        delete {
          Try(AddressFromURIString(node))
            .foreach(address => tracking.tell(Terminate(address)))
          complete(StatusCodes.OK)
        }
      }
    )
  }

  private def randomOp: (WalletCommand, String) = {
    val id2 = Random.between(0, 80).toString
    val op = Random.between(0, 2) match {
      case 0 => GetBalance()
      case 1 => TransferMoney(id2)
    }
    val id = Random.between(0, 80).toString
    (op, id)
  }
}
