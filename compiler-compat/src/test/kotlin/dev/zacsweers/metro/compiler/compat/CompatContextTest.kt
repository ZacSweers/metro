// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CompatContextTest {

  /** A fake factory for testing that reports a fixed currentVersion and minVersion. */
  private class FakeFactory(
    override val minVersion: String,
    private val reportedCurrentVersion: String,
  ) : CompatContext.Factory {
    override val currentVersion: String
      get() = reportedCurrentVersion

    override fun create(): CompatContext {
      error("Not implemented for tests")
    }
  }

  // Regression test for https://github.com/ZacSweers/metro/issues/1544
  @Test
  fun `stable version does select latest RC factory`() {
    val factory220 = FakeFactory(minVersion = "2.2.20", reportedCurrentVersion = "2.3.0")
    val factory230 = FakeFactory(minVersion = "2.3.0-RC3", reportedCurrentVersion = "2.3.0")

    val factories = sequenceOf(factory220, factory230)
    val resolved = CompatContext.resolveFactory(factories, testVersionString = "2.3.0-RC3")

    assertThat(resolved.minVersion).isEqualTo("2.3.0-RC3")
  }

  @Test
  fun `RC version does not select factory requiring release version`() {
    // Bug scenario: compiler is 2.3.0-RC3, factories available for 2.2.20 and 2.3.0
    // Should select 2.2.20 because RC3 is older than the 2.3.0 release
    val factory220 = FakeFactory(minVersion = "2.2.20", reportedCurrentVersion = "2.3.0-RC3")
    val factory230 = FakeFactory(minVersion = "2.3.0", reportedCurrentVersion = "2.3.0-RC3")

    val factories = sequenceOf(factory220, factory230)
    val resolved = CompatContext.resolveFactory(factories, testVersionString = "2.3.0-RC3")

    assertThat(resolved.minVersion).isEqualTo("2.2.20")
  }

  @Test
  fun `release version selects factory requiring that release`() {
    val factory220 = FakeFactory(minVersion = "2.2.20", reportedCurrentVersion = "2.3.0")
    val factory230 = FakeFactory(minVersion = "2.3.0", reportedCurrentVersion = "2.3.0")

    val factories = sequenceOf(factory220, factory230)
    val resolved = CompatContext.resolveFactory(factories, testVersionString = "2.3.0")

    assertThat(resolved.minVersion).isEqualTo("2.3.0")
  }

  @Test
  fun `selects highest compatible factory`() {
    val factory1 = FakeFactory(minVersion = "2.0.0", reportedCurrentVersion = "2.3.0")
    val factory2 = FakeFactory(minVersion = "2.2.0", reportedCurrentVersion = "2.3.0")
    val factory3 = FakeFactory(minVersion = "2.3.0", reportedCurrentVersion = "2.3.0")

    val factories = sequenceOf(factory1, factory2, factory3)
    val resolved = CompatContext.resolveFactory(factories, testVersionString = "2.3.0")

    assertThat(resolved.minVersion).isEqualTo("2.3.0")
  }

  @Test
  fun `skips incompatible factories`() {
    val factory1 = FakeFactory(minVersion = "2.0.0", reportedCurrentVersion = "2.2.0")
    val factory2 = FakeFactory(minVersion = "2.2.0", reportedCurrentVersion = "2.2.0")
    val factory3 = FakeFactory(minVersion = "2.3.0", reportedCurrentVersion = "2.2.0")

    val factories = sequenceOf(factory1, factory2, factory3)
    val resolved = CompatContext.resolveFactory(factories, testVersionString = "2.2.0")

    // Should not select 2.3.0 factory because current version is 2.2.0
    assertThat(resolved.minVersion).isEqualTo("2.2.0")
  }

  @Test
  fun `newer patch version selects newer factory`() {
    val factory1 = FakeFactory(minVersion = "2.3.0", reportedCurrentVersion = "2.3.20")
    val factory2 = FakeFactory(minVersion = "2.3.20", reportedCurrentVersion = "2.3.20")

    val factories = sequenceOf(factory1, factory2)
    val resolved = CompatContext.resolveFactory(factories, testVersionString = "2.3.20")

    assertThat(resolved.minVersion).isEqualTo("2.3.20")
  }

  @Test
  fun `beta version selects older stable factory`() {
    val factory220 = FakeFactory(minVersion = "2.2.20", reportedCurrentVersion = "2.3.0-Beta1")
    val factory230 = FakeFactory(minVersion = "2.3.0", reportedCurrentVersion = "2.3.0-Beta1")

    val factories = sequenceOf(factory220, factory230)
    val resolved = CompatContext.resolveFactory(factories, testVersionString = "2.3.0-Beta1")

    // Beta1 is older than 2.3.0 release, so should use 2.2.20
    assertThat(resolved.minVersion).isEqualTo("2.2.20")
  }

  @Test
  fun `dev version selects older stable factory`() {
    val factory220 = FakeFactory(minVersion = "2.2.20", reportedCurrentVersion = "2.3.0-dev-123")
    val factory230 = FakeFactory(minVersion = "2.3.0", reportedCurrentVersion = "2.3.0-dev-123")

    val factories = sequenceOf(factory220, factory230)
    val resolved = CompatContext.resolveFactory(factories, testVersionString = "2.3.0-dev-123")

    // dev is older than 2.3.0 release, so should use 2.2.20
    assertThat(resolved.minVersion).isEqualTo("2.2.20")
  }

  @Test
  fun `RC can use factory with matching RC minVersion`() {
    val factory_rc2 = FakeFactory(minVersion = "2.3.0-RC2", reportedCurrentVersion = "2.3.0-RC3")
    val factory_rc3 = FakeFactory(minVersion = "2.3.0-RC3", reportedCurrentVersion = "2.3.0-RC3")

    val factories = sequenceOf(factory_rc2, factory_rc3)
    val resolved = CompatContext.resolveFactory(factories, testVersionString = "2.3.0-RC3")

    // RC3 >= RC3, so should select RC3 factory
    assertThat(resolved.minVersion).isEqualTo("2.3.0-RC3")
  }

  @Test
  fun `RC selects older RC factory when current RC is too old`() {
    val factory_rc2 = FakeFactory(minVersion = "2.3.0-RC2", reportedCurrentVersion = "2.3.0-RC2")
    val factory_rc3 = FakeFactory(minVersion = "2.3.0-RC3", reportedCurrentVersion = "2.3.0-RC2")

    val factories = sequenceOf(factory_rc2, factory_rc3)
    val resolved = CompatContext.resolveFactory(factories, testVersionString = "2.3.0-RC2")

    // RC2 < RC3, so should select RC2 factory
    assertThat(resolved.minVersion).isEqualTo("2.3.0-RC2")
  }

  @Test(expected = IllegalStateException::class)
  fun `throws when no compatible factory found`() {
    val factory = FakeFactory(minVersion = "3.0.0", reportedCurrentVersion = "2.3.0")

    val factories = sequenceOf(factory)
    CompatContext.resolveFactory(factories, testVersionString = "2.3.0")
  }

  @Test(expected = IllegalStateException::class)
  fun `throws when no factories available`() {
    val factories = emptySequence<CompatContext.Factory>()
    CompatContext.resolveFactory(factories, testVersionString = "2.3.0")
  }

  @Test
  fun `filters out factories that throw when getting currentVersion`() {
    val goodFactory = FakeFactory(minVersion = "2.2.0", reportedCurrentVersion = "2.3.0")
    val badFactory =
      object : CompatContext.Factory {
        override val minVersion: String = "2.3.0"
        override val currentVersion: String
          get() = throw RuntimeException("Cannot determine version")

        override fun create(): CompatContext = error("Not implemented")
      }

    val factories = sequenceOf(badFactory, goodFactory)
    val resolved = CompatContext.resolveFactory(factories, testVersionString = "2.3.0")

    // Bad factory should be filtered out, good factory selected
    assertThat(resolved.minVersion).isEqualTo("2.2.0")
  }

  @Test
  fun `testVersionString overrides factory currentVersion`() {
    // Factory reports 2.2.0, but we override with 2.3.0
    val factory220 = FakeFactory(minVersion = "2.2.0", reportedCurrentVersion = "2.2.0")
    val factory230 = FakeFactory(minVersion = "2.3.0", reportedCurrentVersion = "2.2.0")

    val factories = sequenceOf(factory220, factory230)
    val resolved = CompatContext.resolveFactory(factories, testVersionString = "2.3.0")

    // testVersionString=2.3.0 should allow selection of 2.3.0 factory
    assertThat(resolved.minVersion).isEqualTo("2.3.0")
  }
}
