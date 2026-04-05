package wvlet.uni.temporal.example

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import io.temporal.common.converter.{
  DefaultDataConverter,
  JacksonJsonPayloadConverter,
  PayloadConverter
}

/**
  * Custom DataConverter that registers Jackson's Scala module so Scala 3 case classes can be
  * serialized/deserialized by Temporal.
  *
  * Without this, Jackson cannot construct Scala case class instances because they lack a no-arg
  * constructor. The Scala module teaches Jackson about Scala product types, Option, collections,
  * etc.
  */
object ScalaDataConverter:
  private val objectMapper: ObjectMapper =
    val mapper = ObjectMapper()
    mapper.registerModule(DefaultScalaModule)
    mapper

  val converter: DefaultDataConverter = DefaultDataConverter
    .newDefaultInstance()
    .withPayloadConverterOverrides(JacksonJsonPayloadConverter(objectMapper))
