package wvlet.uni.weaver

/**
  * Configuration for packing and unpacking values with [[Weaver]].
  *
  * @param discriminatorFieldName
  *   The field name holding the concrete type name of a sealed trait value
  * @param omitNoneFields
  *   If true, case class fields of Option type holding None are omitted when packing, instead of
  *   being written as nil (JSON null). Unpacking reads absent Option fields as None either way.
  */
case class WeaverConfig(discriminatorFieldName: String = "@type", omitNoneFields: Boolean = false):
  def withDiscriminatorFieldName(name: String): WeaverConfig = copy(discriminatorFieldName = name)
  def withOmitNoneFields(enabled: Boolean): WeaverConfig     = copy(omitNoneFields = enabled)
