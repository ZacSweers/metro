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
import com.tschuchort.compiletesting.SourceFile.Companion.kotlin
import dev.zacsweers.lattice.MembersInjector
import dev.zacsweers.lattice.Provider
import dev.zacsweers.lattice.annotations.Named
import dev.zacsweers.lattice.compiler.ExampleClass
import dev.zacsweers.lattice.compiler.LatticeCompilerTest
import dev.zacsweers.lattice.compiler.callProperty
import dev.zacsweers.lattice.compiler.generatedFactoryClass
import dev.zacsweers.lattice.compiler.generatedMembersInjector
import dev.zacsweers.lattice.compiler.getValue
import dev.zacsweers.lattice.compiler.invokeCreate
import dev.zacsweers.lattice.compiler.invokeNewInstance
import dev.zacsweers.lattice.compiler.staticInjectMethod
import dev.zacsweers.lattice.providerOf
import org.junit.Test

class MembersInjectTransformerTest : LatticeCompilerTest() {

  @Test
  fun simple() {
    val result =
      compile(
        kotlin(
          "ExampleClass.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Inject
            import dev.zacsweers.lattice.annotations.Named

            typealias StringList = List<String>

            // Generate a factory too to cover for https://github.com/square/anvil/issues/362
            @Inject
            class ExampleClass {
              @Inject lateinit var string: String
              @Named("qualified") @Inject lateinit var qualifiedString: String
              @Inject lateinit var charSequence: CharSequence
              @Inject lateinit var list: List<String>
              @Inject lateinit var pair: Pair<Pair<String, Int>, Set<String>>
              @Inject lateinit var set: @JvmSuppressWildcards Set<(StringList) -> StringList>
              var setterAnnotated: Map<String, String> = emptyMap()
                @Inject set
              @set:Inject var setterAnnotated2: Map<String, Boolean> = emptyMap()
              @Inject private lateinit var privateField: String
              @Inject
              lateinit var privateSetter: String
                private set
              
              override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false

                other as ExampleClass

                if (string != other.string) return false
                if (qualifiedString != other.qualifiedString) return false
                if (charSequence != other.charSequence) return false
                if (list != other.list) return false
                if (pair != other.pair) return false
                if (set.single().invoke(emptyList())[0] != other.set.single().invoke(emptyList())[0]) return false
                if (setterAnnotated != other.setterAnnotated) return false
                if (setterAnnotated2 != other.setterAnnotated2) return false
                if (privateField != other.privateField) return false
                if (privateSetter != other.privateSetter) return false

                return true
              }

              override fun hashCode(): Int {
                var result = string.hashCode()
                result = 31 * result + qualifiedString.hashCode()
                result = 31 * result + charSequence.hashCode()
                result = 31 * result + list.hashCode()
                result = 31 * result + pair.hashCode()
                result = 31 * result + set.single().invoke(emptyList())[0].hashCode()
                result = 31 * result + setterAnnotated.hashCode()
                result = 31 * result + setterAnnotated2.hashCode()
                result = 31 * result + privateField.hashCode()
                result = 31 * result + privateSetter.hashCode()
                return result
              }
            }

          """
            .trimIndent(),
        ),
        debug = true,
      )

    val membersInjector = result.ExampleClass.generatedMembersInjector()

    val constructor = membersInjector.declaredConstructors.single()
    assertThat(constructor.parameterTypes.toList())
      .containsExactly(
        Provider::class.java,
        Provider::class.java,
        Provider::class.java,
        Provider::class.java,
        Provider::class.java,
        Provider::class.java,
        Provider::class.java,
        Provider::class.java,
        Provider::class.java,
        Provider::class.java,
      )

    @Suppress("RedundantLambdaArrow", "UNCHECKED_CAST")
    val membersInjectorInstance =
      constructor.newInstance(
        Provider { "a" },
        Provider { "b" },
        Provider<CharSequence> { "c" },
        Provider { listOf("d") },
        Provider { Pair(Pair("e", 1), setOf("f")) },
        Provider { setOf { _: List<String> -> listOf("g") } },
        Provider { mapOf("Hello" to "World") },
        Provider { mapOf("Hello" to false) },
        providerOf("private field"),
        providerOf("private setter"),
      ) as MembersInjector<Any>

    val injectInstanceConstructor = result.ExampleClass.generatedFactoryClass().invokeNewInstance()
    membersInjectorInstance.injectMembers(injectInstanceConstructor)

    val injectInstanceStatic = result.ExampleClass.generatedFactoryClass().invokeNewInstance()

    membersInjector.staticInjectMethod("string").invoke(null, injectInstanceStatic, "a")
    membersInjector.staticInjectMethod("qualifiedString").invoke(null, injectInstanceStatic, "b")
    membersInjector
      .staticInjectMethod("charSequence")
      .invoke(null, injectInstanceStatic, "c" as CharSequence)
    membersInjector.staticInjectMethod("list").invoke(null, injectInstanceStatic, listOf("d"))
    membersInjector
      .staticInjectMethod("pair")
      .invoke(null, injectInstanceStatic, Pair(Pair("e", 1), setOf("f")))
    membersInjector
      .staticInjectMethod("set")
      .invoke(null, injectInstanceStatic, setOf { _: List<String> -> listOf("g") })
    // NOTE unlike dagger, we don't put the "Get" or "Set" names from property accessors in these
    membersInjector
      .staticInjectMethod("setterAnnotated")
      .invoke(null, injectInstanceStatic, mapOf("Hello" to "World"))
    membersInjector
      .staticInjectMethod("setterAnnotated2")
      .invoke(null, injectInstanceStatic, mapOf("Hello" to false))
    membersInjector.staticInjectMethod("privateField").invoke(null, injectInstanceStatic, "private field")
    membersInjector.staticInjectMethod("privateSetter").invoke(null, injectInstanceStatic, "private setter")

    assertThat(injectInstanceConstructor).isEqualTo(injectInstanceStatic)
    assertThat(injectInstanceConstructor).isNotSameInstanceAs(injectInstanceStatic)

    // TODO revisit if this is necessary for Lattice
    val namedAnnotation =
      membersInjector.staticInjectMethod("qualifiedString").annotations.singleOrNull {
        it.annotationClass == Named::class
      } ?: error("No qualifier annotation found!")
    assertThat(namedAnnotation.getValue<String>("name")).isEqualTo("qualified")
  }

  @Test
  fun `a factory class is generated for a field injection with Lazy and Provider`() {
    val result =
      compile(
        kotlin(
          "ExampleClass.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Inject
            import dev.zacsweers.lattice.Provider

            class ExampleClass {
              @Inject lateinit var string: String
              @Inject lateinit var stringProvider: Provider<String>
              @Inject lateinit var stringListProvider: Provider<List<String>>
              @Inject lateinit var lazyString: Lazy<String>

              override fun equals(other: Any?): Boolean {
                return toString() == other.toString()
              }
              override fun toString(): String {
               return string + stringProvider() +
                   stringListProvider()[0] + lazyString.value
              }
            }
          """
            .trimIndent(),
        ),
        debug = true,
      )

