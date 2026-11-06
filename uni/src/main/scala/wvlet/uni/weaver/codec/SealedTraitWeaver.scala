package wvlet.uni.weaver.codec

import wvlet.uni.msgpack.spi.MessagePack
import wvlet.uni.msgpack.spi.Packer
import wvlet.uni.msgpack.spi.Unpacker
import wvlet.uni.msgpack.spi.Value
import wvlet.uni.msgpack.spi.ValueType
import wvlet.uni.surface.CName
import wvlet.uni.weaver.Weaver
import wvlet.uni.weaver.WeaverConfig
import wvlet.uni.weaver.WeaverContext

/**
  * A weaver for sealed traits and enums with parameterized cases that serializes/deserializes ADTs
  * with a discriminator field. Uses a flat format where the discriminator field is added alongside
  * child fields.
  *
  * Child weavers are passed by name and resolved on first use. Derived givens for recursive types
  * refer to each other (`Node` -> `Split` -> `Seq[Node]` -> `Node`), so resolving them while the
  * given is still initializing would re-enter its lazy initializer and deadlock.
  *
  * @tparam A
  *   The sealed trait type
  * @param childWeavers
  *   Mapping from child type name to its weaver and optional singleton instance (for case objects
  *   and enum cases without parameters)
  */
class SealedTraitWeaver[A](
    traitName: String,
    childWeavers: => Map[String, (Weaver[? <: A], Option[A])]
) extends Weaver[A]:

  private lazy val weavers: Map[String, (Weaver[? <: A], Option[A])] = childWeavers

  // Build canonical name lookup for flexible type matching
  private lazy val weaversByCanonicalName: Map[String, (String, Weaver[? <: A], Option[A])] =
    weavers.map { (name, weaverAndInstance) =>
      CName.toCanonicalName(name) -> (name, weaverAndInstance._1, weaverAndInstance._2)
    }

  private lazy val knownTypeNames: String = weavers.keys.mkString(", ")

  override def pack(p: Packer, v: A, config: WeaverConfig): Unit =
    if v == null then
      p.packNil
    else
      // productPrefix is the case name for case classes, case objects, and enum cases (whose
      // singleton cases are anonymous classes), and is stable across JVM, JS, and Native
      val className =
        v match
          case p: Product =>
            p.productPrefix
          case _ =>
            v.getClass.getSimpleName.stripSuffix("$")

      weavers.get(className) match
        case Some((childWeaver, singletonOpt)) =>
          singletonOpt match
            case Some(_) =>
              // Case object: just pack the discriminator
              p.packMapHeader(1)
              p.packString(config.discriminatorFieldName)
              p.packString(className)
            case None =>
              // Case class: pack child to msgpack, read back as Value, and repack with discriminator
              val childPacker = MessagePack.newPacker()
              childWeaver.asInstanceOf[Weaver[A]].pack(childPacker, v, config)
              val childMsgpack  = childPacker.toByteArray
              val childUnpacker = MessagePack.newUnpacker(childMsgpack)
              val childValue    = childUnpacker.unpackValue

              childValue match
                case Value.MapValue(entries) =>
                  // Pack map with discriminator first, then child fields
                  p.packMapHeader(entries.size + 1)
                  p.packString(config.discriminatorFieldName)
                  p.packString(className)
                  entries.foreach { (k, v) =>
                    k.writeTo(p)
                    v.writeTo(p)
                  }
                case _ =>
                  throw IllegalArgumentException(
                    s"Expected MAP from child weaver for ${className}, got ${childValue.valueType}"
                  )
        case None =>
          throw IllegalArgumentException(
            s"Unknown child type '${className}' for sealed trait ${traitName}. Known types: ${knownTypeNames}"
          )
      end match

  override def unpack(u: Unpacker, context: WeaverContext): Unit =
    u.getNextValueType match
      case ValueType.MAP =>
        unpackFromMap(u, context)
      case ValueType.NIL =>
        u.unpackNil
        context.setNull
      case ValueType.STRING =>
        // Accept a bare singleton name ("Zero"), the form runtime codecs wrote for enum singletons
        // before enums with parameterized cases used this format
        val name = u.unpackString
        weaversByCanonicalName.get(CName.toCanonicalName(name)) match
          case Some((_, _, Some(instance))) =>
            context.setObject(instance)
          case _ =>
            context.setError(
              IllegalArgumentException(
                s"Unknown singleton '${name}' for ${traitName}. Known types: ${knownTypeNames}"
              )
            )
      case other =>
        u.skipValue
        context.setError(
          IllegalArgumentException(s"Cannot convert ${other} to ${traitName}, expected MAP")
        )

  private def unpackFromMap(u: Unpacker, context: WeaverContext): Unit =
    try
      // Read entire map as Value to extract discriminator and rebuild for child
      val mapValue = u.unpackValue

      mapValue match
        case Value.MapValue(entries) =>
          // Find discriminator field using canonical name matching
          val discriminatorCanonical = CName.toCanonicalName(context.config.discriminatorFieldName)
          val discriminatorEntry     = entries.find { (k, _) =>
            k match
              case Value.StringValue(keyName) =>
                CName.toCanonicalName(keyName) == discriminatorCanonical
              case _ =>
                false
          }

          discriminatorEntry match
            case Some((_, Value.StringValue(typeName))) =>
              val canonicalTypeName = CName.toCanonicalName(typeName)
              weaversByCanonicalName.get(canonicalTypeName) match
                case Some((originalName, childWeaver, singletonOpt)) =>
                  singletonOpt match
                    case Some(instance) =>
                      // Case object: return the singleton instance
                      context.setObject(instance)
                    case None =>
                      // Case class: rebuild map without discriminator and delegate to child weaver
                      val filteredEntries = entries.filter { (k, _) =>
                        k match
                          case Value.StringValue(keyName) =>
                            CName.toCanonicalName(keyName) != discriminatorCanonical
                          case _ =>
                            true
                      }

                      val childMapValue = Value.MapValue(filteredEntries)
                      val childMsgpack  = childMapValue.toMsgpack
                      val childUnpacker = MessagePack.newUnpacker(childMsgpack)
                      childWeaver.unpack(childUnpacker, context)
                case None =>
                  context.setError(
                    IllegalArgumentException(
                      s"Unknown type '${typeName}' for sealed trait ${traitName}. Known types: ${knownTypeNames}"
                    )
                  )
            case Some((_, other)) =>
              context.setError(
                IllegalArgumentException(
                  s"Discriminator field '${context
                      .config
                      .discriminatorFieldName}' must be a string, got ${other.valueType}"
                )
              )
            case None =>
              context.setError(
                IllegalArgumentException(
                  s"Missing discriminator field '${context
                      .config
                      .discriminatorFieldName}' for sealed trait ${traitName}"
                )
              )
          end match
        case _ =>
          context.setError(
            IllegalArgumentException(s"Expected MAP for ${traitName}, got ${mapValue.valueType}")
          )
      end match
    catch
      case e: Exception =>
        context.setError(e)

end SealedTraitWeaver

/**
  * The weaver for a singleton child of a sealed trait or enum (a case object or an enum case
  * without parameters). [[SealedTraitWeaver]] packs only the discriminator for such values, so this
  * weaver packs no fields and unpacks to the singleton itself.
  */
class SingletonWeaver[A](value: A) extends Weaver[A]:
  override def pack(p: Packer, v: A, config: WeaverConfig): Unit = p.packMapHeader(0)

  override def unpack(u: Unpacker, context: WeaverContext): Unit =
    u.skipValue
    context.setObject(value)
