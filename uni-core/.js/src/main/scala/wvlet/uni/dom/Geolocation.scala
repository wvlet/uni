/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package wvlet.uni.dom

import org.scalajs.dom
import wvlet.uni.rx.{Cancelable, Rx, RxVar}

import scala.scalajs.js
import scala.scalajs.js.UndefOr

/**
  * Position data from the Geolocation API.
  *
  * @param latitude
  *   Latitude in decimal degrees
  * @param longitude
  *   Longitude in decimal degrees
  * @param altitude
  *   Altitude in meters above sea level, if available
  * @param accuracy
  *   Accuracy of latitude/longitude in meters
  * @param altitudeAccuracy
  *   Accuracy of altitude in meters, if available
  * @param heading
  *   Direction of travel in degrees (0 = north), if available
  * @param speed
  *   Speed in meters per second, if available
  * @param timestamp
  *   Time when the position was acquired (milliseconds since epoch)
  */
case class GeoPosition(
    latitude: Double,
    longitude: Double,
    altitude: Option[Double],
    accuracy: Double,
    altitudeAccuracy: Option[Double],
    heading: Option[Double],
    speed: Option[Double],
    timestamp: Long
)

/**
  * Geolocation error types.
  */
enum GeoError:
  /** User denied the request for geolocation */
  case PermissionDenied

  /** Location information is unavailable */
  case PositionUnavailable

  /** The request to get user location timed out */
  case Timeout

  /** Geolocation is not supported in this browser */
  case NotSupported

/**
  * Options for geolocation requests.
  *
  * @param enableHighAccuracy
  *   If true, request the most accurate position available (may be slower and use more power)
  * @param timeout
  *   Maximum time in milliseconds to wait for a position (default: no timeout)
  * @param maximumAge
  *   Maximum age in milliseconds of a cached position to accept (default: 0, always get fresh)
  */
case class GeoOptions(
    enableHighAccuracy: Boolean = false,
    timeout: Long = Long.MaxValue,
    maximumAge: Long = 0
)

/**
  * Reactive geolocation tracking using the browser's Geolocation API.
  *
  * Usage:
  * {{{
  *   import wvlet.uni.dom.all.*
  *
  *   // One-shot position request
  *   Geolocation.getCurrentPosition(
  *     onSuccess = pos => println(s"Location: \${pos.latitude}, \${pos.longitude}"),
  *     onError = err => println(s"Error: \${err}")
  *   )
  *
  *   // Continuous watching
  *   val watcher = Geolocation.watch()
  *   div(
  *     watcher.position.map {
  *       case Some(pos) => span(s"\${pos.latitude}, \${pos.longitude}")
  *       case None => span("Waiting for location...")
  *     }
  *   )
  *
  *   // Clean up when done
  *   watcher.cancel
  * }}}
  */