    val membersInjector = result.ExampleClass.generatedMembersInjector()

    val constructor = membersInjector.declaredConstructors.single()
    assertThat(constructor.parameterTypes.toList())
      .containsExactly(
        Provider::class.java,
        Provider::class.java,
        Provider::class.java,
        Provider::class.java,
      )

    @Suppress("UNCHECKED_CAST")
    val membersInjectorInstance =
      constructor.newInstance(
        Provider { "a" },
        Provider { "b" },
        Provider { listOf("c") },
        Provider { "d" },
      ) as MembersInjector<Any>

    val injectInstanceConstructor = result.ExampleClass.getDeclaredConstructor().newInstance()
    membersInjectorInstance.injectMembers(injectInstanceConstructor)

    val injectInstanceStatic = result.ExampleClass.getDeclaredConstructor().newInstance()

    membersInjector.staticInjectMethod("string").invoke(null, injectInstanceStatic, "a")
    membersInjector
      .staticInjectMethod("stringProvider")
      .invoke(null, injectInstanceStatic, Provider { "b" })
    membersInjector
      .staticInjectMethod("stringListProvider")
      .invoke(null, injectInstanceStatic, Provider { listOf("c") })
    membersInjector.staticInjectMethod("lazyString").invoke(null, injectInstanceStatic, lazyOf("d"))

