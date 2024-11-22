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
import dev.zacsweers.lattice.Provider
import dev.zacsweers.lattice.compiler.ExampleComponent
import dev.zacsweers.lattice.compiler.LatticeCompilerTest
import dev.zacsweers.lattice.compiler.callComponentAccessor
import dev.zacsweers.lattice.compiler.callComponentAccessorProperty
import dev.zacsweers.lattice.compiler.createComponentViaFactory
import dev.zacsweers.lattice.compiler.generatedLatticeComponentClass
import java.util.concurrent.Callable
import org.junit.Test

class ComponentTransformerTest : LatticeCompilerTest() {

  @Test
  fun simple() {
    val result =
      compile(
        kotlin(
          "ExampleComponent.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Component
            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Inject
            import dev.zacsweers.lattice.annotations.Singleton
            import dev.zacsweers.lattice.createComponentFactory
            import java.util.concurrent.Callable

            @Singleton
            @Component
            abstract class ExampleComponent(
              @get:Provides
              val text: String
            ) {

              abstract fun exampleClass(): ExampleClass

              @Component.Factory
              fun interface Factory {
                fun create(text: String): ExampleComponent
              }
            }

            @Singleton
            @Inject
            class ExampleClass(private val text: String) : Callable<String> {
              override fun call(): String = text
            }

            fun createExampleClass(): (String) -> Callable<String> {
              val factory = createComponentFactory<ExampleComponent.Factory>()
              return { factory.create(it).exampleClass() }
            }

          """
            .trimIndent(),
        )
      )
    val component =
      result.ExampleComponent.generatedLatticeComponentClass()
        .createComponentViaFactory("Hello, world!")

    val exampleClass = component.callComponentAccessor<Callable<String>>("exampleClass")
    assertThat(exampleClass.call()).isEqualTo("Hello, world!")

    // 2nd pass exercising creating a component via createComponentFactory()
    @Suppress("UNCHECKED_CAST")
    val callableCreator =
      result.classLoader
        .loadClass("test.ExampleComponentKt")
        .getDeclaredMethod("createExampleClass")
        .invoke(null) as (String) -> Callable<String>
    val callable = callableCreator("Hello, world!")
    assertThat(callable.call()).isEqualTo("Hello, world!")
  }

  @Test
  fun `missing binding should fail compilation and report property accessor`() {
    val result =
      compile(
        kotlin(
          "ExampleComponent.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Component
            import dev.zacsweers.lattice.annotations.Inject
            import java.util.concurrent.Callable

            @Component
            interface ExampleComponent {

              val text: String

              @Component.Factory
              fun interface Factory {
                fun create(): ExampleComponent
              }
            }

          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    assertThat(result.messages)
      .contains(
        """
        ExampleComponent.kt:10:3 [LATTICE] Cannot find an @Inject constructor or @Provides-annotated function/property for: kotlin.String

            kotlin.String is requested at
                [test.ExampleComponent] test.ExampleComponent.text
        """
          .trimIndent()
      )
  }

  @Test
  fun `missing binding should fail compilation and report property accessor with qualifier`() {
    val result =
      compile(
        kotlin(
          "ExampleComponent.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Component
            import dev.zacsweers.lattice.annotations.Inject
            import dev.zacsweers.lattice.annotations.Named
            import java.util.concurrent.Callable

            @Component
            interface ExampleComponent {

              @Named("hello")
              val text: String

              @Component.Factory
              fun interface Factory {
                fun create(): ExampleComponent
              }
            }

          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    assertThat(result.messages)
      .contains(
        """
        ExampleComponent.kt:11:3 [LATTICE] Cannot find an @Inject constructor or @Provides-annotated function/property for: @Named("hello") kotlin.String

            @Named("hello") kotlin.String is requested at
                [test.ExampleComponent] test.ExampleComponent.text
        """
          .trimIndent()
      )
  }

  @Test
  fun `missing binding should fail compilation and report property accessor with get site target qualifier`() {
    val result =
      compile(
        kotlin(
          "ExampleComponent.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Component
            import dev.zacsweers.lattice.annotations.Inject
            import dev.zacsweers.lattice.annotations.Named
            import java.util.concurrent.Callable

            @Component
            interface ExampleComponent {

              @get:Named("hello")
              val text: String

              @Component.Factory
              fun interface Factory {
                fun create(): ExampleComponent
              }
            }

          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    assertThat(result.messages)
      .contains(
        """
        ExampleComponent.kt:11:3 [LATTICE] Cannot find an @Inject constructor or @Provides-annotated function/property for: @Named("hello") kotlin.String

            @Named("hello") kotlin.String is requested at
                [test.ExampleComponent] test.ExampleComponent.text
        """
          .trimIndent()
      )
  }

  @Test
  fun `missing binding should fail compilation and function accessor`() {
    val result =
      compile(
        kotlin(
          "ExampleComponent.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Component
            import dev.zacsweers.lattice.annotations.Inject
            import java.util.concurrent.Callable

            @Component
            interface ExampleComponent {

              fun text(): String

              @Component.Factory
              fun interface Factory {
                fun create(): ExampleComponent
              }
            }

          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    assertThat(result.messages)
      .contains(
        """
        ExampleComponent.kt:10:3 [LATTICE] Cannot find an @Inject constructor or @Provides-annotated function/property for: kotlin.String

            kotlin.String is requested at
                [test.ExampleComponent] test.ExampleComponent.text()
        """
          .trimIndent()
      )
  }

  @Test
  fun `missing binding should fail compilation and function accessor with qualifier`() {
    val result =
      compile(
        kotlin(
          "ExampleComponent.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Component
            import dev.zacsweers.lattice.annotations.Inject
            import dev.zacsweers.lattice.annotations.Named
            import java.util.concurrent.Callable

            @Component
            interface ExampleComponent {

              @Named("hello")
              fun text(): String

              @Component.Factory
              fun interface Factory {
                fun create(): ExampleComponent
              }
            }

          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    assertThat(result.messages)
      .contains(
        """
        ExampleComponent.kt:11:3 [LATTICE] Cannot find an @Inject constructor or @Provides-annotated function/property for: @Named("hello") kotlin.String

            @Named("hello") kotlin.String is requested at
                [test.ExampleComponent] test.ExampleComponent.text()
        """
          .trimIndent()
      )
  }

  @Test
  fun `missing binding should fail compilation and report binding stack`() {
    val result =
      compile(
        kotlin(
          "ExampleComponent.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Component
            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Inject

            @Component
            abstract class ExampleComponent() {

              abstract fun exampleClass(): ExampleClass

              @Component.Factory
              fun interface Factory {
                fun create(): ExampleComponent
              }
            }

            @Inject
            class ExampleClass(private val text: String)

          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    assertThat(result.messages)
      .contains(
        """
        ExampleComponent.kt:19:20 [LATTICE] Cannot find an @Inject constructor or @Provides-annotated function/property for: kotlin.String

            kotlin.String is injected at
                [test.ExampleComponent] test.ExampleClass(…, text)
            test.ExampleClass is requested at
                [test.ExampleComponent] test.ExampleComponent.exampleClass()
        """
          .trimIndent()
      )
  }

  @Test
  fun `missing binding should fail compilation and report binding stack with qualifier`() {
    val result =
      compile(
        kotlin(
          "ExampleComponent.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Component
            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Inject
            import dev.zacsweers.lattice.annotations.Named

            @Component
            abstract class ExampleComponent() {

              abstract fun exampleClass(): ExampleClass

              @Component.Factory
              fun interface Factory {
                fun create(): ExampleComponent
              }
            }

            @Inject
            class ExampleClass(@Named("hello") private val text: String)

          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    assertThat(result.messages)
      .contains(
        """
        ExampleComponent.kt:20:20 [LATTICE] Cannot find an @Inject constructor or @Provides-annotated function/property for: @Named("hello") kotlin.String

            @Named("hello") kotlin.String is injected at
                [test.ExampleComponent] test.ExampleClass(…, text)
            test.ExampleClass is requested at
                [test.ExampleComponent] test.ExampleComponent.exampleClass()
        """
          .trimIndent()
      )
  }

  @Test
  fun `scoped bindings from providers are scoped correctly`() {
    // Ensure scoped bindings are properly scoped
    // This means that any calls to them should return the same instance, while any calls
    // to unscoped bindings are called every time.
    val result =
      compile(
        kotlin(
          "ExampleComponent.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Component
            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Inject
            import dev.zacsweers.lattice.annotations.Named
            import dev.zacsweers.lattice.annotations.Singleton

            @Singleton
            @Component
            abstract class ExampleComponent {

              private var scopedCounter = 0
              private var unscopedCounter = 0

              @Named("scoped")
              abstract val scoped: String

              @Named("unscoped")
              abstract val unscoped: String

              @Singleton
              @Provides
              @Named("scoped")
              fun provideScoped(): String = "text " + scopedCounter++

              @Provides
              @Named("unscoped")
              fun provideUnscoped(): String = "text " + unscopedCounter++

              @Component.Factory
              fun interface Factory {
                fun create(): ExampleComponent
              }
            }

            @Inject
            class ExampleClass(@Named("hello") private val text: String)

          """
            .trimIndent(),
        )
      )

    val component =
      result.ExampleComponent.generatedLatticeComponentClass().createComponentViaFactory()

    // Repeated calls to the scoped instance only every return one value
    assertThat(component.callComponentAccessorProperty<String>("scoped")).isEqualTo("text 0")
    assertThat(component.callComponentAccessorProperty<String>("scoped")).isEqualTo("text 0")

    // Repeated calls to the unscoped instance recompute each time
    assertThat(component.callComponentAccessorProperty<String>("unscoped")).isEqualTo("text 0")
    assertThat(component.callComponentAccessorProperty<String>("unscoped")).isEqualTo("text 1")
  }

