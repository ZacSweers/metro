// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SuspendBindingRulesTest {
  private val bindings = mutableMapOf<StringTypeKey, RuleBinding>()
  private val rules =
    SuspendBindingRules<String, StringTypeKey, StringContextualTypeKey, RuleBinding>(
      findBinding = bindings::get,
      bindingCanPassThrough = { binding, request ->
        binding.passThroughRequest == request.render(short = false)
      },
    )

  @Test
  fun `deferred and exact pass-through requests stop propagation`() {
    val canonical = contextKey("Value")
    val provider = contextKey("() -> Value")
    bindings[canonical.typeKey] = RuleBinding(canonical, passThroughRequest = "Value")

    assertThat(rules.stopsPropagation(canonical)).isTrue()
    assertThat(rules.stopsPropagation(provider)).isTrue()
    assertThat(rules.propagates(canonical) { true }).isFalse()
  }

  @Test
  fun `ordinary eager requests propagate suspend requirements`() {
    val request = contextKey("Value")
    bindings[request.typeKey] = RuleBinding(request)

    assertThat(rules.stopsPropagation(request)).isFalse()
    assertThat(rules.propagates(request) { true }).isTrue()
    assertThat(rules.propagates(request) { false }).isFalse()
  }

  @Test
  fun `suspend boundaries and suspend-provider maps are supported`() {
    val suspendProvider = contextKey("suspend () -> Value")
    val suspendProviderMap = contextKey("Map<String, suspend () -> Value>")

    assertThat(rules.isValidBoundary(suspendProvider)).isTrue()
    assertThat(rules.supportsSuspendMultibindingConsumption(false, suspendProviderMap)).isTrue()
    assertThat(rules.supportsSuspendMultibindingConsumption(true, suspendProviderMap)).isFalse()
  }
}

private class RuleBinding(
  override val contextualTypeKey: StringContextualTypeKey,
  val passThroughRequest: String? = null,
) : BaseBinding<String, StringTypeKey, StringContextualTypeKey> {
  override val dependencies: List<StringContextualTypeKey> = emptyList()

  override fun renderLocationDiagnostic(
    short: Boolean,
    shortLocation: Boolean,
    underlineTypeKey: Boolean,
  ): LocationDiagnostic = LocationDiagnostic(typeKey.type, null)

  override fun renderDescriptionDiagnostic(short: Boolean, underlineTypeKey: Boolean): String {
    return typeKey.type
  }
}

private fun contextKey(type: String): StringContextualTypeKey {
  return StringContextualTypeKey.create(StringTypeKey(type))
}
