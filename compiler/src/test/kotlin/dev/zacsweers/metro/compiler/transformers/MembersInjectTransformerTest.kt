// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.transformers

import com.google.common.truth.Truth.assertThat
import com.tschuchort.compiletesting.KotlinCompilation
import dev.zacsweers.metro.MembersInjector
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.compiler.ExampleClass
import dev.zacsweers.metro.compiler.ExampleClass2
import dev.zacsweers.metro.compiler.ExampleGraph
import dev.zacsweers.metro.compiler.MetroCompilerTest
import dev.zacsweers.metro.compiler.assertDiagnostics
import dev.zacsweers.metro.compiler.callFunction
import dev.zacsweers.metro.compiler.callInject
import dev.zacsweers.metro.compiler.callProperty
import dev.zacsweers.metro.compiler.createGraphViaFactory
import dev.zacsweers.metro.compiler.createGraphWithNoArgs
import dev.zacsweers.metro.compiler.generatedFactoryClass
import dev.zacsweers.metro.compiler.generatedImpl
import dev.zacsweers.metro.compiler.generatedMembersInjector
import dev.zacsweers.metro.compiler.invokeCreate
import dev.zacsweers.metro.compiler.invokeNewInstance
import dev.zacsweers.metro.compiler.newInstanceStrict
import dev.zacsweers.metro.compiler.staticInjectMethod
import dev.zacsweers.metro.providerOf
import java.lang.reflect.Constructor
import org.junit.Test

class MembersInjectTransformerTest : MetroCompilerTest() {

  @Test
  fun simple() {
    val result =
      compile(
        source(
          """
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
            .trimIndent()
        )
      )

    val membersInjector = result.ExampleClass.generatedMembersInjector()

    val constructor = membersInjector.injectorClassConstructor()
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
        providerOf("a"),
        providerOf("b"),
        providerOf<CharSequence>("c"),
        providerOf(listOf("d")),
        providerOf(Pair(Pair("e", 1), setOf("f"))),
        providerOf(setOf { _: List<String> -> listOf("g") }),
        providerOf(mapOf("Hello" to "World")),
        providerOf(mapOf("Hello" to false)),
        providerOf("private field"),
        providerOf("private setter"),
      ) as MembersInjector<Any>

    val injectInstanceConstructor = result.ExampleClass.generatedFactoryClass().invokeNewInstance()
    membersInjectorInstance.injectMembers(injectInstanceConstructor)

    val injectInstanceStatic = result.ExampleClass.generatedFactoryClass().invokeNewInstance()

    membersInjector.staticInjectMethod("string").invoke(injectInstanceStatic, "a")
    membersInjector.staticInjectMethod("qualifiedString").invoke(injectInstanceStatic, "b")
    membersInjector
      .staticInjectMethod("charSequence")
      .invoke(injectInstanceStatic, "c" as CharSequence)
    membersInjector.staticInjectMethod("list").invoke(injectInstanceStatic, listOf("d"))
    membersInjector
      .staticInjectMethod("pair")
      .invoke(injectInstanceStatic, Pair(Pair("e", 1), setOf("f")))
    membersInjector
      .staticInjectMethod("set")
      .invoke(injectInstanceStatic, setOf { _: List<String> -> listOf("g") })
    // NOTE unlike dagger, we don't put the "Get" or "Set" names from property accessors in these
    membersInjector
      .staticInjectMethod("setterAnnotated")
      .invoke(injectInstanceStatic, mapOf("Hello" to "World"))
    membersInjector
      .staticInjectMethod("setterAnnotated2")
      .invoke(injectInstanceStatic, mapOf("Hello" to false))
    membersInjector.staticInjectMethod("privateField").invoke(injectInstanceStatic, "private field")
    membersInjector
      .staticInjectMethod("privateSetter")
      .invoke(injectInstanceStatic, "private setter")

    assertThat(injectInstanceConstructor).isEqualTo(injectInstanceStatic)
    assertThat(injectInstanceConstructor).isNotSameInstanceAs(injectInstanceStatic)

    // NOTE change from Anvil - we don't copy qualifier annotations
  }

  @Test
  fun `a factory class is generated for a field injection with Lazy and Provider`() {
    val result =
      compile(
        source(
          """
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
            .trimIndent()
        )
      )

    val membersInjector = result.ExampleClass.generatedMembersInjector()

    val constructor = membersInjector.injectorClassConstructor()
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
        providerOf("a"),
        providerOf("b"),
        providerOf(listOf("c")),
        providerOf("d"),
      ) as MembersInjector<Any>

