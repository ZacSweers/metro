// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.graph

import com.tschuchort.compiletesting.KotlinCompilation
import dev.zacsweers.metro.compiler.MetroCompilerTest
import dev.zacsweers.metro.compiler.MetroOptions
import org.junit.Test

class ShardingOptionsValidationTest : MetroCompilerTest() {

  @Test
  fun `keysPerGraphShard of zero should fail`() {
    compile(
      source(
        """
        @DependencyGraph
        interface TestGraph
        """
      ),
      options = MetroOptions(keysPerGraphShard = 0),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertContains("keysPerGraphShard must be greater than zero but was 0")
    }
  }

  @Test
  fun `keysPerGraphShard of negative value should fail`() {
    compile(
      source(
        """
        @DependencyGraph
        interface TestGraph
        """
      ),
      options = MetroOptions(keysPerGraphShard = -1),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertContains("keysPerGraphShard must be greater than zero but was -1")
    }
  }
}
