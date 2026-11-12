// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.compiler.graph.identity.Nullability
import dev.zacsweers.metro.compiler.graph.identity.ProjectionVariance
import dev.zacsweers.metro.compiler.graph.identity.TypeArgumentIdentity
import dev.zacsweers.metro.compiler.graph.identity.TypeIdentity
import org.junit.Test

class DerivedBindingMatchingTest {
  // Matching

  @Test
  fun `a bare variable binds the whole target with an empty path`() {
    val bindings = match(t, encoder(string))

    assertThat(bindings).containsExactly(t, binding())
  }

  @Test
  fun `equal non-variable types match with no bindings`() {
    assertThat(match(encoder(string), encoder(string))).isEmpty()
  }

  @Test
  fun `different class IDs don't match`() {
    assertThat(match(encoder(t), list(string))).isNull()
    assertThat(match(encoder(string), encoder(int))).isNull()
  }

  @Test
  fun `different class nullability doesn't match`() {
    assertThat(match(encoder(t), encoder(string).nullable())).isNull()
    assertThat(match(encoder(t).nullable(), encoder(string))).isNull()
  }

  @Test
  fun `different argument counts don't match`() {
    val oneArgument = TypeIdentity.Class("test/Box", listOf(invariant(t)))
    val twoArguments = TypeIdentity.Class("test/Box", listOf(invariant(string), invariant(int)))

    assertThat(match(oneArgument, twoArguments)).isNull()
  }

  @Test
  fun `a variable inside a class records its argument path`() {
    assertThat(match(encoder(t), encoder(string))).containsExactly(t, binding(0))
  }

  @Test
  fun `nested paths follow argument positions`() {
    val pattern = encoder(map(string, list(t)))
    val target = encoder(map(string, list(int)))

    assertThat(match(pattern, target)).containsExactly(t, binding(0, 1, 0))
  }

  @Test
  fun `a plain variable binds a nullable target as is`() {
    assertThat(match(encoder(t), encoder(string.nullable()))).containsExactly(t, binding(0))
  }

  @Test
  fun `a nullable variable binds the non-null form of a nullable target`() {
    val bindings = match(encoder(t.nullable()), encoder(string.nullable()))

    assertThat(bindings).containsExactly(t, binding(0, stripsNullability = true))
  }

  @Test
  fun `a nullable variable binds a nullable type parameter target`() {
    val target = encoder(u.nullable())

    assertThat(match(encoder(t.nullable()), target))
      .containsExactly(t, binding(0, stripsNullability = true))
  }

  @Test
  fun `a nullable variable doesn't match a non-null target`() {
    assertThat(match(encoder(t.nullable()), encoder(string))).isNull()
    assertThat(match(encoder(t.nullable()), encoder(u))).isNull()
  }

  @Test
  fun `a definitely non-null variable binds a non-null class as is`() {
    val bindings = match(encoder(t.definitelyNotNull()), encoder(list(string)))

    assertThat(bindings).containsExactly(t, binding(0))
  }

  @Test
  fun `a definitely non-null variable doesn't match a nullable class`() {
    assertThat(match(encoder(t.definitelyNotNull()), encoder(string.nullable()))).isNull()
  }

  @Test
  fun `a definitely non-null variable only matches a definitely non-null type parameter`() {
    val pattern = encoder(t.definitelyNotNull())

    assertThat(match(pattern, encoder(u))).isNull()
    assertThat(match(pattern, encoder(u.nullable()))).isNull()
    assertThat(match(pattern, encoder(u.definitelyNotNull()))).containsExactly(t, binding(0))
  }

  @Test
  fun `a definitely non-null variable doesn't match flexible types`() {
    val flexible = TypeIdentity.Flexible(string, string.nullable())

    assertThat(match(encoder(t.definitelyNotNull()), encoder(flexible))).isNull()
  }

  @Test
  fun `repeated variables that agree keep the first binding`() {
    val bindings = match(pair(t, t), pair(string, string))

    assertThat(bindings).containsExactly(t, binding(0))
  }

  @Test
  fun `repeated variables that disagree don't match`() {
    assertThat(match(pair(t, t), pair(string, int))).isNull()
  }

  @Test
  fun `repeated variables compare after stripping nullability`() {
    val pattern = pair(t, t.nullable())

    assertThat(match(pattern, pair(string, string.nullable()))).containsExactly(t, binding(0))
    assertThat(match(pattern, pair(string.nullable(), string.nullable()))).isNull()
  }

  @Test
  fun `nullable and plain occurrences share one key`() {
    val bindings = match(pair(t.nullable(), t), pair(string.nullable(), string))

    assertThat(bindings).containsExactly(t, binding(0, stripsNullability = true))
  }

