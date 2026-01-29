package wvlet.uni.weaver

import wvlet.uni.json.JSON.JSONValue
import wvlet.uni.msgpack.spi.MessagePack
import wvlet.uni.msgpack.spi.MsgPack
import wvlet.uni.msgpack.spi.Packer
import wvlet.uni.msgpack.spi.Unpacker
import wvlet.uni.msgpack.spi.ValueType
import wvlet.uni.surface.*
import wvlet.uni.weaver.codec.CaseClassWeaver
import wvlet.uni.weaver.codec.EnumWeaver
import wvlet.uni.weaver.codec.JSONWeaver
import wvlet.uni.weaver.codec.PrimitiveWeaver

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import scala.collection.mutable.ListBuffer
import scala.jdk.CollectionConverters.*

trait Weaver[A]:
  def weave(v: A, config: WeaverConfig = WeaverConfig()): MsgPack         = toMsgPack(v, config)
  def unweave(msgpack: MsgPack, config: WeaverConfig = WeaverConfig()): A =
    val unpacker = MessagePack.newUnpacker(msgpack)
    val context  = WeaverContext(config)
    unpack(unpacker, context)
    if context.hasError then
      throw context.getError.get
    else
      context.getLastValue.asInstanceOf[A]

  def fromJson(json: String, config: WeaverConfig = WeaverConfig()): A =
    val msgpack = JSONWeaver.weave(json, config)
    unweave(msgpack, config)

  /**
    * Decode directly from a JSONValue without going through a string roundtrip. This is more
    * efficient when you already have a parsed JSONValue.
    */
  def fromJSONValue(v: JSONValue, config: WeaverConfig = WeaverConfig()): A =
    val packer = MessagePack.newPacker()
    JSONWeaver.packJsonValue(packer, v, config)
    unweave(packer.toByteArray, config)

  def toJson(v: A, config: WeaverConfig = WeaverConfig()): String =
    val msgpack = toMsgPack(v, config)
    JSONWeaver.unweave(msgpack, config)

  def toMsgPack(v: A, config: WeaverConfig = WeaverConfig()): MsgPack =
    val packer = MessagePack.newPacker()
    pack(packer, v, config)
    packer.toByteArray

  /**
    * Pack the value v as MessagePack value using the given packer.
    * @param p
    * @param v
    */
  def pack(p: Packer, v: A, config: WeaverConfig): Unit

  /**
    * @param u
    * @return
    */
  def unpack(u: Unpacker, context: WeaverContext): Unit

end Weaver

