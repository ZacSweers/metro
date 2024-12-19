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
package dev.zacsweers.lattice.compiler.transformers

import com.google.common.truth.Truth.assertThat
import com.tschuchort.compiletesting.KotlinCompilation.ExitCode
import com.tschuchort.compiletesting.SourceFile.Companion.kotlin
import dev.zacsweers.lattice.compiler.ExampleComponent
import dev.zacsweers.lattice.compiler.LatticeCompilerTest
import dev.zacsweers.lattice.compiler.createComponentWithNoArgs
import dev.zacsweers.lattice.compiler.generatedLatticeComponentClass
import dev.zacsweers.lattice.compiler.invokeCreateAs
import dev.zacsweers.lattice.compiler.provideValueAs
import dev.zacsweers.lattice.compiler.providesFactoryClass
import dev.zacsweers.lattice.internal.Factory
import dev.zacsweers.lattice.provider
import org.junit.Test

class ProvidesTransformerTest : LatticeCompilerTest() {

  @Test
  fun `simple function provider`() {
    val result =
      compile(
        kotlin(
          "ExampleComponent.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Component

            @Component
            interface ExampleComponent {
              @Provides
              fun provideValue(): String = "Hello, world!"
            }
          """
            .trimIndent(),
        )
      )

    val component =
      result.ExampleComponent.generatedLatticeComponentClass().createComponentWithNoArgs()
    val providesFactoryClass = result.ExampleComponent.providesFactoryClass()
    // Exercise calling the static provideValue function directly
    val providedValue = providesFactoryClass.provideValueAs<String>("provideValue", component)
    assertThat(providedValue).isEqualTo("Hello, world!")

    // Exercise calling the create + invoke() functions
    val providesFactory = providesFactoryClass.invokeCreateAs<Factory<String>>(component)
    assertThat(providesFactory()).isEqualTo("Hello, world!")
  }

  @Test
  fun `simple property provider`() {
    val result =
      compile(
        kotlin(
          "ExampleComponent.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Component

            @Component
            interface ExampleComponent {
              @Provides
              val value: String get() = "Hello, world!"
            }
          """
            .trimIndent(),
        ),
        debug = true,
      )

    val component =
      result.ExampleComponent.generatedLatticeComponentClass().createComponentWithNoArgs()
    val providesFactoryClass = result.ExampleComponent.providesFactoryClass()
    // Exercise calling the static provideValue function directly
    val providedValue = providesFactoryClass.provideValueAs<String>("getValue", component)
    assertThat(providedValue).isEqualTo("Hello, world!")

    // Exercise calling the create + invoke() functions
    val providesFactory = providesFactoryClass.invokeCreateAs<Factory<String>>(component)
    assertThat(providesFactory()).isEqualTo("Hello, world!")
  }

  @Test
  fun `simple function provider in a companion object`() {
    val result =
      compile(
        kotlin(
          "ExampleComponent.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Component

            @Component
            interface ExampleComponent {
              companion object {
                @Provides
                fun provideValue(): String = "Hello, world!"
              }
            }
          """
            .trimIndent(),
        ),
        debug = true,
      )

    val providesFactoryClass = result.ExampleComponent.providesFactoryClass(companion = true)
    // These should be objects since they require no parameters
    // TODO these appear to need metadata annotations written correctly to work
    //    assertThat(providesFactoryClass.kotlin.objectInstance).isNotNull()
    // Exercise calling the static provideValue function directly
    val providedValue = providesFactoryClass.provideValueAs<String>("provideValue")
    assertThat(providedValue).isEqualTo("Hello, world!")

    // Exercise calling the create + invoke() functions
    val providesFactory = providesFactoryClass.invokeCreateAs<Factory<String>>()
    assertThat(providesFactory()).isEqualTo("Hello, world!")
  }

  @Test
  fun `simple property provider in a companion object`() {
    val result =
      compile(
        kotlin(
          "ExampleComponent.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Component

            @Component
            interface ExampleComponent {
              companion object {
                @Provides
                val value: String get() = "Hello, world!"
              }
            }
          """
            .trimIndent(),
        ),
        debug = true,
      )

    val providesFactoryClass = result.ExampleComponent.providesFactoryClass(companion = true)
    // These should be objects since they require no parameters
    // TODO these appear to need metadata annotations written correctly to work
    //    assertThat(providesFactoryClass.kotlin.objectInstance).isNotNull()
    // Exercise calling the static provideValue function directly
    val providedValue = providesFactoryClass.provideValueAs<String>("getValue")
    assertThat(providedValue).isEqualTo("Hello, world!")

    // Exercise calling the create + invoke() functions
    val providesFactory = providesFactoryClass.invokeCreateAs<Factory<String>>()
    assertThat(providesFactory()).isEqualTo("Hello, world!")
  }

