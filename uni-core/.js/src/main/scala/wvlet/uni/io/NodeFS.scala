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
package wvlet.uni.io

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js.typedarray.Uint8Array

/**
  * Node.js fs module facade.
  */
@js.native
@JSImport("fs", JSImport.Namespace)
private[io] object NodeFSModule extends js.Object:
  def existsSync(path: String): Boolean                                       = js.native
  def readFileSync(path: String, encoding: String): String                    = js.native
  def readFileSync(path: String): js.typedarray.Uint8Array                    = js.native
  def writeFileSync(path: String, data: String, options: js.Object): Unit     = js.native
  def writeFileSync(path: String, data: Uint8Array, options: js.Object): Unit = js.native
  def appendFileSync(path: String, data: String): Unit                        = js.native
  def appendFileSync(path: String, data: Uint8Array): Unit                    = js.native
  def readdirSync(path: String, options: js.Object): js.Array[js.Dynamic]     = js.native
  def mkdirSync(path: String, options: js.Object): Unit                       = js.native
  def rmdirSync(path: String, options: js.Object): Unit                       = js.native
  def unlinkSync(path: String): Unit                                          = js.native
  def statSync(path: String): NodeStats                                       = js.native
  def lstatSync(path: String): NodeStats                                      = js.native
  def copyFileSync(src: String, dest: String, mode: Int): Unit                = js.native
  def renameSync(oldPath: String, newPath: String): Unit                      = js.native
  def mkdtempSync(prefix: String): String                                     = js.native
  def symlinkSync(target: String, path: String): Unit                         = js.native
  def readlinkSync(path: String): String                                      = js.native

  // Promises API
  val promises: NodeFSPromises = js.native

@js.native
private[io] trait NodeFSPromises extends js.Object:
  def readFile(path: String, encoding: String): js.Promise[String]                    = js.native
  def readFile(path: String): js.Promise[js.typedarray.Uint8Array]                    = js.native
  def writeFile(path: String, data: String, options: js.Object): js.Promise[Unit]     = js.native
  def writeFile(path: String, data: Uint8Array, options: js.Object): js.Promise[Unit] = js.native
  def readdir(path: String, options: js.Object): js.Promise[js.Array[js.Dynamic]]     = js.native
  def mkdir(path: String, options: js.Object): js.Promise[Unit]                       = js.native
  def rm(path: String, options: js.Object): js.Promise[Unit]                          = js.native
  def stat(path: String): js.Promise[NodeStats]                                       = js.native
  def access(path: String): js.Promise[Unit]                                          = js.native
  def copyFile(src: String, dest: String): js.Promise[Unit]                           = js.native
  def rename(oldPath: String, newPath: String): js.Promise[Unit]                      = js.native
  def mkdtemp(prefix: String): js.Promise[String]                                     = js.native

@js.native
private[io] trait NodeStats extends js.Object:
  def isFile(): Boolean         = js.native
  def isDirectory(): Boolean    = js.native
  def isSymbolicLink(): Boolean = js.native
  def size: Double              = js.native
  def mtimeMs: Double           = js.native
  def atimeMs: Double           = js.native
  def ctimeMs: Double           = js.native
  def birthtimeMs: Double       = js.native
  def mode: Int                 = js.native

@js.native
private[io] trait NodeDirent extends js.Object:
  def name: String              = js.native
  def isFile(): Boolean         = js.native
  def isDirectory(): Boolean    = js.native
  def isSymbolicLink(): Boolean = js.native

/**
  * Node.js path module facade.
  */
@js.native
@JSImport("path", JSImport.Namespace)
private[io] object NodePathModule extends js.Object:
  def sep: String                                = js.native
  def join(paths: String*): String               = js.native
  def resolve(paths: String*): String            = js.native
  def dirname(path: String): String              = js.native
  def basename(path: String): String             = js.native
  def extname(path: String): String              = js.native
  def isAbsolute(path: String): Boolean          = js.native
  def relative(from: String, to: String): String = js.native
  def normalize(path: String): String            = js.native

/**
  * Node.js os module facade.
  */
@js.native
@JSImport("os", JSImport.Namespace)
private[io] object NodeOSModule extends js.Object:
  def homedir(): String  = js.native
  def tmpdir(): String   = js.native
  def platform(): String = js.native
