// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import androidx.collection.MutableScatterMap
import com.google.common.truth.Truth.assertThat
import kotlin.test.assertFailsWith
import org.junit.Test

class BindingGraphValidatorTest {
  @Test
  fun `reports bindings with an incompatible scope`() {
    val binding = validationBinding("Scoped", scope = "BindingScope")
    val validator =
      validator(
        bindings = bindingsOf(binding),
        graphScopes = setOf("GraphScope"),
        scopeOf = { it.scope },
      )

    val issue = validator.validateAll(binding).single()

    assertThat(issue).isEqualTo(GraphValidationIssue.IncompatibleScope(binding, "BindingScope"))
  }

  @Test
  fun `accepts bindings with a matching scope`() {
    val binding = validationBinding("Scoped", scope = "SharedScope")
    val validator =
      validator(
        bindings = bindingsOf(binding),
        graphScopes = setOf("SharedScope"),
        scopeOf = { it.scope },
      )

    assertThat(validator.validateAll(binding)).isEmpty()
  }

  @Test
  fun `reports a forbidden empty multibinding`() {
    val multibinding = validationBinding("Set<String>")
    val validator =
      validator(
        bindings = bindingsOf(multibinding),
        multibindingKindOf = { candidate ->
          MultibindingKind.SET.takeIf { candidate == multibinding }
        },
      )

    assertThat(validator.validateAll(multibinding))
      .containsExactly(GraphValidationIssue.EmptyMultibinding(multibinding))
  }

  @Test
  fun `allows an explicitly empty multibinding`() {
    val multibinding = validationBinding("Set<String>")
    val validator =
      validator(
        bindings = bindingsOf(multibinding),
        multibindingKindOf = { MultibindingKind.SET },
        multibindingAllowsEmpty = { true },
      )

    assertThat(validator.validateAll(multibinding)).isEmpty()
  }

  @Test
  fun `does not traverse set contribution keys`() {
    val multibinding = validationBinding("Set<Value>")
    val sourceKeys =
      object : AbstractCollection<StringTypeKey>() {
        override val size: Int = 2

        override fun iterator(): Iterator<StringTypeKey> {
          error("Set multibindings must not iterate contribution keys")
        }
      }
    val validator =
      validator(
        bindings = bindingsOf(multibinding),
        multibindingKindOf = { MultibindingKind.SET },
        multibindingSourceKeys = { sourceKeys },
      )

    assertThat(validator.validateAll(multibinding)).isEmpty()
  }

  @Test
  fun `reports duplicate map keys with their contributions`() {
    val first = validationBinding("FirstContribution")
    val second = validationBinding("SecondContribution")
    val multibinding = validationBinding("Map<String, Value>")
    val bindings = bindingsOf(first, second, multibinding)
    val validator =
      validator(
        bindings = bindings,
        multibindingKindOf = { candidate ->
          MultibindingKind.MAP.takeIf { candidate == multibinding }
        },
        multibindingSourceKeys = { listOf(first.typeKey, second.typeKey) },
        isMapContribution = { it == first || it == second },
        mapKeyOf = { "same-key" },
      )

    assertThat(validator.validateAll(multibinding))
      .containsExactly(
        GraphValidationIssue.DuplicateMapKey(
          multibinding = multibinding,
          mapKey = "same-key",
          contributions = listOf(first, second),
        )
      )
  }

  @Test
  fun `map validation checks cancellation after work has started`() {
    val contributions = List(300) { index -> validationBinding("Contribution$index") }
    val multibinding = validationBinding("Map<String, Value>")
    val validator =
      validator(
        bindings = bindingsOf(*(contributions + multibinding).toTypedArray()),
        multibindingKindOf = { candidate ->
          MultibindingKind.MAP.takeIf { candidate == multibinding }
        },
        multibindingSourceKeys = { contributions.map { it.typeKey } },
        isMapContribution = { true },
      )
    var checks = 0

    assertFailsWith<ValidationCancellationException> {
      validator.validate(
        multibinding,
        ensureActive = {
          if (++checks == 2) throw ValidationCancellationException()
        },
      ) {}
    }

    assertThat(checks).isEqualTo(2)
  }

  @Test
  fun `keeps null map keys distinct from bindings without map contributions`() {
    val first = validationBinding("FirstContribution")
    val second = validationBinding("SecondContribution")
    val unrelated = validationBinding("UnrelatedBinding")
    val multibinding = validationBinding("Map<String, Value>")
    val validator =
      validator(
        bindings = bindingsOf(first, second, unrelated, multibinding),
        multibindingKindOf = { candidate ->
          MultibindingKind.MAP.takeIf { candidate == multibinding }
        },
        multibindingSourceKeys = { listOf(first.typeKey, second.typeKey, unrelated.typeKey) },
        isMapContribution = { it == first || it == second },
        mapKeyOf = { null },
      )

    assertThat(validator.validateAll(multibinding))
      .containsExactly(
        GraphValidationIssue.DuplicateMapKey(
          multibinding = multibinding,
          mapKey = null,
          contributions = listOf(first, second),
        )
      )
  }