  @Test
  fun `simple function provider with arguments`() {
    val result =
      compile(
        kotlin(
          "ExampleComponent.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Component

            @Component
            interface ExampleComponent {
              @Provides
              fun provideIntValue(): Int = 1

              @Provides
              fun provideStringValue(intValue: Int): String = "Hello, ${'$'}intValue!"
            }
          """
            .trimIndent(),
        )
      )

    val component =
      result.ExampleComponent.generatedLatticeComponentClass().createComponentWithNoArgs()
    val providesFactoryClass = result.ExampleComponent.providesFactoryClass("provideStringValue")

    // Exercise calling the static provideValue function directly
    val providedValue =
      providesFactoryClass.provideValueAs<String>("provideStringValue", component, 2)
    assertThat(providedValue).isEqualTo("Hello, 2!")

    // Exercise calling the create + invoke() functions
    val providesFactory =
      providesFactoryClass.invokeCreateAs<Factory<String>>(component, provider { 2 })
    assertThat(providesFactory()).isEqualTo("Hello, 2!")
  }

  @Test
  fun `simple function provider with multiple arguments`() {
    val result =
      compile(
        kotlin(
          "ExampleComponent.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Component

            @Component
            interface ExampleComponent {
              @Provides
              fun provideBooleanValue(): Boolean = false

              @Provides
              fun provideIntValue(): Int = 1

              @Provides
              fun provideStringValue(intValue: Int, booleanValue: Boolean): String = "Hello, ${'$'}intValue! ${'$'}booleanValue"
            }
          """
            .trimIndent(),
        )
      )

    val component =
      result.ExampleComponent.generatedLatticeComponentClass().createComponentWithNoArgs()
    val providesFactoryClass = result.ExampleComponent.providesFactoryClass("provideStringValue")

    // Exercise calling the static provideValue function directly
    val providedValue =
      providesFactoryClass.provideValueAs<String>("provideStringValue", component, 2, true)
    assertThat(providedValue).isEqualTo("Hello, 2! true")

    // Exercise calling the create + invoke() functions
    val providesFactory =
      providesFactoryClass.invokeCreateAs<Factory<String>>(
        component,
        provider { 2 },
        provider { true },
      )
    assertThat(providesFactory()).isEqualTo("Hello, 2! true")
  }

  @Test
  fun `simple function provider with multiple arguments of the same type key`() {
    val result =
      compile(
        kotlin(
          "ExampleComponent.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Component
            import dev.zacsweers.lattice.annotations.Named

            @Component
            interface ExampleComponent {
              @Provides
              fun provideIntValue(): Int = 1

              @Provides
              fun provideStringValue(
                intValue: Int,
                intValue2: Int
              ): String = "Hello, ${'$'}intValue - ${'$'}intValue2!"
            }
          """
            .trimIndent(),
        )
      )

    val component =
      result.ExampleComponent.generatedLatticeComponentClass().createComponentWithNoArgs()
    val providesFactoryClass = result.ExampleComponent.providesFactoryClass("provideStringValue")

    // Exercise calling the static provideValue function directly
    val providedValue =
      providesFactoryClass.provideValueAs<String>("provideStringValue", component, 2, 3)
    assertThat(providedValue).isEqualTo("Hello, 2 - 3!")

    // Exercise calling the create + invoke() functions
    val providesFactory =
      providesFactoryClass.invokeCreateAs<Factory<String>>(
        component,
        provider { 2 },
        provider { 3 },
      )
    assertThat(providesFactory()).isEqualTo("Hello, 2 - 3!")
  }

  @Test
  fun `simple function provider with multiple arguments of the same type with different qualifiers`() {
    val result =
      compile(
        kotlin(
          "ExampleComponent.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Component
            import dev.zacsweers.lattice.annotations.Named

            @Component
            interface ExampleComponent {
              @Provides
              fun provideIntValue(): Int = 1

              @Named("int2")
              @Provides
              fun provideIntValue2(): Int = 1

              @Provides
              fun provideStringValue(
                intValue: Int,
                @Named("int2") intValue2: Int
              ): String = "Hello, ${'$'}intValue - ${'$'}intValue2!"
            }
          """
            .trimIndent(),
        )
      )

    val component =
      result.ExampleComponent.generatedLatticeComponentClass().createComponentWithNoArgs()
    val providesFactoryClass = result.ExampleComponent.providesFactoryClass("provideStringValue")

    // Exercise calling the static provideValue function directly
    val providedValue =
      providesFactoryClass.provideValueAs<String>("provideStringValue", component, 2, 3)
    assertThat(providedValue).isEqualTo("Hello, 2 - 3!")

    // Exercise calling the create + invoke() functions
    val providesFactory =
      providesFactoryClass.invokeCreateAs<Factory<String>>(
        component,
        provider { 2 },
        provider { 3 },
      )
    assertThat(providesFactory()).isEqualTo("Hello, 2 - 3!")
  }