    val injectInstanceConstructor = result.ExampleClass.newInstanceStrict()
    membersInjectorInstance.injectMembers(injectInstanceConstructor)

    val injectInstanceStatic = result.ExampleClass.newInstanceStrict()

    membersInjector.staticInjectMethod("string").invoke(injectInstanceStatic, "a")
    membersInjector
      .staticInjectMethod("stringProvider")
      .invoke(injectInstanceStatic, providerOf("b"))
    membersInjector
      .staticInjectMethod("stringListProvider")
      .invoke(injectInstanceStatic, providerOf(listOf("c")))
    membersInjector.staticInjectMethod("lazyString").invoke(injectInstanceStatic, lazyOf("d"))

    assertThat(injectInstanceConstructor).isEqualTo(injectInstanceStatic)
    assertThat(injectInstanceConstructor).isNotSameInstanceAs(injectInstanceStatic)
  }

  @Test
  fun `a factory class is generated for a field injection with Lazy wrapped in a Provider`() {
    val result =
      compile(
        source(
          """
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
            .trimIndent()
        )
      )

    val membersInjector = result.ExampleClass.generatedMembersInjector()

    val constructor = membersInjector.injectorClassConstructor()
    assertThat(constructor.parameterTypes.toList()).containsExactly(Provider::class.java)

    @Suppress("UNCHECKED_CAST")
    val membersInjectorInstance = constructor.newInstance(providerOf("a")) as MembersInjector<Any>

    val injectInstanceConstructor = result.ExampleClass.newInstanceStrict()
    membersInjectorInstance.injectMembers(injectInstanceConstructor)

    val injectInstanceStatic = result.ExampleClass.newInstanceStrict()

    membersInjector
      .staticInjectMethod("lazyStringProvider")
      .invoke(injectInstanceStatic, providerOf(lazyOf("a")))

    assertThat(injectInstanceConstructor).isEqualTo(injectInstanceStatic)
    assertThat(injectInstanceConstructor).isNotSameInstanceAs(injectInstanceStatic)
  }

  @Test
  fun `a factory class is generated for a field injection on a super class`() {
    val result =
      compile(
        source(
          """
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
            .trimIndent()
        )
      )

    val membersInjector = result.ExampleClass.generatedMembersInjector()

    val injectorConstructor = membersInjector.injectorClassConstructor()
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
        providerOf(base1),
        providerOf(base2),
        providerOf(middle1),
        providerOf(middle2),
        providerOf(name),
      ) as MembersInjector<Any>

    val classInstanceConstructor = result.ExampleClass.newInstanceStrict()
    injectorInstance.injectMembers(classInstanceConstructor)

    assertThat(classInstanceConstructor.callProperty<Any>("name")).isEqualTo(name)
    assertThat(classInstanceConstructor.callProperty<Any>("middle1")).isEqualTo(middle1)
    assertThat(classInstanceConstructor.callProperty<Any>("middle2")).isEqualTo(middle2)
    assertThat(classInstanceConstructor.callProperty<Any>("base1")).isEqualTo(base1)
    assertThat(classInstanceConstructor.callProperty<Any>("base2")).isEqualTo(base2)

    val classInstanceStatic = result.ExampleClass.newInstanceStrict()
    injectorInstance.injectMembers(classInstanceStatic)

    assertThat(classInstanceStatic.callProperty<Any>("name")).isEqualTo(name)
    assertThat(classInstanceStatic.callProperty<Any>("middle1")).isEqualTo(middle1)
    assertThat(classInstanceStatic.callProperty<Any>("middle2")).isEqualTo(middle2)
    assertThat(classInstanceStatic.callProperty<Any>("base1")).isEqualTo(base1)
    assertThat(classInstanceStatic.callProperty<Any>("base2")).isEqualTo(base2)
  }

  @Test
  fun `a factory class is generated for a field injection with a generic class`() {
    compile(
      source(
        """
          abstract class ExampleClass<T> {
            @Inject lateinit var string: String
          }
          """
      )
    ) {
      val membersInjector = ExampleClass.generatedMembersInjector()

      val constructor = membersInjector.injectorClassConstructor()
      assertThat(constructor.parameterTypes.toList()).containsExactly(Provider::class.java)
    }
  }

  @Test
  fun `a factory class is generated for a generic field injection with a generic class`() {
    compile(
      source(
        """
          class ExampleClass<T, R> {
            @Inject lateinit var unknownItems: List<T>
          }
          """
      )
    ) {
      val membersInjector = ExampleClass.generatedMembersInjector()

      val constructor = membersInjector.injectorClassConstructor()
      assertThat(constructor.parameterTypes.toList()).containsExactly(Provider::class.java)
    }
  }

  @Test
  fun `a factory class is generated for a field injection in a class with a parent class with a generic field injection`() {
    compile(
      source(
        """
          abstract class Base<T> {
            @Inject lateinit var unknownItems: List<T>
          }

          class ExampleClass : Base<String>() {
            @Inject lateinit var numbers: List<Int>

            override fun equals(other: Any?): Boolean {
              if (this === other) return true
              if (javaClass != other?.javaClass) return false

              other as ExampleClass

              if (unknownItems != other.unknownItems) return false
              if (numbers != other.numbers) return false

              return true
            }
          }
          """
      )
    ) {
      val baseMembersInjector = classLoader.loadClass("test.Base").generatedMembersInjector()
      val injectClassMembersInjector = ExampleClass.generatedMembersInjector()

      val constructor = injectClassMembersInjector.injectorClassConstructor()
      assertThat(constructor.parameterTypes.toList())
        .containsExactly(Provider::class.java, Provider::class.java)

      @Suppress("UNCHECKED_CAST")
      val membersInjectorInstance =
        constructor.newInstance(providerOf(listOf("a", "b")), providerOf(listOf(1, 2)))
          as MembersInjector<Any>

      val injectInstanceConstructor = ExampleClass.newInstanceStrict()
      membersInjectorInstance.injectMembers(injectInstanceConstructor)

      val injectInstanceStatic = ExampleClass.newInstanceStrict()

      injectClassMembersInjector
        .staticInjectMethod("numbers")
        .invoke(injectInstanceStatic, listOf(1, 2))
      baseMembersInjector
        .staticInjectMethod("unknownItems")
        .invoke(injectInstanceStatic, listOf("a", "b"))

      assertThat(injectInstanceConstructor).isEqualTo(injectInstanceStatic)
      assertThat(injectInstanceConstructor).isNotSameInstanceAs(injectInstanceStatic)
    }
  }

  @Test
  fun `a factory class is generated for a field injection in a class with an ancestor class with a generic field injection`() {
    compile(
      source(
        """
          abstract class Base<T> {
            @Inject lateinit var unknownItems: List<T>
          }

          abstract class Middle<R> : Base<R>() {
            @Inject lateinit var numbers: List<Int>

            override fun equals(other: Any?): Boolean {
              if (this === other) return true
              if (javaClass != other?.javaClass) return false

              other as ExampleClass

              if (unknownItems != other.unknownItems) return false
              if (numbers != other.numbers) return false

              return true
            }
          }

          class ExampleClass : Middle<String>() {
            @Inject lateinit var bools: List<Boolean>
          }
          """
      )
    ) {
      val baseMembersInjector = classLoader.loadClass("test.Base").generatedMembersInjector()
      val middleMembersInjector = classLoader.loadClass("test.Middle").generatedMembersInjector()
      val injectClassMembersInjector = ExampleClass.generatedMembersInjector()

      val constructor = injectClassMembersInjector.injectorClassConstructor()
      assertThat(constructor.parameterTypes.toList())
        .containsExactly(Provider::class.java, Provider::class.java, Provider::class.java)

      @Suppress("UNCHECKED_CAST")
      val membersInjectorInstance =
        constructor.newInstance(
          providerOf(listOf("a", "b")),
          providerOf(listOf(1, 2)),
          providerOf(listOf(true)),
        ) as MembersInjector<Any>

      val injectInstanceConstructor = ExampleClass.newInstanceStrict()
      membersInjectorInstance.injectMembers(injectInstanceConstructor)

      val injectInstanceStatic = ExampleClass.newInstanceStrict()

      injectClassMembersInjector
        .staticInjectMethod("bools")
        .invoke(injectInstanceStatic, listOf(true))
      middleMembersInjector.staticInjectMethod("numbers").invoke(injectInstanceStatic, listOf(1, 2))
      baseMembersInjector
        .staticInjectMethod("unknownItems")
        .invoke(injectInstanceStatic, listOf("a", "b"))

      assertThat(injectInstanceConstructor).isEqualTo(injectInstanceStatic)
      assertThat(injectInstanceConstructor).isNotSameInstanceAs(injectInstanceStatic)
    }
  }

  @Test
  fun `a member injector is generated for a class with a super class in another module`() {
    val otherModuleResult =
      compile(
        source(
          source =
            """
          abstract class Base {
            @Inject lateinit var string: String
          }
          """
        )
      )

    compile(
      source(
        """
          class ExampleClass : Base() {
            @Inject lateinit var numbers: List<Int>

            override fun equals(other: Any?): Boolean {
              if (this === other) return true
              if (javaClass != other?.javaClass) return false

              other as ExampleClass

              if (numbers != other.numbers) return false
              if (string != other.string) return false

              return true
            }
          }
          """
      ),
      previousCompilationResult = otherModuleResult,
    ) {
      val baseMembersInjector = classLoader.loadClass("test.Base").generatedMembersInjector()

      val injectClassMembersInjector = ExampleClass.generatedMembersInjector()

      val constructor = injectClassMembersInjector.injectorClassConstructor()
      assertThat(constructor.parameterTypes.toList())
        .containsExactly(Provider::class.java, Provider::class.java)

      @Suppress("UNCHECKED_CAST")
      val membersInjectorInstance =
        constructor.newInstance(providerOf("a"), providerOf(listOf(1, 2))) as MembersInjector<Any>

      val injectInstanceConstructor = ExampleClass.newInstanceStrict()
      membersInjectorInstance.injectMembers(injectInstanceConstructor)

      val injectInstanceStatic = ExampleClass.newInstanceStrict()

      injectClassMembersInjector
        .staticInjectMethod("numbers")
        .invoke(injectInstanceStatic, listOf(1, 2))
      baseMembersInjector.staticInjectMethod("string").invoke(injectInstanceStatic, "a")

      assertThat(injectInstanceConstructor).isEqualTo(injectInstanceStatic)
      assertThat(injectInstanceConstructor).isNotSameInstanceAs(injectInstanceStatic)
    }
  }

  @Test
  fun `multiple members injection on a single graph are permitted`() {
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {
          @DependencyGraph.Factory
          fun interface Factory {
            fun create(@Provides value: Any): ExampleGraph
          }
          fun inject(exampleClass1: ExampleClass)
          fun inject(exampleClass2: ExampleClass2)
        }

        class ExampleClass {
          @Inject lateinit var value: Any
        }
        class ExampleClass2 {
          @Inject lateinit var value: Any
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphViaFactory(3)
      val instance = ExampleClass.newInstanceStrict()
      val instance2 = ExampleClass2.newInstanceStrict()
      graph.callInject(instance)
      graph.callInject(instance2)
      assertThat(instance.callProperty<Any>("value")).isEqualTo(3)
      assertThat(instance2.callProperty<Any>("value")).isEqualTo(3)
    }
  }

  @Test
  fun `graph empty inject function`() {
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {
          fun inject(value: ExampleClass)
        }

        class ExampleClass
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val instance = ExampleClass.newInstanceStrict()
      // noop call
      graph.callInject(instance)
    }
  }

  @Test
  fun `graph inject function simple`() {
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {
          fun inject(value: ExampleClass)

          @DependencyGraph.Factory
          fun interface Factory {
            fun create(@Provides value: Int): ExampleGraph
          }
        }

        class ExampleClass {
          @Inject var int: Int = 2
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphViaFactory(3)
      val instance = ExampleClass.newInstanceStrict()
      graph.callInject(instance)
      assertThat(instance.callProperty<Int>("int")).isEqualTo(3)
    }
  }

  @Test
  fun `graph inject function simple constructed class`() {
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {
          val exampleClass: ExampleClass

          @DependencyGraph.Factory
          fun interface Factory {
            fun create(@Provides value: Int, @Provides value2: Long): ExampleGraph
          }
        }

        @Inject
        class ExampleClass(val long: Long) {
          @Inject var int: Int = 2
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphViaFactory(3, 4L)
      val instance = graph.callProperty<Any>("exampleClass")
      assertThat(instance.callProperty<Int>("int")).isEqualTo(3)
      assertThat(instance.callProperty<Long>("long")).isEqualTo(4L)
    }
  }

  @Test
  fun `graph inject function simple constructed class with inherited members`() {
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {
          val exampleClass: ExampleClass

          @DependencyGraph.Factory
          fun interface Factory {
            fun create(@Provides int: Int, @Provides long: Long): ExampleGraph
          }
        }

        abstract class Base {
          @Inject var baseLong: Long = 0L
        }

        @Inject
        class ExampleClass(val long: Long) : Base() {
          @Inject var int: Int = 2
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphViaFactory(3, 4L)
      val instance = graph.callProperty<Any>("exampleClass")
      assertThat(instance.callProperty<Int>("int")).isEqualTo(3)
      assertThat(instance.callProperty<Long>("long")).isEqualTo(4L)
      assertThat(instance.callProperty<Long>("baseLong")).isEqualTo(4L)
    }
  }

  @Test
  fun `graph inject function simple constructed class with private inherited members`() {
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {
          val exampleClass: ExampleClass

          @DependencyGraph.Factory
          fun interface Factory {
            fun create(@Provides value: Int, @Provides value2: Long): ExampleGraph
          }
        }

        abstract class Base {
          @Inject private var privateBaseLong: Long = 0L
          fun baseLong() = privateBaseLong
        }

        @Inject
        class ExampleClass(val long: Long) : Base() {
          @Inject var int: Int = 2
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphViaFactory(3, 4L)
      val instance = graph.callProperty<Any>("exampleClass")
      assertThat(instance.callProperty<Int>("int")).isEqualTo(3)
      assertThat(instance.callProperty<Long>("long")).isEqualTo(4L)
      assertThat(instance.callFunction<Long>("baseLong")).isEqualTo(4L)
    }
  }

  @Test
  fun `graph inject function - constructor injected`() {
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {
          val exampleClass: ExampleClass

          @DependencyGraph.Factory
          fun interface Factory {
            fun create(@Provides value: Int, @Provides value2: Long): ExampleGraph
          }
        }
        @Inject
        class ExampleClass {
          var long: Long = 0
          var int: Int = 0

          @Inject fun injectValues(long: Long, int: Int) {
            this.long = long
            this.int = int
          }
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphViaFactory(3, 4L)
      val instance = graph.callProperty<Any>("exampleClass")
      assertThat(instance.callProperty<Int>("int")).isEqualTo(3)
      assertThat(instance.callProperty<Long>("long")).isEqualTo(4L)
    }
  }

  @Test
  fun `graph inject function - constructor injected - with qualifiers`() {
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {
          val exampleClass: ExampleClass

          @DependencyGraph.Factory
          fun interface Factory {
            fun create(@Provides @Named("int") value: Int, @Provides value2: Long): ExampleGraph
          }
        }
        @Inject
        class ExampleClass {
          var long: Long = 0
          var int: Int = 0

          @Inject fun injectValues(long: Long, @Named("int") int: Int) {
            this.long = long
            this.int = int
          }
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphViaFactory(3, 4L)
      val instance = graph.callProperty<Any>("exampleClass")
      assertThat(instance.callProperty<Int>("int")).isEqualTo(3)
      assertThat(instance.callProperty<Long>("long")).isEqualTo(4L)
    }
  }

  @Test
  fun `graph inject function - graph injector`() {
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {
          fun inject(exampleClass: ExampleClass)

          @DependencyGraph.Factory
          fun interface Factory {
            fun create(@Provides value: Int, @Provides value2: Long): ExampleGraph
          }
        }

        class ExampleClass {
          var long: Long = 0
          var int: Int = 0

          @Inject fun injectValues(long: Long, int: Int) {
            this.long = long
            this.int = int
          }
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphViaFactory(3, 4L)
      val instance = ExampleClass.newInstanceStrict()
      graph.callInject(instance)
      assertThat(instance.callProperty<Int>("int")).isEqualTo(3)
      assertThat(instance.callProperty<Long>("long")).isEqualTo(4L)
    }
  }

  @Test
  fun `graph inject function - graph injector - with qualifier`() {
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {
          fun inject(exampleClass: ExampleClass)

          @DependencyGraph.Factory
          fun interface Factory {
            fun create(@Provides @Named("int") value: Int, @Provides value2: Long): ExampleGraph
          }
        }

        class ExampleClass {
          var long: Long = 0
          var int: Int = 0

          @Inject fun injectValues(long: Long, @Named("int") int: Int) {
            this.long = long
            this.int = int
          }
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphViaFactory(3, 4L)
      val instance = ExampleClass.newInstanceStrict()
      graph.callInject(instance)
      assertThat(instance.callProperty<Int>("int")).isEqualTo(3)
      assertThat(instance.callProperty<Long>("long")).isEqualTo(4L)
    }
  }

  @Test
  fun `graph inject function - member's dependency bindings should be picked up by graph`() {
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {
          fun inject(exampleClass: ExampleClass)
        }
        class ExampleClass {
          var int: Int = 0
          @Inject fun injectValues(dependency: Dependency) {
            this.int = dependency.int
          }
        }
        @Inject
        class Dependency {
          val int: Int = 3
        }
        """
          .trimIndent()
      )
    ) {
      val graph = ExampleGraph.generatedImpl().createGraphWithNoArgs()
      val instance = ExampleClass.newInstanceStrict()
      graph.callInject(instance)
      assertThat(instance.callProperty<Int>("int")).isEqualTo(3)
    }
  }

  @Test
  fun `lateinit member inject of missing dep should fail`() {
    compile(
      source(
        """
        @DependencyGraph
        interface ExampleGraph {
          fun inject(exampleClass: ExampleClass)
        }
        class ExampleClass {
          @Inject lateinit var value: String
        }
        """
          .trimIndent()
      ),
      expectedExitCode = KotlinCompilation.ExitCode.COMPILATION_ERROR,
    ) {
      assertDiagnostics(
        """
        e: ExampleGraph.kt:7:11 [Metro/MissingBinding] Cannot find an @Inject constructor or @Provides-annotated function/property for: kotlin.String

            kotlin.String is injected at
                [test.ExampleGraph] test.ExampleGraph.inject()
            dev.zacsweers.metro.MembersInjector<test.ExampleClass> is requested at
                [test.ExampleGraph] test.ExampleGraph.inject()
        """
          .trimIndent()
      )
    }
  }

  private fun Class<MembersInjector<*>>.injectorClassConstructor(): Constructor<*> {
    return declaredConstructors.single { !it.isSynthetic }.also { it.isAccessible = true }
  }
}
