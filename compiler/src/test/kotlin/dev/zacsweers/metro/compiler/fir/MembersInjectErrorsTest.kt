// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import com.tschuchort.compiletesting.KotlinCompilation.ExitCode
import dev.zacsweers.metro.compiler.MetroCompilerTest
import dev.zacsweers.metro.compiler.assertDiagnostics
import dev.zacsweers.metro.compiler.assertNoWarningsOrErrors
import org.junit.Test

class MembersInjectErrorsTest : MetroCompilerTest() {

  @Test
  fun `java classes cannot be used for member injection via graph inject function`() {
    compile(
      sourceJava(
        """
        public class JavaActivity {
          @Inject String value;
        }
        """
          .trimIndent()
      ),
      source(
        """
        @DependencyGraph
        interface ExampleGraph {
          fun inject(activity: JavaActivity)
        }
        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        "e: ExampleGraph.kt:8:14 Java class 'test.JavaActivity' cannot be used for member injection. Metro cannot generate injection code for Java classes. Convert the class to Kotlin or use constructor injection instead."
      )
    }
  }

  @Test
  fun `java classes with injected setter cannot be used for member injection`() {
    compile(
      sourceJava(
        """
        public class JavaActivity {
          private String value;

          @Inject
          public void setValue(String value) {
            this.value = value;
          }
        }
        """
          .trimIndent()
      ),
      source(
        """
        @DependencyGraph
        interface ExampleGraph {
          fun inject(activity: JavaActivity)
        }
        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        "e: ExampleGraph.kt:8:14 Java class 'test.JavaActivity' cannot be used for member injection. Metro cannot generate injection code for Java classes. Convert the class to Kotlin or use constructor injection instead."
      )
    }
  }

  @Test
  fun `suspend functions are unsupported`() {
    compile(
      source(
        """
        class ExampleClass {
          @Inject suspend fun setValue(value: Int) {

          }
        }
        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics("e: ExampleClass.kt:7:23 Injected functions cannot be suspend functions.")
    }
  }

  @Test
  fun `composable functions are unsupported`() {
    compile(
      sourceFiles =
        arrayOf(
          COMPOSE_ANNOTATIONS,
          source(
            """
            import androidx.compose.runtime.Composable

            class ExampleClass {
              @Inject @Composable fun setValue(value: Int) {

              }
            }
            """
              .trimIndent()
          ),
        ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics("e: ExampleClass.kt:9:27 Injected members cannot be composable functions.")
    }
  }

  @Test
  fun `composable property getter is ok`() {
    compile(
      sourceFiles =
        arrayOf(
          COMPOSE_ANNOTATIONS,
          source(
            """
            import androidx.compose.runtime.Composable

            class ExampleClass {
              @Inject var value: Int = 0
                @Composable get
            }
            """
              .trimIndent()
          ),
        )
    )
  }

  @Test
  fun `composable property getter site target is ok`() {
    compile(
      sourceFiles =
        arrayOf(
          COMPOSE_ANNOTATIONS,
          source(
            """
            import androidx.compose.runtime.Composable

            class ExampleClass {
              @Inject @get:Composable var value: Int = 0
            }
            """
              .trimIndent()
          ),
        )
    )
  }

  @Test
  fun `injected properties in injected classes can move up - lateinit var`() {
    compile(
      source(
        """
        @Inject
        class ExampleClass {
          @Inject lateinit var value: String
        }
        """
          .trimIndent()
      )
    ) {
      assertDiagnostics(
        "w: ExampleClass.kt:8:24 Non-null injected member property in constructor-injected class should usually be moved to the inject constructor. If this has a default value, use Metro's default values support."
      )
    }
  }

  @Test
  fun `injected properties in injected classes can move up - primitive`() {
    compile(
      source(
        """
        @Inject
        class ExampleClass {
          @Inject var value: Int = 0
        }
        """
          .trimIndent()
      )
    ) {
      assertDiagnostics(
        "w: ExampleClass.kt:8:15 Non-null injected member property in constructor-injected class should usually be moved to the inject constructor. If this has a default value, use Metro's default values support."
      )
    }
  }

  @Test
  fun `injected properties in injected classes can move up - nullable gets no warning`() {
    compile(
      source(
        """
        @Inject
        class ExampleClass {
          @Inject var value: Int? = 0
        }
        """
          .trimIndent()
      )
    ) {
      assertNoWarningsOrErrors()
    }
  }

  @Test
  fun `injected function return types`() {
    compile(
      source(
        """
        class ExampleClass {
          // ok
          @Inject fun set1(value: Int) {

          }
          // ok
          @Inject fun set2(value: Int): Unit {

          }
          @Inject fun set3(value: Int): Nothing {
            TODO()
          }
        }
        """
          .trimIndent()
      )
    ) {
      assertDiagnostics(
        "w: ExampleClass.kt:15:33 Return types for injected member functions will always be ignored."
      )
    }
  }

  @Test
  fun `abstract injections are not allowed - abstract class`() {
    compile(
      source(
        """
        abstract class ExampleClass {
          @set:Inject abstract var intSetter: Int
          @Inject abstract fun intFunction(int: Int)
        }
        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleClass.kt:7:15 Injected members cannot be abstract.
        e: ExampleClass.kt:8:11 Injected members cannot be abstract.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `member injections are not allowed in non-classes`() {
    compile(
      source(
        fileNameWithoutExtension = "NonClasses",
        source =
          """
          enum class EnumClass {
            INSTANCE;
            @Inject fun intFunction(int: Int) {

            }
          }
          object ObjectClass {
            @Inject fun intFunction(int: Int) {

            }
          }
          interface InterfaceClass {
            @Inject fun intFunction(int: Int)
          }
          """
            .trimIndent(),
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: NonClasses.kt:8:15 Only regular classes can have member injections but containing class was ENUM_CLASS.
        e: NonClasses.kt:13:15 Only regular classes can have member injections but containing class was OBJECT.
        e: NonClasses.kt:18:15 Only regular classes can have member injections but containing class was INTERFACE.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `constructor property parameters cannot be annotated inject`() {
    compile(
      source(
        source =
          """
          class ExampleClass(
            @Inject val int: Int,
            @set:Inject var int2: Int,
          )
          """
            .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleClass.kt:7:15 Constructor property parameters should not be annotated with `@Inject`. Annotate the constructor or class instead.
        e: ExampleClass.kt:8:19 Constructor property parameters should not be annotated with `@Inject`. Annotate the constructor or class instead.
        """
          .trimIndent()
      )
    }
  }
}
