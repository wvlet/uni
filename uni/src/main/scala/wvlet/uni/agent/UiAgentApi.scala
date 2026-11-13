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
package wvlet.uni.agent

import wvlet.uni.mcp.description
import wvlet.uni.rx.Rx

/**
  * The tools an AI agent uses to read and operate a running uni-ui app. uni-ui's `UiAgent`
  * implements it in the page; transports expose it as MCP tools (`MCPServer.withTools`), as WebMCP
  * tools, or over Uni RPC, for example from an Electron main process to its renderer.
  *
  * Results are JSON text, so each action can carry its own JSON Schema and every transport passes
  * the result through unchanged. Every method returns `Rx`, so a forwarder in another process (an
  * Electron main process calling its renderer) can implement the trait over an async transport.
  */
trait UiAgentApi:

  @description(
    "List the UI components on screen as a tree. Each node has a ref, kind, label, value, items, " +
      "disabled, and the actions available now with a JSON Schema for each action's value. Call " +
      "it before acting, and again after an action changes the screen."
  )
  def snapshot(): Rx[String]

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
      ref: Option[String] = None
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
      value: Option[String] = None
  ): Rx[String]

  @description(
    "List the changes the human made since a cursor, such as a selection or typed text. Pass " +
      "the cursor from the previous call (0 the first time) to see only newer changes."
  )
  def changes(
      @description("The cursor from the previous call, or 0")
      since: Long = 0
  ): Rx[String]

end UiAgentApi