  @Test
  fun `providers from supertypes are wired correctly`() {
    // Ensure providers from supertypes are correctly wired. This means both incorporating them in
    // binding resolution and being able to invoke them correctly in the resulting component.
    val result =
      compile(
        kotlin(
          "ExampleComponent.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Component
            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Inject
            import dev.zacsweers.lattice.annotations.Named
            import dev.zacsweers.lattice.annotations.Singleton

            @Component
            interface ExampleComponent : TextProvider {

              val value: String

              @Component.Factory
              fun interface Factory {
                fun create(): ExampleComponent
              }
            }

            interface TextProvider {
              @Provides
              fun provideValue(): String = "Hello, world!"
            }

          """
            .trimIndent(),
        )
      )

    val component =
      result.ExampleComponent.generatedLatticeComponentClass().createComponentViaFactory()
    assertThat(component.callComponentAccessorProperty<String>("value")).isEqualTo("Hello, world!")
  }

  @Test
  fun `providers from supertype companion objects are visible`() {
    // Ensure providers from supertypes are correctly wired. This means both incorporating them in
    // binding resolution and being able to invoke them correctly in the resulting component.
    val result =
      compile(
        kotlin(
          "ExampleComponent.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Component
            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Inject
            import dev.zacsweers.lattice.annotations.Named
            import dev.zacsweers.lattice.annotations.Singleton

            @Component
            interface ExampleComponent : TextProvider {

              val value: String

              @Component.Factory
              fun interface Factory {
                fun create(): ExampleComponent
              }
            }

            interface TextProvider {
              companion object {
                @Provides
                fun provideValue(): String = "Hello, world!"
              }
            }

          """
            .trimIndent(),
        )
      )

    val component =
      result.ExampleComponent.generatedLatticeComponentClass().createComponentViaFactory()
    assertThat(component.callComponentAccessorProperty<String>("value")).isEqualTo("Hello, world!")
  }

