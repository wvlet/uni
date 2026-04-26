package wvlet.uni.weaver.codec

import wvlet.uni.msgpack.spi.Packer
import wvlet.uni.msgpack.spi.Unpacker
import wvlet.uni.msgpack.spi.ValueType
import wvlet.uni.weaver.Weaver
import wvlet.uni.weaver.WeaverConfig
import wvlet.uni.weaver.WeaverContext

import scala.collection.mutable.ListBuffer

object AnyWeaver:
  val default: Weaver[Any] = AnyWeaver()

  def apply(knownWeavers: Map[Class[?], Weaver[?]] = Map.empty): Weaver[Any] = AnyWeaverImpl(
    knownWeavers
  )

/**
  * Weaver for Any values. Supports basic types for packing/unpacking collections like Seq[Any],
  * Map[String, Any].
  *
  * Pack: Uses runtime type matching to serialize values. For unknown types, looks up in
  * knownWeavers or falls back to toString.
  *
  * Unpack: Produces generic types (Long for INTEGER, Double for FLOAT, etc.) since type information
  * is not preserved in the serialized format.
  *
  * @param knownWeavers
  *   Map of Class to Weaver for custom types that should be properly serialized
  */
class AnyWeaverImpl(knownWeavers: Map[Class[?], Weaver[?]]) extends Weaver[Any]:

  override def pack(p: Packer, v: Any, config: WeaverConfig): Unit =
    v match
      case null =>
        p.packNil
      // Primitives
      case v: String =>
        p.packString(v)
      case v: Boolean =>
        p.packBoolean(v)
      case v: Int =>
        p.packInt(v)
      case v: Long =>
        p.packLong(v)
      case v: Float =>
        p.packFloat(v)
      case v: Double =>
        p.packDouble(v)
      case v: Byte =>
        p.packByte(v)
      case v: Short =>
        p.packShort(v)
      case v: Char =>
        p.packString(v.toString)
      // Binary
      case v: Array[Byte] =>
        p.packBinaryHeader(v.length)
        p.writePayload(v)
      // Collections
      case v: Option[?] =>
        if v.isEmpty then
          p.packNil
        else
          pack(p, v.get, config)
      case v: Seq[?] =>
        p.packArrayHeader(v.size)
        v.foreach(elem => pack(p, elem, config))
      case v: Array[?] =>
        p.packArrayHeader(v.length)
        v.foreach(elem => pack(p, elem, config))
      case m: Map[?, ?] =>
        p.packMapHeader(m.size)
        m.foreach { (k, v) =>
          pack(p, k, config)
          pack(p, v, config)
        }
      // Java collections (commonly produced by YAML/JSON parsers like SnakeYAML / Jackson)
      case m: java.util.Map[?, ?] =>
        p.packMapHeader(m.size)
        val it = m.entrySet().iterator()
        while it.hasNext do
          val entry = it.next()
          pack(p, entry.getKey, config)
          pack(p, entry.getValue, config)
      case v: java.util.Collection[?] =>
        p.packArrayHeader(v.size)
        val it = v.iterator()
        while it.hasNext do
          pack(p, it.next(), config)
      // Scala 3 enum support
      case e: scala.reflect.Enum =>
        p.packString(e.toString)
      // Known weavers lookup
      case other =>
        knownWeavers.get(other.getClass) match
          case Some(weaver) =>
            weaver.asInstanceOf[Weaver[Any]].pack(p, other, config)
          case None =>
            // Fallback: toString for unknown types
            p.packString(other.toString)
  end pack

  override def unpack(u: Unpacker, context: WeaverContext): Unit =
    u.getNextValueType match
      case ValueType.NIL =>
        u.unpackNil
        context.setNull
      case ValueType.BOOLEAN =>
        context.setBoolean(u.unpackBoolean)
      case ValueType.INTEGER =>
        context.setLong(u.unpackLong)
      case ValueType.FLOAT =>
        context.setDouble(u.unpackDouble)
      case ValueType.STRING =>
        context.setString(u.unpackString)
      case ValueType.BINARY =>
        val len     = u.unpackBinaryHeader
        val payload = u.readPayload(len)
        context.setObject(payload)
      case ValueType.ARRAY =>
        unpackArray(u, context)
      case ValueType.MAP =>
        unpackMap(u, context)
      case ValueType.EXTENSION =>
        val ext     = u.unpackExtTypeHeader
        val payload = u.readPayload(ext.byteLength)
        context.setObject(payload)
  end unpack

  private def unpackArray(u: Unpacker, context: WeaverContext): Unit =
    try
      val len    = u.unpackArrayHeader
      val buffer = ListBuffer.empty[Any]
      var i      = 0
      while i < len do
        val elemContext = WeaverContext(context.config)
        unpack(u, elemContext)
        if elemContext.hasError then
          context.setError(elemContext.getError.get)
          // Skip remaining elements
          while i + 1 < len do
            u.skipValue
            i += 1
          return
        else if elemContext.isNull then
          buffer += null
        else
          buffer += elemContext.getLastValue
        i += 1
      context.setObject(buffer.toSeq)
    catch
      case e: Exception =>
        context.setError(e)

  private def unpackMap(u: Unpacker, context: WeaverContext): Unit =
    try
      val len    = u.unpackMapHeader
      val buffer = ListBuffer.empty[(Any, Any)]
      var i      = 0
      while i < len do
        // Unpack key
        val keyContext = WeaverContext(context.config)
        unpack(u, keyContext)
        if keyContext.hasError then
          context.setError(keyContext.getError.get)
          // Skip the value for current pair (key already consumed by unpack)
          u.skipValue
          i += 1
          // Skip remaining pairs
          while i < len do
            u.skipValue
            u.skipValue
            i += 1
          return
        val key = keyContext.getLastValue

        // Unpack value
        val valContext = WeaverContext(context.config)
        unpack(u, valContext)
        if valContext.hasError then
          context.setError(valContext.getError.get)
          // Skip remaining pairs
          while i + 1 < len do
            u.skipValue
            u.skipValue
            i += 1
          return
        val value = valContext.getLastValue
        buffer += (key -> value)
        i += 1
      end while
      context.setObject(buffer.toMap)
    catch
      case e: Exception =>
        context.setError(e)

end AnyWeaverImpl
