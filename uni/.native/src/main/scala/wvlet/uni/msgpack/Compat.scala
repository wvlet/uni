package wvlet.uni.msgpack.spi

import java.io.InputStream
import java.io.OutputStream

import wvlet.uni.msgpack.impl.PureScalaBufferPacker
import wvlet.uni.msgpack.impl.PureScalaBufferUnpacker
import wvlet.uni.msgpack.impl.ByteArrayBuffer

/**
  * Compatibility layer for Scala.js
  */
object Compat:
  def isScalaJS = false

  def floatToIntBits(v: Float): Int     = java.lang.Float.floatToRawIntBits(v)
  def doubleToLongBits(v: Double): Long = java.lang.Double.doubleToRawLongBits(v)

  def newBufferPacker: BufferPacker               = new PureScalaBufferPacker
  def newUnpacker(msgpack: Array[Byte]): Unpacker = newUnpacker(msgpack, 0, msgpack.length)
  def newUnpacker(msgpack: Array[Byte], offset: Int, len: Int): Unpacker =
    new PureScalaBufferUnpacker(ByteArrayBuffer.fromArray(msgpack, offset, len))