  @Test
  fun `providers overridden from supertypes take precedence`() {
    // Ensure that providers overridden from supertypes take precedence
    val result =
      compile(
        kotlin(
          "ExampleComponent.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Component
            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Inject
            import dev.zacsweers.lattice.annotations.Named
            import dev.zacsweers.lattice.annotations.Singleton

            @Component
            interface ExampleComponent : TextProvider {

              val value: String
              
              override fun provideValue(): String = "Hello, overridden world!"

              @Component.Factory
              fun interface Factory {
                fun create(): ExampleComponent
              }
            }

            interface TextProvider {
              @Provides
              fun provideValue(): String = "Hello, world!"
            }

          """
            .trimIndent(),
        )
      )

    val component =
      result.ExampleComponent.generatedLatticeComponentClass().createComponentViaFactory()
    assertThat(component.callComponentAccessorProperty<String>("value"))
      .isEqualTo("Hello, overridden world!")
  }

  @Test
  fun `unscoped providers get reused if used multiple times`() {
    // One aspect of provider fields is we want to reuse them if they're used from multiple places
    // even if they're unscoped
    //
    // private val stringProvider: Provider<String> = StringProvider_Factory.create(...)
    // private val stringUserProvider = StringUserProviderFactory.create(stringProvider)
    // private val stringUserProvider2 = StringUserProvider2Factory.create(stringProvider)
    val result =
      compile(
        kotlin(
          "ExampleComponent.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Component
            import dev.zacsweers.lattice.annotations.Provides

            @Component
            interface ExampleComponent {

              val valueLengths: Int

              @Provides
              fun provideValue(): String = "Hello, world!"

              @Provides
              fun provideValueLengths(value: String, value2: String): Int = value.length + value2.length
        
              @Component.Factory
              fun interface Factory {
                fun create(): ExampleComponent
              }
            }

          """
            .trimIndent(),
        )
      )

    val component =
      result.ExampleComponent.generatedLatticeComponentClass().createComponentViaFactory()

    // Assert we generated a shared field
    val provideValueField =
      component.javaClass.getDeclaredField("provideValueProvider").apply { isAccessible = true }

    // Get its instance
    @Suppress("UNCHECKED_CAST")
    val provideValueProvider = provideValueField.get(component) as Provider<String>

    // Get its computed value to plug in below
    val providerValue = provideValueProvider()
    assertThat(component.javaClass.getDeclaredField("provideValueProvider"))
    assertThat(component.callComponentAccessorProperty<Int>("valueLengths"))
      .isEqualTo(providerValue.length * 2)
  }

