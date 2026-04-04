/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package wvlet.uni.http.codegen

/**
  * Intermediate representation for HTTP client code generation. These case classes capture service
  * metadata extracted from Scala 3 traits and drive source code generation.
  */

/**
  * Configuration for a single client generation target.
  *
  * @param apiClassName
  *   Fully-qualified trait name (e.g., "com.example.api.UserService")
  * @param clientType
  *   Whether to generate sync, async, or both client classes
  * @param targetPackage
  *   Package for generated code (defaults to apiClassName's package + ".client")
  */
case class CodegenConfig(
    apiClassName: String,
    clientType: ClientType = ClientType.Sync,
    targetPackage: Option[String] = None
):
  def withClientType(t: ClientType): CodegenConfig  = copy(clientType = t)
  def withTargetPackage(pkg: String): CodegenConfig = copy(targetPackage = Some(pkg))
  def noTargetPackage: CodegenConfig                = copy(targetPackage = None)

  /**
    * Resolve the target package: use explicit setting or derive from the API class package
    */
  def resolvedTargetPackage: String = targetPackage.getOrElse:
    val lastDot = apiClassName.lastIndexOf('.')
    if lastDot >= 0 then
      apiClassName.substring(0, lastDot)
    else
      ""

  /**
    * Extract the simple class name from the fully-qualified API class name
    */
  def apiSimpleName: String =
    val lastDot = apiClassName.lastIndexOf('.')
    if lastDot >= 0 then
      apiClassName.substring(lastDot + 1)
    else
      apiClassName

end CodegenConfig

enum ClientType:
  case Sync,
    Async,
    Both

object ClientType:
  def fromString(s: String): ClientType =
    s.toLowerCase match
      case "sync" =>
        Sync
      case "async" =>
        Async
      case "both" =>
        Both
      case "rpc" =>
        Sync // rpc defaults to sync
      case other =>
        throw IllegalArgumentException(s"Unknown client type: ${other}")

/**
  * Represents a service trait with its methods.
  *
  * @param packageName
  *   Package containing the trait
  * @param serviceName
  *   Simple name of the trait
  * @param methods
  *   Extracted method definitions
  */
case class ServiceDef(packageName: String, serviceName: String, methods: Seq[MethodDef]):
  def fullName: String =
    if packageName.isEmpty then
      serviceName
    else
      s"${packageName}.${serviceName}"

/**
  * Represents a single method in a service trait.
  *
  * @param name
  *   Method name
  * @param httpMethod
  *   HTTP method ("POST" for RPC, from @Endpoint for REST)
  * @param path
  *   URL path ("/{fullServiceName}/{methodName}" for RPC)
  * @param params
  *   Method parameters
  * @param returnType
  *   Return type reference
  * @param isRPC
  *   true if this is an RPC method (no @Endpoint annotation)
  */
case class MethodDef(
    name: String,
    httpMethod: String,
    path: String,
    params: Seq[ParamDef],
    returnType: TypeRef,
    isRPC: Boolean
)

/**
  * Represents a method parameter.
  *
  * @param name
  *   Parameter name
  * @param typeName
  *   Type reference
  * @param hasDefault
  *   Whether the parameter has a default value
  * @param isOption
  *   Whether the parameter is Option[T]
  */
case class ParamDef(
    name: String,
    typeName: TypeRef,
    hasDefault: Boolean = false,
    isOption: Boolean = false
)

/**
  * Represents a type reference, possibly with type arguments.
  *
  * @param fullName
  *   Fully-qualified type name (e.g., "scala.Long")
  * @param shortName
  *   Simple type name (e.g., "Long")
  * @param typeArgs
  *   Type arguments for generic types
  */
case class TypeRef(fullName: String, shortName: String, typeArgs: Seq[TypeRef] = Seq.empty):
  def isUnit: Boolean   = fullName == "scala.Unit" || fullName == "Unit"
  def isOption: Boolean = fullName == "scala.Option" || fullName == "Option"

  /**
    * Render as a Scala type expression (e.g., "Map[String, User]")
    */
  def render: String =
    if typeArgs.isEmpty then
      shortName
    else
      s"${shortName}[${typeArgs.map(_.render).mkString(", ")}]"

object TypeRef:
  val Unit: TypeRef    = TypeRef("scala.Unit", "Unit")
  val String: TypeRef  = TypeRef("java.lang.String", "String")
  val Int: TypeRef     = TypeRef("scala.Int", "Int")
  val Long: TypeRef    = TypeRef("scala.Long", "Long")
  val Boolean: TypeRef = TypeRef("scala.Boolean", "Boolean")
  val Double: TypeRef  = TypeRef("scala.Double", "Double")
