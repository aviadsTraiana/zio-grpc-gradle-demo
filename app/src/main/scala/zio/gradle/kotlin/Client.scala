package zio.gradle.kotlin

import io.grpc.{ManagedChannelBuilder, Status}
import io.grpc.examples.routeguide.route_guide.{Point, Rectangle, RouteNote}
import io.grpc.examples.routeguide.route_guide.ZioRouteGuide.RouteGuideClient
import scalapb.zio_grpc.ZManagedChannel
import zio.{ExitCode, Has, Layer, Schedule, URIO, ZIO}
import zio.clock.Clock
import zio.console._
import zio.stream.ZStream

object Client extends zio.App {

  val channelBuilder: ManagedChannelBuilder[_] =
    ManagedChannelBuilder
            .forAddress("localhost",8980)
            .usePlaintext()
            .asInstanceOf[ManagedChannelBuilder[_]]

  val managedChannel: ZManagedChannel[Any] = ZManagedChannel(channelBuilder)
  val routeLayer : Layer[Throwable,RouteGuideClient] = RouteGuideClient.live(managedChannel)

  def printFeature(lat:Int,lon:Int) : ZIO[RouteGuideClient with Console,Status,Unit] = {
    val point = Point(latitude = lat,longitude = lon)
    (for{
      feature <- RouteGuideClient.getFeature(point)
      _ <- putStrLn(s"got the following feature: ${feature.name}")
    } yield ()).catchSome{
      case status if status == Status.NOT_FOUND =>
        putStrLn(s"could not find feature: $status")
    }
  }

  val rec =  Rectangle(
    lo = Some(Point(400000000, -750000000)),
    hi = Some(Point(420000000, -730000000))
  )
  val listFeatures: ZIO[Console with Has[RouteGuideClient.ZService[Any, Any]] with Any, Status, Unit] =
    RouteGuideClient.listFeatures(rec).zipWithIndex.foreach{ case (feature,index) =>
    putStrLn(s"result #$index : $feature")
  }


  import zio.duration._
  import zio.random._
  val stream100PointToServer = ZStream
          .repeatEffect(nextIntBetween(0,100)).map(x=> Point(x,x+1))
          .tap((p: Point) =>putStrLn(s"Visiting (${p.latitude}, ${p.longitude})") )
          .schedule(Schedule.spaced(300.millis))
          .take(100)

  val recordedRouteSummary = RouteGuideClient.recordRoute(stream100PointToServer)
  val printSummary = for{
   summary <- recordedRouteSummary
   _ <- putStrLn{
     s"""
       |Finished trip with ${summary.pointCount} points.
       |Passed ${summary.featureCount} features.
       |Travelled ${summary.distance} meters.
       |It took ${summary.elapsedTime} seconds.
       |""".stripMargin
   }
  } yield ()

  val routeChat =
    for {
      res <-
              RouteGuideClient
                      .routeChat(
                        ZStream(
                          RouteNote(
                            location = Some(Point()),
                            message = "First message"
                          ),
                          RouteNote(
                            location = Some(Point(0, 10000000)),
                            message = "Second Message"
                          ),
                          RouteNote(
                            location = Some(Point(10000000, 0)),
                            message = "Third Message"
                          ),
                          RouteNote(
                            location = Some(Point(10000000, 10000000)),
                            message = "Four Message"
                          )
                        ).tap { note =>
                          putStrLn(
                            s"""Sending message "${note.message}" at ${note.getLocation.latitude}, ${note.getLocation.longitude}"""
                          )
                        }
                      )
                      .foreach { note =>
                        putStrLn(
                          s"""Got message "${note.message}" at ${note.getLocation.latitude}, ${note.getLocation.longitude}"""
                        )
                      }
    } yield ()


  val appLogic: ZIO[Has[RouteGuideClient.ZService[Any, Any]] with Any with Console with Random with Clock, Status, Unit] = for {
    // Looking for a valid feature
   _ <- printFeature(409146138, -746188906)
   // Looking for a missing feature
   _ <- printFeature(0,0)
   _ <- listFeatures
   _ <- printSummary
  _ <- routeChat
  } yield ()

  final def run(args: List[String]): URIO[zio.ZEnv, ExitCode] =
    appLogic.provideCustomLayer(routeLayer).exitCode

}