  @Test
  fun `several variables each get their own binding`() {
    val bindings = match(map(t, list(v)), map(string, list(int)))

    assertThat(bindings).containsExactly(t, binding(0), v, binding(1, 0))
  }

  @Test
  fun `a variable never binds a star`() {
    val target = TypeIdentity.Class("test/Encoder", listOf(TypeArgumentIdentity.Star))

    assertThat(match(encoder(t), target)).isNull()
  }

  @Test
  fun `a star in the pattern only matches a star`() {
    val pattern = TypeIdentity.Class("test/Encoder", listOf(TypeArgumentIdentity.Star))

    assertThat(match(pattern, pattern)).isEmpty()
    assertThat(match(pattern, encoder(string))).isNull()
  }

  @Test
  fun `projection variance must match at variable positions`() {
    val outPattern = encoder(t, ProjectionVariance.Out)

    assertThat(match(outPattern, encoder(string, ProjectionVariance.Out)))
      .containsExactly(t, binding(0))
    assertThat(match(outPattern, encoder(string))).isNull()
    assertThat(match(encoder(t), encoder(string, ProjectionVariance.In))).isNull()
  }

  @Test
  fun `projection variance must match at constant positions`() {
    val pattern = encoder(list(string), ProjectionVariance.Out)

    assertThat(match(pattern, encoder(list(string), ProjectionVariance.In))).isNull()
  }

  @Test
  fun `a non-variable type parameter must equal the target`() {
    val constant = { _: TypeIdentity.TypeParameter -> false }

    assertThat(matchTypePattern(encoder(t), encoder(t), constant)).isEmpty()
    assertThat(matchTypePattern(encoder(t), encoder(string), constant)).isNull()
    assertThat(matchTypePattern(encoder(t), encoder(t.nullable()), constant)).isNull()
  }

  @Test
  fun `only the variables the caller names bind`() {
    val onlyT = { parameter: TypeIdentity.TypeParameter -> parameter.name == "T" }

    assertThat(matchTypePattern(pair(t, u), pair(string, u), onlyT)).containsExactly(t, binding(0))
    assertThat(matchTypePattern(pair(t, u), pair(string, int), onlyT)).isNull()
  }

  @Test
  fun `flexible patterns only match by equality`() {
    val flexible = TypeIdentity.Flexible(string, string.nullable())

    assertThat(match(encoder(flexible), encoder(flexible))).isEmpty()
    assertThat(match(encoder(flexible), encoder(string))).isNull()
  }

  @Test
  fun `intersection and unresolved patterns only match by equality`() {
    val intersection = TypeIdentity.Intersection(listOf(string, int))
    val unresolved = TypeIdentity.Unresolved("Missing")

    assertThat(match(intersection, intersection)).isEmpty()
    assertThat(match(intersection, string)).isNull()
    assertThat(match(unresolved, unresolved)).isEmpty()
    assertThat(match(unresolved, TypeIdentity.Unresolved("Other"))).isNull()
  }

  // Specificity

  @Test
  fun `no candidates select none`() {
    assertThat(select()).isEqualTo(DerivedCandidateSelection.None)
  }

  @Test
  fun `one candidate is selected alone`() {
    val element = Provider("element", encoder(param("element")))

    assertThat(select(element)).isEqualTo(DerivedCandidateSelection.Single(element))
  }

  @Test
  fun `a more specific pattern wins`() {
    val element = Provider("element", encoder(param("element")))
    val list = Provider("list", encoder(list(param("list"))))

    assertThat(select(element, list))
      .isEqualTo(DerivedCandidateSelection.MostSpecific(list, listOf(element)))
  }

  @Test
  fun `alpha-equivalent patterns are ambiguous`() {
    val first = Provider("first", encoder(param("first")))
    val second = Provider("second", encoder(param("second")))

    assertThat(select(first, second))
      .isEqualTo(DerivedCandidateSelection.Ambiguous(listOf(first, second)))
  }

  @Test
  fun `crossing patterns are ambiguous`() {
    val left = Provider("left", pair(param("left"), string))
    val right = Provider("right", pair(string, param("right")))

    assertThat(select(left, right))
      .isEqualTo(DerivedCandidateSelection.Ambiguous(listOf(left, right)))
  }

  @Test
  fun `the most specific of a chain wins and others keep their order`() {
    val element = Provider("element", encoder(param("element")))
    val nested = Provider("nested", encoder(list(list(param("nested")))))
    val list = Provider("list", encoder(list(param("list"))))

    assertThat(select(element, nested, list))
      .isEqualTo(DerivedCandidateSelection.MostSpecific(nested, listOf(element, list)))
  }

