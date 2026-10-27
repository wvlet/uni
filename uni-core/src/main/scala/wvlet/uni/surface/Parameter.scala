package wvlet.uni.surface

trait ParameterBase extends Serializable:
  def name: String
  def surface: Surface

  def call(obj: Any, x: Any*): Any

trait Parameter extends ParameterBase:
  def index: Int
  def name: String

  /**
    * Surface for representing this parameter type
    */
  def surface: Surface

  /**
    * Returns true if this parameter has @required annotation
    */
  def isRequired: Boolean

  /**
    * Returns true if this parameter has @required annotation
    */
  def isSecret: Boolean

  /**
    * Get all annotations on this parameter with their primitive values
    */
  def annotations: Seq[Annotation]

  /**
    * Find an annotation by name
    */
  def findAnnotation(name: String): Option[Annotation] = annotations.find(_.is(name))

  /**
    * Check if this parameter has an annotation with the given name
    */
  def hasAnnotation(name: String): Boolean = annotations.exists(_.is(name))

  /**
    * Get this parameter value from a given object x
    */
  def get(x: Any): Any

  override def call(obj: Any, x: Any*): Any = get(obj)

  /**
    * Get the default value of this parameter. For example the default value of x in class A(x:Int =
    * 10) is 10
    *
    * @return
    */
  def getDefaultValue: Option[Any]

end Parameter