  @Test
  fun `providers cannot have type parameters`() {
    val result =
      compile(
        kotlin(
          "ExampleComponent.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Component
            import dev.zacsweers.lattice.annotations.Named

            @Component
            interface ExampleComponent {
              @Provides
              fun <T> provideValue(): Int = 1
            }
          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    assertThat(result.messages)
      .contains(
        """
        ExampleComponent.kt:9:3 @Provides functions may not have type parameters
      """
          .trimIndent()
      )
  }

  @Test
  fun `function with receivers are not currently supported`() {
    val result =
      compile(
        kotlin(
          "ExampleComponent.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Component

            @Component
            interface ExampleComponent {
              @Provides
              private fun String.provideValue(): Int = length
            }
          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    assertThat(result.messages)
      .contains(
        """
          ExampleComponent.kt:9:22 `@Provides` declarations may not have receiver parameters unless they are binds providers.
        """
          .trimIndent()
      )
  }

  // TODO
  //  ------- from Anvil
  //  a factory class is generated for a provider method with generic type
  //  a factory class is generated for a provider method with advanced generics
  //  two factory classes are generated for two provider methods
  //  a factory class is generated for a provider method in an object
  //  a factory class is generated for a provider method with parameters
  //  a factory class is generated for a provider method with provider parameters
  //  a factory class is generated for a provider method with lazy parameters
  //  a factory class is generated for a provider method with a lazy parameter using a fully
  //  qualified name
  //  a factory class is generated for a provider method with generic parameters
  //  a factory class is generated for a provider method with parameters in an object
  //  a factory class is generated for a provider method with parameters in a companion object
  //  a factory class is generated for a nullable provider method
  //  a factory class is generated for a nullable provider method in an object
  //  a factory class is generated for a nullable provider method with nullable parameters
  //  no factory class is generated for a binding method in an abstract class
  //  a factory class is generated for a provider method in a companion object
  //  a factory class is generated for a provider method in an inner module
  //  a factory class is generated for a provider method returning an inner class
  //  a factory class is generated for a provider method in a companion object in an inner module
  //  no factory class is generated for multibindings
  //  a factory class is generated for multibindings provider method
  //  a factory class is generated for multibindings provider method elements into set
  //  a factory class is generated for a provided function
  //  a factory class is generated for a multibindings provided function with a typealias
  //  a factory class is generated when Anvil generates the provider method
  //  an error is thrown for overloaded provider methods
  //  a factory class is generated for provided properties
  //  a factory class is generated for provided properties in a companion object module
  //  a factory class is generated for provided nullable properties
  //  warnings are suppressed
  //  a factory class is generated for a provider method without a package
  //  a provider method cannot be abstract
  //  an interface is not allowed to contain a provider method
  //  an interface with a companion object is allowed to contain a provider method
  //  a provides method with nested generics but no explicit @JvmSuppressWildcards still adds
  //   @JvmSuppressWildcards in the generated code from another module
  //  a provides method with generics but no explicit @JvmSuppressWildcards still adds
  //   @JvmSuppressWildcards in the generated code from another module
  //  a provides method with the word 'is' as prefix is supported
  //  a provides method with starting with 'is_' is supported
  //  a provides method with starting with 'is' but not as a word is supported
  //  a provides method with 'is' as prefix is supported for objects
  //  extension functions are invalid
  //  ------- unclear if relevant
  //  a factory class is generated for a capital case package name
  //  a factory class is generated for a capital case package name and lower class name
  //  a factory class is generated for a capital case package name and inner class
  //  a factory class is generated for an uppercase factory function
  //  a factory class is generated for a provided Factory and avoids ambiguous import
  //  a factory class is generated for a provider method with imports
  //  a factory class is generated for a provider method with imports and fully qualified return
  //   type
  //  a factory class is generated for a provider method with star imports
  //  a factory class is generated for an internal provider method with a mangled name
  //  a factory class is generated for an internal provider method with a mangled name in an object
  //  a factory class is generated for an internal provider method with a mangled name in a
  //   companion object
  //  a factory class is generated for an internal provider method with a mangled name in a object
  //   in a module with a dash-separated name
  //  the factory does not contain the mangled name if the function is internal and uses
  //   @PublishedApi
  //  a factory class is generated for a method returning a java class with a star import
  //  a factory class is generated for a method returning a class with a named import
  //  a factory class is generated ignoring the named import original path
  //  a factory class is generated for provided properties in an object module
  //  a factory class is generated for provided nullable properties in an object module
}