  @Test
  fun `a tie at the top of a chain is ambiguous`() {
    val element = Provider("element", encoder(param("element")))
    val first = Provider("first", encoder(list(param("first"))))
    val second = Provider("second", encoder(list(param("second"))))

    assertThat(select(element, first, second))
      .isEqualTo(DerivedCandidateSelection.Ambiguous(listOf(element, first, second)))
  }

  // Divergence

  @Test
  fun `different class sets don't diverge`() {
    val earlier = listOf(encoder(list(user)))

    assertThat(isDivergentDerivation(encoder(list(list(string))), earlier)).isFalse()
  }

  @Test
  fun `a bigger key with the same class set diverges`() {
    val earlier = listOf(encoder(string), encoder(list(string)))

    assertThat(isDivergentDerivation(encoder(list(list(string))), earlier)).isTrue()
  }

  @Test
  fun `an equal-size key with the same class set doesn't diverge`() {
    val earlier = listOf(pair(string, int))

    assertThat(isDivergentDerivation(pair(int, string), earlier)).isFalse()
  }

  @Test
  fun `no earlier keys never diverge`() {
    assertThat(isDivergentDerivation(encoder(list(list(string))), emptyList())).isFalse()
  }

  @Test
  fun `class IDs cover every nested class`() {
    assertThat(derivationClassIds(encoder(map(string, list(t)))))
      .containsExactly("test/Encoder", "kotlin/collections/Map", "kotlin/String", LIST)
  }

  @Test
  fun `node counts cover classes, type parameters, and stars`() {
    val withStar = TypeIdentity.Class("test/Box", listOf(TypeArgumentIdentity.Star, invariant(t)))

    assertThat(derivationNodeCount(string)).isEqualTo(1)
    assertThat(derivationNodeCount(encoder(list(string)))).isEqualTo(3)
    assertThat(derivationNodeCount(withStar)).isEqualTo(3)
    assertThat(derivationNodeCount(list(string.nullable()))).isEqualTo(2)
  }

  // Helpers

  /** A generic provider whose own type parameters have [ownerId] as their owner. */
  private data class Provider(val ownerId: String, val pattern: TypeIdentity)

  private fun select(vararg candidates: Provider): DerivedCandidateSelection<Provider> =
    selectMostSpecificCandidate(
      candidates.toList(),
      patternOf = { it.pattern },
      isVariableOf = { candidate, parameter -> parameter.ownerId == candidate.ownerId },
    )

  private fun param(ownerId: String) = TypeIdentity.TypeParameter(ownerId, 0, "T")

  /** Matches with every type parameter treated as a variable. */
  private fun match(pattern: TypeIdentity, target: TypeIdentity) =
    matchTypePattern(pattern, target) { true }

  private fun binding(vararg path: Int, stripsNullability: Boolean = false) =
    TypeVariableBinding(path.toList(), stripsNullability)

  private fun invariant(type: TypeIdentity) =
    TypeArgumentIdentity.Projection(ProjectionVariance.Invariant, type)

  private fun encoder(
    argument: TypeIdentity,
    variance: ProjectionVariance = ProjectionVariance.Invariant,
  ) =
    TypeIdentity.Class("test/Encoder", listOf(TypeArgumentIdentity.Projection(variance, argument)))

  private fun list(element: TypeIdentity) = TypeIdentity.Class(LIST, listOf(invariant(element)))

  private fun map(key: TypeIdentity, value: TypeIdentity) =
    TypeIdentity.Class("kotlin/collections/Map", listOf(invariant(key), invariant(value)))

  private fun pair(first: TypeIdentity, second: TypeIdentity) =
    TypeIdentity.Class("kotlin/Pair", listOf(invariant(first), invariant(second)))

  private fun TypeIdentity.nullable(): TypeIdentity = withNullability(Nullability.Nullable)

  private fun TypeIdentity.definitelyNotNull(): TypeIdentity =
    withNullability(Nullability.DefinitelyNotNull)

  private fun TypeIdentity.withNullability(nullability: Nullability): TypeIdentity =
    when (this) {
      is TypeIdentity.Class -> copy(nullability = nullability)
      is TypeIdentity.TypeParameter -> copy(nullability = nullability)
      else -> error("Unsupported type $this")
    }

  private companion object {
    const val LIST = "kotlin/collections/List"
    val string = TypeIdentity.Class("kotlin/String")
    val int = TypeIdentity.Class("kotlin/Int")
    val user = TypeIdentity.Class("test/User")
    val t = TypeIdentity.TypeParameter("test/provider", 0, "T")
    val v = TypeIdentity.TypeParameter("test/provider", 1, "V")

    /** A type parameter from outside the provider, like a graph class's own parameter. */
    val u = TypeIdentity.TypeParameter("test/Graph", 0, "U")
  }
}
