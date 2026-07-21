// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BoundTypeResolutionTest {

  private fun resolve(
    supertypes: List<String>,
    defaults: Map<String, String> = emptyMap(),
  ): BoundTypeResolution<String> = resolveImplicitBoundType(supertypes) { defaults[it] }

  @Test
  fun `no supertypes`() {
    assertThat(resolve(emptyList())).isEqualTo(BoundTypeResolution.NoSupertypes)
  }

  @Test
  fun `sole supertype is used`() {
    assertThat(resolve(listOf("Service"))).isEqualTo(BoundTypeResolution.Resolved("Service"))
  }

  @Test
  fun `multiple supertypes without default binding is unresolvable`() {
    assertThat(resolve(listOf("A", "B")))
      .isEqualTo(BoundTypeResolution.MultipleSupertypes(listOf("A", "B")))
  }

  @Test
  fun `single default binding wins over sole-supertype fallback`() {
    // Factory<T> with @DefaultBinding<Factory<*>> binds as Factory<*>, not Factory<T>.
    assertThat(resolve(listOf("FactoryT"), mapOf("FactoryT" to "FactoryStar")))
      .isEqualTo(BoundTypeResolution.Resolved("FactoryStar"))
  }

  @Test
  fun `default binding wins even with multiple supertypes`() {
    assertThat(resolve(listOf("A", "B"), mapOf("B" to "BDefault")))
      .isEqualTo(BoundTypeResolution.Resolved("BDefault"))
  }

  @Test
  fun `multiple default bindings are ambiguous`() {
    assertThat(resolve(listOf("A", "B"), mapOf("A" to "ADefault", "B" to "BDefault")))
      .isEqualTo(BoundTypeResolution.AmbiguousDefaultBinding(listOf("ADefault", "BDefault")))
  }
}
