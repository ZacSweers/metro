// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.compat

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class KotlinToolingVersionTest {

  @Test
  fun `parse simple version`() {
    val version = KotlinToolingVersion("2.3.0")
    assertThat(version.major).isEqualTo(2)
    assertThat(version.minor).isEqualTo(3)
    assertThat(version.patch).isEqualTo(0)
    assertThat(version.classifier).isNull()
  }

  @Test
  fun `parse version with RC classifier`() {
    val version = KotlinToolingVersion("2.3.0-RC3")
    assertThat(version.major).isEqualTo(2)
    assertThat(version.minor).isEqualTo(3)
    assertThat(version.patch).isEqualTo(0)
    assertThat(version.classifier).isEqualTo("RC3")
  }

  @Test
  fun `parse version with beta classifier`() {
    val version = KotlinToolingVersion("2.3.0-Beta1")
    assertThat(version.major).isEqualTo(2)
    assertThat(version.minor).isEqualTo(3)
    assertThat(version.patch).isEqualTo(0)
    assertThat(version.classifier).isEqualTo("Beta1")
  }

  @Test
  fun `parse version with dev classifier`() {
    val version = KotlinToolingVersion("2.3.20-dev-5437")
    assertThat(version.major).isEqualTo(2)
    assertThat(version.minor).isEqualTo(3)
    assertThat(version.patch).isEqualTo(20)
    assertThat(version.classifier).isEqualTo("dev-5437")
  }

  @Test
  fun `stable version has STABLE maturity`() {
    assertThat(KotlinToolingVersion("2.3.0").maturity)
      .isEqualTo(KotlinToolingVersion.Maturity.STABLE)
  }

  @Test
  fun `RC version has RC maturity`() {
    assertThat(KotlinToolingVersion("2.3.0-RC3").maturity)
      .isEqualTo(KotlinToolingVersion.Maturity.RC)
    assertThat(KotlinToolingVersion("2.3.0-RC").maturity)
      .isEqualTo(KotlinToolingVersion.Maturity.RC)
    assertThat(KotlinToolingVersion("2.3.0-RC1").maturity)
      .isEqualTo(KotlinToolingVersion.Maturity.RC)
  }

  @Test
  fun `beta version has BETA maturity`() {
    assertThat(KotlinToolingVersion("2.3.0-Beta1").maturity)
      .isEqualTo(KotlinToolingVersion.Maturity.BETA)
    assertThat(KotlinToolingVersion("2.3.0-Beta").maturity)
      .isEqualTo(KotlinToolingVersion.Maturity.BETA)
  }

  @Test
  fun `alpha version has ALPHA maturity`() {
    assertThat(KotlinToolingVersion("2.3.0-alpha1").maturity)
      .isEqualTo(KotlinToolingVersion.Maturity.ALPHA)
  }

  @Test
  fun `dev version has DEV maturity`() {
    assertThat(KotlinToolingVersion("2.3.20-dev-5437").maturity)
      .isEqualTo(KotlinToolingVersion.Maturity.DEV)
  }

  @Test
  fun `release is newer than RC - the bug scenario`() {
    val release = KotlinToolingVersion("2.3.0")
    val rc3 = KotlinToolingVersion("2.3.0-RC3")

    assertThat(release).isGreaterThan(rc3)
    assertThat(rc3).isLessThan(release)
  }

  @Test
  fun `RC3 is older than release`() {
    val rc3 = KotlinToolingVersion("2.3.0-RC3")
    val release = KotlinToolingVersion("2.3.0")

    assertThat(rc3).isLessThan(release)
    assertThat(release).isGreaterThan(rc3)
  }

  @Test
  fun `RC2 is older than RC3`() {
    val rc2 = KotlinToolingVersion("2.3.0-RC2")
    val rc3 = KotlinToolingVersion("2.3.0-RC3")

    assertThat(rc2).isLessThan(rc3)
    assertThat(rc3).isGreaterThan(rc2)
  }

  @Test
  fun `beta is older than RC`() {
    val beta = KotlinToolingVersion("2.3.0-Beta1")
    val rc = KotlinToolingVersion("2.3.0-RC1")

    assertThat(beta).isLessThan(rc)
  }

  @Test
  fun `alpha is older than beta`() {
    val alpha = KotlinToolingVersion("2.3.0-alpha1")
    val beta = KotlinToolingVersion("2.3.0-Beta1")

    assertThat(alpha).isLessThan(beta)
  }

  @Test
  fun `dev is oldest pre-release`() {
    val dev = KotlinToolingVersion("2.3.0-dev-123")
    val alpha = KotlinToolingVersion("2.3.0-alpha1")

    assertThat(dev).isLessThan(alpha)
  }

  @Test
  fun `different major versions compare correctly`() {
    val v1 = KotlinToolingVersion("1.9.0")
    val v2 = KotlinToolingVersion("2.0.0")

    assertThat(v1).isLessThan(v2)
  }

  @Test
  fun `different minor versions compare correctly`() {
    val v1 = KotlinToolingVersion("2.2.0")
    val v2 = KotlinToolingVersion("2.3.0")

    assertThat(v1).isLessThan(v2)
  }

  @Test
  fun `different patch versions compare correctly`() {
    val v1 = KotlinToolingVersion("2.3.0")
    val v2 = KotlinToolingVersion("2.3.20")

    assertThat(v1).isLessThan(v2)
  }

  @Test
  fun `same versions are equal`() {
    val v1 = KotlinToolingVersion("2.3.0")
    val v2 = KotlinToolingVersion("2.3.0")

    assertThat(v1.compareTo(v2)).isEqualTo(0)
    assertThat(v1).isEqualTo(v2)
  }

  @Test
  fun `same RC versions are equal`() {
    val v1 = KotlinToolingVersion("2.3.0-RC3")
    val v2 = KotlinToolingVersion("2.3.0-RC3")

    assertThat(v1.compareTo(v2)).isEqualTo(0)
    assertThat(v1).isEqualTo(v2)
  }

  @Test
  fun `string compareTo version works`() {
    assertThat("2.3.0".compareTo(KotlinToolingVersion("2.3.0-RC3"))).isGreaterThan(0)
    assertThat("2.3.0-RC3".compareTo(KotlinToolingVersion("2.3.0"))).isLessThan(0)
  }

  @Test
  fun `version compareTo string works`() {
    assertThat(KotlinToolingVersion("2.3.0").compareTo("2.3.0-RC3")).isGreaterThan(0)
    assertThat(KotlinToolingVersion("2.3.0-RC3").compareTo("2.3.0")).isLessThan(0)
  }

  @Test
  fun `version without patch defaults to 0`() {
    val version = KotlinToolingVersion("2.3")
    assertThat(version.major).isEqualTo(2)
    assertThat(version.minor).isEqualTo(3)
    assertThat(version.patch).isEqualTo(0)
  }

  @Test
  fun `pre-release of newer base version is newer than older release`() {
    val oldRelease = KotlinToolingVersion("2.2.20")
    val newRC = KotlinToolingVersion("2.3.0-RC3")

    assertThat(newRC).isGreaterThan(oldRelease)
  }

  @Test
  fun `comparison across major versions ignores maturity`() {
    val oldStable = KotlinToolingVersion("1.9.0")
    val newDev = KotlinToolingVersion("2.0.0-dev-123")

    // Even though dev is lowest maturity, 2.x > 1.x
    assertThat(newDev).isGreaterThan(oldStable)
  }

  @Test
  fun `classifierNumber is extracted from RC`() {
    assertThat(KotlinToolingVersion("2.3.0-RC3").classifierNumber).isEqualTo(3)
    assertThat(KotlinToolingVersion("2.3.0-RC").classifierNumber).isNull()
    assertThat(KotlinToolingVersion("2.3.0-rc1").classifierNumber).isEqualTo(1)
  }

  @Test
  fun `classifierNumber is extracted from beta`() {
    assertThat(KotlinToolingVersion("2.3.0-Beta2").classifierNumber).isEqualTo(2)
  }

  @Test
  fun `toString returns original format`() {
    assertThat(KotlinToolingVersion("2.3.0").toString()).isEqualTo("2.3.0")
    assertThat(KotlinToolingVersion("2.3.0-RC3").toString()).isEqualTo("2.3.0-RC3")
    assertThat(KotlinToolingVersion("2.3.20-dev-5437").toString()).isEqualTo("2.3.20-dev-5437")
  }

  @Test
  fun `isStable returns true for release`() {
    assertThat(KotlinToolingVersion("2.3.0").isStable).isTrue()
    assertThat(KotlinToolingVersion("2.3.0-RC3").isStable).isFalse()
  }

  @Test
  fun `isPreRelease returns true for non-stable`() {
    assertThat(KotlinToolingVersion("2.3.0").isPreRelease).isFalse()
    assertThat(KotlinToolingVersion("2.3.0-RC3").isPreRelease).isTrue()
    assertThat(KotlinToolingVersion("2.3.0-Beta1").isPreRelease).isTrue()
  }

  @Test
  fun `isRC returns true for RC versions`() {
    assertThat(KotlinToolingVersion("2.3.0-RC3").isRC).isTrue()
    assertThat(KotlinToolingVersion("2.3.0").isRC).isFalse()
  }
}
