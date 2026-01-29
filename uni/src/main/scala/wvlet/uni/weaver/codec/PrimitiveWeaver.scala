package wvlet.uni.weaver.codec

import wvlet.uni.msgpack.spi.Packer
import wvlet.uni.msgpack.spi.Unpacker
import wvlet.uni.msgpack.spi.ValueType
import wvlet.uni.weaver.Weaver
import wvlet.uni.weaver.WeaverConfig
import wvlet.uni.weaver.WeaverContext
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.Duration as ScalaDuration
import scala.jdk.CollectionConverters.*
import scala.reflect.ClassTag
import java.net.URI
import java.time.Instant
import java.util.UUID

object PrimitiveWeaver:

  private[codec] def safeUnpack[T](
      context: WeaverContext,
      operation: => T,
      setValue: T => Unit
  ): Unit =
    try
      val value = operation
      setValue(value)
    catch
      case e: Exception =>
        context.setError(e)

  private[codec] def safeConvertFromString[T](
      context: WeaverContext,
      u: Unpacker,
      converter: String => T,
      setValue: T => Unit,
      typeName: String
  ): Unit =
    try
      val s              = u.unpackString
      val convertedValue = converter(s)
      setValue(convertedValue)
    catch
      case e: IllegalArgumentException =>
        context.setError(e)
      case e: Exception =>
        context.setError(new IllegalArgumentException(s"Cannot convert string to ${typeName}", e))

  private[codec] def safeUnpackNil(context: WeaverContext, u: Unpacker): Unit =
    try
      u.unpackNil
      context.setNull
    catch
      case e: Exception =>
        context.setError(e)

  private def unpackArrayToBuffer[A](
      u: Unpacker,
      context: WeaverContext,
      elementWeaver: Weaver[A]
  ): Option[ListBuffer[A]] =
    try
      val arraySize = u.unpackArrayHeader
      val buffer    = ListBuffer.empty[A]

      var i        = 0
      var hasError = false
      while i < arraySize && !hasError do
        val elementContext = WeaverContext(context.config)
        elementWeaver.unpack(u, elementContext)

        if elementContext.hasError then
          context.setError(elementContext.getError.get)
          hasError = true
          // Skip remaining elements to keep unpacker in consistent state
          while i + 1 < arraySize do
            u.skipValue
            i += 1
        else
          buffer += elementContext.getLastValue.asInstanceOf[A]
          i += 1

      if hasError then
        None
      else
        Some(buffer)
    catch
      case e: Exception =>
        context.setError(e)
        None

  private def unpackMapToBuffer[K, V](
      u: Unpacker,
      context: WeaverContext,
      keyWeaver: Weaver[K],
      valueWeaver: Weaver[V]
  ): Option[ListBuffer[(K, V)]] =
    try
      val mapSize = u.unpackMapHeader
      val buffer  = ListBuffer.empty[(K, V)]

      var i        = 0
      var hasError = false
      while i < mapSize && !hasError do
        // Unpack key
        val keyContext = WeaverContext(context.config)
        keyWeaver.unpack(u, keyContext)

        if keyContext.hasError then
          context.setError(keyContext.getError.get)
          hasError = true
          // Skip remaining pairs to keep unpacker in consistent state
          while i < mapSize do
            u.skipValue // Skip key
            u.skipValue // Skip value
            i += 1
        else
          val key = keyContext.getLastValue.asInstanceOf[K]

          // Unpack value
          val valueContext = WeaverContext(context.config)
          valueWeaver.unpack(u, valueContext)

          if valueContext.hasError then
            context.setError(valueContext.getError.get)
            hasError = true
            // Skip remaining pairs to keep unpacker in consistent state
            while i + 1 < mapSize do
              u.skipValue // Skip key
              u.skipValue // Skip value
              i += 1
          else
            val value = valueContext.getLastValue.asInstanceOf[V]
            buffer += (key -> value)
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

  private def collectionWeaver[A, C](
      elementWeaver: Weaver[A],
      typeName: String,
      packElements: (Packer, C, WeaverConfig) => Unit,
      factory: ListBuffer[A] => C
  ): Weaver[C] =
    new Weaver[C]:
      override def pack(p: Packer, v: C, config: WeaverConfig): Unit = packElements(p, v, config)

      override def unpack(u: Unpacker, context: WeaverContext): Unit =
        u.getNextValueType match
          case ValueType.ARRAY =>
            unpackArrayToBuffer(u, context, elementWeaver) match
              case Some(buffer) =>
                context.setObject(factory(buffer))
              case None =>
          case ValueType.NIL =>
            safeUnpackNil(context, u)
          case other =>
            u.skipValue
            context.setError(
              new IllegalArgumentException(s"Cannot convert ${other} to ${typeName}")
            )

  private def iterableCollectionWeaver[A, C <: Iterable[A]](
      elementWeaver: Weaver[A],
      typeName: String,
      factory: ListBuffer[A] => C
  ): Weaver[C] = collectionWeaver(
    elementWeaver,
    typeName,
    (p, v, config) =>
      p.packArrayHeader(v.size)
      v.foreach(elementWeaver.pack(p, _, config))
    ,
    factory
  )

  given intWeaver: Weaver[Int] =
    new Weaver[Int]:
      override def pack(p: Packer, v: Int, config: WeaverConfig): Unit = p.packInt(v)

      override def unpack(u: Unpacker, context: WeaverContext): Unit =
        u.getNextValueType match
          case ValueType.INTEGER =>
            try
              context.setInt(u.unpackInt)
            catch
              case e: Exception =>
                context.setError(e)
          case ValueType.FLOAT =>
            try
              val d = u.unpackDouble
              if d.isWhole && d >= Int.MinValue && d <= Int.MaxValue then
                context.setInt(d.toInt)
              else
                context.setError(new IllegalArgumentException(s"Cannot convert double ${d} to Int"))
            catch
              case e: Exception =>
                context.setError(e)
          case ValueType.STRING =>
            val s = u.unpackString
            try
              val intValue = s.toInt
              context.setInt(intValue)
            catch
              case e: NumberFormatException =>
                context.setError(
                  new IllegalArgumentException(s"Cannot convert string '${s}' to Int", e)
                )
              case e: Exception =>
                context.setError(e)
          case ValueType.BOOLEAN =>
            try
              val b = u.unpackBoolean
              context.setInt(
                if b then
                  1
                else
                  0
              )
            catch
              case e: Exception =>
                context.setError(e)
          case ValueType.NIL =>
            safeUnpackNil(context, u)
          case other =>
            u.skipValue
            context.setError(new IllegalArgumentException(s"Cannot convert ${other} to Int"))

  given stringWeaver: Weaver[String] =
    new Weaver[String]:
      override def pack(p: Packer, v: String, config: WeaverConfig): Unit = p.packString(v)

      // Helper method to safely perform unpacking operations
      private def withSafeUnpack[T](
          context: WeaverContext,
          operation: => T,
          valueMapper: T => String
      ): Unit =
        try
          val value = operation
          context.setString(valueMapper(value))
        catch
          case e: Exception =>
            context.setError(e)

      override def unpack(u: Unpacker, context: WeaverContext): Unit =
        u.getNextValueType match
          case ValueType.STRING =>
            withSafeUnpack(context, u.unpackString, identity)
          case ValueType.INTEGER =>
            withSafeUnpack(context, u.unpackLong, _.toString)
          case ValueType.FLOAT =>
            withSafeUnpack(context, u.unpackDouble, _.toString)
          case ValueType.BOOLEAN =>
            withSafeUnpack(context, u.unpackBoolean, _.toString)
          case ValueType.NIL =>
            safeUnpackNil(context, u)
          case other =>
            u.skipValue
            context.setError(new IllegalArgumentException(s"Cannot convert ${other} to String"))

  given longWeaver: Weaver[Long] =
    new Weaver[Long]:
      override def pack(p: Packer, v: Long, config: WeaverConfig): Unit = p.packLong(v)

      override def unpack(u: Unpacker, context: WeaverContext): Unit =
        u.getNextValueType match
          case ValueType.INTEGER =>
            safeUnpack(context, u.unpackLong, context.setLong)
          case ValueType.FLOAT =>
            safeUnpack(
              context, {
                val d = u.unpackDouble
                if d.isWhole && d >= Long.MinValue && d <= Long.MaxValue then
                  d.toLong
                else
                  throw new IllegalArgumentException(s"Cannot convert double ${d} to Long")
              },
              context.setLong
            )
          case ValueType.STRING =>
            safeConvertFromString(context, u, _.toLong, context.setLong, "Long")
          case ValueType.BOOLEAN =>
            safeUnpack(
              context,
              if u.unpackBoolean then
                1L
              else
                0L
              ,
              context.setLong
            )
          case ValueType.NIL =>
            safeUnpackNil(context, u)
          case other =>
            u.skipValue
            context.setError(new IllegalArgumentException(s"Cannot convert ${other} to Long"))

  given doubleWeaver: Weaver[Double] =
    new Weaver[Double]:
      override def pack(p: Packer, v: Double, config: WeaverConfig): Unit = p.packDouble(v)

      override def unpack(u: Unpacker, context: WeaverContext): Unit =
        u.getNextValueType match
          case ValueType.FLOAT =>
            safeUnpack(context, u.unpackDouble, context.setDouble)
          case ValueType.INTEGER =>
            safeUnpack(context, u.unpackLong.toDouble, context.setDouble)
          case ValueType.STRING =>
            safeConvertFromString(context, u, _.toDouble, context.setDouble, "Double")
          case ValueType.BOOLEAN =>
            safeUnpack(
              context,
              if u.unpackBoolean then
                1.0
              else
                0.0
              ,
              context.setDouble
            )
          case ValueType.NIL =>
            safeUnpackNil(context, u)
          case other =>
            u.skipValue
            context.setError(new IllegalArgumentException(s"Cannot convert ${other} to Double"))

  given floatWeaver: Weaver[Float] =
    new Weaver[Float]:
      override def pack(p: Packer, v: Float, config: WeaverConfig): Unit = p.packFloat(v)

      override def unpack(u: Unpacker, context: WeaverContext): Unit =
        u.getNextValueType match
          case ValueType.FLOAT =>
            safeUnpack(
              context, {
                val d = u.unpackDouble
                if d >= Float.MinValue && d <= Float.MaxValue then
                  d.toFloat
                else
                  throw new IllegalArgumentException(s"Double ${d} out of Float range")
              },
              context.setFloat
            )
          case ValueType.INTEGER =>
            safeUnpack(context, u.unpackLong.toFloat, context.setFloat)
          case ValueType.STRING =>
            safeConvertFromString(context, u, _.toFloat, context.setFloat, "Float")
          case ValueType.BOOLEAN =>
            safeUnpack(
              context,
              if u.unpackBoolean then
                1.0f
              else
                0.0f
              ,
              context.setFloat
            )
          case ValueType.NIL =>
            safeUnpackNil(context, u)
          case other =>
            u.skipValue
            context.setError(new IllegalArgumentException(s"Cannot convert ${other} to Float"))

  given booleanWeaver: Weaver[Boolean] =
    new Weaver[Boolean]:
      override def pack(p: Packer, v: Boolean, config: WeaverConfig): Unit = p.packBoolean(v)

      override def unpack(u: Unpacker, context: WeaverContext): Unit =
        u.getNextValueType match
          case ValueType.BOOLEAN =>
            safeUnpack(context, u.unpackBoolean, context.setBoolean)
          case ValueType.INTEGER =>
            safeUnpack(context, u.unpackLong != 0, context.setBoolean)
          case ValueType.FLOAT =>
            safeUnpack(context, u.unpackDouble != 0.0, context.setBoolean)
          case ValueType.STRING =>
            try
              val s = u.unpackString
              s.toLowerCase match
                case "true" | "1" | "yes" | "on" =>
                  context.setBoolean(true)
                case "false" | "0" | "no" | "off" | "" =>
                  context.setBoolean(false)
                case _ =>
                  context.setError(
                    new IllegalArgumentException(s"Cannot convert string '${s}' to Boolean")
                  )
            catch
              case e: Exception =>
                context.setError(e)
          case ValueType.NIL =>
            safeUnpackNil(context, u)
          case other =>
            u.skipValue
            context.setError(new IllegalArgumentException(s"Cannot convert ${other} to Boolean"))

  given byteWeaver: Weaver[Byte] =
    new Weaver[Byte]:
      override def pack(p: Packer, v: Byte, config: WeaverConfig): Unit = p.packByte(v)

      override def unpack(u: Unpacker, context: WeaverContext): Unit =
        u.getNextValueType match
          case ValueType.INTEGER =>
            safeUnpack(
              context, {
                val l = u.unpackLong
                if l >= Byte.MinValue && l <= Byte.MaxValue then
                  l.toByte
                else
                  throw new IllegalArgumentException(s"Long ${l} out of Byte range")
              },
              context.setByte
            )
          case ValueType.FLOAT =>
            safeUnpack(
              context, {
                val d = u.unpackDouble
                if d.isWhole && d >= Byte.MinValue && d <= Byte.MaxValue then
                  d.toByte
                else
                  throw new IllegalArgumentException(s"Cannot convert double ${d} to Byte")
              },
              context.setByte
            )
          case ValueType.STRING =>
            safeConvertFromString(context, u, _.toByte, context.setByte, "Byte")
          case ValueType.BOOLEAN =>
            safeUnpack(
              context,
              if u.unpackBoolean then
                1.toByte
              else
                0.toByte
              ,
              context.setByte
            )
          case ValueType.NIL =>
            safeUnpackNil(context, u)
          case other =>
            u.skipValue
            context.setError(new IllegalArgumentException(s"Cannot convert ${other} to Byte"))

  given shortWeaver: Weaver[Short] =
    new Weaver[Short]:
      override def pack(p: Packer, v: Short, config: WeaverConfig): Unit = p.packShort(v)

      override def unpack(u: Unpacker, context: WeaverContext): Unit =
        u.getNextValueType match
          case ValueType.INTEGER =>
            safeUnpack(
              context, {
                val l = u.unpackLong
                if l >= Short.MinValue && l <= Short.MaxValue then
                  l.toShort
                else
                  throw new IllegalArgumentException(s"Long ${l} out of Short range")
              },
              context.setShort
            )
          case ValueType.FLOAT =>
            safeUnpack(
              context, {
                val d = u.unpackDouble
                if d.isWhole && d >= Short.MinValue && d <= Short.MaxValue then
                  d.toShort
                else
                  throw new IllegalArgumentException(s"Cannot convert double ${d} to Short")
              },
              context.setShort
            )
          case ValueType.STRING =>
            safeConvertFromString(context, u, _.toShort, context.setShort, "Short")
          case ValueType.BOOLEAN =>
            safeUnpack(
              context,
              if u.unpackBoolean then
                1.toShort
              else
                0.toShort
              ,
              context.setShort
            )
          case ValueType.NIL =>
            safeUnpackNil(context, u)
          case other =>
            u.skipValue
            context.setError(new IllegalArgumentException(s"Cannot convert ${other} to Short"))

  given charWeaver: Weaver[Char] =
    new Weaver[Char]:
      override def pack(p: Packer, v: Char, config: WeaverConfig): Unit = p.packString(v.toString)

      override def unpack(u: Unpacker, context: WeaverContext): Unit =
        u.getNextValueType match
          case ValueType.STRING =>
            safeUnpack(
              context, {
                val s = u.unpackString
                if s.length == 1 then
                  s.charAt(0)
                else
                  throw new IllegalArgumentException(
                    s"Cannot convert string '${s}' to Char - must be single character"
                  )
              },
              context.setChar
            )
          case ValueType.INTEGER =>
            safeUnpack(
              context, {
                val l = u.unpackLong
                if l >= Char.MinValue && l <= Char.MaxValue then
                  l.toChar
                else
                  throw new IllegalArgumentException(s"Long ${l} out of Char range")
              },
              context.setChar
            )
          case ValueType.NIL =>
            safeUnpackNil(context, u)
          case other =>
            u.skipValue
            context.setError(new IllegalArgumentException(s"Cannot convert ${other} to Char"))

  given listWeaver[A](using elementWeaver: Weaver[A]): Weaver[List[A]] = iterableCollectionWeaver(
    elementWeaver,
    "List",
    _.toList
  )

  given mapWeaver[K, V](using keyWeaver: Weaver[K], valueWeaver: Weaver[V]): Weaver[Map[K, V]] =
    new Weaver[Map[K, V]]:
      override def pack(p: Packer, v: Map[K, V], config: WeaverConfig): Unit =
        p.packMapHeader(v.size)
        v.foreach { case (key, value) =>
          keyWeaver.pack(p, key, config)
          valueWeaver.pack(p, value, config)
        }

      override def unpack(u: Unpacker, context: WeaverContext): Unit =
        u.getNextValueType match
          case ValueType.MAP =>
            unpackMapToBuffer(u, context, keyWeaver, valueWeaver) match
              case Some(buffer) =>
                context.setObject(buffer.toMap)
              case None => // Error already set in unpackMapToBuffer
          case ValueType.NIL =>
            safeUnpackNil(context, u)
          case other =>
            u.skipValue
            context.setError(new IllegalArgumentException(s"Cannot convert ${other} to Map"))

  given seqWeaver[A](using elementWeaver: Weaver[A]): Weaver[Seq[A]] = iterableCollectionWeaver(
    elementWeaver,
    "Seq",
    _.toSeq
  )

  given indexedSeqWeaver[A](using elementWeaver: Weaver[A]): Weaver[IndexedSeq[A]] =
    iterableCollectionWeaver(elementWeaver, "IndexedSeq", _.toIndexedSeq)

  @scala.annotation.targetName("givenJavaListWeaver")
  given javaListWeaver[A](using elementWeaver: Weaver[A]): Weaver[java.util.List[A]] =
    collectionWeaver(
      elementWeaver,
      "java.util.List",
      (p, v, config) =>
        p.packArrayHeader(v.size)
        v.asScala.foreach(elementWeaver.pack(p, _, config))
      ,
      _.asJava
    )

  @scala.annotation.targetName("givenJavaMapWeaver")
  given javaMapWeaver[K, V](using
      keyWeaver: Weaver[K],
      valueWeaver: Weaver[V]
  ): Weaver[java.util.Map[K, V]] =
    new Weaver[java.util.Map[K, V]]:
      override def pack(p: Packer, v: java.util.Map[K, V], config: WeaverConfig): Unit =
        p.packMapHeader(v.size)
        v.asScala
          .foreach { case (key, value) =>
            keyWeaver.pack(p, key, config)
            valueWeaver.pack(p, value, config)
          }

      override def unpack(u: Unpacker, context: WeaverContext): Unit =
        u.getNextValueType match
          case ValueType.MAP =>
            unpackMapToBuffer(u, context, keyWeaver, valueWeaver) match
              case Some(buffer) =>
                context.setObject(buffer.toMap.asJava)
              case None =>
          case ValueType.NIL =>
            safeUnpackNil(context, u)
          case other =>
            u.skipValue
            context.setError(
              new IllegalArgumentException(s"Cannot convert ${other} to java.util.Map")
            )

  @scala.annotation.targetName("givenJavaSetWeaver")
  given javaSetWeaver[A](using elementWeaver: Weaver[A]): Weaver[java.util.Set[A]] =
    collectionWeaver(
      elementWeaver,
      "java.util.Set",
      (p, v, config) =>
        p.packArrayHeader(v.size)
        v.asScala.foreach(elementWeaver.pack(p, _, config))
      ,
      buf => buf.toSet.asJava
    )

  given listMapWeaver[K, V](using
      keyWeaver: Weaver[K],
      valueWeaver: Weaver[V]
  ): Weaver[scala.collection.immutable.ListMap[K, V]] =
    new Weaver[scala.collection.immutable.ListMap[K, V]]:
      override def pack(
          p: Packer,
          v: scala.collection.immutable.ListMap[K, V],
          config: WeaverConfig
      ): Unit =
        p.packMapHeader(v.size)
        v.foreach { case (key, value) =>
          keyWeaver.pack(p, key, config)
          valueWeaver.pack(p, value, config)
        }

      override def unpack(u: Unpacker, context: WeaverContext): Unit =
        u.getNextValueType match
          case ValueType.MAP =>
            unpackMapToBuffer(u, context, keyWeaver, valueWeaver) match
              case Some(buffer) =>
                context.setObject(scala.collection.immutable.ListMap.from(buffer))
              case None => // Error already set in unpackMapToBuffer
          case ValueType.NIL =>
            safeUnpackNil(context, u)
          case other =>
            u.skipValue
            context.setError(new IllegalArgumentException(s"Cannot convert ${other} to ListMap"))

  given optionWeaver[A](using elementWeaver: Weaver[A]): Weaver[Option[A]] =
    new Weaver[Option[A]]:
      override def pack(p: Packer, v: Option[A], config: WeaverConfig): Unit =
        v match
          case Some(value) =>
            elementWeaver.pack(p, value, config)
          case None =>
            p.packNil

      override def unpack(u: Unpacker, context: WeaverContext): Unit =
        u.getNextValueType match
          case ValueType.NIL =>
            u.unpackNil
            context.setObject(None)
          case _ =>
            val elementContext = WeaverContext(context.config)
            elementWeaver.unpack(u, elementContext)
            if elementContext.hasError then
              context.setError(elementContext.getError.get)
            else if elementContext.isNull then
              context.setObject(None)
            else
              context.setObject(Some(elementContext.getLastValue))

  given setWeaver[A](using elementWeaver: Weaver[A]): Weaver[Set[A]] = iterableCollectionWeaver(
    elementWeaver,
    "Set",
    _.toSet
  )

  given bigIntWeaver: Weaver[BigInt] =
    new Weaver[BigInt]:
      override def pack(p: Packer, v: BigInt, config: WeaverConfig): Unit =
        if v.isValidLong then
          p.packLong(v.longValue)
        else
          p.packString(v.toString(10))

      override def unpack(u: Unpacker, context: WeaverContext): Unit =
        u.getNextValueType match
          case ValueType.INTEGER =>
            safeUnpack(context, BigInt(u.unpackLong), context.setObject)
          case ValueType.STRING =>
            safeConvertFromString(context, u, BigInt(_), context.setObject, "BigInt")
          case ValueType.NIL =>
            safeUnpackNil(context, u)
          case other =>
            u.skipValue
            context.setError(new IllegalArgumentException(s"Cannot convert ${other} to BigInt"))

  given bigDecimalWeaver: Weaver[BigDecimal] =
    new Weaver[BigDecimal]:
      override def pack(p: Packer, v: BigDecimal, config: WeaverConfig): Unit = p.packString(
        v.toString
      )

      override def unpack(u: Unpacker, context: WeaverContext): Unit =
        u.getNextValueType match
          case ValueType.STRING =>
            safeConvertFromString(context, u, BigDecimal(_), context.setObject, "BigDecimal")
          case ValueType.FLOAT =>
            safeUnpack(context, BigDecimal(u.unpackDouble), context.setObject)
          case ValueType.INTEGER =>
            safeUnpack(context, BigDecimal(u.unpackLong), context.setObject)
          case ValueType.NIL =>
            safeUnpackNil(context, u)
          case other =>
            u.skipValue
            context.setError(new IllegalArgumentException(s"Cannot convert ${other} to BigDecimal"))

  given eitherWeaver[A, B](using
      leftWeaver: Weaver[A],
      rightWeaver: Weaver[B]
  ): Weaver[Either[A, B]] =
    new Weaver[Either[A, B]]:
      override def pack(p: Packer, v: Either[A, B], config: WeaverConfig): Unit =
        p.packArrayHeader(2)
        v match
          case Left(l) =>
            leftWeaver.pack(p, l, config)
            p.packNil
          case Right(r) =>
            p.packNil
            rightWeaver.pack(p, r, config)

      override def unpack(u: Unpacker, context: WeaverContext): Unit =
        u.getNextValueType match
          case ValueType.ARRAY =>
            try
              val arraySize = u.unpackArrayHeader
              if arraySize != 2 then
                context.setError(
                  IllegalArgumentException(
                    s"Either requires ARRAY of size 2, got size ${arraySize}"
                  )
                )
                var i = 0
                while i < arraySize do
                  u.skipValue
                  i += 1
              else
                // Check first element
                if u.getNextValueType == ValueType.NIL then
                  // First is nil -> Right
                  u.unpackNil
                  val rightContext = WeaverContext(context.config)
                  rightWeaver.unpack(u, rightContext)
                  if rightContext.hasError then
                    context.setError(rightContext.getError.get)
                  else
                    context.setObject(Right(rightContext.getLastValue))
                else
                  // First is non-nil -> Left
                  val leftContext = WeaverContext(context.config)
                  leftWeaver.unpack(u, leftContext)
                  if leftContext.hasError then
                    context.setError(leftContext.getError.get)
                    u.skipValue // skip second element
                  else
                    u.unpackNil // skip the nil second element
                    context.setObject(Left(leftContext.getLastValue))
              end if
            catch
              case e: Exception =>
                context.setError(e)
          case ValueType.NIL =>
            safeUnpackNil(context, u)
          case other =>
            u.skipValue
            context.setError(new IllegalArgumentException(s"Cannot convert ${other} to Either"))

  given instantWeaver: Weaver[Instant] =
    new Weaver[Instant]:
      override def pack(p: Packer, v: Instant, config: WeaverConfig): Unit = p.packTimestamp(v)

      override def unpack(u: Unpacker, context: WeaverContext): Unit =
        u.getNextValueType match
          case ValueType.EXTENSION =>
            safeUnpack(context, u.unpackTimestamp, context.setObject)
          case ValueType.INTEGER =>
            safeUnpack(context, Instant.ofEpochMilli(u.unpackLong), context.setObject)
          case ValueType.STRING =>
            safeConvertFromString(context, u, Instant.parse(_), context.setObject, "Instant")
          case ValueType.NIL =>
            safeUnpackNil(context, u)
          case other =>
            u.skipValue
            context.setError(new IllegalArgumentException(s"Cannot convert ${other} to Instant"))

  given uuidWeaver: Weaver[UUID] =
    new Weaver[UUID]:
      override def pack(p: Packer, v: UUID, config: WeaverConfig): Unit = p.packString(v.toString)

      override def unpack(u: Unpacker, context: WeaverContext): Unit =
        u.getNextValueType match
          case ValueType.STRING =>
            safeConvertFromString(context, u, UUID.fromString(_), context.setObject, "UUID")
          case ValueType.NIL =>
            safeUnpackNil(context, u)
          case other =>
            u.skipValue
            context.setError(new IllegalArgumentException(s"Cannot convert ${other} to UUID"))

  given arrayWeaver[A](using elementWeaver: Weaver[A], ct: ClassTag[A]): Weaver[Array[A]] =
    collectionWeaver(
      elementWeaver,
      "Array",
      (p, v, config) =>
        p.packArrayHeader(v.length)
        v.foreach(elementWeaver.pack(p, _, config))
      ,
      _.toArray
    )

  given vectorWeaver[A](using elementWeaver: Weaver[A]): Weaver[Vector[A]] =
    iterableCollectionWeaver(elementWeaver, "Vector", _.toVector)

  given scalaDurationWeaver: Weaver[ScalaDuration] =
    new Weaver[ScalaDuration]:
      override def pack(p: Packer, v: ScalaDuration, config: WeaverConfig): Unit = p.packString(
        v.toString
      )

      override def unpack(u: Unpacker, context: WeaverContext): Unit =
        u.getNextValueType match
          case ValueType.STRING =>
            safeConvertFromString(
              context,
              u,
              ScalaDuration(_),
              context.setObject,
              "scala.concurrent.duration.Duration"
            )
          case ValueType.NIL =>
            safeUnpackNil(context, u)
          case other =>
            u.skipValue
            context.setError(
              new IllegalArgumentException(
                s"Cannot convert ${other} to scala.concurrent.duration.Duration"
              )
            )

  given uriWeaver: Weaver[URI] =
    new Weaver[URI]:
      override def pack(p: Packer, v: URI, config: WeaverConfig): Unit = p.packString(v.toString)

      override def unpack(u: Unpacker, context: WeaverContext): Unit =
        u.getNextValueType match
          case ValueType.STRING =>
            safeConvertFromString(context, u, URI(_), context.setObject, "URI")
          case ValueType.NIL =>
            safeUnpackNil(context, u)
          case other =>
            u.skipValue
            context.setError(new IllegalArgumentException(s"Cannot convert ${other} to URI"))

  // Tuple support via recursive given resolution
  trait TupleElementWeaver[T <: Tuple]:
    def size: Int
    def packElements(p: Packer, v: T, config: WeaverConfig): Unit
    def unpackElements(u: Unpacker, context: WeaverContext): Option[T]

  given emptyTupleElementWeaver: TupleElementWeaver[EmptyTuple] =
    new TupleElementWeaver[EmptyTuple]:
      def size: Int                                                               = 0
      def packElements(p: Packer, v: EmptyTuple, config: WeaverConfig): Unit      = ()
      def unpackElements(u: Unpacker, context: WeaverContext): Option[EmptyTuple] = Some(EmptyTuple)

  given nonEmptyTupleElementWeaver[H, T <: Tuple](using
      headWeaver: Weaver[H],
      tailElementWeaver: TupleElementWeaver[T]
  ): TupleElementWeaver[H *: T] =
    new TupleElementWeaver[H *: T]:
      def size: Int = 1 + tailElementWeaver.size
      def packElements(p: Packer, v: H *: T, config: WeaverConfig): Unit =
        headWeaver.pack(p, v.head, config)
        tailElementWeaver.packElements(p, v.tail, config)
      def unpackElements(u: Unpacker, context: WeaverContext): Option[H *: T] =
        val headContext = WeaverContext(context.config)
        headWeaver.unpack(u, headContext)
        if headContext.hasError then
          context.setError(headContext.getError.get)
          None
        else
          val head = headContext.getLastValue.asInstanceOf[H]
          tailElementWeaver.unpackElements(u, context) match
            case Some(tail) =>
              Some(head *: tail)
            case None =>
              None

  given emptyTupleWeaver: Weaver[EmptyTuple] =
    new Weaver[EmptyTuple]:
      override def pack(p: Packer, v: EmptyTuple, config: WeaverConfig): Unit = p.packArrayHeader(0)
      override def unpack(u: Unpacker, context: WeaverContext): Unit          =
        u.getNextValueType match
          case ValueType.ARRAY =>
            val arraySize = u.unpackArrayHeader
            var i         = 0
            while i < arraySize do
              u.skipValue
              i += 1
            context.setObject(EmptyTuple)
          case ValueType.NIL =>
            safeUnpackNil(context, u)
          case other =>
            u.skipValue
            context.setError(new IllegalArgumentException(s"Cannot convert ${other} to EmptyTuple"))

  given tupleWeaver[H, T <: Tuple](using
      tupleElemWeaver: TupleElementWeaver[H *: T]
  ): Weaver[H *: T] =
    new Weaver[H *: T]:
      override def pack(p: Packer, v: H *: T, config: WeaverConfig): Unit =
        p.packArrayHeader(tupleElemWeaver.size)
        tupleElemWeaver.packElements(p, v, config)
      override def unpack(u: Unpacker, context: WeaverContext): Unit =
        u.getNextValueType match
          case ValueType.ARRAY =>
            val arraySize = u.unpackArrayHeader
            if arraySize != tupleElemWeaver.size then
              context.setError(
                IllegalArgumentException(
                  s"Tuple requires ARRAY of size ${tupleElemWeaver.size}, got size ${arraySize}"
                )
              )
              var i = 0
              while i < arraySize do
                u.skipValue
                i += 1
            else
              tupleElemWeaver.unpackElements(u, context) match
                case Some(tuple) =>
                  context.setObject(tuple)
                case None =>
          case ValueType.NIL =>
            safeUnpackNil(context, u)
          case other =>
            u.skipValue
            context.setError(new IllegalArgumentException(s"Cannot convert ${other} to Tuple"))

  given anyWeaver: Weaver[Any] = AnyWeaver.default

end PrimitiveWeaver
