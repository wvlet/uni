package wvlet.uni.surface

case class MethodRef(
    owner: Class[?],
    name: String,
    paramTypes: Seq[Class[?]],
    isConstructor: Boolean
)

trait MethodParameter extends Parameter:
  def method: MethodRef

  /**
    * Method owner instance is necessary to find by-name parameter default values
    * @param methodOwner
    * @return
    */
  def getMethodArgDefaultValue(methodOwner: Any): Option[Any] = getDefaultValue

  /**
    * Resolve the effective default value of this parameter: the statically captured one if present,
    * otherwise the method-argument default evaluated on the owner instance (trait/class method
    * defaults are compiled as instance methods, so they are unreachable without it).
    */
  def resolveDefaultValue(methodOwner: Option[Any]): Option[Any] = getDefaultValue.orElse(
    methodOwner.flatMap { owner =>
      try
        getMethodArgDefaultValue(owner)
      catch
        case _: Exception =>
          None
    }
  )

object MethodParameter:
  def accessor[A, B](cl: Class[A])(body: A => B): Any => B = (x: Any) => body(cl.cast(x))

trait MethodSurface extends ParameterBase:
  def mod: Int
  def owner: Surface
  def name: String
  def args: Seq[MethodParameter]
  def surface: Surface = returnType
  def returnType: Surface

  /**
    * Annotations on this method
    */
  def annotations: Seq[Annotation] = Seq.empty

  /**
    * Find an annotation by name (simple name or fully qualified name)
    */
  def findAnnotation(name: String): Option[Annotation] = annotations.find(_.is(name))

  /**
    * Check if this method has an annotation with the given name
    */
  def hasAnnotation(name: String): Boolean = annotations.exists(_.is(name))

  def isPublic: Boolean    = (mod & MethodModifier.PUBLIC) != 0
  def isPrivate: Boolean   = (mod & MethodModifier.PRIVATE) != 0
  def isProtected: Boolean = (mod & MethodModifier.PROTECTED) != 0
  def isStatic: Boolean    = (mod & MethodModifier.STATIC) != 0
  def isFinal: Boolean     = (mod & MethodModifier.FINAL) != 0
  def isAbstract: Boolean  = (mod & MethodModifier.ABSTRACT) != 0

/**
  * Parameters of a Surface. Renamed from StdMethodParameter to StaticMethodParameter for binary
  * compatibility
  */
case class StaticMethodParameter(
    method: MethodRef,
    index: Int,
    name: String,
    isRequired: Boolean,
    isSecret: Boolean,
    surface: Surface,
    private val defaultValue: Option[Any] = None,
    accessor: Option[Any => Any] = None,
    methodArgAccessor: Option[Any => Any] = None,
    annotations: Seq[Annotation] = Seq.empty
) extends MethodParameter:
  override def toString: String             = s"${name}:${surface.name}"
  def get(x: Any): Any                      = accessor.map(a => a(x)).getOrElse(null)
  override def getDefaultValue: Option[Any] = defaultValue
  override def getMethodArgDefaultValue(methodOwner: Any): Option[Any] = methodArgAccessor.map {
    acc =>
      acc(methodOwner)
  }
