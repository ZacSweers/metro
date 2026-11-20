// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.transformers

import com.google.common.truth.Truth.assertThat
import dev.zacsweers.metro.compiler.ExampleGraph
import dev.zacsweers.metro.compiler.MetroCompilerTest
import dev.zacsweers.metro.compiler.callProperty
import dev.zacsweers.metro.compiler.createGraphWithNoArgs
import dev.zacsweers.metro.compiler.generatedImpl
import org.junit.Ignore
import org.junit.Test

class NullableBindingsTest : MetroCompilerTest() {
  @Ignore("Not supported yet")
  @Test
  fun `injectable generic class with nullable type arg`() {
    compile(
      source(
        """
        @DependencyGraph(Unit::class)
        interface ExampleGraph {
          val foo: GenericFoo<String?>

          @Inject
          class GenericFoo<T>(val input: T)

          @Provides
          fun provideNullableString(): String? = "hello"
        }
        """
          .trimIndent(),
      ),
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val foo = graph.callProperty<Any>("foo")
      assertThat(foo.callProperty<String?>("input")).isEqualTo("hello")
    }
  }
}
