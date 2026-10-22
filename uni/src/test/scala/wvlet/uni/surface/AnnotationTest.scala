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
package wvlet.uni.surface

import wvlet.uni.test.UniTest

import scala.annotation.StaticAnnotation

// Test annotations with various parameter types
class label(val value: String)                             extends StaticAnnotation
class description(val text: String, val priority: Int = 0) extends StaticAnnotation
class range(val min: Int, val max: Int)                    extends StaticAnnotation
class tags(val values: String*)                            extends StaticAnnotation
class enabled(val flag: Boolean)                           extends StaticAnnotation
class precision(val value: Double)                         extends StaticAnnotation
class marker                                               extends StaticAnnotation

// Test case class with annotated parameters
case class ModelWithAnnotations(
    @label("user-id")
    @description("The unique identifier", priority = 1)
    id: String,
    @range(min = 0, max = 100)
    score: Int,
    @enabled(true)
    active: Boolean,
    @marker
    name: String,
    @precision(0.001)
    value: Double,
    @tags("foo", "bar")
    category: String
)

// Test case class with class-level annotations
@label("labeled-model")
@marker
case class LabeledModel(id: String)

type LabeledModelAlias = LabeledModel

// Test class with method having annotated parameters
class ServiceWithAnnotatedMethods:
  def process(
      @label("input-data")
      data: String,
      @range(min = 1, max = 10)
      count: Int
  ): Unit = {}

class AnnotationTest extends UniTest:

  test("extract annotations with string values") {
    val s   = Surface.of[ModelWithAnnotations]
    val pid = s.params.find(_.name == "id").get

    pid.annotations.size shouldBe 2

    val labelAnnot = pid.findAnnotation("label").get
    labelAnnot.name shouldBe "label"
    labelAnnot.get("value") shouldBe Some("user-id")
    labelAnnot.getAs[String]("value") shouldBe Some("user-id")

    val descAnnot = pid.findAnnotation("description").get
    descAnnot.name shouldBe "description"
    descAnnot.get("text") shouldBe Some("The unique identifier")
    descAnnot.get("priority") shouldBe Some(1)
  }

  test("extract annotations with int values") {
    val s      = Surface.of[ModelWithAnnotations]
    val pScore = s.params.find(_.name == "score").get

    pScore.hasAnnotation("range") shouldBe true
    val rangeAnnot = pScore.findAnnotation("range").get
    rangeAnnot.get("min") shouldBe Some(0)
    rangeAnnot.get("max") shouldBe Some(100)
  }

  test("extract annotations with boolean values") {
    val s       = Surface.of[ModelWithAnnotations]
    val pActive = s.params.find(_.name == "active").get

    val enabledAnnot = pActive.findAnnotation("enabled").get
    enabledAnnot.get("flag") shouldBe Some(true)
  }

  test("extract annotations with double values") {
    val s      = Surface.of[ModelWithAnnotations]
    val pValue = s.params.find(_.name == "value").get

    val precisionAnnot = pValue.findAnnotation("precision").get
    precisionAnnot.get("value") shouldBe Some(0.001)
  }

  test("extract annotations with varargs values") {
    val s         = Surface.of[ModelWithAnnotations]
    val pCategory = s.params.find(_.name == "category").get

    val tagsAnnot = pCategory.findAnnotation("tags").get
    tagsAnnot.get("values") shouldBe Some(Seq("foo", "bar"))
    tagsAnnot.getAs[Seq[String]]("values") shouldBe Some(Seq("foo", "bar"))
  }

  test("extract marker annotations without parameters") {
    val s     = Surface.of[ModelWithAnnotations]
    val pName = s.params.find(_.name == "name").get

    pName.hasAnnotation("marker") shouldBe true
    val markerAnnot = pName.findAnnotation("marker").get
    markerAnnot.params.isEmpty shouldBe true
  }

  test("hasAnnotation returns false for missing annotations") {
    val s   = Surface.of[ModelWithAnnotations]
    val pid = s.params.find(_.name == "id").get

    pid.hasAnnotation("nonexistent") shouldBe false
    pid.findAnnotation("nonexistent") shouldBe None
  }

  test("extract annotations from method parameters") {
    val methods       = Surface.methodsOf[ServiceWithAnnotatedMethods]
    val processMethod = methods.find(_.name == "process").get

    val dataParam = processMethod.args.find(_.name == "data").get
    dataParam.hasAnnotation("label") shouldBe true
    val labelAnnot = dataParam.findAnnotation("label").get
    labelAnnot.get("value") shouldBe Some("input-data")

    val countParam = processMethod.args.find(_.name == "count").get
    countParam.hasAnnotation("range") shouldBe true
    val rangeAnnot = countParam.findAnnotation("range").get
    rangeAnnot.get("min") shouldBe Some(1)
    rangeAnnot.get("max") shouldBe Some(10)
  }

  test("annotation toString format") {
    val annot1 = Annotation("label", "test.label", Map("value" -> "test"))
    annot1.toString shouldBe "@label(value=test)"

    val annot2 = Annotation("marker", "test.marker", Map.empty)
    annot2.toString shouldBe "@marker"
  }

  test("annotation is() method checks both name and fullName") {
    val annot = Annotation("label", "wvlet.uni.surface.label", Map("value" -> "test"))

    annot.is("label") shouldBe true
    annot.is("wvlet.uni.surface.label") shouldBe true
    annot.is("other") shouldBe false
  }

  test("existing @required and @secret annotations should still work") {
    case class LocalModel(
        @required
        id: String,
        @secret
        password: String
    )

    val s   = Surface.of[LocalModel]
    val pid = s.params.find(_.name == "id").get
    val ppw = s.params.find(_.name == "password").get

    pid.isRequired shouldBe true
    ppw.isSecret shouldBe true

    // Also check they appear in annotations list
    pid.hasAnnotation("required") shouldBe true
    ppw.hasAnnotation("secret") shouldBe true
  }

  test("extract class-level annotations") {
    val s = Surface.of[LabeledModel]
    s.annotations.map(_.name).toSet shouldBe Set("label", "marker")
    s.findAnnotation("label").flatMap(_.getAs[String]("value")) shouldBe Some("labeled-model")
    s.hasAnnotation("wvlet.uni.surface.marker") shouldBe true
  }

  test("leave out compiler-internal class annotations") {
    // Top-level classes carry @SourceFile, which holds the source path
    Surface.of[ModelWithAnnotations].annotations shouldBe Seq.empty
    Surface
      .of[LabeledModel]
      .annotations
      .exists(_.fullName.startsWith("scala.annotation.internal")) shouldBe false
  }

  test("aliases forward class-level annotations") {
    Surface.of[LabeledModelAlias].findAnnotation("label").isDefined shouldBe true
  }

end AnnotationTest
