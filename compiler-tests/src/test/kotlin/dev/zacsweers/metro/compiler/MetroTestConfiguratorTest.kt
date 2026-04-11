// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MetroTestConfiguratorTest {

  private fun shouldSkip(
    compilerVersion: KotlinVersion,
    targetVersion: String? = null,
    minVersion: String? = null,
    maxVersion: String? = null,
  ): Boolean =
    MetroTestConfigurator.shouldSkipForCompilerVersion(
      compilerVersion = compilerVersion,
      targetVersion = targetVersion,
      minVersion = minVersion,
      maxVersion = maxVersion,
    )

  @Test
  fun `no directives - never skips`() {
    assertFalse(shouldSkip(KotlinVersion(2, 4, 0)))
  }

  @Test
  fun `target version matches major and minor`() {
    assertFalse(shouldSkip(KotlinVersion(2, 4, 0), targetVersion = "2.4"))
  }

  @Test
  fun `target version matches exact`() {
    assertFalse(shouldSkip(KotlinVersion(2, 4, 0), targetVersion = "2.4.0"))
  }

  @Test
  fun `target version mismatches minor`() {
    assertTrue(shouldSkip(KotlinVersion(2, 3, 0), targetVersion = "2.4"))
  }

  @Test
  fun `target version mismatches patch when fully specified`() {
    assertTrue(shouldSkip(KotlinVersion(2, 4, 0), targetVersion = "2.4.20"))
  }

  @Test
  fun `target version ignores patch when only major and minor specified`() {
    assertFalse(shouldSkip(KotlinVersion(2, 4, 20), targetVersion = "2.4"))
  }

  @Test
  fun `target version supersedes min and max`() {
    // Target matches, so min/max should be ignored even though they would skip
    assertFalse(
      shouldSkip(
        KotlinVersion(2, 4, 0),
        targetVersion = "2.4",
        minVersion = "2.5",
        maxVersion = "2.3",
      )
    )
  }

  @Test
  fun `target version mismatch supersedes min and max`() {
    // Target doesn't match, so skip even though min/max would allow
    assertTrue(
      shouldSkip(
        KotlinVersion(2, 3, 0),
        targetVersion = "2.4",
        minVersion = "2.2",
        maxVersion = "2.5",
      )
    )
  }

  @Test
  fun `min version - at minimum`() {
    assertFalse(shouldSkip(KotlinVersion(2, 4, 0), minVersion = "2.4"))
  }

  @Test
  fun `min version - above minimum`() {
    assertFalse(shouldSkip(KotlinVersion(2, 5, 0), minVersion = "2.4"))
  }

  @Test
  fun `min version - below minimum`() {
    assertTrue(shouldSkip(KotlinVersion(2, 3, 0), minVersion = "2.4"))
  }

  @Test
  fun `min version - dev build at minimum is included`() {
    // 2.4.0-dev-1234 becomes KotlinVersion(2, 4, 0), same as parsing "2.4"
    assertFalse(shouldSkip(KotlinVersion(2, 4, 0), minVersion = "2.4"))
  }

  @Test
  fun `min version - exact patch at minimum`() {
    assertFalse(shouldSkip(KotlinVersion(2, 4, 20), minVersion = "2.4.20"))
  }

  @Test
  fun `min version - below exact patch minimum`() {
    assertTrue(shouldSkip(KotlinVersion(2, 4, 0), minVersion = "2.4.20"))
  }

  @Test
  fun `max version - at maximum`() {
    assertFalse(shouldSkip(KotlinVersion(2, 4, 0), maxVersion = "2.4"))
  }

  @Test
  fun `max version - below maximum`() {
    assertFalse(shouldSkip(KotlinVersion(2, 3, 0), maxVersion = "2.4"))
  }

  @Test
  fun `max version - above maximum`() {
    assertTrue(shouldSkip(KotlinVersion(2, 5, 0), maxVersion = "2.4"))
  }

  @Test
  fun `max version - dev build at maximum is included`() {
    assertFalse(shouldSkip(KotlinVersion(2, 4, 0), maxVersion = "2.4"))
  }

  @Test
  fun `max version - exact patch at maximum`() {
    assertFalse(shouldSkip(KotlinVersion(2, 4, 20), maxVersion = "2.4.20"))
  }

  @Test
  fun `max version - above exact patch maximum`() {
    assertTrue(shouldSkip(KotlinVersion(2, 4, 21), maxVersion = "2.4.20"))
  }

  @Test
  fun `range - within range`() {
    assertFalse(shouldSkip(KotlinVersion(2, 4, 0), minVersion = "2.3", maxVersion = "2.5"))
  }

  @Test
  fun `range - at lower bound`() {
    assertFalse(shouldSkip(KotlinVersion(2, 3, 0), minVersion = "2.3", maxVersion = "2.5"))
  }

  @Test
  fun `range - at upper bound`() {
    assertFalse(shouldSkip(KotlinVersion(2, 5, 0), minVersion = "2.3", maxVersion = "2.5"))
  }

  @Test
  fun `range - below lower bound`() {
    assertTrue(shouldSkip(KotlinVersion(2, 2, 0), minVersion = "2.3", maxVersion = "2.5"))
  }

  @Test
  fun `range - above upper bound`() {
    assertTrue(shouldSkip(KotlinVersion(2, 6, 0), minVersion = "2.3", maxVersion = "2.5"))
  }
}
