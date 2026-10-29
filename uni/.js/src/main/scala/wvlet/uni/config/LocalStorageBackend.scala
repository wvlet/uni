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
package wvlet.uni.config

import org.scalajs.dom
import wvlet.uni.rx.Cancelable

import scala.scalajs.js

/**
  * Stores each key in browser storage (localStorage by default) as `<prefix>/<key>`, or as `<key>`
  * when the prefix is empty. Watching a key follows changes made in other tabs through `storage`
  * events.
  */
class LocalStorageBackend(prefix: String, storage: dom.Storage = dom.window.localStorage)
    extends StoreBackend:
  private def itemKey(key: String): String =
    if prefix.isEmpty then
      key
    else
      s"${prefix}/${key}"

  override def read(key: String): Option[String]       = Option(storage.getItem(itemKey(key)))
  override def write(key: String, value: String): Unit = storage.setItem(itemKey(key), value)
  override def remove(key: String): Unit               = storage.removeItem(itemKey(key))

  override def moveAside(key: String): Unit = read(key).foreach { v =>
    storage.setItem(s"${itemKey(key)}.corrupt-${System.currentTimeMillis()}", v)
    remove(key)
  }

  override def watch(key: String)(onChange: () => Unit): Cancelable =
    val listener: js.Function1[dom.StorageEvent, Unit] =
      (e: dom.StorageEvent) =>
        // A null key means the storage was cleared
        if e.storageArea == storage && (e.key == null || e.key == itemKey(key)) then
          onChange()
    dom.window.addEventListener("storage", listener)
    Cancelable(() => dom.window.removeEventListener("storage", listener))

end LocalStorageBackend
