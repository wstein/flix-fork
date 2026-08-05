/*
 * Copyright 2026 Werner Stein
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ca.uwaterloo.flix.language.ast

import org.scalatest.funsuite.AnyFunSuite

class TestSimpleType extends AnyFunSuite {

  test("a Java type is identified by its class, not by its type arguments") {
    // `SimpleType` decides representation, and a Java class is one class however it was applied.
    // The compiler reaches the same one down paths that box or erase the arguments differently --
    // `ArrayList[Bool]` and `ArrayList[Object]` both arise for a single expression -- so counting
    // the arguments as identity makes those paths disagree about a type they agree about, and the
    // type verifier rejects the program. The arguments exist only to describe an exported
    // boundary; see `ExportPlan.GenericNative`.
    val ofBool = SimpleType.Native(classOf[java.util.ArrayList[?]], List(SimpleType.Bool))
    val ofObject = SimpleType.Native(classOf[java.util.ArrayList[?]], List(SimpleType.Object))
    val raw = SimpleType.Native(classOf[java.util.ArrayList[?]], Nil)

    assertResult(ofObject)(ofBool)
    assertResult(raw)(ofBool)
    assertResult(ofObject.hashCode())(ofBool.hashCode())
    assertResult(raw.hashCode())(ofBool.hashCode())
    assertResult(1)(Set(ofBool, ofObject, raw).size)
  }

  test("a different Java class is a different type") {
    val list = SimpleType.Native(classOf[java.util.ArrayList[?]], Nil)
    val map = SimpleType.Native(classOf[java.util.HashMap[?, ?]], Nil)
    assert(list != map)
  }

  test("the type arguments survive even though they do not affect identity") {
    // Equality ignores them; the boundary still has to be able to read them back.
    val tpe = SimpleType.Native(classOf[java.util.ArrayList[?]], List(SimpleType.String))
    assertResult(List(SimpleType.String))(tpe.targs)
  }
}
