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
package wvlet.uni.ui.agent

import wvlet.uni.json.JSON.{JSONArray, JSONBoolean, JSONLong, JSONObject, JSONString, JSONValue}
import wvlet.uni.ui.ChangeSource

/** One choice a component offers, such as an option of a select or a tab of a tab list. */
case class UiItem(label: String, selected: Boolean = false):
  def toJSON: JSONObject = UiJson.obj(
    "label"    -> Some(JSONString(label)),
    "selected" -> Option.when(selected)(JSONBoolean(true))
  )

/**
  * An action an agent can take on a component now.
  *
  * @param input
  *   the JSON Schema of the action's `value`, or None when it takes no value
  */
case class UiAction(name: String, description: String, input: Option[JSONObject] = None):
  def toJSON: JSONObject = UiJson.obj(
    "name"        -> Some(JSONString(name)),
    "description" -> Some(JSONString(description)),
    "value"       -> input
  )

/**
  * A uni-ui component as an agent sees it.
  *
  * @param ref
  *   the handle for [[UiAgent.act]]: an explicit [[UiAgent.ref]], or one derived from the kind and
  *   label, stable while the component stays mounted
  * @param value
  *   the current value as text: the chosen option, the typed text, `open` or `closed`. Secret
  *   values read `<redacted>`
  * @param moreItems
  *   how many items were left out of `items` to keep the snapshot small
  */
case class UiNode(
    ref: String,
    kind: String,
    label: String,
    value: Option[String] = None,
    items: Seq[UiItem] = Nil,
    moreItems: Int = 0,
    disabled: Boolean = false,
    actions: Seq[UiAction] = Nil,
    children: Seq[UiNode] = Nil
):
  /** This node and all nodes below it, depth first. */
  def flatten: Seq[UiNode] = this +: children.flatMap(_.flatten)

  def action(name: String): Option[UiAction] = actions.find(_.name == name)

  /** The JSON an agent reads. Empty fields are left out to keep snapshots small. */
  def toJSON: JSONObject = UiJson.obj(
    "ref"       -> Some(JSONString(ref)),
    "kind"      -> Some(JSONString(kind)),
    "label"     -> Option.when(label.nonEmpty)(JSONString(label)),
    "value"     -> value.map(JSONString(_)),
    "items"     -> Option.when(items.nonEmpty)(JSONArray(items.map(_.toJSON).toIndexedSeq)),
    "moreItems" -> Option.when(moreItems > 0)(JSONLong(moreItems)),
    "disabled"  -> Option.when(disabled)(JSONBoolean(true)),
    "actions"   -> Option.when(actions.nonEmpty)(JSONArray(actions.map(_.toJSON).toIndexedSeq)),
    "children"  -> Option.when(children.nonEmpty)(JSONArray(children.map(_.toJSON).toIndexedSeq))
  )

end UiNode

/** How an agent's action ended. */
enum ActStatus(val name: String):
  /** The change was made. */
  case Applied extends ActStatus("applied")

  /** The app's `onChange` handler canceled it. */
  case Canceled extends ActStatus("canceled")

  /** The human declined it in the confirmation dialog. */
  case Declined extends ActStatus("declined")

  /** The policy forbids it, the component is disabled, or the action or value is not valid. */
  case Refused extends ActStatus("refused")

  /** No component on screen has the ref. */
  case NotFound extends ActStatus("not-found")

/** The result of [[UiAgent.act]], with the component as it is afterwards when it is still there. */
case class ActResult(status: ActStatus, message: String = "", node: Option[UiNode] = None):
  def toJSON: JSONObject = UiJson.obj(
    "status"  -> Some(JSONString(status.name)),
    "message" -> Option.when(message.nonEmpty)(JSONString(message)),
    "node"    -> node.map(_.toJSON)
  )

/**
  * A change a uni-ui component made to app state, as reported by [[UiAgent.subscribe]] and
  * [[UiAgent.changes]].
  *
  * @param seq
  *   the change's position in the feed, used as the `since` cursor of [[UiAgent.changes]]
  * @param ref
  *   the component's ref, when the component is registered with the agent
  * @param key
  *   the state that changed: `selected`, `value`, `open`, `sort`, and so on
  */
case class UiChange(
    seq: Long,
    ref: Option[String],
    kind: Option[String],
    key: String,
    previous: String,
    value: String,
    reason: String,
    source: ChangeSource
):
  def toJSON: JSONObject = UiJson.obj(
    "seq"      -> Some(JSONLong(seq)),
    "ref"      -> ref.map(JSONString(_)),
    "kind"     -> kind.map(JSONString(_)),
    "key"      -> Some(JSONString(key)),
    "previous" -> Some(JSONString(previous)),
    "value"    -> Some(JSONString(value)),
    "reason"   -> Some(JSONString(reason)),
    "source"   -> Some(JSONString(source.toString.toLowerCase))
  )

end UiChange

private[agent] object UiJson:
  def obj(fields: (String, Option[JSONValue])*): JSONObject = JSONObject(
    fields.collect { case (k, Some(v)) =>
      k -> v
    }
  )

  def array(xs: Seq[JSONValue]): JSONArray = JSONArray(xs.toIndexedSeq)

  /** The schema of a free-text value. */
  def text(description: String): JSONObject = JSONObject(
    Seq("type" -> JSONString("string"), "description" -> JSONString(description))
  )

  /** The schema of a value chosen from `choices`. */
  def choice(description: String, choices: Seq[String]): JSONObject = JSONObject(
    Seq(
      "type"        -> JSONString("string"),
      "description" -> JSONString(description),
      "enum"        -> array(choices.map(JSONString(_)))
    )
  )

end UiJson
