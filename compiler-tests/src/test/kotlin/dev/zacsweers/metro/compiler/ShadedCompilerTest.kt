// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import kotlin.test.Test
import kotlin.test.assertTrue

class ShadedCompilerTest {
  @Test
  fun `optional terminal providers initialize from the shaded compiler jar`() {
    val stylerCompanion =
      Class.forName(
        "dev.zacsweers.metro.compiler.diagnostics.render.Styler\$Companion",
        false,
        javaClass.classLoader,
      )
    val compilerArtifact = stylerCompanion.protectionDomain.codeSource.location.path
    assertTrue(compilerArtifact.endsWith(".jar"), "Expected shaded compiler jar: $compilerArtifact")

    // mordant-core has no JVM terminal provider. If one is added again, exercise its dynamic
    // loading from the R8-processed artifact so it cannot be silently replaced with invalid code.
    val jnaProvider =
      try {
        Class.forName(
          "dev.zacsweers.metro.compiler.shaded.com.github.ajalt.mordant.terminal.terminalinterface.jna.TerminalInterfaceProviderJna",
          true,
          stylerCompanion.classLoader,
        )
      } catch (_: ClassNotFoundException) {
        return
      }
    val provider = jnaProvider.getDeclaredConstructor().newInstance()
    jnaProvider.getMethod("load").invoke(provider)
  }
}
