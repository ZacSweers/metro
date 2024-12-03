/*
 * Copyright (C) 2024 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.zacsweers.lattice.compiler.fir

import com.tschuchort.compiletesting.KotlinCompilation.ExitCode
import com.tschuchort.compiletesting.SourceFile.Companion.kotlin
import dev.zacsweers.lattice.compiler.LatticeCompilerTest
import dev.zacsweers.lattice.compiler.assertContainsAll
import org.junit.Test

class InjectConstructorErrorsTest : LatticeCompilerTest() {

  @Test
  fun `cannot have multiple inject targets`() {
    val result =
      compile(
        kotlin(
          "ExampleClass.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Inject

            @Inject
            class ExampleClass @Inject constructor(private val value: String)

          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertContains(
      """
        ExampleClass.kt:5:1 You should annotate either a class XOR constructor with `@Inject` but not both.
      """
        .trimIndent()
    )
  }

  @Test
  fun `suggest moving inject annotation to class if constructor is empty`() {
    val result =
      compile(
        kotlin(
          "ExampleClass.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Inject

            class ExampleClass @Inject constructor()

          """
            .trimIndent(),
        )
      )
    result.assertContains(
      """
        ExampleClass.kt:5:20 There are no parameters on the @Inject-annotated constructor. Consider moving the annotation to the class instead.
      """
        .trimIndent()
    )
  }

  @Test
  fun `cannot have multiple inject constructors`() {
    val result =
      compile(
        kotlin(
          "ExampleClass.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Inject

            class ExampleClass @Inject constructor() {
              @Inject constructor(value: String) : this()
            }

          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    result.assertContains(
      """
        ExampleClass.kt:5:20 Only one `@Inject` constructor is allowed.
      """
        .trimIndent()
    )
  }

  @Test
  fun `only classes can be injected`() {
    val result =
      compile(
        kotlin(
          "ExampleClass.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Inject

            @Inject
            enum class EnumExampleClass {
              @Inject
              INSTANCE
            }

            @Inject
            object ObjectExampleClass

            @Inject
            interface InterfaceExampleClass

            @Inject
            annotation class AnnotationExampleClass

            @Inject
            class HappyClass

          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertContainsAll(
      "ExampleClass.kt:6:12 Only classes can be annotated with @Inject or have @Inject-constructors.",
      "ExampleClass.kt:12:8 Only classes can be annotated with @Inject or have @Inject-constructors.",
      "ExampleClass.kt:15:11 Only classes can be annotated with @Inject or have @Inject-constructors.",
      "ExampleClass.kt:18:18 Only classes can be annotated with @Inject or have @Inject-constructors.",
    )
  }

  @Test
  fun `only final classes can be injected`() {
    val result =
      compile(
        kotlin(
          "ExampleClass.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Inject

            @Inject
            open class OpenExampleClass

            @Inject
            sealed class SealedExampleClass

            @Inject
            abstract class AbstractExampleClass

            @Inject
            class HappyClass
          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertContainsAll(
      "ExampleClass.kt:6:1 Only final classes be annotated with @Inject or have @Inject-constructors.",
      "ExampleClass.kt:9:1 Only final classes be annotated with @Inject or have @Inject-constructors.",
      "ExampleClass.kt:12:1 Only final classes be annotated with @Inject or have @Inject-constructors.",
    )
  }

  @Test
  fun `local classes cannot be injected`() {
    val result =
      compile(
        kotlin(
          "ExampleClass.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Inject

            fun example() {
              @Inject
              class ExampleClass
            }
          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertContains(
      """
        ExampleClass.kt:7:9 Local classes cannot be annotated with @Inject or have @Inject-constructors.
      """
        .trimIndent()
    )
  }

  @Test
  fun `injected classes must be visible`() {
    val result =
      compile(
        kotlin(
          "ExampleClass.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Inject

            @Inject
            private class PrivateClass

            open class Example {
              @Inject
              protected class ProtectedClass
            }

            @Inject
            internal class HappyInternalClass

            @Inject
            public class HappyPublicClass
          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertContainsAll(
      "ExampleClass.kt:6:1 Injected classes must be visible, either `public` or `internal`.",
      "ExampleClass.kt:10:3 Injected classes must be visible, either `public` or `internal`.",
    )
  }

  @Test
  fun `injected constructors must be visible`() {
    val result =
      compile(
        kotlin(
          "ExampleClass.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Inject

            @Inject
            class ClassWithPrivateConstructor private constructor()

            class ClassWithInjectedPrivateConstructor @Inject private constructor()

            class ClassWithInjectedProtectedConstructor @Inject protected constructor()

            @Inject
            class HappyClassWithExplicitInternalConstructor internal constructor()

            @Inject
            class HappyClassWithExplicitPublicConstructor public constructor()

            @Inject
            class HappyClassWithNoConstructor
          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )
    result.assertContainsAll(
      "ExampleClass.kt:6:35 Injected constructors must be visible, either `public` or `internal`.",
      "ExampleClass.kt:8:51 Injected constructors must be visible, either `public` or `internal`.",
      "ExampleClass.kt:10:53 Injected constructors must be visible, either `public` or `internal`.",
    )
  }
}
