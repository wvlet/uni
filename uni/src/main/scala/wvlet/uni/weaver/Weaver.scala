package wvlet.uni.weaver

import wvlet.uni.json.JSON.JSONValue
import wvlet.uni.msgpack.spi.MessagePack
import wvlet.uni.msgpack.spi.MsgPack
import wvlet.uni.msgpack.spi.Packer
import wvlet.uni.msgpack.spi.Unpacker
import wvlet.uni.msgpack.spi.ValueType
import wvlet.uni.surface.*
import wvlet.uni.weaver.codec.AnyWeaver
import wvlet.uni.weaver.codec.CaseClassWeaver
import wvlet.uni.weaver.codec.EnumWeaver
import wvlet.uni.weaver.codec.JSONWeaver
import wvlet.uni.weaver.codec.PrimitiveWeaver

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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

  /**
    * Hydrate a value of type `A` from a `Map[String, Any]`. This is convenient when working with
    * loosely typed sources such as YAML/HOCON parsers that produce `Map[String, Any]`. Nested Maps,
    * Seqs/Arrays, primitives, and `Option` values are all packed via [[AnyWeaver]] before being
    * decoded by this Weaver.
    */
  def fromMap(map: Map[String, Any], config: WeaverConfig = WeaverConfig()): A =
    val packer = MessagePack.newPacker()
    AnyWeaver.default.pack(packer, map, config)
    unweave(packer.toByteArray, config)

  /**
    * Serialize a value of type `A` to a `Map[String, Any]`. Inverse of [[fromMap]]. Nested objects
    * become nested `Map[String, Any]` values; collections become `Seq[Any]`. Throws
    * [[IllegalArgumentException]] if the top-level value does not pack as a MsgPack map.
    */
  def toMap(v: A, config: WeaverConfig = WeaverConfig()): Map[String, Any] =
    val msgpack  = toMsgPack(v, config)
    val unpacker = MessagePack.newUnpacker(msgpack)
    val context  = WeaverContext(config)
    AnyWeaver.default.unpack(unpacker, context)
    if context.hasError then
      throw context.getError.get
    context.getLastValue match
      case m: Map[?, ?] =>
        m.map { (k, v) =>
            k.toString -> v
          }
          .toMap
      case other =>
        throw IllegalArgumentException(
          s"toMap expected a map representation, but got ${Option(other)
              .map(_.getClass.getName)
              .getOrElse("null")}"
        )

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

  def fromMap[A](map: Map[String, Any], config: WeaverConfig = WeaverConfig())(using
      weaver: Weaver[A]
  ): A = weaver.fromMap(map, config)

  def toMap[A](v: A, config: WeaverConfig = WeaverConfig())(using
      weaver: Weaver[A]
  ): Map[String, Any] = weaver.toMap(v, config)

  // Export primitive weavers for compile-time resolution
  // Java collection weavers are not exported to avoid type erasure conflicts.
  // Import them explicitly from PrimitiveWeaver if needed.
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
    anyWeaver
  }
  export PrimitiveWeaver.TupleElementWeaver
  export PrimitiveWeaver.{emptyTupleElementWeaver, nonEmptyTupleElementWeaver}

  // Cache for weavers created from Surface
  private val surfaceWeaverCache = ConcurrentHashMap[String, Weaver[?]]()

  /**
    * Create a Weaver from Surface at runtime. Uses Surface type information to look up or build
    * appropriate Weaver by composing existing weavers.
    *
    * This is used by RPC framework to derive weavers for method parameters and return types without
    * requiring compile-time type information.
    *
    * Self-recursive types (e.g. `class T(children: List[T])`) are supported via [[LazyWeaver]]:
    * when a recursive request is made for a surface that is still being built on the current stack,
    * a placeholder is returned that resolves to the cached weaver lazily on first use.
    */
  def fromSurface(surface: Surface): Weaver[?] = fromSurface(surface, Set.empty)

  private def fromSurface(surface: Surface, seen: Set[Surface]): Weaver[?] =
    val cached = surfaceWeaverCache.get(surface.fullName)
    if cached != null then
      cached
    else if seen.contains(surface) then
      // Break the cycle: defer resolution until the outer build populates the cache.
      LazyWeaver(surface)
    else
      val w = buildWeaver(surface, seen + surface)
      // putIfAbsent guards against a concurrent build winning the race.
      val existing = surfaceWeaverCache.putIfAbsent(surface.fullName, w)
      if existing == null then
        w
      else
        existing

  /**
    * Extensible weaver factories for runtime weaver construction. Each factory takes the current
    * `seen` set (surfaces being built on the recursion stack) and returns a partial function from
    * Surface to Weaver. Factories are tried in order until one matches.
    *
    * Recursive factories must thread `seen` through their `fromSurface` calls so that
    * self-recursive types resolve to a [[LazyWeaver]] placeholder instead of looping.
    *
    * This can be extended by platform-specific code to add support for additional types.
    */
  type WeaverFactory = Set[Surface] => PartialFunction[Surface, Weaver[?]]

  private val primitiveFactory: WeaverFactory =
    import PrimitiveWeaver.given
    _ => {
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

  private val collectionFactory: WeaverFactory =
    seen => {
      // Option[A]
      case s: OptionSurface =>
        OptionWeaver(fromSurface(s.elementSurface, seen))

      // Seq/List/Vector/IndexedSeq
      case s if s.isSeq && s.typeArgs.nonEmpty =>
        SeqWeaver(fromSurface(s.typeArgs.head, seen), s.rawType)

      // Set (Scala)
      case s if classOf[Set[?]].isAssignableFrom(s.rawType) && s.typeArgs.nonEmpty =>
        SetWeaver(fromSurface(s.typeArgs.head, seen))

      // Map (Scala)
      case s if s.isMap && s.typeArgs.size >= 2 =>
        MapWeaver(fromSurface(s.typeArgs(0), seen), fromSurface(s.typeArgs(1), seen))

      // Array
      case s: ArraySurface =>
        ArrayWeaver(fromSurface(s.elementSurface, seen), s.elementSurface.rawType)
    }

  private val javaCollectionFactory: WeaverFactory =
    seen => {
      // java.util.List
      case s if classOf[java.util.List[?]].isAssignableFrom(s.rawType) && s.typeArgs.nonEmpty =>
        JavaListWeaver(fromSurface(s.typeArgs.head, seen))

      // java.util.Set
      case s if classOf[java.util.Set[?]].isAssignableFrom(s.rawType) && s.typeArgs.nonEmpty =>
        JavaSetWeaver(fromSurface(s.typeArgs.head, seen))

      // java.util.Map
      case s if classOf[java.util.Map[?, ?]].isAssignableFrom(s.rawType) && s.typeArgs.size >= 2 =>
        JavaMapWeaver(fromSurface(s.typeArgs(0), seen), fromSurface(s.typeArgs(1), seen))
    }

  private val complexTypeFactory: WeaverFactory =
    seen => {
      // Enum
      case s: EnumSurface =>
        EnumWeaver(s)

      // Case class (has objectFactory)
      case s if s.objectFactory.isDefined =>
        val fieldWeavers = s.params.map(p => fromSurface(p.surface, seen)).toIndexedSeq
        CaseClassWeaver(s, fieldWeavers)
    }

  // Fallback for surfaces with no objectFactory (open abstract types). Lossy by design:
  // a custom `given Weaver[A]` is required to preserve subtype data on round-trip.
  private val emptyObjectWeaver: Weaver[Any] =
    new Weaver[Any]:
      override def pack(p: Packer, v: Any, config: WeaverConfig): Unit =
        if v == null then
          p.packNil
        else
          p.packMapHeader(0)
      override def unpack(u: Unpacker, context: WeaverContext): Unit =
        u.getNextValueType match
          case ValueType.NIL =>
            u.unpackNil
            context.setNull
          case _ =>
            u.skipValue
            context.setNull

  private val emptyObjectFallbackFactory: WeaverFactory =
    _ => {
      case s if !s.isPrimitive && s.objectFactory.isEmpty =>
        emptyObjectWeaver
    }

  private val defaultFactories: Seq[WeaverFactory] = Seq(
    primitiveFactory,
    collectionFactory,
    javaCollectionFactory,
    complexTypeFactory,
    emptyObjectFallbackFactory
  )

  private def buildWeaver(surface: Surface, seen: Set[Surface]): Weaver[?] = defaultFactories
    .iterator
    .flatMap(_(seen).lift(surface))
    .nextOption()
    .getOrElse(
      throw IllegalArgumentException(s"Cannot create Weaver for type: ${surface.fullName}")
    )

  /**
    * Placeholder Weaver used to break cycles when deriving weavers for self-recursive types. The
    * actual Weaver is looked up from [[surfaceWeaverCache]] lazily on first use, by which point the
    * outer build has finished populating the cache.
    */
  private class LazyWeaver(surface: Surface) extends Weaver[Any]:
    private lazy val ref: Weaver[Any] =
      val w = surfaceWeaverCache.get(surface.fullName)
      if w == null then
        throw IllegalStateException(
          s"LazyWeaver for ${surface.fullName} resolved before its target was cached"
        )
      w.asInstanceOf[Weaver[Any]]

    override def pack(p: Packer, v: Any, config: WeaverConfig): Unit = ref.pack(p, v, config)
    override def unpack(u: Unpacker, context: WeaverContext): Unit   = ref.unpack(u, context)

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

end Weaver

// Backward compatibility aliases
@deprecated("Use Weaver instead", "2026.1.x")
type ObjectWeaver[A] = Weaver[A]

@deprecated("Use Weaver instead", "2026.1.x")
val ObjectWeaver = Weaver