  @Test
  fun `unscoped providers do not get reused if used only once`() {
    // One aspect of provider fields is we want to reuse them if they're used from multiple places
    // even if they're unscoped. If they're not though, then we don't do this
    //
    // private val stringUserProvider =
    // StringUserProviderFactory.create(StringProvider_Factory.create(...))
    val result =
      compile(
        kotlin(
          "ExampleComponent.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Component
            import dev.zacsweers.lattice.annotations.Provides

            @Component
            interface ExampleComponent {

              val valueLengths: Int

              @Provides
              fun provideValue(): String = "Hello, world!"

              @Provides
              fun provideValueLengths(value: String): Int = value.length
        
              @Component.Factory
              fun interface Factory {
                fun create(): ExampleComponent
              }
            }

          """
            .trimIndent(),
        )
      )

    val component =
      result.ExampleComponent.generatedLatticeComponentClass().createComponentViaFactory()

    assertThat(
        component.javaClass.declaredFields.singleOrNull { it.name == "provideValueProvider" }
      )
      .isNull()

    assertThat(component.callComponentAccessorProperty<Int>("valueLengths"))
      .isEqualTo("Hello, world!".length)
  }

  @Test
  fun `unscoped components may not reference scoped types`() {
    val result =
      compile(
        kotlin(
          "ExampleComponent.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Component
            import dev.zacsweers.lattice.annotations.Provides
            import dev.zacsweers.lattice.annotations.Singleton

            @Component
            interface ExampleComponent {

              val value: String
              
              @Singleton
              @Provides
              fun provideValue(): String = "Hello, world!"
        
              @Component.Factory
              fun interface Factory {
                fun create(): ExampleComponent
              }
            }

          """
            .trimIndent(),
        ),
        expectedExitCode = ExitCode.COMPILATION_ERROR,
      )

    assertThat(result.messages)
      .contains(
        """
          ExampleComponent.kt:7:1 [LATTICE] [Lattice/IncompatiblyScopedBindings] test.ExampleComponent (unscoped) may not reference scoped bindings:
              kotlin.String 
              kotlin.String is requested at
                  [test.ExampleComponent] test.ExampleComponent.value
        """.trimIndent()
      )
  }

  // TODO
  //  - scoping
  //  - keys are reused. Provider with the same type key multiple times should call the provider
  //   twice. Unscoped should be the same, scoped should increment
  //  - advanced graph resolution (i.e. complex dep chains)
  //  - break-the-chain deps
  //  - provides
  //  - @get:Provides?
  //  - Binds examples
  //  - Component deps
  //  - Private providers?
  //  - Inherited exposed types + deduping overrides?
}
