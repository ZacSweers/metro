// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import com.tschuchort.compiletesting.KotlinCompilation.ExitCode
import dev.zacsweers.metro.compiler.MetroCompilerTest
import dev.zacsweers.metro.compiler.assertDiagnostics
import dev.zacsweers.metro.compiler.assertNoWarningsOrErrors
import org.junit.Test

class InjectConstructorErrorsTest : MetroCompilerTest() {

  @Test
  fun `cannot have multiple inject targets`() {
    compile(
      source(
        """
        @Inject
        class ExampleClass @Inject constructor(private val value: String)
        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        "e: ExampleClass.kt:7:20 You should annotate either a class XOR constructor with `@Inject` but not both."
      )
    }
  }

  @Test
  fun `suggest moving inject annotation to class if constructor is empty`() {
    compile(
      source(
        """
        class ExampleClass @Inject constructor()
        """
          .trimIndent()
      )
    ) {
      assertDiagnostics(
        "w: ExampleClass.kt:6:20 There is only one @Inject-annotated constructor. Consider moving the annotation to the class instead."
      )
    }
  }

  @Test
  fun `do not suggest moving inject annotation to class if the option is disabled`() {
    compile(
      source(
        """
        class ExampleClass @Inject constructor()
        """
          .trimIndent()
      ),
      options = metroOptions.copy(warnOnInjectAnnotationPlacement = false),
    ) {
      assertNoWarningsOrErrors()
    }
  }

  @Test
  fun `do not suggest moving inject annotation to class if there are multiple constructors`() {
    compile(
      source(
        """
        class ExampleClass internal constructor(int: Int) {
          @Inject constructor() : this(0)
        }
        """
          .trimIndent()
      )
    ) {
      assertNoWarningsOrErrors()
    }
  }

  @Test
  fun `cannot have multiple inject constructors`() {
    compile(
      source(
        """
        class ExampleClass @Inject constructor() {
          @Inject constructor(value: String) : this()
        }
        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleClass.kt:6:20 Only one `@Inject` constructor is allowed.
        e: ExampleClass.kt:7:3 Only one `@Inject` constructor is allowed.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `only classes can be injected`() {
    compile(
      source(
        fileNameWithoutExtension = "OnlyClasses",
        source =
          """
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
    ) {
      assertDiagnostics(
        """
        e: OnlyClasses.kt:7:12 Only classes can be annotated with @Inject or have @Inject-annotated constructors.
        e: OnlyClasses.kt:13:8 Only classes can be annotated with @Inject or have @Inject-annotated constructors.
        e: OnlyClasses.kt:16:11 Only classes can be annotated with @Inject or have @Inject-annotated constructors.
        e: OnlyClasses.kt:19:18 Only classes can be annotated with @Inject or have @Inject-annotated constructors.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `only final and open classes can be injected`() {
    compile(
      source(
        fileNameWithoutExtension = "FinalClasses",
        source =
          """
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
    ) {
      assertDiagnostics(
        """
        e: FinalClasses.kt:10:1 Only final and open classes be annotated with @Inject or have @Inject-annotated constructors.
        e: FinalClasses.kt:13:1 Only final and open classes be annotated with @Inject or have @Inject-annotated constructors.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `local classes cannot be injected`() {
    compile(
      source(
        """
        fun example() {
          @Inject
          class ExampleClass
        }
        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        "e: ExampleClass.kt:8:9 Local classes cannot be annotated with @Inject or have @Inject-annotated constructors."
      )
    }
  }

  @Test
  fun `injected classes must be visible`() {
    compile(
      source(
        fileNameWithoutExtension = "VisibleClasses",
        source =
          """
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
    ) {
      assertDiagnostics(
        """
        e: VisibleClasses.kt:7:1 Injected classes must be public or internal.
        e: VisibleClasses.kt:11:3 Injected classes must be public or internal.
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun `assisted factories cannot have type params`() {
    compile(
      source(
        """
        @AssistedInject
        class ExampleClass<T> {
          @AssistedFactory
          interface Factory {
            fun <T> create(): ExampleClass<T>
          }
        }
        """
          .trimIndent()
      ),
      expectedExitCode = ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        "e: ExampleClass.kt:10:13 `@AssistedFactory` functions cannot have type parameters."
      )
    }
  }
}
