// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import androidx.collection.MutableScatterMap
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BindingGraphValidatorTest {
  @Test
  fun `reports bindings with an incompatible scope`() {
    val binding = validationBinding("Scoped", scope = "BindingScope")
    val validator =
      validator(
        bindings = bindingsOf(binding),
        graphScopes = setOf("GraphScope"),
      ) { candidate ->
        BindingValidationMetadata(scope = candidate.scope)
      }

    val issue = validator.validate(binding).single()

    assertThat(issue)
      .isEqualTo(BindingGraphValidationIssue.IncompatibleScope(binding, "BindingScope"))
  }

  @Test
  fun `accepts bindings with a matching scope`() {
    val binding = validationBinding("Scoped", scope = "SharedScope")
    val validator =
      validator(
        bindings = bindingsOf(binding),
        graphScopes = setOf("SharedScope"),
      ) { candidate ->
        BindingValidationMetadata(scope = candidate.scope)
      }

    assertThat(validator.validate(binding)).isEmpty()
  }

  @Test
  fun `reports a forbidden empty multibinding`() {
    val multibinding = validationBinding("Set<String>")
    val validator =
      validator(bindingsOf(multibinding)) { candidate ->
        BindingValidationMetadata(
          multibinding =
            candidate
              .takeIf { it == multibinding }
              ?.let {
                MultibindingValidationMetadata(
                  kind = MultibindingKind.SET,
                  allowEmpty = false,
                  sourceBindings = emptyList(),
                )
              }
        )
      }

    assertThat(validator.validate(multibinding))
      .containsExactly(BindingGraphValidationIssue.EmptyMultibinding(multibinding))
  }

  @Test
  fun `allows an explicitly empty multibinding`() {
    val multibinding = validationBinding("Set<String>")
    val validator =
      validator(bindingsOf(multibinding)) {
        BindingValidationMetadata(
          multibinding =
            MultibindingValidationMetadata(
              kind = MultibindingKind.SET,
              allowEmpty = true,
              sourceBindings = emptyList(),
            )
        )
      }

    assertThat(validator.validate(multibinding)).isEmpty()
  }

  @Test
  fun `reports duplicate map keys with their contributions`() {
    val first = validationBinding("FirstContribution")
    val second = validationBinding("SecondContribution")
    val multibinding = validationBinding("Map<String, Value>")
    val bindings = bindingsOf(first, second, multibinding)
    val validator =
      validator(bindings) { binding ->
        when (binding) {
          multibinding ->
            BindingValidationMetadata(
              multibinding =
                MultibindingValidationMetadata(
                  kind = MultibindingKind.MAP,
                  allowEmpty = false,
                  sourceBindings = listOf(first.typeKey, second.typeKey),
                )
            )
          first,
          second ->
            BindingValidationMetadata(
              mapContribution = MapContributionValidationMetadata("same-key")
            )
          else -> error("Unexpected binding: $binding")
        }
      }

    assertThat(validator.validate(multibinding))
      .containsExactly(
        BindingGraphValidationIssue.DuplicateMapKey(
          multibinding = multibinding,
          mapKey = "same-key",
          contributions = listOf(first, second),
        )
      )
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

private fun validator(
  bindings: MutableScatterMap<StringTypeKey, StringBinding>,
  graphScopes: Set<String> = emptySet(),
  metadata: (StringBinding) -> BindingValidationMetadata<StringTypeKey, String, String>,
): StringBindingGraphValidator = BindingGraphValidator(bindings, graphScopes, metadata)

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
