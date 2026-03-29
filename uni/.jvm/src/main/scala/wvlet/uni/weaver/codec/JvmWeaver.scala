package wvlet.uni.weaver.codec

import wvlet.uni.msgpack.spi.Packer
import wvlet.uni.msgpack.spi.Unpacker
import wvlet.uni.msgpack.spi.ValueType
import wvlet.uni.weaver.Weaver
import wvlet.uni.weaver.WeaverConfig
import wvlet.uni.weaver.WeaverContext
import java.io.File
import java.net.URI
import java.net.URL
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime

object JvmWeaver:

  private def stringBasedWeaver[A](
      typeName: String,
      serialize: A => String,
      deserialize: String => A
  ): Weaver[A] =
    new Weaver[A]:
      override def pack(p: Packer, v: A, config: WeaverConfig): Unit = p.packString(serialize(v))

      override def unpack(u: Unpacker, context: WeaverContext): Unit =
        u.getNextValueType match
          case ValueType.STRING =>
            PrimitiveWeaver.safeConvertFromString(
              context,
              u,
              deserialize,
              context.setObject,
              typeName
            )
          case ValueType.NIL =>
            PrimitiveWeaver.safeUnpackNil(context, u)
          case other =>
            u.skipValue
            context.setError(
              IllegalArgumentException(s"Cannot convert ${other} to ${typeName}, expected STRING")
            )

  given zonedDateTimeWeaver: Weaver[ZonedDateTime] = stringBasedWeaver(
    "ZonedDateTime",
    _.toString,
    ZonedDateTime.parse(_)
  )

  given localDateWeaver: Weaver[LocalDate] = stringBasedWeaver(
    "LocalDate",
    _.toString,
    LocalDate.parse(_)
  )

  given localDateTimeWeaver: Weaver[LocalDateTime] = stringBasedWeaver(
    "LocalDateTime",
    _.toString,
    LocalDateTime.parse(_)
  )

  given durationWeaver: Weaver[Duration] = stringBasedWeaver(
    "Duration",
    _.toString,
    Duration.parse(_)
  )

  given offsetDateTimeWeaver: Weaver[OffsetDateTime] = stringBasedWeaver(
    "OffsetDateTime",
    _.toString,
    OffsetDateTime.parse(_)
  )

  given fileWeaver: Weaver[File] = stringBasedWeaver("File", _.getPath, File(_))

  given urlWeaver: Weaver[URL] = stringBasedWeaver("URL", _.toString, s => URI(s).toURL)

  given pathWeaver: Weaver[java.nio.file.Path] = stringBasedWeaver(
    "Path",
    _.toString,
    java.nio.file.Path.of(_)
  )

  given optionalWeaver[A](using elementWeaver: Weaver[A]): Weaver[java.util.Optional[A]] =
    new Weaver[java.util.Optional[A]]:
      override def pack(p: Packer, v: java.util.Optional[A], config: WeaverConfig): Unit =
        if v.isPresent then
          elementWeaver.pack(p, v.get, config)
        else
          p.packNil

      override def unpack(u: Unpacker, context: WeaverContext): Unit =
        u.getNextValueType match
          case ValueType.NIL =>
            u.unpackNil
            context.setObject(java.util.Optional.empty[A]())
          case _ =>
            val elementContext = WeaverContext(context.config)
            elementWeaver.unpack(u, elementContext)
            if elementContext.hasError then
              context.setError(elementContext.getError.get)
            else if elementContext.isNull then
              context.setObject(java.util.Optional.empty[A]())
            else
              context.setObject(
                java.util.Optional.ofNullable(elementContext.getLastValue.asInstanceOf[A])
              )

end JvmWeaver
