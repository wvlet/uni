package wvlet.uni.weaver.codec

import wvlet.uni.msgpack.spi.Packer
import wvlet.uni.msgpack.spi.Unpacker
import wvlet.uni.msgpack.spi.ValueType
import wvlet.uni.surface.CName
import wvlet.uni.surface.Parameter
import wvlet.uni.surface.Surface
import wvlet.uni.weaver.Weaver
import wvlet.uni.weaver.WeaverConfig
import wvlet.uni.weaver.WeaverContext

/**
  * A weaver for case classes that serializes to/from MsgPack maps. Uses map format for schema
  * evolution robustness.
  */
class CaseClassWeaver[A](surface: Surface, fieldWeavers: IndexedSeq[Weaver[?]]) extends Weaver[A]:

  override def innerWeavers: Seq[Weaver[?]] = fieldWeavers

  // Pre-compute canonicalized name lookup for flexible field matching
  private val paramsByCanonicalName: Map[String, (Int, Parameter)] =
    surface
      .params
      .zipWithIndex
      .map { (p, i) =>
        CName.toCanonicalName(p.name) -> (i, p)
      }
      .toMap

  override def pack(p: Packer, v: A, config: WeaverConfig): Unit =
    if v == null then
      p.packNil
    else
      p.packMapHeader(surface.params.size)
      var i = 0
      while i < surface.params.size do
        val param = surface.params(i)
        p.packString(param.name)
        val value = param.get(v)
        fieldWeavers(i).asInstanceOf[Weaver[Any]].pack(p, value, config)
        i += 1

  override def unpack(u: Unpacker, context: WeaverContext): Unit =
    u.getNextValueType match
      case ValueType.MAP =>
        unpackFromMap(u, context)
      case ValueType.NIL =>
        u.unpackNil
        context.setNull
      case other =>
        u.skipValue
        context.setError(
          IllegalArgumentException(s"Cannot convert ${other} to ${surface.name}, expected MAP")
        )

  private def unpackFromMap(u: Unpacker, context: WeaverContext): Unit =
    try
      val mapSize     = u.unpackMapHeader
      val fieldValues = Array.ofDim[Any](surface.params.size)
      val fieldSet    = Array.fill(surface.params.size)(false)

      var i        = 0
      var hasError = false
      while i < mapSize && !hasError do
        val fieldName = u.unpackString
        paramsByCanonicalName.get(CName.toCanonicalName(fieldName)) match
          case Some((idx, param)) =>
            val fieldContext = WeaverContext(context.config)
            fieldWeavers(idx).unpack(u, fieldContext)
            if fieldContext.hasError then
              context.setError(fieldContext.getError.get)
              hasError = true
              i = skipRemainingFields(u, i, mapSize)
            else if fieldContext.isNull && !param.surface.isOption then
              // Null value for a non-Option field is an error
              context.setError(
                IllegalArgumentException(
                  s"Null value not allowed for non-optional field '${param.name}' of ${surface
                      .name}"
                )
              )
              hasError = true
              i = skipRemainingFields(u, i, mapSize)
            else
              fieldValues(idx) = fieldContext.getLastValue
              fieldSet(idx) = true
          case None =>
            // Unknown field, skip
            u.skipValue
        end match
        i += 1
      end while

      if !hasError then
        buildInstance(fieldValues, fieldSet, context)
    catch
      case e: Exception =>
        context.setError(e)

  /**
    * Skip remaining key-value pairs in the map to keep unpacker in a consistent state.
    * @return
    *   the updated index after skipping
    */
  private def skipRemainingFields(u: Unpacker, currentIndex: Int, mapSize: Int): Int =
    var i = currentIndex
    while i + 1 < mapSize do
      u.skipValue // key
      u.skipValue // value
      i += 1
    i

  private def buildInstance(
      fieldValues: Array[Any],
      fieldSet: Array[Boolean],
      context: WeaverContext
  ): Unit =
    // Fill missing fields with defaults or None for Option types
    var i        = 0
    var hasError = false
    while i < surface.params.size && !hasError do
      if !fieldSet(i) then
        val param = surface.params(i)
        // Try default value first
        param.getDefaultValue match
          case Some(defaultVal) =>
            fieldValues(i) = defaultVal
          case None =>
            // Check if the field is Option type
            if param.surface.isOption then
              fieldValues(i) = None
            else
              context.setError(
                IllegalArgumentException(
                  s"Missing required field '${param.name}' for ${surface.name}"
                )
              )
              hasError = true
      i += 1

    if !hasError then
      surface.objectFactory match
        case Some(factory) =>
          try
            val instance = factory.newInstance(fieldValues.toSeq)
            context.setObject(instance)
          catch
            case e: Exception =>
              context.setError(e)
        case None =>
          context.setError(
            IllegalArgumentException(s"No object factory available for ${surface.name}")
          )

  end buildInstance

end CaseClassWeaver
