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
package example.server

import wvlet.uni.surface.description
import wvlet.uni.rx.Rx

/**
  * The agent bridge's MCP tools: uni's `UiAgentApi`, which describes one page, plus a `windows`
  * tool and an optional window id on every call, so an agent can drive any open harness window.
  *
  * Each result wraps the renderer's JSON as `{"window": id, "result": ...}`, naming the window that
  * answered.
  */
trait HarnessAgentApi:

  @description(
    "List the open app windows: id, title, focused, and default (the window a call without a " +
      "window id goes to: the focused window, or else the newest)."
  )
  def windows(): Rx[String]

  @description(
    "List the UI components on screen as a tree. Each node has a ref, kind, label, value, items, " +
      "disabled, and the actions available now with a JSON Schema for each action's value. Call " +
      "it before acting, and again after an action changes the screen."
  )
  def snapshot(
      @description("The window id from windows; omit for the default window")
      window: Option[Int] = None
  ): Rx[String]

  @description(
    "Find components without loading the whole tree: by kind (button, select, textfield, tabs, " +
      "menu, menuitem, dialog, list, table, tree), by text in the label or value, or by ref prefix."
  )
  def query(
      @description("Component kind, such as select")
      kind: Option[String] = None,
      @description("Text to find in the label or the value, case-insensitive")
      text: Option[String] = None,
      @description("Ref prefix")
      ref: Option[String] = None,
      @description("The window id from windows; omit for the default window")
      window: Option[Int] = None
  ): Rx[String]

  @description(
    "Run an action on a component, as listed in its snapshot. The result status is applied, " +
      "canceled (the app refused the change), declined (the human said no), refused (not " +
      "allowed, disabled, or a bad value), or not-found."
  )
  def act(
      @description("The component's ref from snapshot")
      ref: String,
      @description("An action listed for the component, such as press, setText, or select")
      action: String,
      @description("The action's value, matching its JSON Schema; omit when it takes none")
      value: Option[String] = None,
      @description("The window id from windows; omit for the default window")
      window: Option[Int] = None
  ): Rx[String]

  @description(
    "List the changes the human made in one window since a cursor, such as a selection or typed " +
      "text. Pass the cursor from the previous call (0 the first time) to see only newer changes. " +
      "A cursor is valid only for the window that returned it, so pass that window's id."
  )
  def changes(
      @description("The cursor from the previous call, or 0")
      since: Long = 0,
      @description("The window id from windows; omit for the default window")
      window: Option[Int] = None
  ): Rx[String]

end HarnessAgentApi