object Weaver:

  /**
    * Derive a Weaver for a case class or sealed trait at compile-time.
    * {{{
    * case class Person(name: String, age: Int)
    * given Weaver[Person] = Weaver.of[Person]
    *
    * // Or with derives clause:
    * case class Person(name: String, age: Int) derives Weaver
    * }}}
    */
  inline def of[A]: Weaver[A] = WeaverDerivation.deriveWeaver[A]

  // For `derives Weaver` clause
  inline def derived[A]: Weaver[A] = of[A]

  def weave[A](v: A, config: WeaverConfig = WeaverConfig())(using weaver: Weaver[A]): MsgPack =
    weaver.weave(v, config)

  def unweave[A](msgpack: MsgPack, config: WeaverConfig = WeaverConfig())(using
      weaver: Weaver[A]
  ): A = weaver.unweave(msgpack, config)

  def toJson[A](v: A, config: WeaverConfig = WeaverConfig())(using weaver: Weaver[A]): String =
    weaver.toJson(v, config)

  def fromJson[A](json: String, config: WeaverConfig = WeaverConfig())(using weaver: Weaver[A]): A =
    weaver.fromJson(json, config)

  // Export primitive weavers for compile-time resolution
  // Java collection weavers use @targetName to avoid type erasure conflicts
  export PrimitiveWeaver.{
    intWeaver,
    stringWeaver,
    longWeaver,
    doubleWeaver,
    floatWeaver,
    booleanWeaver,
    byteWeaver,
    shortWeaver,
    charWeaver,
    bigIntWeaver,
    bigDecimalWeaver,
    instantWeaver,
    uuidWeaver,
    uriWeaver,
    scalaDurationWeaver,
    optionWeaver,
    listWeaver,
    seqWeaver,
    indexedSeqWeaver,
    vectorWeaver,
    setWeaver,
    mapWeaver,
    listMapWeaver,
    arrayWeaver,
    eitherWeaver,
    emptyTupleWeaver,
    tupleWeaver,
    anyWeaver,
    javaListWeaver,
    javaMapWeaver,
    javaSetWeaver
  }
  export PrimitiveWeaver.TupleElementWeaver
  export PrimitiveWeaver.{emptyTupleElementWeaver, nonEmptyTupleElementWeaver}

  // Cache for weavers created from Surface
  private val surfaceWeaverCache = ConcurrentHashMap[String, Weaver[?]]().asScala

  /**
    * Create a Weaver from Surface at runtime. Uses Surface type information to look up or build
    * appropriate Weaver by composing existing weavers.
    *
    * This is used by RPC framework to derive weavers for method parameters and return types without
    * requiring compile-time type information.
    */
  def fromSurface(surface: Surface): Weaver[?] = surfaceWeaverCache.getOrElseUpdate(
    surface.fullName,
    buildWeaver(surface)
  )

  /**
    * Extensible weaver factories for runtime weaver construction. Each factory is a partial
    * function that maps a Surface to a Weaver. Factories are tried in order until one matches.
    *
    * This can be extended by platform-specific code to add support for additional types.
    */
  type WeaverFactory = PartialFunction[Surface, Weaver[?]]

  private val primitiveFactory: WeaverFactory =
    import PrimitiveWeaver.given
    {
      case s if s.rawType == classOf[Unit] || s.fullName == "Unit" =>
        unitWeaver
      case s if s.rawType == classOf[Int] =>
        intWeaver
      case s if s.rawType == classOf[Long] =>
        longWeaver
      case s if s.rawType == classOf[String] =>
        stringWeaver
      case s if s.rawType == classOf[Boolean] =>
        booleanWeaver
      case s if s.rawType == classOf[Double] =>
        doubleWeaver
      case s if s.rawType == classOf[Float] =>
        floatWeaver
      case s if s.rawType == classOf[Byte] =>
        byteWeaver
      case s if s.rawType == classOf[Short] =>
        shortWeaver
      case s if s.rawType == classOf[Char] =>
        charWeaver
      case s if s.rawType == classOf[BigInt] =>
        bigIntWeaver
      case s if s.rawType == classOf[BigDecimal] =>
        bigDecimalWeaver
      case s if s.rawType == classOf[UUID] =>
        uuidWeaver
      case s if s.rawType == classOf[Instant] =>
        instantWeaver
    }

  end primitiveFactory

  private val collectionFactory: WeaverFactory = {
    // Option[A]
    case s: OptionSurface =>
      buildOptionWeaver(fromSurface(s.elementSurface))

    // Seq/List/Vector/IndexedSeq
    case s if s.isSeq && s.typeArgs.nonEmpty =>
      buildSeqWeaver(fromSurface(s.typeArgs.head), s.rawType)

    // Set (Scala)
    case s if classOf[Set[?]].isAssignableFrom(s.rawType) && s.typeArgs.nonEmpty =>
      buildSetWeaver(fromSurface(s.typeArgs.head))

    // Map (Scala)
    case s if s.isMap && s.typeArgs.size >= 2 =>
      buildMapWeaver(fromSurface(s.typeArgs(0)), fromSurface(s.typeArgs(1)))

    // Array
    case s: ArraySurface =>
      buildArrayWeaver(fromSurface(s.elementSurface), s.elementSurface.rawType)
  }

  private val javaCollectionFactory: WeaverFactory = {
    // java.util.List
    case s if classOf[java.util.List[?]].isAssignableFrom(s.rawType) && s.typeArgs.nonEmpty =>
      buildJavaListWeaver(fromSurface(s.typeArgs.head))

    // java.util.Set
    case s if classOf[java.util.Set[?]].isAssignableFrom(s.rawType) && s.typeArgs.nonEmpty =>
      buildJavaSetWeaver(fromSurface(s.typeArgs.head))

    // java.util.Map
    case s if classOf[java.util.Map[?, ?]].isAssignableFrom(s.rawType) && s.typeArgs.size >= 2 =>
      buildJavaMapWeaver(fromSurface(s.typeArgs(0)), fromSurface(s.typeArgs(1)))
  }

  private val complexTypeFactory: WeaverFactory = {
    // Enum
    case s: EnumSurface =>
      EnumWeaver(s)

    // Case class (has objectFactory)
    case s if s.objectFactory.isDefined =>
      val fieldWeavers = s.params.map(p => fromSurface(p.surface)).toIndexedSeq
      CaseClassWeaver(s, fieldWeavers)
  }

  private val defaultFactories: Seq[WeaverFactory] = Seq(
    primitiveFactory,
    collectionFactory,
    javaCollectionFactory,
    complexTypeFactory
  )

  private def buildWeaver(surface: Surface): Weaver[?] = defaultFactories
    .collectFirst {
      case f if f.isDefinedAt(surface) =>
        f(surface)
    }
    .getOrElse(
      throw IllegalArgumentException(s"Cannot create Weaver for type: ${surface.fullName}")
    )

  // Weaver factory methods for composing weavers at runtime

  private val unitWeaver: Weaver[Unit] =
    new Weaver[Unit]:
      override def pack(p: Packer, v: Unit, config: WeaverConfig): Unit = p.packNil
      override def unpack(u: Unpacker, context: WeaverContext): Unit    =
        u.getNextValueType match
          case ValueType.NIL =>
            u.unpackNil
            context.setObject(())
          case _ =>
            u.skipValue
            context.setObject(())

  private def buildOptionWeaver(inner: Weaver[?]): Weaver[Option[?]] =
    new Weaver[Option[?]]:
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

  private def buildSeqWeaver(elem: Weaver[?], targetType: Class[?]): Weaver[Seq[?]] =
    new Weaver[Seq[?]]:
      override def pack(p: Packer, v: Seq[?], config: WeaverConfig): Unit =
        p.packArrayHeader(v.size)
        v.foreach(e => elem.asInstanceOf[Weaver[Any]].pack(p, e, config))

      override def unpack(u: Unpacker, context: WeaverContext): Unit =
        u.getNextValueType match
          case ValueType.ARRAY =>
            unpackArrayToBuffer(u, context, elem) match
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

  private def buildSetWeaver(elem: Weaver[?]): Weaver[Set[?]] =
    new Weaver[Set[?]]:
      override def pack(p: Packer, v: Set[?], config: WeaverConfig): Unit =
        p.packArrayHeader(v.size)
        v.foreach(e => elem.asInstanceOf[Weaver[Any]].pack(p, e, config))

      override def unpack(u: Unpacker, context: WeaverContext): Unit =
        u.getNextValueType match
          case ValueType.ARRAY =>
            unpackArrayToBuffer(u, context, elem) match
              case Some(buffer) =>
                context.setObject(buffer.toSet)
              case None => // Error already set
          case ValueType.NIL =>
            u.unpackNil
            context.setNull
          case other =>
            u.skipValue
            context.setError(IllegalArgumentException(s"Cannot convert ${other} to Set"))

  private def buildMapWeaver(keyWeaver: Weaver[?], valueWeaver: Weaver[?]): Weaver[Map[?, ?]] =
    new Weaver[Map[?, ?]]:
      override def pack(p: Packer, v: Map[?, ?], config: WeaverConfig): Unit =
        p.packMapHeader(v.size)
        v.foreach { case (key, value) =>
          keyWeaver.asInstanceOf[Weaver[Any]].pack(p, key, config)
          valueWeaver.asInstanceOf[Weaver[Any]].pack(p, value, config)
        }

      override def unpack(u: Unpacker, context: WeaverContext): Unit =
        u.getNextValueType match
          case ValueType.MAP =>
            unpackMapToBuffer(u, context, keyWeaver, valueWeaver) match
              case Some(buffer) =>
                context.setObject(buffer.toMap)
              case None => // Error already set
          case ValueType.NIL =>
            u.unpackNil
            context.setNull
          case other =>
            u.skipValue
            context.setError(IllegalArgumentException(s"Cannot convert ${other} to Map"))

  private def buildArrayWeaver(elem: Weaver[?], elemClass: Class[?]): Weaver[Array[?]] =
    new Weaver[Array[?]]:
      override def pack(p: Packer, v: Array[?], config: WeaverConfig): Unit =
        p.packArrayHeader(v.length)
        v.foreach(e => elem.asInstanceOf[Weaver[Any]].pack(p, e, config))

      override def unpack(u: Unpacker, context: WeaverContext): Unit =
        u.getNextValueType match
          case ValueType.ARRAY =>
            unpackArrayToBuffer(u, context, elem) match
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

  private def buildJavaListWeaver(elem: Weaver[?]): Weaver[java.util.List[?]] =
    new Weaver[java.util.List[?]]:
      override def pack(p: Packer, v: java.util.List[?], config: WeaverConfig): Unit =
        p.packArrayHeader(v.size)
        v.forEach(e => elem.asInstanceOf[Weaver[Any]].pack(p, e, config))

      override def unpack(u: Unpacker, context: WeaverContext): Unit =
        u.getNextValueType match
          case ValueType.ARRAY =>
            unpackArrayToBuffer(u, context, elem) match
              case Some(buffer) =>
                val list = new java.util.ArrayList[Any](buffer.size)
                buffer.foreach(e => list.add(e))
                context.setObject(list)
              case None => // Error already set
          case ValueType.NIL =>
            u.unpackNil
            context.setNull
          case other =>
            u.skipValue
            context.setError(IllegalArgumentException(s"Cannot convert ${other} to java.util.List"))

  private def buildJavaSetWeaver(elem: Weaver[?]): Weaver[java.util.Set[?]] =
    new Weaver[java.util.Set[?]]:
      override def pack(p: Packer, v: java.util.Set[?], config: WeaverConfig): Unit =
        p.packArrayHeader(v.size)
        v.forEach(e => elem.asInstanceOf[Weaver[Any]].pack(p, e, config))

      override def unpack(u: Unpacker, context: WeaverContext): Unit =
        u.getNextValueType match
          case ValueType.ARRAY =>
            unpackArrayToBuffer(u, context, elem) match
              case Some(buffer) =>
                val set = new java.util.HashSet[Any](buffer.size)
                buffer.foreach(e => set.add(e))
                context.setObject(set)
              case None => // Error already set
          case ValueType.NIL =>
            u.unpackNil
            context.setNull
          case other =>
            u.skipValue
            context.setError(IllegalArgumentException(s"Cannot convert ${other} to java.util.Set"))

  private def buildJavaMapWeaver(
      keyWeaver: Weaver[?],
      valueWeaver: Weaver[?]
  ): Weaver[java.util.Map[?, ?]] =
    new Weaver[java.util.Map[?, ?]]:
      override def pack(p: Packer, v: java.util.Map[?, ?], config: WeaverConfig): Unit =
        p.packMapHeader(v.size)
        v.forEach { (key, value) =>
          keyWeaver.asInstanceOf[Weaver[Any]].pack(p, key, config)
          valueWeaver.asInstanceOf[Weaver[Any]].pack(p, value, config)
        }

      override def unpack(u: Unpacker, context: WeaverContext): Unit =
        u.getNextValueType match
          case ValueType.MAP =>
            unpackMapToBuffer(u, context, keyWeaver, valueWeaver) match
              case Some(buffer) =>
                val map = new java.util.HashMap[Any, Any](buffer.size)
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

  // Helper methods for unpacking collections

  private def unpackArrayToBuffer(
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

  private def unpackMapToBuffer(
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

end Weaver

// Backward compatibility aliases
@deprecated("Use Weaver instead", "2026.1.x")
type ObjectWeaver[A] = Weaver[A]

@deprecated("Use Weaver instead", "2026.1.x")
val ObjectWeaver = Weaver
