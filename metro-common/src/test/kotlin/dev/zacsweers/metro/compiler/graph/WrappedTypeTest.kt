// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.compiler.MetroClassIds
import org.junit.Test

class WrappedTypeTest {
  // https://github.com/ZacSweers/metro/issues/2928
  @Test
  fun `map qualifiers stay on the map inside wrappers`() {
    val map = map(WrappedType.Canonical("String"))
    val qualifiedMap = "@Named(\"one\") Map<String, String>"
    val cases =
      listOf(
        map to qualifiedMap,
        WrappedType.Provider(map, MetroClassIds.provider) to "Provider<$qualifiedMap>",
        WrappedType.Lazy(map, MetroClassIds.lazy) to "Lazy<$qualifiedMap>",
        WrappedType.SuspendProvider(map, MetroClassIds.suspendProvider) to
          "SuspendProvider<$qualifiedMap>",
        WrappedType.SuspendLazy(map, MetroClassIds.suspendLazy) to "SuspendLazy<$qualifiedMap>",
        WrappedType.Provider(WrappedType.Lazy(map, MetroClassIds.lazy), MetroClassIds.provider) to
          "Provider<Lazy<$qualifiedMap>>",
      )

    for ((type, expected) in cases) {
      assertThat(type.render("@Named(\"one\")") { it }).isEqualTo(expected)
    }
  }

  @Test
  fun `qualifying a map preserves its value wrappers`() {
    val value = WrappedType.Provider(WrappedType.Canonical("String"), MetroClassIds.provider)

    assertThat(map(value).render("@MapOne") { it })
      .isEqualTo("@MapOne Map<String, Provider<String>>")
  }

  @Test
  fun `map qualifiers do not qualify nested maps`() {
    val nestedMap = map(map(WrappedType.Canonical("String")))

    assertThat(nestedMap.render("@MapOne") { it })
      .isEqualTo("@MapOne Map<String, Map<String, String>>")
  }

  @Test
  fun `unqualified maps preserve their rendering`() {
    assertThat(map(WrappedType.Canonical("String")).render { it }).isEqualTo("Map<String, String>")
  }

  @Test
  fun `scalar qualifiers stay inside wrappers`() {
    val type =
      WrappedType.Provider(
        WrappedType.Canonical("Set<String>"),
        MetroClassIds.provider,
      )

    assertThat(type.render("@SetOne") { it }).isEqualTo("Provider<@SetOne Set<String>>")
  }

  private fun map(value: WrappedType<String>): WrappedType.Map<String> =
    WrappedType.Map("String", value) { "Map<String, ${value.canonicalType()}>" }
}
