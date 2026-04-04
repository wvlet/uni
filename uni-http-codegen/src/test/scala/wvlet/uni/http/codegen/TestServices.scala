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
package wvlet.uni.http.codegen

/**
  * Test service traits used by TastyServiceScanner tests. These traits are compiled and their
  * .tasty files are read at test time.
  */

case class User(id: Long, name: String, email: String)

trait TestUserService:
  def getUser(id: Long): User
  def createUser(name: String, email: String): User
  def deleteUser(id: Long): Unit
  def listUsers(): Seq[User]

trait TestHealthService:
  def ping(): String

trait TestOptionalParamService:
  def search(query: String, limit: Option[Int]): Seq[User]
