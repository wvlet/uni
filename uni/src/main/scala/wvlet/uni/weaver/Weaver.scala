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
import wvlet.uni.weaver.codec.SealedTraitWeaver

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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

  /**
    * Build a Weaver for a non-sealed abstract class (or trait) `A` from an explicit list of
    * concrete subclass weavers. Use this when `Weaver.of[A]` cannot derive automatically because
    * `A` is open and the macro can't enumerate its subtypes.
    *
    * The resulting weaver shares the wire format of [[Weaver.of]] for sealed traits: a
    * discriminator field (default `@type`) holds the concrete subclass name, and the remaining
    * fields hold the subclass payload.
    *
    * {{{
    * abstract class Animal(val name: String)
    * case class Dog(name: String, breed: String) extends Animal(name) derives Weaver
    * case class Cat(name: String, color: String) extends Animal(name) derives Weaver
    *
    * given Weaver[Animal] = Weaver.subclassesOf[Animal](
    *   Weaver.SubclassEntry(classOf[Dog], Weaver.of[Dog]),
    *   Weaver.SubclassEntry(classOf[Cat], Weaver.of[Cat])
    * )
    *
    * case class Owner(name: String, pet: Animal) derives Weaver
    * }}}
    *
    * Each entry binds a concrete subclass `S <: A` to a `Weaver[S]` (and optionally an `S`
    * singleton instance). Sharing the type parameter prevents mismatched registrations like
    * `SubclassEntry(classOf[Dog], Weaver.of[Cat])` from compiling.
    *
    * For Scala `object` (singleton) subclasses, pass `singleton = Some(MyObject)` explicitly so the
    * registration works portably on JVM, Scala.js, and Native:
    * {{{
    * given Weaver[Signal] = Weaver.subclassesOf[Signal](
    *   Weaver.SubclassEntry(classOf[On.type],  Weaver.of[On.type],  Some(On)),
    *   Weaver.SubclassEntry(classOf[Off.type], Weaver.of[Off.type], Some(Off)),
    *   Weaver.SubclassEntry(classOf[Pulse],    Weaver.of[Pulse])
    * )
    * }}}
    *
    * @param subclassWeavers
    *   one [[SubclassEntry]] per concrete subclass. Discriminator names come from
    *   `Class.getSimpleName` and are validated for collisions after canonicalization (matching how
    *   the discriminator is resolved during unpack).
    */
  def subclassesOf[A](subclassWeavers: SubclassEntry[A, ?]*)(using
      ct: scala.reflect.ClassTag[A]
  ): Weaver[A] =
    if subclassWeavers.isEmpty then
      throw IllegalArgumentException("Weaver.subclassesOf requires at least one subclass entry")
    // Reject abstract intermediate registrations: pack-time dispatch keys on the concrete
    // runtime class name, so abstract entries can never match an actual instance.
    val abstractEntries = subclassWeavers
      .toSeq
      .filter { entry =>
        entry.singleton.isEmpty && java.lang.reflect.Modifier.isAbstract(entry.cls.getModifiers)
      }
    if abstractEntries.nonEmpty then
      val names = abstractEntries.map(_.cls.getName).mkString(", ")
      throw IllegalArgumentException(
        s"Weaver.subclassesOf received abstract class entries: ${names}. " +
          s"Pack-time dispatch keys on the concrete runtime class, so abstract intermediates " +
          s"can never match. Register concrete subclasses (or singletons) instead."
      )
    val pairs: Seq[(String, (Weaver[? <: A], Option[A]))] = subclassWeavers
      .toSeq
      .map { entry =>
        val name      = entry.cls.getSimpleName.stripSuffix("$")
        val weaver    = entry.weaver.asInstanceOf[Weaver[? <: A]]
        val singleton = entry.singleton.asInstanceOf[Option[A]]
        (name, (weaver, singleton))
      }
    // Discriminator lookup at unpack time normalizes via CName.toCanonicalName, so two
    // registered children that differ only in casing/separators (e.g. FooBar vs foo_bar) would
    // collide silently if we only compared raw names.
    val canonicalDupes = pairs
      .groupBy { (name, _) =>
        CName.toCanonicalName(name)
      }
      .collect {
        case (canonical, group) if group.size > 1 =>
          canonical -> group.map(_._1)
      }
    if canonicalDupes.nonEmpty then
      val rendered = canonicalDupes
        .map { (canonical, names) =>
          s"${names.mkString(" / ")} (canonical: ${canonical})"
        }
        .mkString("; ")
      throw IllegalArgumentException(
        s"Weaver.subclassesOf received subclass names that collide after canonicalization: " +
          s"${rendered}. Each subclass must have a unique discriminator name."
      )
    val parentName =
      try
        ct.runtimeClass.getSimpleName.stripSuffix("$")
      catch
        case _: Throwable =>
          "abstract type"
    new SealedTraitWeaver[A](parentName, pairs.toMap)

  end subclassesOf

  /**
    * One subclass registration for [[subclassesOf]]. The shared subtype parameter `S <: A` keeps
    * the class, weaver, and optional singleton on the same concrete subtype, so mismatched
    * registrations are rejected at compile time.
    */
  case class SubclassEntry[A, S <: A](cls: Class[S], weaver: Weaver[S], singleton: Option[S] = None)

  object SubclassEntry:

    /**
      * Register a singleton subclass (Scala `object`) without supplying a `Weaver[S]`. The pack/
      * unpack paths inside [[SealedTraitWeaver]] never consult the child weaver when a singleton is
      * set — they just emit the discriminator and return the cached instance — so a placeholder
      * weaver is fine. This makes plain `object` subclasses (which can't `derives Weaver`)
      * registrable on any platform.
      */
    def singleton[A, S <: A](cls: Class[S], instance: S): SubclassEntry[A, S] = SubclassEntry[A, S](
      cls,
      singletonOnlyWeaver[S](cls),
      Some(instance)
    )

    /**
      * JVM-only convenience: build a [[SubclassEntry]] for a Scala `object` subclass by recovering
      * the singleton via the synthetic `MODULE$` field reflectively. Throws
      * `IllegalArgumentException` if `cls` doesn't have `MODULE$` (i.e. it isn't a Scala module).
      * Use [[singleton]] (passing the instance directly) on Scala.js / Native.
      */
    def forSingleton[A, S <: A](cls: Class[S], weaver: Weaver[S]): SubclassEntry[A, S] =
      Weaver.singletonInstanceOf[A](cls.asInstanceOf[Class[? <: A]]) match
        case Some(instance) =>
          SubclassEntry[A, S](cls, weaver, Some(instance.asInstanceOf[S]))
        case None =>
          throw IllegalArgumentException(
            s"SubclassEntry.forSingleton: ${cls.getName} is not a Scala module " +
              s"(no MODULE$$ field), or reflection isn't supported on this platform. " +
              s"Pass `singleton = Some(...)` explicitly to construct a SubclassEntry instead."
          )

    /**
      * Placeholder Weaver used when registering a singleton-only subclass. SealedTraitWeaver short-
      * circuits to the singleton instance whenever it's defined, so this is never invoked during
      * normal pack/unpack — but we throw if it ever is, to surface bugs loudly instead of silently.
      */
    private def singletonOnlyWeaver[S](cls: Class[S]): Weaver[S] =
      new Weaver[S]:
        private def fail =
          throw new IllegalStateException(
            s"singletonOnlyWeaver for ${cls.getName} should never be invoked; " +
              s"SealedTraitWeaver dispatches singleton subclasses without consulting the weaver."
          )
        override def pack(p: wvlet.uni.msgpack.spi.Packer, v: S, config: WeaverConfig): Unit = fail
        override def unpack(u: wvlet.uni.msgpack.spi.Unpacker, context: WeaverContext): Unit = fail

  end SubclassEntry

  /**
    * Recover a singleton instance from the synthetic `MODULE$` field that the Scala compiler emits
    * for `object` definitions on JVM. Returns None if the class isn't a module, or if reflection
    * isn't supported on the current platform (Scala.js / Native).
    */
  private[weaver] def singletonInstanceOf[A](cls: Class[? <: A]): Option[A] =
    try
      val field = cls.getField("MODULE$")
      Some(field.get(null).asInstanceOf[A])
    catch
      case _: Throwable =>
        None

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

end Weaver

// Backward compatibility aliases
@deprecated("Use Weaver instead", "2026.1.x")
type ObjectWeaver[A] = Weaver[A]

@deprecated("Use Weaver instead", "2026.1.x")
val ObjectWeaver = Weaver
