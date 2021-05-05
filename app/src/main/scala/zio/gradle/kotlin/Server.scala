package zio.gradle.kotlin

import io.grpc.examples.routeguide.route_guide._
import io.grpc.Status
import scalapb.json4s.JsonFormat
import scalapb.zio_grpc.{ServerMain, ServiceList}
import scalapb.zio_grpc.CanBind.canBindAny
import zio.{stream, IO, Ref, Task, ZEnv, ZIO}
import zio.clock.Clock
import zio.stream.ZStream

import scala.io.Source
import scala.math._

object Server extends ServerMain {
  val featuresDatabase: Task[FeatureDatabase] = ZIO.effect {
    JsonFormat.fromJsonString[FeatureDatabase](
      Source.fromResource("route_guide_db.json").mkString
    )
  }
  val createRouteGuide: ZIO[Any, Throwable, RouteGuideService] = for {
    db <- featuresDatabase
    routeNotes <- Ref.make(Map.empty[Point, List[RouteNote]])
  } yield new RouteGuideService(db.feature, routeNotes)

  override def port: Int = 8980

  def services: ServiceList[ZEnv] =
    ServiceList.addM(createRouteGuide)

}

class RouteGuideService(features: Seq[Feature],
                        routeNotesRef: Ref[Map[Point, List[RouteNote]]])
    extends ZioRouteGuide.ZRouteGuide[ZEnv, Any] {
  override def getFeature(request: Point): ZIO[ZEnv, Status, Feature] =
    ZIO.fromOption(findFeature(request)).orElseFail(Status.NOT_FOUND)

  private def findFeature(point: Point): Option[Feature] =
    features.find(f => f.getLocation == point && f.name.nonEmpty)

  override def listFeatures(
    request: Rectangle
  ): ZStream[ZEnv, Status, Feature] = {
    val left = request.getLo.longitude min request.getHi.longitude
    val right = request.getLo.longitude max request.getHi.longitude
    val top = request.getLo.latitude max request.getHi.latitude
    val bottom = request.getLo.latitude min request.getHi.latitude

    ZStream.fromIterable(features.filter { feature =>
      val lat = feature.getLocation.latitude
      val lon = feature.getLocation.longitude
      lon >= left && lon <= right && lat >= bottom && lat <= top
    })
  }

  override def recordRoute(
    request: stream.Stream[Status, Point]
  ): ZIO[Clock, Status, RouteSummary] = {
    // Zips each element with the previous element, initially accompanied by None.
    request.zipWithPrevious
      .fold(RouteSummary()) {
        case (summary, (maybePrevPoint, currentPoint)) =>
          // Compute the next status based on the current status.
          summary.copy(
            pointCount = summary.pointCount + 1,
            featureCount =
              summary.featureCount + (if (findFeature(currentPoint).isDefined) 1
                                      else 0),
            distance = summary.distance + maybePrevPoint
              .map(calcDistance(_, currentPoint))
              .getOrElse(0)
          )
      }
      .timed // returns a new effect that times the execution
      .map {
        case (duration, summary) =>
          summary.copy(elapsedTime = (duration.toMillis / 1000).toInt)
      }

  }

  private def calcDistance(start: Point, end: Point): Int = {
    val r = 6371000 // earth radius in meters
    val CoordFactor: Double = 1e7
    val lat1 = toRadians(start.latitude) / CoordFactor
    val lat2 = toRadians(end.latitude) / CoordFactor
    val lon1 = toRadians(start.longitude) / CoordFactor
    val lon2 = toRadians(end.longitude) / CoordFactor
    val deltaLat = lat2 - lat1
    val deltaLon = lon2 - lon1

    val a = sin(deltaLat / 2) * sin(deltaLat / 2)
    +cos(lat1) * cos(lat2) * sin(deltaLon / 2) * sin(deltaLon / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))

    (r * c).toInt
  }

  override def routeChat(
    request: stream.Stream[Status, RouteNote]
  ): ZStream[ZEnv, Status, RouteNote] = {
    // By using flatMap, we can map each RouteNote we receive to a stream with
    // the existing RouteNotes for that location, and those sub-streams are going
    // to get concatenated.
    // We start from an effect that updates the map with the new RouteNote,
    // and returns the notes associated with the location just before the update.
    request.flatMap { note: RouteNote =>
      val updateMapEffect: IO[Nothing, List[RouteNote]] = routeNotesRef.modify {
        routeNotes =>
          val messages: List[RouteNote] =
            routeNotes.getOrElse(note.getLocation, Nil)
          val updatedMap =
            routeNotes.updated(note.getLocation, note :: messages)
          (messages, updatedMap)
      }

      // We create a stream from the effect.
      ZStream.fromIterableM(updateMapEffect)
    }

  }
}