  @Test
  fun `does not inspect multibinding details for ordinary bindings`() {
    val binding = validationBinding("Ordinary")
    val validator =
      validator(
        bindings = bindingsOf(binding),
        multibindingAllowsEmpty = { error("Ordinary bindings have no multibinding metadata") },
        multibindingSourceKeys = { error("Ordinary bindings have no contribution keys") },
        isMapContribution = { error("Ordinary bindings are not map contributions") },
        mapKeyOf = { error("Ordinary bindings have no map keys") },
      )

    assertThat(validator.validateAll(binding)).isEmpty()
  }

  @Test
  fun `reports an assisted target requested as a graph root`() {
    val target = validationBinding("Target")
    val validator =
      validator(
        bindings = bindingsOf(target),
        rootKeys = setOf(target.typeKey),
        assistedKindOf = { AssistedBindingKind.TARGET },
      )

    assertThat(validator.validateAll(target))
      .containsExactly(
        GraphValidationIssue.InvalidAssistedInjection(
          binding = target,
          requestingBinding = null,
        )
      )
  }

  @Test
  fun `reports an assisted target requested by a regular binding`() {
    val target = validationBinding("Target")
    val consumer = validationBinding("Consumer")
    val validator =
      validator(
        bindings = bindingsOf(target, consumer),
        reverseAdjacency = mapOf(target.typeKey to setOf(consumer.typeKey)),
        assistedKindOf = { binding -> AssistedBindingKind.TARGET.takeIf { binding == target } },
      )

    assertThat(validator.validateAll(target))
      .containsExactly(
        GraphValidationIssue.InvalidAssistedInjection(
          binding = target,
          requestingBinding = consumer,
        )
      )
  }

  @Test
  fun `allows an assisted factory to use its target`() {
    val target = validationBinding("Target")
    val factory = validationBinding("Factory")
    val validator =
      validator(
        bindings = bindingsOf(target, factory),
        reverseAdjacency = mapOf(target.typeKey to setOf(factory.typeKey)),
        assistedKindOf = { binding ->
          when (binding) {
            target -> AssistedBindingKind.TARGET
            factory -> AssistedBindingKind.FACTORY
            else -> null
          }
        },
      )

    assertThat(validator.validateAll(target)).isEmpty()
  }
}

private typealias StringBindingGraphValidator =
  BindingGraphValidator<
    String,
    StringTypeKey,
    StringContextualTypeKey,
    StringBinding,
    String,
    String,
  >

private class ValidationCancellationException : RuntimeException()

private fun StringBindingGraphValidator.validateAll(
  binding: StringBinding
): List<GraphValidationIssue<StringBinding, String, String>> = buildList {
  validate(binding) { add(it) }
}

private fun validator(
  bindings: MutableScatterMap<StringTypeKey, StringBinding>,
  graphScopes: Set<String> = emptySet(),
  rootKeys: Set<StringTypeKey> = emptySet(),
  reverseAdjacency: Map<StringTypeKey, Set<StringTypeKey>> = emptyMap(),
  scopeOf: (StringBinding) -> String? = { null },
  assistedKindOf: (StringBinding) -> AssistedBindingKind? = { null },
  multibindingKindOf: (StringBinding) -> MultibindingKind? = { null },
  multibindingAllowsEmpty: (StringBinding) -> Boolean = { false },
  multibindingSourceKeys: (StringBinding) -> Collection<StringTypeKey> = { emptyList() },
  isMapContribution: (StringBinding) -> Boolean = { false },
  mapKeyOf: (StringBinding) -> String? = { null },
): StringBindingGraphValidator =
  BindingGraphValidator(
    bindings = bindings,
    graphScopes = graphScopes,
    scopeOf = scopeOf,
    assistedKindOf = assistedKindOf,
    multibindingKindOf = multibindingKindOf,
    multibindingAllowsEmpty = multibindingAllowsEmpty,
    multibindingSourceKeys = multibindingSourceKeys,
    isMapContribution = isMapContribution,
    mapKeyOf = mapKeyOf,
    rootKeys = rootKeys,
    reverseAdjacency = reverseAdjacency,
  )

private fun validationBinding(type: String, scope: String? = null): StringBinding =
  StringBinding(StringContextualTypeKey.create(StringTypeKey(type)), scope = scope)

private fun bindingsOf(
  vararg bindings: StringBinding
): MutableScatterMap<StringTypeKey, StringBinding> =
  MutableScatterMap<StringTypeKey, StringBinding>().apply {
    for (binding in bindings) {
      put(binding.typeKey, binding)
    }
  }
