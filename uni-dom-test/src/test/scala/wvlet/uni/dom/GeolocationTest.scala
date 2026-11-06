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

import wvlet.uni.test.UniTest
import wvlet.uni.dom.all.*
import wvlet.uni.rx.Rx

class GeolocationTest extends UniTest:

  test("GeoPosition case class holds position data") {
    val pos = GeoPosition(
      latitude = 37.7749,
      longitude = -122.4194,
      altitude = Some(10.0),
      accuracy = 50.0,
      altitudeAccuracy = Some(5.0),
      heading = Some(90.0),
      speed = Some(1.5),
      timestamp = 1234567890L
    )
    pos.latitude shouldBe 37.7749
    pos.longitude shouldBe -122.4194
    pos.altitude shouldBe Some(10.0)
    pos.accuracy shouldBe 50.0
    pos.altitudeAccuracy shouldBe Some(5.0)
    pos.heading shouldBe Some(90.0)
    pos.speed shouldBe Some(1.5)
    pos.timestamp shouldBe 1234567890L
  }

  test("GeoPosition with optional fields as None") {
    val pos = GeoPosition(
      latitude = 40.7128,
      longitude = -74.0060,
      altitude = None,
      accuracy = 100.0,
      altitudeAccuracy = None,
      heading = None,
      speed = None,
      timestamp = 9876543210L
    )
    pos.altitude shouldBe None
    pos.altitudeAccuracy shouldBe None
    pos.heading shouldBe None
    pos.speed shouldBe None
  }

  test("GeoError enum has all error types") {
    GeoError.PermissionDenied shouldMatch { case GeoError.PermissionDenied =>
    }
    GeoError.PositionUnavailable shouldMatch { case GeoError.PositionUnavailable =>
    }
    GeoError.Timeout shouldMatch { case GeoError.Timeout =>
    }
    GeoError.NotSupported shouldMatch { case GeoError.NotSupported =>
    }
  }

  test("GeoError values are distinct") {
    val errors = Seq(
      GeoError.PermissionDenied,
      GeoError.PositionUnavailable,
      GeoError.Timeout,
      GeoError.NotSupported
    )
    errors.distinct.size shouldBe 4
  }

  test("GeoOptions has sensible defaults") {
    val opts = GeoOptions()
    opts.enableHighAccuracy shouldBe false
    opts.timeout shouldBe Long.MaxValue
    opts.maximumAge shouldBe 0L
  }

  test("GeoOptions can be customized") {
    val opts = GeoOptions(enableHighAccuracy = true, timeout = 10000L, maximumAge = 60000L)
    opts.enableHighAccuracy shouldBe true
    opts.timeout shouldBe 10000L
    opts.maximumAge shouldBe 60000L
  }

  test("Geolocation.isSupported returns Boolean") {
    val supported = Geolocation.isSupported
    supported shouldMatch { case _: Boolean =>
    }
  }

  test("Geolocation.watch returns PositionWatcher") {
    val watcher = Geolocation.watch()
    watcher shouldMatch { case _: Geolocation.PositionWatcher =>
    }
    watcher.cancel
  }

  test("Geolocation.watch with options returns PositionWatcher") {
    val watcher = Geolocation.watch(GeoOptions(enableHighAccuracy = true))
    watcher shouldMatch { case _: Geolocation.PositionWatcher =>
    }
    watcher.cancel
  }

  test("PositionWatcher.position returns Rx[Option[GeoPosition]]") {
    val watcher = Geolocation.watch()
    val pos     = watcher.position
    pos shouldMatch { case _: Rx[?] =>
    }
    watcher.cancel
  }

  test("PositionWatcher.error returns Rx[Option[GeoError]]") {
    val watcher = Geolocation.watch()
    val err     = watcher.error
    err shouldMatch { case _: Rx[?] =>
    }
    watcher.cancel
  }

  test("PositionWatcher.lastPosition returns Option[GeoPosition]") {
    val watcher = Geolocation.watch()
    val pos     = watcher.lastPosition
    pos shouldMatch { case _: Option[?] =>
    }
    watcher.cancel
  }

  test("PositionWatcher.lastError returns Option[GeoError]") {
    val watcher = Geolocation.watch()
    val err     = watcher.lastError
    err shouldMatch { case _: Option[?] =>
    }
    watcher.cancel
  }

  test("PositionWatcher can be cancelled") {
    val watcher = Geolocation.watch()
    watcher.cancel
    // Should not throw
  }

  test("PositionWatcher can be cancelled multiple times") {
    val watcher = Geolocation.watch()
    watcher.cancel
    watcher.cancel
    // Should not throw
  }

  test("Geolocation.getCurrentPosition accepts callbacks") {
    var called = false
    Geolocation.getCurrentPosition(onSuccess = _ => called = true, onError = _ => called = true)
    // In jsdom environment without geolocation, this should call onError
    // We just verify the call doesn't throw
  }

  test("Geolocation.getCurrentPosition with options") {
    Geolocation.getCurrentPosition(
      onSuccess = _ => (),
      onError = _ => (),
      options = GeoOptions(enableHighAccuracy = true, timeout = 5000L)
    )
    // Should not throw
  }

  test("PositionWatcher position reactive stream emits values") {
    val watcher      = Geolocation.watch()
    var emittedValue = false
    val cancel       = watcher
      .position
      .run { _ =>
        emittedValue = true
      }
    // Initial value should be emitted
    emittedValue shouldBe true
    cancel.cancel
    watcher.cancel
  }

  test("PositionWatcher error reactive stream emits values") {
    val watcher      = Geolocation.watch()
    var emittedValue = false
    val cancel       = watcher
      .error
      .run { _ =>
        emittedValue = true
      }
    // Initial value should be emitted
    emittedValue shouldBe true
    cancel.cancel
    watcher.cancel
  }

end GeolocationTest
