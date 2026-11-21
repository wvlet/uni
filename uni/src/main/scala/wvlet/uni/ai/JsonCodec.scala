package wvlet.uni.ai

import wvlet.uni.json.JSON
import wvlet.uni.json.JSON.*
import wvlet.uni.msgpack.spi.Packer
import wvlet.uni.msgpack.spi.Unpacker
import wvlet.uni.weaver.Weaver
import wvlet.uni.weaver.WeaverConfig
import wvlet.uni.weaver.WeaverContext
import wvlet.uni.weaver.codec.JSONValueWeaver

/**
  * Thrown when a JSON payload does not match the vendor API shape a codec expects
  */
class JsonCodecException(message: String) extends IllegalArgumentException(message)

/**
  * Encodes and decodes a value to the exact JSON shape of a vendor API: snake_case or camelCase
  * keys as the vendor spells them, no `null` for absent optional fields, and `type`-tagged or
  * key-tagged unions.
  */
trait JsonCodec[A]:
  def encode(a: A): JSONValue
  def decode(v: JSONValue): A

  def toJson(a: A): String      = encode(a).toJSON
  def fromJson(json: String): A = decode(JSON.parseAny(json))

  /**
    * A Weaver that packs the same JSON shape, for returning these values from Uni routers and RPC
    * handlers
    */
  def weaver: Weaver[A] = JsonCodec.WeaverBridge(this)

object JsonCodec:
  def apply[A](using c: JsonCodec[A]): JsonCodec[A] = c

  def of[A](enc: A => JSONValue, dec: JSONValue => A): JsonCodec[A] =
    new JsonCodec[A]:
      def encode(a: A): JSONValue = enc(a)
      def decode(v: JSONValue): A = dec(v)

  /**
    * A codec for JSON objects, built from an encoder producing fields and a decoder reading a
    * [[JsonObj]]
    */
  def obj[A](enc: A => Seq[JsonField], dec: JsonObj => A): JsonCodec[A] =
    new JsonCodec[A]:
      def encode(a: A): JSONValue = Json.obj(enc(a)*)
      def decode(v: JSONValue): A = dec(JsonObj(v))

  private class WeaverBridge[A](codec: JsonCodec[A]) extends Weaver[A]:
    override def pack(p: Packer, v: A, config: WeaverConfig): Unit = JSONValueWeaver.pack(
      p,
      codec.encode(v),
      config
    )

    override def unpack(u: Unpacker, context: WeaverContext): Unit =
      try
        context.setObject(codec.decode(JSONValueWeaver.unpackJson(u)))
      catch
        case e: Exception =>
          context.setError(e)

  given JsonCodec[String] = of(
    JSONString(_),
    {
      case JSONString(s) =>
        s
      case other =>
        throw JsonCodecException(s"Expected a string, got ${other.toJSON}")
    }
  )

  given JsonCodec[Long] = of(
    JSONLong(_),
    {
      case JSONLong(x) =>
        x
      case JSONDouble(x) =>
        x.toLong
      case other =>
        throw JsonCodecException(s"Expected a number, got ${other.toJSON}")
    }
  )

  given JsonCodec[Int] = of(JSONLong(_), v => summon[JsonCodec[Long]].decode(v).toInt)

  given JsonCodec[Double] = of(
    JSONDouble(_),
    {
      case JSONDouble(x) =>
        x
      case JSONLong(x) =>
        x.toDouble
      case other =>
        throw JsonCodecException(s"Expected a number, got ${other.toJSON}")
    }
  )

  given JsonCodec[Boolean] = of(
    JSONBoolean(_),
    {
      case JSONBoolean(b) =>
        b
      case other =>
        throw JsonCodecException(s"Expected a boolean, got ${other.toJSON}")
    }
  )

  given JsonCodec[JSONValue] = of(identity, identity)

  given seqCodec[A](using c: JsonCodec[A]): JsonCodec[Seq[A]] = of(
    xs => JSONArray(xs.map(c.encode).toIndexedSeq),
    {
      case JSONArray(xs) =>
        xs.map(c.decode)
      case other =>
        throw JsonCodecException(s"Expected an array, got ${other.toJSON}")
    }
  )

  given mapCodec[A](using c: JsonCodec[A]): JsonCodec[Map[String, A]] = of(
    m => JSONObject(m.toSeq.map((k, v) => k -> c.encode(v))),
    {
      case JSONObject(kv) =>
        kv.map((k, v) => k -> c.decode(v)).toMap
      case other =>
        throw JsonCodecException(s"Expected an object, got ${other.toJSON}")
    }
  )

