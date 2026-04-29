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
package wvlet.uni.log

import scala.scalanative.meta.LinktimeInfo
import scalanative.posix.time.*
import scalanative.unsafe.*
import scalanative.unsigned.*
import scalanative.libc.stdio.*
import scalanative.libc.string.*

/**
  * Format timestamps in Scala Native.
  *
  * On POSIX targets we delegate to `strftime` + `localtime_r` so the system timezone offset (e.g.
  * `+0900`) is preserved. MSVC's CRT does not provide `localtime_r`, so on Windows we fall back to
  * a pure-Scala UTC formatter (suffix `Z`). `LinktimeInfo.isWindows` is resolved at link time, so
  * the unused branch is dead-code-eliminated and the POSIX path keeps its existing behavior.
  */
object LogTimestampFormatter:

  def formatTimestamp(timeMillis: Long): String =
    if LinktimeInfo.isWindows then
      formatUtc(timeMillis, withSpace = true)
    else
      formatPosix(c"%Y-%m-%d %H:%M:%S.", timeMillis)

  def formatTimestampWithoutSpace(timeMillis: Long): String =
    if LinktimeInfo.isWindows then
      formatUtc(timeMillis, withSpace = false)
    else
      formatPosix(c"%Y-%m-%dT%H:%M:%S.", timeMillis)

  private def formatPosix(pattern: CString, timeMillis: Long): String = Zone {
    val ttPtr = alloc[time_t]()
    !ttPtr = (timeMillis / 1000).toSize
    val tmPtr = alloc[tm]()
    localtime_r(ttPtr, tmPtr)
    val bufSize        = 29.toUSize // max size for time strings
    val buf: Ptr[Byte] = alloc[Byte](bufSize)
    strftime(buf, bufSize, pattern, tmPtr)
    val ms = timeMillis % 1000

    val msBuf: Ptr[Byte] = alloc[Byte](3)
    sprintf(msBuf, c"%03d", ms)
    strcat(buf, msBuf)

    val tzBuf: Ptr[Byte] = alloc[Byte](5)
    strftime(tzBuf, 5.toUSize, c"%z", tmPtr)
    if strlen(tzBuf) <= 1.toUSize then
      // For UTC-00:00
      strcat(buf, c"Z")
    else
      strcat(buf, tzBuf)
    fromCString(buf)
  }

  // Pure-Scala UTC formatter used on Windows. Howard Hinnant's
  // civil-from-days algorithm gives correct year/month/day for any epoch
  // millis without leaning on POSIX-only C symbols.
  private def formatUtc(timeMillis: Long, withSpace: Boolean): String =
    val seconds        = Math.floorDiv(timeMillis, 1000L)
    val millis         = Math.floorMod(timeMillis, 1000L)
    val daysSinceEpoch = Math.floorDiv(seconds, 86400L)
    val secondsInDay   = Math.floorMod(seconds, 86400L)

    val hour   = (secondsInDay / 3600L).toInt
    val minute = ((secondsInDay % 3600L) / 60L).toInt
    val second = (secondsInDay  % 60L).toInt

    val z   = daysSinceEpoch + 719468L
    val era =
      (
        if z >= 0 then
          z
        else
          z - 146096L
      ) / 146097L
    val doe   = (z - era * 146097L).toInt
    val yoe   = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val y0    = yoe.toLong + era * 400L
    val doy   = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp    = (5 * doy + 2) / 153
    val day   = doy - (153 * mp + 2) / 5 + 1
    val month =
      if mp < 10 then
        mp + 3
      else
        mp - 9
    val year =
      (
        if month <= 2 then
          y0 + 1
        else
          y0
      ).toInt

    val sep =
      if withSpace then
        ' '
      else
        'T'
    f"$year%04d-$month%02d-$day%02d$sep$hour%02d:$minute%02d:$second%02d.$millis%03dZ"

  end formatUtc

end LogTimestampFormatter
