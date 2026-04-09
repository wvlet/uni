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
package wvlet.uni.json

import wvlet.uni.json.JSON.*
import wvlet.uni.log.LogSupport

import scala.compiletime.uninitialized

class JSONValueBuilder extends JSONContext[JSONValue] with LogSupport:
  self =>

  override def result: JSONValue                           = null
  override def isObjectContext: Boolean                    = false
  override def closeContext(s: JSONSource, end: Int): Unit = {}
  def add(v: JSONValue): Unit                              = {}

  // Comment buffering for JSONC support (protected so inner builders access their own instance)
  protected val pendingLeadingComments    = scala.collection.mutable.ArrayBuffer[JSONComment]()
  protected var lastAddedValue: JSONValue = null

  override def addComment(comment: JSONComment, isTrailing: Boolean): Unit =
    if isTrailing && lastAddedValue != null then
      lastAddedValue.trailingComment = Some(comment)
    else
      pendingLeadingComments += comment

  protected def attachPendingComments(v: JSONValue): Unit =
    if pendingLeadingComments.nonEmpty then
      v.leadingComments = pendingLeadingComments.toSeq
      pendingLeadingComments.clear()
    lastAddedValue = v

  override def singleContext(s: JSONSource, start: Int): JSONContext[JSONValue] =
    new JSONValueBuilder:
      private var holder: JSONValue                            = uninitialized
      override def isObjectContext                             = false
      override def closeContext(s: JSONSource, end: Int): Unit = {}
      override def add(v: JSONValue): Unit                     =
        attachPendingComments(v)
        holder = v
      override def result: JSONValue = holder

  override def objectContext(s: JSONSource, start: Int): JSONContext[JSONValue] =
    new JSONValueBuilder:
      private var key: String                                  = null
      private val list                                         = Seq.newBuilder[(String, JSONValue)]
      override def closeContext(s: JSONSource, end: Int): Unit =
        val r = result
        // Remaining comments at end of container
        if pendingLeadingComments.nonEmpty then
          if lastAddedValue != null then
            // Attach as trailing on last element
            lastAddedValue.trailingComment = Some(pendingLeadingComments.head)
          else
            // Empty container: attach comments to the container itself
            r.leadingComments = pendingLeadingComments.toSeq
          pendingLeadingComments.clear()
        self.add(r)
      override def isObjectContext: Boolean = true
      override def add(v: JSONValue): Unit  =
        if key == null then
          key = v.toString
        else
          attachPendingComments(v)
          list += key -> v
          key = null
      override def result: JSONValue = JSONObject(list.result())

  override def arrayContext(s: JSONSource, start: Int): JSONContext[JSONValue] =
    new JSONValueBuilder:
      private val list                                         = IndexedSeq.newBuilder[JSONValue]
      override def isObjectContext: Boolean                    = false
      override def closeContext(s: JSONSource, end: Int): Unit =
        val r = result
        // Remaining comments at end of container
        if pendingLeadingComments.nonEmpty then
          if lastAddedValue != null then
            lastAddedValue.trailingComment = Some(pendingLeadingComments.head)
          else
            r.leadingComments = pendingLeadingComments.toSeq
          pendingLeadingComments.clear()
        self.add(r)
      override def add(v: JSONValue): Unit =
        attachPendingComments(v)
        list += v
      override def result: JSONValue = JSONArray(list.result())

  override def addNull(s: JSONSource, start: Int, end: Int): Unit   = add(JSONNull())
  override def addString(s: JSONSource, start: Int, end: Int): Unit = add(
    JSONString(s.substring(start, end))
  )

  override def addUnescapedString(s: String): Unit = add(JSONString(s))
  override def addNumber(s: JSONSource, start: Int, end: Int, dotIndex: Int, expIndex: Int): Unit =
    val v               = s.substring(start, end)
    val num: JSONNumber =
      if dotIndex >= 0 || expIndex >= 0 then
        JSONDouble(v.toDouble)
      else
        try
          JSONLong(v.toLong)
        catch
          case _: NumberFormatException =>
            // JSON is not suited to representing scientific values.
            throw IntegerOverflow(BigInt(v).bigInteger)
    add(num)

  override def addBoolean(s: JSONSource, v: Boolean, start: Int, end: Int): Unit =
    val b =
      if v then
        JSONBoolean(true)
      else
        JSONBoolean(false)
    add(b)

end JSONValueBuilder
