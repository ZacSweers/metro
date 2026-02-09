// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CompilerVersionAliasesTest {

  @Test
  fun `no alias returns original version`() {
    val version = KotlinToolingVersion("2.3.0")
    val resolved = CompilerVersionAliases.map(version)
    assertThat(resolved).isEqualTo(version)
  }

  @Test
  fun `user alias takes priority over built-in`() {
    val version = KotlinToolingVersion("2.3.255-dev-255")
    val userAliases = mapOf("2.3.255-dev-255" to "2.3.20-Beta2")
    val resolved = CompilerVersionAliases.map(version, userAliases)
    assertThat(resolved).isEqualTo(KotlinToolingVersion("2.3.20-Beta2"))
  }

  @Test
  fun `alias resolution returns correct KotlinToolingVersion`() {
    val version = KotlinToolingVersion("1.0.255-dev-255")
    val userAliases = mapOf("1.0.255-dev-255" to "2.3.0")
    val resolved = CompilerVersionAliases.map(version, userAliases)!!
    assertThat(resolved.major).isEqualTo(2)
    assertThat(resolved.minor).isEqualTo(3)
    assertThat(resolved.patch).isEqualTo(0)
    assertThat(resolved.classifier).isNull()
  }

  @Test
  fun `unmatched alias returns original version unchanged`() {
    val version = KotlinToolingVersion("2.3.0")
    val userAliases = mapOf("2.3.255-dev-255" to "2.3.20-Beta2")
    val resolved = CompilerVersionAliases.map(version, userAliases)
    assertThat(resolved).isEqualTo(version)
  }

  @Test
  fun `aliased version does not have patch 255`() {
    val version = KotlinToolingVersion("2.3.255-dev-255")
    val userAliases = mapOf("2.3.255-dev-255" to "2.3.20-Beta2")
    val resolved = CompilerVersionAliases.map(version, userAliases)!!
    assertThat(resolved.patch).isNotEqualTo(255)
  }

  @Test
  fun `CLI_ONLY alias returns null`() {
    val version = KotlinToolingVersion("2.2.255-dev-255")
    val resolved = CompilerVersionAliases.map(version)
    assertThat(resolved).isNull()
  }

  @Test
  fun `user alias overrides built-in CLI_ONLY`() {
    val version = KotlinToolingVersion("2.2.255-dev-255")
    val userAliases = mapOf("2.2.255-dev-255" to "2.2.20-dev-5810")
    val resolved = CompilerVersionAliases.map(version, userAliases)
    assertThat(resolved).isEqualTo(KotlinToolingVersion("2.2.20-dev-5810"))
  }

  @Test
  fun `user-provided CLI_ONLY also returns null`() {
    val version = KotlinToolingVersion("1.9.0-custom")
    val userAliases = mapOf("1.9.0-custom" to "CLI_ONLY")
    val resolved = CompilerVersionAliases.map(version, userAliases)
    assertThat(resolved).isNull()
  }
}
