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
package wvlet.uni.http.rpc

import scala.annotation.StaticAnnotation

/**
  * Marks a Scala trait/class as an RPC interface. Every public method declared on the annotated
  * type is exposed as an HTTP endpoint when the type is registered with
  * [[wvlet.uni.http.router.RxRouter]] via `RxRouter.of[T]`.
  *
  * @param path
  *   Optional URI prefix beginning with `/`. When empty, the endpoint path is
  *   `/{fully-qualified-class-name}/{methodName}`. When set (e.g. `/v1`), the endpoint path is
  *   `/v1/{className}/{methodName}`.
  * @param description
  *   Optional human-readable description used by codegen tooling.
  *
  * Mirrors `wvlet.airframe.http.RPC` so callers porting from airframe can swap their imports.
  */
case class RPC(path: String = "", description: String = "") extends StaticAnnotation
