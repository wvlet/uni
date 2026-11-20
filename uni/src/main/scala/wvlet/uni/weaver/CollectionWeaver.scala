package wvlet.uni.weaver

import wvlet.uni.msgpack.spi.Packer
import wvlet.uni.msgpack.spi.Unpacker
import wvlet.uni.msgpack.spi.ValueType

import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*

/**
  * Named weaver classes for collection types. These are used by the runtime WeaverFactory to create
  * weavers from Surface information.
  */

class OptionWeaver(inner: Weaver[?]) extends Weaver[Option[?]]:
  override def innerWeavers: Seq[Weaver[?]] = Seq(inner)

  override def pack(p: Packer, v: Option[?], config: WeaverConfig): Unit =
    v match
      case Some(value) =>
        inner.asInstanceOf[Weaver[Any]].pack(p, value, config)
      case None =>
        p.packNil

  override def unpack(u: Unpacker, context: WeaverContext): Unit =
    u.getNextValueType match
      case ValueType.NIL =>
        u.unpackNil
        context.setObject(None)
      case _ =>
        val elementContext = WeaverContext(context.config)
        inner.unpack(u, elementContext)
        if elementContext.hasError then
          context.setError(elementContext.getError.get)
        else if elementContext.isNull then
          context.setObject(None)
        else
          context.setObject(Some(elementContext.getLastValue))

class SeqWeaver(elem: Weaver[?], targetType: Class[?]) extends Weaver[Seq[?]]:
  override def innerWeavers: Seq[Weaver[?]] = Seq(elem)

  override def pack(p: Packer, v: Seq[?], config: WeaverConfig): Unit =
    p.packArrayHeader(v.size)
    v.foreach(e => elem.asInstanceOf[Weaver[Any]].pack(p, e, config))

  override def unpack(u: Unpacker, context: WeaverContext): Unit =
    u.getNextValueType match
      case ValueType.ARRAY =>
        CollectionWeaver.unpackArrayToBuffer(u, context, elem) match
          case Some(buffer) =>
            val result =
              if classOf[List[?]].isAssignableFrom(targetType) then
                buffer.toList
              else if classOf[IndexedSeq[?]].isAssignableFrom(targetType) then
                buffer.toIndexedSeq
              else if classOf[Vector[?]].isAssignableFrom(targetType) then
                buffer.toVector
              else
                buffer.toSeq
            context.setObject(result)
          case None => // Error already set
      case ValueType.NIL =>
        u.unpackNil
        context.setNull
      case other =>
        u.skipValue
        context.setError(IllegalArgumentException(s"Cannot convert ${other} to Seq"))

class SetWeaver(elem: Weaver[?]) extends Weaver[Set[?]]:
  override def innerWeavers: Seq[Weaver[?]] = Seq(elem)

  override def pack(p: Packer, v: Set[?], config: WeaverConfig): Unit =
    p.packArrayHeader(v.size)
    v.foreach(e => elem.asInstanceOf[Weaver[Any]].pack(p, e, config))

  override def unpack(u: Unpacker, context: WeaverContext): Unit =
    u.getNextValueType match
      case ValueType.ARRAY =>
        CollectionWeaver.unpackArrayToBuffer(u, context, elem) match
          case Some(buffer) =>
            context.setObject(buffer.toSet)
          case None => // Error already set
      case ValueType.NIL =>
        u.unpackNil
        context.setNull
      case other =>
        u.skipValue
        context.setError(IllegalArgumentException(s"Cannot convert ${other} to Set"))

class MapWeaver(keyWeaver: Weaver[?], valueWeaver: Weaver[?]) extends Weaver[Map[?, ?]]:
  override def innerWeavers: Seq[Weaver[?]] = Seq(keyWeaver, valueWeaver)

  override def pack(p: Packer, v: Map[?, ?], config: WeaverConfig): Unit =
    p.packMapHeader(v.size)
    v.foreach { case (key, value) =>
      keyWeaver.asInstanceOf[Weaver[Any]].pack(p, key, config)
      valueWeaver.asInstanceOf[Weaver[Any]].pack(p, value, config)
    }

  override def unpack(u: Unpacker, context: WeaverContext): Unit =
    u.getNextValueType match
      case ValueType.MAP =>
        CollectionWeaver.unpackMapToBuffer(u, context, keyWeaver, valueWeaver) match
          case Some(buffer) =>
            context.setObject(buffer.toMap)
          case None => // Error already set
      case ValueType.NIL =>
        u.unpackNil
        context.setNull
      case other =>
        u.skipValue
        context.setError(IllegalArgumentException(s"Cannot convert ${other} to Map"))

class ArrayWeaver(elem: Weaver[?], elemClass: Class[?]) extends Weaver[Array[?]]:
  override def innerWeavers: Seq[Weaver[?]] = Seq(elem)

  override def pack(p: Packer, v: Array[?], config: WeaverConfig): Unit =
    p.packArrayHeader(v.length)
    v.foreach(e => elem.asInstanceOf[Weaver[Any]].pack(p, e, config))

  override def unpack(u: Unpacker, context: WeaverContext): Unit =
    u.getNextValueType match
      case ValueType.ARRAY =>
        CollectionWeaver.unpackArrayToBuffer(u, context, elem) match
          case Some(buffer) =>
            val arr = java.lang.reflect.Array.newInstance(elemClass, buffer.size)
            var i   = 0
            buffer.foreach { e =>
              java.lang.reflect.Array.set(arr, i, e)
              i += 1
            }
            context.setObject(arr)
          case None => // Error already set
      case ValueType.NIL =>
        u.unpackNil
        context.setNull
      case other =>
        u.skipValue
        context.setError(IllegalArgumentException(s"Cannot convert ${other} to Array"))

