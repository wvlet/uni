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
package wvlet.uni.test.fuzz

/**
  * Reported when an input runs longer than [[FuzzConfig.slowInputMillis]] twice in a row.
  */
final class SlowInput(val millis: Long, val limitMillis: Long)
    extends Exception(s"The input took ${millis} ms, over the limit of ${limitMillis} ms"):
  // The stack trace is the runner's, not the target's
  override def fillInStackTrace(): Throwable = this
