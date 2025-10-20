// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import dev.zacsweers.metro.compiler.test.COMPILER_VERSION
import org.jetbrains.kotlin.test.directives.model.DirectivesContainer
import org.jetbrains.kotlin.test.services.MetaTestConfigurator
import org.jetbrains.kotlin.test.services.TestServices

class MetroTestConfigurator(testServices: TestServices) : MetaTestConfigurator(testServices) {
  override val directiveContainers: List<DirectivesContainer>
    get() = listOf(MetroDirectives)

  override fun shouldSkipTest(): Boolean {
    val targetKotlinVersion = targetKotlinVersion(testServices) ?: return false
    return targetKotlinVersion > COMPILER_VERSION
  }
}