object Geolocation:

  /**
    * Check if geolocation is supported in the current environment. Returns false in non-browser
    * environments (e.g., Node.js) or if the Geolocation API is unavailable.
    */
  def isSupported: Boolean =
    try
      !js.isUndefined(dom.window) && !js.isUndefined(dom.window.navigator) &&
        !js.isUndefined(dom.window.navigator.geolocation) &&
        dom.window.navigator.geolocation != null
    catch
      case _: Throwable =>
        false

  /**
    * Request the current position once.
    *
    * @param onSuccess
    *   Called with the position when successful
    * @param onError
    *   Called with the error if the request fails
    * @param options
    *   Options for the position request
    */
  def getCurrentPosition(
      onSuccess: GeoPosition => Unit,
      onError: GeoError => Unit = _ => (),
      options: GeoOptions = GeoOptions()
  ): Unit =
    if !isSupported then
      onError(GeoError.NotSupported)
    else
      val geo = dom.window.navigator.geolocation
      geo.getCurrentPosition(
        successCallback = (pos: dom.Position) => onSuccess(toGeoPosition(pos)),
        errorCallback = (err: dom.PositionError) => onError(toGeoError(err)),
        options = toPositionOptions(options)
      )

  /**
    * Start watching the position continuously.
    *
    * @param options
    *   Options for position watching
    * @return
    *   A PositionWatcher that provides reactive position updates
    */
  def watch(options: GeoOptions = GeoOptions()): PositionWatcher = PositionWatcher(options)

  /**
    * A position watcher that provides reactive updates as the position changes.
    */
  class PositionWatcher(options: GeoOptions) extends Cancelable:
    private val positionVar: RxVar[Option[GeoPosition]] = Rx.variable(None)
    private val errorVar: RxVar[Option[GeoError]]       = Rx.variable(None)
    private var watchId: Option[Int]                    = None

    // Start watching immediately if supported
    if !isSupported then
      errorVar := Some(GeoError.NotSupported)
    else
      val geo = dom.window.navigator.geolocation
      val id  = geo.watchPosition(
        successCallback =
          (pos: dom.Position) =>
            positionVar := Some(toGeoPosition(pos))
            errorVar    := None
        ,
        errorCallback = (err: dom.PositionError) => errorVar := Some(toGeoError(err)),
        options = toPositionOptions(options)
      )
      watchId = Some(id)

    /**
      * Reactive stream of position updates. The initial value is `None`, and it emits
      * `Some(position)` each time a new position is acquired. On success, the error stream is
      * cleared to `None`.
      */
    def position: Rx[Option[GeoPosition]] = positionVar

    /**
      * Reactive stream of errors. The initial value is `None` (or `Some(NotSupported)` if
      * geolocation is unavailable). Emits `Some(error)` when an error occurs.
      */
    def error: Rx[Option[GeoError]] = errorVar

    /**
      * Get the last known position, if any.
      */
    def lastPosition: Option[GeoPosition] = positionVar.get

    /**
      * Get the last error, if any.
      */
    def lastError: Option[GeoError] = errorVar.get

    /**
      * Stop watching the position.
      */
    override def cancel: Unit =
      watchId.foreach { id =>
        if isSupported then
          dom.window.navigator.geolocation.clearWatch(id)
      }
      watchId = None

  end PositionWatcher

  private def toGeoPosition(pos: dom.Position): GeoPosition =
    val coords = pos.coords
    GeoPosition(
      latitude = coords.latitude,
      longitude = coords.longitude,
      altitude = toOption(coords.altitude),
      accuracy = coords.accuracy,
      altitudeAccuracy = toOption(coords.altitudeAccuracy),
      heading = toOption(coords.heading),
      speed = toOption(coords.speed),
      timestamp = pos.timestamp.toLong
    )

  // Error codes defined by the Geolocation API spec:
  // 1 = PERMISSION_DENIED, 2 = POSITION_UNAVAILABLE, 3 = TIMEOUT
  private def toGeoError(err: dom.PositionError): GeoError =
    err.code match
      case 1 =>
        GeoError.PermissionDenied
      case 2 =>
        GeoError.PositionUnavailable
      case 3 =>
        GeoError.Timeout
      case _ =>
        GeoError.PositionUnavailable // Fallback for unknown codes

  private def toPositionOptions(options: GeoOptions): dom.PositionOptions =
    val opts = js.Dynamic.literal()
    opts.enableHighAccuracy = options.enableHighAccuracy
    if options.timeout != Long.MaxValue then
      opts.timeout = options.timeout.toDouble
    if options.maximumAge > 0 then
      opts.maximumAge = options.maximumAge.toDouble
    opts.asInstanceOf[dom.PositionOptions]

  // Convert JS undefined/null/NaN values to None for optional coordinate fields
  private def toOption(value: UndefOr[Double]): Option[Double] =
    if js.isUndefined(value) then
      None
    else
      val v = value.asInstanceOf[Double]
      if v.isNaN then
        None
      else
        Some(v)

end Geolocation
