package saxi.server

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{ExecutorService, Executors}

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Source}
import akka.util.ByteString
import saxi._
import saxi.protocol.Protocol

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}

object Main {
  implicit val system: ActorSystem = ActorSystem("my-system")
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val executionContext: ExecutionContextExecutor = system.dispatcher
  val port = 9080

  var ebb: OpenEBB = _
  val device: Device = Device.Axidraw

  val ebbQ: ExecutorService = Executors.newFixedThreadPool(1)
  val ebbEC: ExecutionContextExecutor = ExecutionContext.fromExecutor(ebbQ)

  var cancel: AtomicReference[Boolean] = new AtomicReference(false)

  val flow: Flow[Protocol.ClientMessage, Protocol.ServerMessage, NotUsed] =
    Flow[Protocol.ClientMessage].mapConcat {
      case Protocol.Ping(data) => Protocol.Pong(data) :: Nil
      case Protocol.Move(angle, distance) =>
        val duration = 0.01 * distance
        val x = math.round(math.cos(angle) * distance * device.stepsPerMm)
        val y = math.round(math.sin(angle) * distance * device.stepsPerMm)
        ebbQ.submit({ () =>
          ebb.enableMotors(5)
          ebb.moveAtConstantRate(duration, x, y)
        }: Runnable)
        Nil
      case Protocol.SetPenHeight(height, rate) =>
        ebbQ.submit({ () =>
          ebb.setPenHeight(height, rate)
        }: Runnable)
        Nil
      case Protocol.DisableMotors() =>
        ebbQ.submit({ () =>
          ebb.disableMotors()
        }: Runnable)
        Nil
      case _ =>
        Nil
    }


  def websocketChatFlow(): Flow[Message, Message, Any] =
    Flow[Message]
      .flatMapConcat {
        case BinaryMessage.Streamed(bs) =>
          (bs via Flow[ByteString].fold(ByteString.empty)(_ concat _)) map BinaryMessage.Strict
        case TextMessage.Streamed(ts) => (ts via Flow[String].fold("")(_ + _)) map TextMessage.Strict
        case other: Message =>
          Source.single(other)
      }
      .collect {
        case BinaryMessage.Strict(msg) =>
          import boopickle.Default._
          val res = Unpickle[Protocol.ClientMessage].fromBytes(msg.asByteBuffer)
          res
      }
      .via(flow)
      .map {
        msg: Protocol.ServerMessage =>
          import boopickle.Default._
          BinaryMessage.Strict(ByteString(Pickle.intoBytes(msg)))
      }
      .merge(Source.single(TextMessage.Strict("oh hello, friend!")))
      .via(reportErrorsFlow)

  def reportErrorsFlow[T]: Flow[T, T, Any] =
    Flow[T]
      .watchTermination()((_, f) => f.onComplete {
        case Failure(cause) =>
          println(s"WS stream failed with $cause")
        case _ =>
      })

  def doPlot(plan: Planning.Plan): Future[Unit] = {
    Future {
      ebb.enableMotors(microsteppingMode = 2)
      plan.motions.collectFirst { case m: Planning.PenMotion => m }.foreach { m =>
        ebb.setPenHeight(m.initialPos, 1000, delay=1000)
      }

      cancel.set(false)
      val motions = plan.motions.iterator
      while (!cancel.get() && motions.hasNext) {
        val motion = motions.next()
        ebb.executeMotion(motion)
      }
      if (cancel.get()) {
        ebb.setPenHeight(Device.Axidraw.penPctToPos(0), 1000)
      }
      ebb.waitUntilMotorsIdle()
      ebb.disableMotors()
    }(ebbEC)
  }

  val route: Route = {
    pathSingleSlash {
      getFromResource("web/index.html")
      /*
      get {
        complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>Say hello to akka-http</h1>"))
      }
      */
    } ~ pathPrefix("js") {
      get {
        getFromResourceDirectory("js")
      }
    } ~ path("chat") {
      handleWebSocketMessages(websocketChatFlow())
    } ~ path("prepare") {
      post {
        entity(as[Array[Byte]]) { bytes =>
          complete {
            Future {
              import boopickle.Default._
              val toolProfile = Unpickle[ToolingProfile].fromBytes(ByteBuffer.wrap(bytes))
              ebb.setPenHeight(toolProfile.penUpPos, 1000)
              ebb.disableMotors()
            }(ebbEC).map { _ =>
              HttpEntity(ContentTypes.`application/json`, "{}")
            }
          }
        }
      }
    } ~ path("plot") {
      withoutSizeLimit { // TODO: what if anything do i need to do to push this over WS instead of HTTP?
        post {
          entity(as[Array[Byte]]) { bytes =>
            complete {
              import boopickle.Default._
              val plan = Unpickle[Planning.Plan].fromBytes(ByteBuffer.wrap(bytes))
              println(s"Received plan: est. duration ${Util.formatDuration(plan.duration)} ...")
              doPlot(plan)
              HttpEntity(ContentTypes.`application/json`, "{}")
            }
          }
        }
      }
    } ~ path("cancel") {
      post {
        complete {
          cancel.set(true)
          HttpEntity(ContentTypes.`application/json`, "{}")
        }
      }
    }
  }

  def main(args: Array[String]): Unit = {
    try {
      ebb = EBB.findFirst.get.open()
    } catch {
      case t: Throwable =>
        println("Error connecting to EBB:")
        t.printStackTrace()
    }
    val bindingFuture = Http().bindAndHandle(route, "0.0.0.0", port)
    bindingFuture.onComplete {
      case Success(binding) =>
        val localAddress = binding.localAddress
        println(s"Server is listening on ${localAddress.getHostName}:${localAddress.getPort}")
      case Failure(e) =>
        println(s"Binding failed with ${e.getMessage}")
        system.terminate()
    }
  }
}
