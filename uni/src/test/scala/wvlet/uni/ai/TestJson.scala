package wvlet.uni.ai

import wvlet.uni.json.JSON
import wvlet.uni.json.JSON.*

/** JSON comparison that ignores the order of object keys */
object TestJson:
  def normalize(v: JSONValue): JSONValue =
    v match
      case JSONObject(fields) =>
        JSONObject(fields.map((k, x) => k -> normalize(x)).sortBy(_._1))
      case JSONArray(xs) =>
        JSONArray(xs.map(normalize))
      case other =>
        other

  def parse(s: String): JSONValue = normalize(JSON.parseAny(s))