end JsonCodec

/**
  * One key of a JSON object being built; `None` values are dropped, so absent optional fields never
  * become `null`
  */
type JsonField = (String, Option[JSONValue])

object Json:
  def obj(fields: JsonField*): JSONObject = JSONObject(
    fields.collect { case (k, Some(v)) =>
      k -> v
    }
  )

  /** Required field */
  def field[A](key: String, a: A)(using c: JsonCodec[A]): JsonField = key -> Some(c.encode(a))

  /** Optional field, omitted when None */
  def opt[A](key: String, a: Option[A])(using c: JsonCodec[A]): JsonField = key -> a.map(c.encode)

  /** Sequence field, omitted when empty */
  def nonEmpty[A](key: String, xs: Seq[A])(using c: JsonCodec[Seq[A]]): JsonField =
    key -> (
      if xs.isEmpty then
        None
      else
        Some(c.encode(xs))
    )

  /** Map field, omitted when empty */
  def nonEmptyMap(key: String, m: Map[String, JSONValue]): JsonField =
    key -> (
      if m.isEmpty then
        None
      else
        Some(JSONObject(m.toSeq))
    )

  /** Unmodeled fields, appended as they are */
  def extra(m: Map[String, JSONValue]): Seq[JsonField] = m.toSeq.map((k, v) => k -> Some(v))

  /** Parse a JSON string, or return it as a JSON string when it is not valid JSON */
  def parseOrString(s: String): JSONValue =
    try
      JSON.parseAny(s)
    catch
      case _: Exception =>
        JSONString(s)

end Json

/**
  * Read access to a JSON object, with errors that name the missing or mistyped key
  */
class JsonObj(val value: JSONObject):
  def fields: Seq[(String, JSONValue)] = value.v

  def raw(key: String): Option[JSONValue] =
    value.get(key) match
      case Some(_: JSONNull) =>
        None
      case other =>
        other

  def has(key: String): Boolean = raw(key).isDefined

  def get[A](key: String)(using c: JsonCodec[A]): A =
    raw(key) match
      case Some(v) =>
        decodeAt(key, v)
      case None =>
        throw JsonCodecException(s"Missing required field '${key}' in ${value.toJSON.take(200)}")

  def opt[A](key: String)(using c: JsonCodec[A]): Option[A] = raw(key).map(v => decodeAt(key, v))

  def getOrElse[A](key: String, default: => A)(using c: JsonCodec[A]): A = opt[A](key).getOrElse(
    default
  )

  def seq[A](key: String)(using c: JsonCodec[A]): Seq[A] = opt[Seq[A]](key).getOrElse(Seq.empty)

  def str(key: String): String                     = get[String](key)
  def strOpt(key: String): Option[String]          = opt[String](key)
  def strOrEmpty(key: String): String              = strOpt(key).getOrElse("")
  def obj(key: String): JsonObj                    = JsonObj(get[JSONValue](key))
  def objOpt(key: String): Option[JsonObj]         = opt[JSONValue](key).map(JsonObj(_))
  def jsonMap(key: String): Map[String, JSONValue] = opt[Map[String, JSONValue]](key).getOrElse(
    Map.empty
  )

  /** Fields other than the given known keys, kept so that unmodeled fields survive a round trip */
  def extra(known: Set[String]): Map[String, JSONValue] =
    value
      .v
      .collect {
        case (k, v) if !known.contains(k) =>
          k -> v
      }
      .toMap

  private def decodeAt[A](key: String, v: JSONValue)(using c: JsonCodec[A]): A =
    try
      c.decode(v)
    catch
      case e: JsonCodecException =>
        throw JsonCodecException(s"Field '${key}': ${e.getMessage}")

end JsonObj

object JsonObj:
  def apply(v: JSONValue): JsonObj =
    v match
      case o: JSONObject =>
        new JsonObj(o)
      case other =>
        throw JsonCodecException(s"Expected an object, got ${other.toJSON.take(200)}")

  def unapply(v: JSONValue): Option[JsonObj] =
    v match
      case o: JSONObject =>
        Some(new JsonObj(o))
      case _ =>
        None
