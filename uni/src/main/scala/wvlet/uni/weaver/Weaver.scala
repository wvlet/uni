package wvlet.uni.weaver

import wvlet.uni.json.JSON.JSONValue
import wvlet.uni.msgpack.spi.MessagePack
import wvlet.uni.msgpack.spi.MsgPack
import wvlet.uni.msgpack.spi.Packer
import wvlet.uni.msgpack.spi.Unpacker
import wvlet.uni.msgpack.spi.ValueType
import wvlet.uni.surface.*
import wvlet.uni.util.ElapsedTime
import wvlet.uni.util.ULID
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

  /**
    * Composite weavers (collections, case classes, etc.) override this to expose the weavers they
    * delegate to. Used by [[Weaver.fromSurfaceOpt]] to detect when any nested weaver in the tree is
    * the lossy empty-object fallback. Default: no inner weavers.
    */
  def innerWeavers: Seq[Weaver[?]] = Seq.empty

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
    ulidWeaver,
    elapsedTimeWeaver,
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

  // Cache for weavers created from Surface. Only fully-resolved weaver trees are committed.
  private val surfaceWeaverCache = ConcurrentHashMap[String, Weaver[?]]()

  // Per-thread accumulator for the current top-level fromSurface call. Each entry holds
  // the LazyWeaver placeholder that was returned for recursive requests at that key, plus
  // the final built weaver once the build completes. Entries are published to
  // surfaceWeaverCache only after the outer-most fromSurface call returns, by which time
  // every LazyWeaver placeholder has had its target set directly via `resolve`. This
  // means consumers reading the cache never have to look up a placeholder's target — it's
  // already wired in — so concurrent threads observing a sub-weaver in cache cannot see
  // an unresolved LazyWeaver, regardless of commit order.
  private final class PendingEntry(val placeholder: LazyWeaver):
    var built: Weaver[?] = null

  private val pendingBuild
      : ThreadLocal[scala.collection.mutable.LinkedHashMap[String, PendingEntry]] =
    new ThreadLocal[scala.collection.mutable.LinkedHashMap[String, PendingEntry]]:
      override def initialValue() = scala.collection.mutable.LinkedHashMap.empty

  /**
    * Like [[fromSurface]], but returns `None` when the resulting weaver tree contains the lossy
    * empty-object fallback at any position — top-level or nested inside a collection / case class.
    * Callers that want to fall back to a different encoding strategy (e.g. toString-quoted JSON)
    * when a type isn't directly supported can use this overload to detect the gap, including cases
    * like `Seq[Either[A, B]]` or `case class Foo(d: LocalDate)` where `fromSurface` would otherwise
    * embed an empty fallback inside an outer composite weaver.
    */
  def fromSurfaceOpt(surface: Surface): Option[Weaver[?]] =
    val w = fromSurface(surface)
    if containsEmptyFallback(w) then
      None
    else
      Some(w)

  private def containsEmptyFallback(w: Weaver[?]): Boolean =
    val visited = java
      .util
      .Collections
      .newSetFromMap(new java.util.IdentityHashMap[Weaver[?], java.lang.Boolean])
    def walk(x: Weaver[?]): Boolean =
      if !visited.add(x) then
        false
      else if x eq emptyObjectWeaver then
        true
      else
        x.innerWeavers.exists(walk)
    walk(w)

  /**
    * Create a Weaver from Surface at runtime. Uses Surface type information to look up or build
    * appropriate Weaver by composing existing weavers.
    *
    * This is used by RPC framework to derive weavers for method parameters and return types without
    * requiring compile-time type information.
    *
    * Self-recursive types (e.g. `class T(children: List[T])`) are supported via [[LazyWeaver]]:
    * when a recursive request is made for a surface still on the current build stack, a placeholder
    * is returned. The placeholder's target is wired in directly once the surface's weaver is built,
    * so it never depends on cache lookups at use time.
    */
  def fromSurface(surface: Surface): Weaver[?] =
    val key    = surface.fullName
    val cached = surfaceWeaverCache.get(key)
    if cached != null then
      return cached

    val pending = pendingBuild.get()
    pending.get(key) match
      case Some(entry) if entry.built != null =>
        // Already finished within this build — return the same instance for sibling references.
        entry.built
      case Some(entry) =>
        // Recursive request: parent is still being built. Return its placeholder; it will be
        // resolved when the parent's buildWeaver call returns, before any consumer sees it.
        entry.placeholder
      case None =>
        val isOuterMost = pending.isEmpty
        val entry       = PendingEntry(LazyWeaver())
        pending(key) = entry
        try
          val built = buildWeaver(surface)
          // Wire the placeholder directly to the built weaver so any structures that
          // captured the placeholder during recursion now have a working target.
          entry.placeholder.resolve(built)
          entry.built = built
          if isOuterMost then
            val it = pending.iterator
            while it.hasNext do
              val (k, e) = it.next()
              surfaceWeaverCache.putIfAbsent(k, e.built)
          built
        finally
          if isOuterMost then
            pending.clear()

  end fromSurface

  /**
    * Extensible weaver factories for runtime weaver construction. Each factory is a partial
    * function that maps a Surface to a Weaver. Factories are tried in order until one matches.
    *
    * Recursion is handled transparently via [[fromSurface]]: factories may call it directly when
    * descending into child surfaces; cycle detection is keyed on `Surface.fullName` so a
    * `LazySurface` placeholder resolves to the same key as the corresponding `GenericSurface`.
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
      case s if s.rawType == classOf[ULID] =>
        ulidWeaver
      case s if s.rawType == classOf[ElapsedTime] =>
        elapsedTimeWeaver
    }

  end primitiveFactory

  private val collectionFactory: WeaverFactory = {
    // Option[A]
    case s: OptionSurface =>
      OptionWeaver(fromSurface(s.elementSurface))

    // Seq/List/Vector/IndexedSeq
    case s if s.isSeq && s.typeArgs.nonEmpty =>
      SeqWeaver(fromSurface(s.typeArgs.head), s.rawType)

    // Set (Scala)
    case s if classOf[Set[?]].isAssignableFrom(s.rawType) && s.typeArgs.nonEmpty =>
      SetWeaver(fromSurface(s.typeArgs.head))

    // Map (Scala)
    case s if s.isMap && s.typeArgs.size >= 2 =>
      MapWeaver(fromSurface(s.typeArgs(0)), fromSurface(s.typeArgs(1)))

    // Array
    case s: ArraySurface =>
      ArrayWeaver(fromSurface(s.elementSurface), s.elementSurface.rawType)
  }

  private val javaCollectionFactory: WeaverFactory = {
    // java.util.List
    case s if classOf[java.util.List[?]].isAssignableFrom(s.rawType) && s.typeArgs.nonEmpty =>
      JavaListWeaver(fromSurface(s.typeArgs.head))

    // java.util.Set
    case s if classOf[java.util.Set[?]].isAssignableFrom(s.rawType) && s.typeArgs.nonEmpty =>
      JavaSetWeaver(fromSurface(s.typeArgs.head))

    // java.util.Map
    case s if classOf[java.util.Map[?, ?]].isAssignableFrom(s.rawType) && s.typeArgs.size >= 2 =>
      JavaMapWeaver(fromSurface(s.typeArgs(0)), fromSurface(s.typeArgs(1)))
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

  private val emptyObjectFallbackFactory: WeaverFactory = {
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

  private def buildWeaver(surface: Surface): Weaver[?] = defaultFactories
    .collectFirst {
      case f if f.isDefinedAt(surface) =>
        f(surface)
    }
    .getOrElse(
      throw IllegalArgumentException(s"Cannot create Weaver for type: ${surface.fullName}")
    )

  /**
    * Placeholder Weaver used to break cycles when deriving weavers for self-recursive types. Its
    * target is wired in by the outer build via [[resolve]] before any consumer can use it; pack and
    * unpack delegate directly to the resolved weaver. Holding a direct reference (rather than
    * re-looking up a global cache) means visibility to other threads doesn't depend on commit order
    * — the placeholder is always fully wired by the time it escapes the build.
    */
  private final class LazyWeaver extends Weaver[Any]:
    @volatile
    private var ref: Weaver[Any] = null

    def resolve(w: Weaver[?]): Unit = ref = w.asInstanceOf[Weaver[Any]]

    override def innerWeavers: Seq[Weaver[?]] =
      val w = ref
      if w == null then
        Seq.empty
      else
        Seq(w)

    override def pack(p: Packer, v: Any, config: WeaverConfig): Unit =
      val w = ref
      if w == null then
        throw IllegalStateException("LazyWeaver used before its target was resolved")
      w.pack(p, v, config)

    override def unpack(u: Unpacker, context: WeaverContext): Unit =
      val w = ref
      if w == null then
        throw IllegalStateException("LazyWeaver used before its target was resolved")
      w.unpack(u, context)

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