    assertThat(injectInstanceConstructor).isEqualTo(injectInstanceStatic)
    assertThat(injectInstanceConstructor).isNotSameInstanceAs(injectInstanceStatic)
  }

  @Test
  fun `a factory class is generated for a field injection with Lazy wrapped in a Provider`() {
    val result =
      compile(
        kotlin(
          "ExampleClass.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Inject
            import dev.zacsweers.lattice.Provider

            class ExampleClass {
              @Inject lateinit var lazyStringProvider: Provider<Lazy<String>>

              override fun equals(other: Any?): Boolean {
                return toString() == other.toString()
              }
              override fun toString(): String {
               return lazyStringProvider().value
              }
            }
          """
            .trimIndent(),
        ),
        debug = true,
      )

    val membersInjector = result.ExampleClass.generatedMembersInjector()

    val constructor = membersInjector.declaredConstructors.single()
    assertThat(constructor.parameterTypes.toList()).containsExactly(Provider::class.java)

    val membersInjectorInstance = constructor.newInstance(Provider { "a" }) as MembersInjector<Any>

    val injectInstanceConstructor = result.ExampleClass.getDeclaredConstructor().newInstance()
    membersInjectorInstance.injectMembers(injectInstanceConstructor)

    val injectInstanceStatic = result.ExampleClass.getDeclaredConstructor().newInstance()

    membersInjector
      .staticInjectMethod("lazyStringProvider")
      .invoke(null, injectInstanceStatic, Provider { lazyOf("a") })

    assertThat(injectInstanceConstructor).isEqualTo(injectInstanceStatic)
    assertThat(injectInstanceConstructor).isNotSameInstanceAs(injectInstanceStatic)
  }

  @Test
  fun `a factory class is generated for a field injection on a super class`() {
    val result =
      compile(
        kotlin(
          "ExampleClass.kt",
          """
            package test

            import dev.zacsweers.lattice.annotations.Inject
            import dev.zacsweers.lattice.Provider

            class ExampleClass : Middle() {

              @Inject
              lateinit var name: String
            }

            abstract class Middle : Base() {

              @Inject
              lateinit var middle1: Set<Int>

              @Inject
              lateinit var middle2: Set<String>
            }

            abstract class Base {

              @Inject
              lateinit var base1: List<Int>

              @Inject
              lateinit var base2: List<String>
            }
          """
            .trimIndent(),
        ),
        debug = true,
      )

    val membersInjector = result.ExampleClass.generatedMembersInjector()

    val injectorConstructor = membersInjector.declaredConstructors.single()
    assertThat(injectorConstructor.parameterTypes.toList())
      .containsExactly(
        Provider::class.java,
        Provider::class.java,
        Provider::class.java,
        Provider::class.java,
        Provider::class.java,
      )

    val name = "name"
    val middle1 = setOf(1)
    val middle2 = setOf("middle2")
    val base1 = listOf(3)
    val base2 = listOf("base2")

    @Suppress("UNCHECKED_CAST")
    val injectorInstance =
      membersInjector.invokeCreate(
        Provider { base1 },
        Provider { base2 },
        Provider { middle1 },
        Provider { middle2 },
        Provider { name },
      ) as MembersInjector<Any>

    val classInstanceConstructor = result.ExampleClass.getDeclaredConstructor().newInstance()
    injectorInstance.injectMembers(classInstanceConstructor)

    assertThat(classInstanceConstructor.callProperty<Any>("name")).isEqualTo(name)
    assertThat(classInstanceConstructor.callProperty<Any>("middle1")).isEqualTo(middle1)
    assertThat(classInstanceConstructor.callProperty<Any>("middle2")).isEqualTo(middle2)
    assertThat(classInstanceConstructor.callProperty<Any>("base1")).isEqualTo(base1)
    assertThat(classInstanceConstructor.callProperty<Any>("base2")).isEqualTo(base2)

    val classInstanceStatic = result.ExampleClass.getDeclaredConstructor().newInstance()
    injectorInstance.injectMembers(classInstanceStatic)

    assertThat(classInstanceStatic.callProperty<Any>("name")).isEqualTo(name)
    assertThat(classInstanceStatic.callProperty<Any>("middle1")).isEqualTo(middle1)
    assertThat(classInstanceStatic.callProperty<Any>("middle2")).isEqualTo(middle2)
    assertThat(classInstanceStatic.callProperty<Any>("base1")).isEqualTo(base1)
    assertThat(classInstanceStatic.callProperty<Any>("base2")).isEqualTo(base2)
  }
}