class JavaListWeaver(elem: Weaver[?]) extends Weaver[java.util.List[?]]:
  override def innerWeavers: Seq[Weaver[?]] = Seq(elem)

  override def pack(p: Packer, v: java.util.List[?], config: WeaverConfig): Unit =
    p.packArrayHeader(v.size)
    v.forEach(e => elem.asInstanceOf[Weaver[Any]].pack(p, e, config))

  override def unpack(u: Unpacker, context: WeaverContext): Unit =
    u.getNextValueType match
      case ValueType.ARRAY =>
        CollectionWeaver.unpackArrayToBuffer(u, context, elem) match
          case Some(buffer) =>
            context.setObject(java.util.ArrayList[Any](buffer.asJava))
          case None => // Error already set
      case ValueType.NIL =>
        u.unpackNil
        context.setNull
      case other =>
        u.skipValue
        context.setError(IllegalArgumentException(s"Cannot convert ${other} to java.util.List"))

class JavaSetWeaver(elem: Weaver[?]) extends Weaver[java.util.Set[?]]:
  override def innerWeavers: Seq[Weaver[?]] = Seq(elem)

  override def pack(p: Packer, v: java.util.Set[?], config: WeaverConfig): Unit =
    p.packArrayHeader(v.size)
    v.forEach(e => elem.asInstanceOf[Weaver[Any]].pack(p, e, config))

  override def unpack(u: Unpacker, context: WeaverContext): Unit =
    u.getNextValueType match
      case ValueType.ARRAY =>
        CollectionWeaver.unpackArrayToBuffer(u, context, elem) match
          case Some(buffer) =>
            context.setObject(java.util.HashSet[Any](buffer.asJava))
          case None => // Error already set
      case ValueType.NIL =>
        u.unpackNil
        context.setNull
      case other =>
        u.skipValue
        context.setError(IllegalArgumentException(s"Cannot convert ${other} to java.util.Set"))

class JavaMapWeaver(keyWeaver: Weaver[?], valueWeaver: Weaver[?])
    extends Weaver[java.util.Map[?, ?]]:
  override def innerWeavers: Seq[Weaver[?]] = Seq(keyWeaver, valueWeaver)

  override def pack(p: Packer, v: java.util.Map[?, ?], config: WeaverConfig): Unit =
    p.packMapHeader(v.size)
    v.forEach { (key, value) =>
      keyWeaver.asInstanceOf[Weaver[Any]].pack(p, key, config)
      valueWeaver.asInstanceOf[Weaver[Any]].pack(p, value, config)
    }

  override def unpack(u: Unpacker, context: WeaverContext): Unit =
    u.getNextValueType match
      case ValueType.MAP =>
        CollectionWeaver.unpackMapToBuffer(u, context, keyWeaver, valueWeaver) match
          case Some(buffer) =>
            val map = java.util.HashMap[Any, Any](buffer.size)
            buffer.foreach { case (k, v) =>
              map.put(k, v)
            }
            context.setObject(map)
          case None => // Error already set
      case ValueType.NIL =>
        u.unpackNil
        context.setNull
      case other =>
        u.skipValue
        context.setError(IllegalArgumentException(s"Cannot convert ${other} to java.util.Map"))

/**
  * Shared helper methods for unpacking collections.
  */
object CollectionWeaver:

  def unpackArrayToBuffer(
      u: Unpacker,
      context: WeaverContext,
      elementWeaver: Weaver[?]
  ): Option[ListBuffer[Any]] =
    try
      val arraySize = u.unpackArrayHeader
      val buffer    = ListBuffer.empty[Any]

      var i        = 0
      var hasError = false
      while i < arraySize && !hasError do
        val elementContext = WeaverContext(context.config)
        elementWeaver.unpack(u, elementContext)

        if elementContext.hasError then
          context.setError(elementContext.getError.get)
          hasError = true
          // Skip remaining elements
          while i + 1 < arraySize do
            u.skipValue
            i += 1
        else
          buffer += elementContext.getLastValue
          i += 1

      if hasError then
        None
      else
        Some(buffer)
    catch
      case e: Exception =>
        context.setError(e)
        None

  def unpackMapToBuffer(
      u: Unpacker,
      context: WeaverContext,
      keyWeaver: Weaver[?],
      valueWeaver: Weaver[?]
  ): Option[ListBuffer[(Any, Any)]] =
    try
      val mapSize = u.unpackMapHeader
      val buffer  = ListBuffer.empty[(Any, Any)]

      var i        = 0
      var hasError = false
      while i < mapSize && !hasError do
        val keyContext = WeaverContext(context.config)
        keyWeaver.unpack(u, keyContext)

        if keyContext.hasError then
          context.setError(keyContext.getError.get)
          hasError = true
          // Skip remaining pairs - need to skip value for current pair first
          u.skipValue
          while i + 1 < mapSize do
            u.skipValue // key
            u.skipValue // value
            i += 1
        else
          val key          = keyContext.getLastValue
          val valueContext = WeaverContext(context.config)
          valueWeaver.unpack(u, valueContext)

          if valueContext.hasError then
            context.setError(valueContext.getError.get)
            hasError = true
            // Skip remaining pairs
            while i + 1 < mapSize do
              u.skipValue // key
              u.skipValue // value
              i += 1
          else
            buffer += (key -> valueContext.getLastValue)
            i += 1
      end while

      if hasError then
        None
      else
        Some(buffer)
    catch
      case e: Exception =>
        context.setError(e)
        None

end CollectionWeaver
