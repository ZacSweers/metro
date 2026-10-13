// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph.identity

import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.compiler.graph.StringTypeKey
import dev.zacsweers.metro.compiler.graph.createMapBindingId
import org.junit.Test

class TypeKeyIdentityTest {
  @Test
  fun `equal identities compare as zero and hash alike`() {
    val left = key(string, named("one"))
    val right = key(string, named("one"))

    assertThat(left).isEqualTo(right)
    assertThat(left.hashCode()).isEqualTo(right.hashCode())
    assertThat(left.compareTo(right)).isEqualTo(0)
  }

  @Test
  fun `qualifiers with the same simple name in different packages stay distinct`() {
    val first = key(string, AnnotationIdentity("first/Key"))
    val second = key(string, AnnotationIdentity("second/Key"))

    assertThat(first).isNotEqualTo(second)
    assertThat(first.compareTo(second)).isNotEqualTo(0)
  }

  @Test
  fun `string punctuation can't merge arguments`() {
    val joined = key(string, named("a\", \"b"))
    val pair =
      key(
        string,
        AnnotationIdentity(
          "test/Named",
          listOf(stringValue("a"), stringValue("b")),
        ),
      )

    assertThat(joined).isNotEqualTo(pair)
    assertThat(joined.encoded).isNotEqualTo(pair.encoded)
  }

  @Test
  fun `constant kinds take part in identity`() {
    val int = AnnotationValueIdentity.Constant(ConstantKind.Int, "1")
    val long = AnnotationValueIdentity.Constant(ConstantKind.Long, "1")
    val intKey = key(string, AnnotationIdentity("test/Id", listOf(int)))
    val longKey = key(string, AnnotationIdentity("test/Id", listOf(long)))

    assertThat(intKey).isNotEqualTo(longKey)
    assertThat(intKey.compareTo(longKey)).isNotEqualTo(0)
  }

  @Test
  fun `array element boundaries take part in identity`() {
    val one = arrayKey(stringValue("a,b"))
    val two = arrayKey(stringValue("a"), stringValue("b"))

    assertThat(one).isNotEqualTo(two)
    assertThat(one.encoded).isNotEqualTo(two.encoded)
  }

  @Test
  fun `names with structural characters are escaped`() {
    val nested = TypeIdentity.Class("test/Outer<A>")
    val generic =
      TypeIdentity.Class(
        "test/Outer",
        listOf(
          TypeArgumentIdentity.Projection(ProjectionVariance.Invariant, TypeIdentity.Class("A")),
        ),
      )

    assertThat(nested).isNotEqualTo(generic)
    assertThat(nested.encoded).isNotEqualTo(generic.encoded)
  }

  @Test
  fun `nullability and variance take part in identity`() {
    val notNull = TypeIdentity.Class("kotlin/String")
    val nullable = TypeIdentity.Class("kotlin/String", nullability = Nullability.Nullable)
    val invariant = list(ProjectionVariance.Invariant)
    val covariant = list(ProjectionVariance.Out)

    assertThat(notNull.encoded).isNotEqualTo(nullable.encoded)
    assertThat(invariant.encoded).isNotEqualTo(covariant.encoded)
  }

  @Test
  fun `ordering is transitive and consistent with equality`() {
    val keys =
      listOf(
        key(string),
        key(string, named("b")),
        key(string, named("a")),
        key(TypeIdentity.Class("kotlin/Int")),
        key(string, AnnotationIdentity("first/Key")),
        key(TypeIdentity.Class("kotlin/String", nullability = Nullability.Nullable)),
      )

    for (a in keys) {
      for (b in keys) {
        assertThat(a.compareTo(b) == 0).isEqualTo(a == b)
        assertThat(Integer.signum(a.compareTo(b))).isEqualTo(-Integer.signum(b.compareTo(a)))
        for (c in keys) {
          if (a < b && b < c) {
            assertThat(a < c).isTrue()
          }
        }
      }
    }
  }

  @Test
  fun `qualified keys sort before unqualified keys`() {
    val sorted = listOf(key(string), key(string, named("a"))).sorted()

    assertThat(sorted.first().qualifier).isNotNull()
  }

  @Test
  fun `java Void class literals match Nothing on the JVM`() {
    val void = classLiteralIdentity(TypeIdentity.Class("java/lang/Void"), isJvm = true)
    val voidOffJvm = classLiteralIdentity(TypeIdentity.Class("java/lang/Void"), isJvm = false)

    assertThat(void).isEqualTo(TypeIdentity.Class("kotlin/Nothing"))
    assertThat(voidOffJvm).isEqualTo(TypeIdentity.Class("java/lang/Void"))
  }

  @Test
  fun `array class literals keep their component only on the JVM`() {
    val stringArray =
      TypeIdentity.Class(
        "kotlin/Array",
        listOf(TypeArgumentIdentity.Projection(ProjectionVariance.Out, string)),
      )
    val intArray =
      TypeIdentity.Class(
        "kotlin/Array",
        listOf(
          TypeArgumentIdentity.Projection(
            ProjectionVariance.Invariant,
            TypeIdentity.Class("kotlin/Int"),
          ),
        ),
      )

    assertThat(classLiteralIdentity(stringArray, isJvm = true))
      .isNotEqualTo(classLiteralIdentity(intArray, isJvm = true))
    assertThat(classLiteralIdentity(stringArray, isJvm = false))
      .isEqualTo(classLiteralIdentity(intArray, isJvm = false))
  }

  @Test
  fun `map binding IDs can't be confused across their parts`() {
    val first = createMapBindingId(TypeIdentity.Class("a/B_c"), StringTypeKey("d/E"))
    val second = createMapBindingId(TypeIdentity.Class("a/B"), StringTypeKey("c_d/E"))

    assertThat(first).isNotEqualTo(second)
  }

  private val string = TypeIdentity.Class("kotlin/String")

  private fun key(type: TypeIdentity, qualifier: AnnotationIdentity? = null) =
    TypeKeyIdentity(type, qualifier)

  private fun named(value: String) = AnnotationIdentity("test/Named", listOf(stringValue(value)))

  private fun stringValue(value: String) =
    AnnotationValueIdentity.Constant(ConstantKind.String, value)

  private fun arrayKey(vararg values: AnnotationValueIdentity) =
    key(
      string,
      AnnotationIdentity("test/Tags", listOf(AnnotationValueIdentity.Array(values.toList()))),
    )

  private fun list(variance: ProjectionVariance) =
    TypeIdentity.Class(
      "kotlin/collections/List",
      listOf(TypeArgumentIdentity.Projection(variance, string)),
    )
}
