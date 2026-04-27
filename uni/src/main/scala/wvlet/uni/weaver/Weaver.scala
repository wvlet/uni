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
    *   classOf[Dog] -> Weaver.of[Dog],
    *   classOf[Cat] -> Weaver.of[Cat]
    * )
    *
    * case class Owner(name: String, pet: Animal) derives Weaver
    * }}}
    *
    * Subclasses must be concrete (case classes or regular classes with derivable Weavers); Scala
    * `case object`s aren't supported here because abstract classes with constructor parameters
    * cannot be extended by `object`s. For sealed hierarchies that include case objects, use
    * `derives Weaver` on the sealed parent instead.
    *
    * @param subclassWeavers
    *   pairs of `(concrete subclass, weaver)`. Names are taken from `Class.getSimpleName`.
    */
  def subclassesOf[A](subclassWeavers: (Class[? <: A], Weaver[? <: A])*): Weaver[A] =
    if subclassWeavers.isEmpty then
      throw IllegalArgumentException(
        "Weaver.subclassesOf requires at least one (Class, Weaver) pair"
      )
    val entries: Seq[(String, (Weaver[? <: A], Option[A]))] = subclassWeavers.map { (cls, weaver) =>
      val name = cls.getSimpleName.stripSuffix("$")
      name -> (weaver, None)
    }
    val map = entries.toMap
    if map.size != entries.size then
      val dupes = entries
        .groupBy(_._1)
        .collect {
          case (k, vs) if vs.size > 1 =>
            k
        }
        .mkString(", ")
      throw IllegalArgumentException(
        s"Weaver.subclassesOf received duplicate subclass names: ${dupes}. " +
          s"Each subclass must have a unique simpleName."
      )
    // Derive a human-readable label from the first subclass's superclass for error messages.
    val firstCls     = subclassWeavers.head._1
    val abstractName = Option(firstCls.getSuperclass)
      .filterNot(_ == classOf[Object])
      .map(_.getSimpleName)
      .getOrElse("abstract type")
    new SealedTraitWeaver[A](abstractName, map)

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
