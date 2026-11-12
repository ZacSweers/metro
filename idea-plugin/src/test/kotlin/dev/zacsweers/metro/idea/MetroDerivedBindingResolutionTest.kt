// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.zacsweers.metro.compiler.graph.explanation.BindingReason
import dev.zacsweers.metro.idea.index.MetroResolutionService
import dev.zacsweers.metro.idea.model.BindingIndex
import dev.zacsweers.metro.idea.model.GraphQueryContext
import dev.zacsweers.metro.idea.model.KaBinding
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty

/** Editor resolution of keys that generic `@Provides` and `@Binds` functions derive. */
class MetroDerivedBindingResolutionTest : BasePlatformTestCase() {

  override fun setUp() {
    super.setUp()
    project.enableImmediateAutomaticRefresh()
    project.setMetroOptions(DERIVED_BINDINGS_OPTION)
    module.addMetroRuntimeLibrary()
  }

  /** A configured fixture with its index and the single graph's query context. */
  private class Fixture(
    val file: KtFile,
    val index: BindingIndex,
    val queryContext: GraphQueryContext,
  ) {
    /** The bindings the graph accessor [name] resolves to. */
    fun bindingsFor(name: String): List<KaBinding> {
      val accessor = file.declarationsIncludingNested().property(name)
      val consumer = checkNotNull(index.consumerEntryAt(accessor)) { "No consumer for $name" }
      return index.bindingsFor(consumer, queryContext)
    }

    /** The single binding for accessor [name], which must come from a generic function. */
    fun derivedBindingFor(name: String): KaBinding {
      val binding = bindingsFor(name).single()
      assertNotNull("$name should be derived", binding.derivation)
      return binding
    }
  }

  private fun configure(source: String, graphName: String = "AppGraph"): Fixture {
    val file = myFixture.configureMetroFile(source)
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val graph = index.graphs.single { it.name == graphName }
    val queryContext = checkNotNull(index.queryContext(index.contextsFor(graph).single()))
    return Fixture(file, index, queryContext)
  }

  private fun KaBinding.declarationName(): String? {
    return (pointer.element as? KtNamedFunction)?.name
  }

  /** Dependency keys without the graph instance that graph-declared providers need. */
  private fun KaBinding.dependencyKeys(): List<String> {
    return dependencies.map { it.typeKey.renderedType }.filterNot { it == "test.AppGraph" }
  }

  fun testDerivedKeyResolvesToGenericProvider() {
    val fixture =
      configure(
        """
        interface Encoder<T>
        class User

        @DependencyGraph
        interface AppGraph {
          val users: Encoder<List<User>>

          @Provides fun user(): Encoder<User> = error("unused")
          @Provides fun <T> list(element: Encoder<T>): Encoder<List<T>> = error("unused")
        }
        """,
      )

    val binding = fixture.derivedBindingFor("users")
    assertTrue(binding is KaBinding.Provided)
    assertEquals("list", binding.declarationName())
    assertEquals("test.Encoder<kotlin.collections.List<test.User>>", binding.typeKey.renderedType)
    assertEquals(listOf("test.Encoder<test.User>"), binding.dependencyKeys())
    assertEquals(
      listOf("T" to "test.User"),
      checkNotNull(binding.derivation).renderedTypeArguments(short = false),
    )
  }

  fun testNestedDerivationSubstitutesTheInnerKey() {
    val fixture =
      configure(
        """
        interface Encoder<T>

        @DependencyGraph
        interface AppGraph {
          val nested: Encoder<List<List<String>>>

          @Provides fun string(): Encoder<String> = error("unused")
          @Provides fun <T> list(element: Encoder<T>): Encoder<List<T>> = error("unused")
        }
        """,
      )

    val binding = fixture.derivedBindingFor("nested")
    assertEquals(
      listOf("test.Encoder<kotlin.collections.List<kotlin.String>>"),
      binding.dependencyKeys(),
    )
    val template = checkNotNull(binding.derivation).template
    val innerKey = binding.dependencies.last().typeKey
    val inner = fixture.index.derivedBindingFor(template, innerKey)
    assertEquals(listOf("test.Encoder<kotlin.String>"), checkNotNull(inner).dependencyKeys())
  }

  fun testNullableAndUnboundedPatternsFollowNullability() {
    val fixture =
      configure(
        """
        interface Encoder<T>
        interface Box<T>
        class User

        @DependencyGraph
        interface AppGraph {
          val nullableUser: Encoder<User?>
          val nullableBox: Box<String?>

          @Provides fun user(): Encoder<User> = error("unused")
          @Provides fun <T : Any> nullable(inner: Encoder<T>): Encoder<T?> = error("unused")
          @Provides fun <T> box(): Box<T> = error("unused")
        }
        """,
      )

    val nullable = fixture.derivedBindingFor("nullableUser")
    assertEquals("nullable", nullable.declarationName())
    assertEquals(listOf("test.Encoder<test.User>"), nullable.dependencyKeys())

    val box = fixture.derivedBindingFor("nullableBox")
    assertEquals(
      listOf("T" to "kotlin.String?"),
      checkNotNull(box.derivation).renderedTypeArguments(short = false),
    )
  }

  fun testBoundsDecideWhetherAProviderApplies() {
    val fixture =
      configure(
        """
        interface Box<T>
        interface Numbers<T>

        @DependencyGraph
        interface AppGraph {
          val box: Box<String>
          val nullableBox: Box<String?>
          val numbers: Numbers<Int>

          @Provides fun <T : Any> box(): Box<T> = error("unused")
          @Provides fun <T : Number> numbers(): Numbers<T> = error("unused")
        }
        """,
      )

    assertEquals("box", fixture.derivedBindingFor("box").declarationName())
    // A nullable argument breaks the non-null bound, so no binding applies.
    assertEquals(emptyList<KaBinding>(), fixture.bindingsFor("nullableBox"))
    // Class bounds can't be checked without a session, so they're assumed to hold.
    val numbers = fixture.derivedBindingFor("numbers")
    assertFalse(checkNotNull(numbers.derivation).boundsVerified)
  }

  fun testMostSpecificProviderWinsAndTheOtherIsLessSpecific() {
    val fixture =
      configure(
        """
        interface Encoder<T>

        @DependencyGraph
        interface AppGraph {
          val strings: Encoder<List<String>>

          @Provides fun string(): Encoder<String> = error("unused")
          @Provides fun <T> any(): Encoder<T> = error("unused")
          @Provides fun <T> list(element: Encoder<T>): Encoder<List<T>> = error("unused")
        }
        """,
      )

    assertEquals("list", fixture.derivedBindingFor("strings").declarationName())

    val accessor = fixture.file.declarationsIncludingNested().property("strings")
    val consumer = checkNotNull(fixture.index.consumerEntryAt(accessor))
    val explanation =
      fixture.index.withResolutionSession { session ->
        fixture.index.explainBindings(session, consumer, fixture.queryContext)
      }
    val selected = explanation.candidates.single { it.selected }
    assertEquals(BindingReason.SELECTED_DERIVED, selected.reasonCode)
    assertEquals(
      listOf("Derived from `test.AppGraph.list` with `T = kotlin.String`."),
      selected.details,
    )
    val rejected = explanation.candidates.single { it.reasonCode == BindingReason.LESS_SPECIFIC }
    assertEquals("any", rejected.binding.declarationName())
    assertEquals(listOf("list"), rejected.relatedBindings.map { it.declarationName() })
  }

  fun testExactBindingWinsOverGenericProvider() {
    val fixture =
      configure(
        """
        interface Encoder<T>

        @DependencyGraph
        interface AppGraph {
          val strings: Encoder<List<String>>

          @Provides fun strings(): Encoder<List<String>> = error("unused")
          @Provides fun <T> list(element: Encoder<T>): Encoder<List<T>> = error("unused")
        }
        """,
      )

    val binding = fixture.bindingsFor("strings").single()
    assertNull(binding.derivation)
    assertEquals("strings", binding.declarationName())
  }

  fun testInjectClassIsUsedWhenNoPatternMatches() {
    val fixture =
      configure(
        """
        @Inject class Holder<T>

        @DependencyGraph
        interface AppGraph {
          val holder: Holder<String>

          @Provides fun <T> listHolder(): Holder<List<T>> = error("unused")
        }
        """,
      )

    assertTrue(fixture.bindingsFor("holder").single() is KaBinding.ConstructorInjected)
  }

  fun testQualifiedGenericProviderOnlyServesItsQualifier() {
    val fixture =
      configure(
        """
        interface Encoder<T>

        @DependencyGraph
        interface AppGraph {
          @Named("pretty") val pretty: Encoder<String>
          val plain: Encoder<String>

          @Named("pretty") @Provides fun <T> pretty(): Encoder<T> = error("unused")
        }
        """,
      )

    assertEquals("pretty", fixture.derivedBindingFor("pretty").declarationName())
    assertEquals(emptyList<KaBinding>(), fixture.bindingsFor("plain"))
  }

  fun testGenericBindsAliasesTheSubstitutedImplementation() {
    val fixture =
      configure(
        """
        interface Cache<T>
        @Inject class LruCache<T> : Cache<T>

        @DependencyGraph
        interface AppGraph {
          val cache: Cache<String>

          @Binds fun <T> lru(impl: LruCache<T>): Cache<T>
        }
        """,
      )

    val binding = fixture.derivedBindingFor("cache")
    assertTrue(binding is KaBinding.Alias)
    assertEquals("lru", binding.declarationName())
    assertEquals(
      "test.LruCache<kotlin.String>",
      (binding as KaBinding.Alias).consumedKey?.typeKey?.renderedType,
    )
  }

  fun testTypeAliasInRequestedTypeIsExpandedBeforeMatching() {
    val fixture =
      configure(
        """
        interface Encoder<T>
        class User
        typealias Users = List<User>

        @DependencyGraph
        interface AppGraph {
          val users: Encoder<Users>

          @Provides fun user(): Encoder<User> = error("unused")
          @Provides fun <T> list(element: Encoder<T>): Encoder<List<T>> = error("unused")
        }
        """,
      )

    val binding = fixture.derivedBindingFor("users")
    assertEquals(listOf("test.Encoder<test.User>"), binding.dependencyKeys())
  }

  fun testConsumersOfDerivedKeysAreUsagesOfTheGenericProvider() {
    val fixture =
      configure(
        """
        interface Encoder<T>
        class User

        @DependencyGraph
        interface AppGraph {
          val users: Encoder<List<User>>
          val strings: Encoder<List<String>>
          val user: Encoder<User>

          @Provides fun user(): Encoder<User> = error("unused")
          @Provides fun string(): Encoder<String> = error("unused")
          @Provides fun <T> list(element: Encoder<T>): Encoder<List<T>> = error("unused")
        }
        """,
      )

    val list = fixture.file.declarationsIncludingNested().function("list")
    val templates = fixture.index.bindingEntriesAt(list)
    assertEquals(1, templates.size)
    val consumers = fixture.index.consumersFor(templates)
    assertEquals(
      setOf("users", "strings"),
      consumers.mapTo(mutableSetOf()) { (it.pointer.element as? KtProperty)?.name },
    )
  }

  fun testGenericProvidersAreIgnoredWhenDerivedBindingsAreDisabled() {
    project.setMetroOptions()
    val fixture =
      configure(
        """
        interface Encoder<T>

        @DependencyGraph
        interface AppGraph {
          val strings: Encoder<List<String>>

          @Provides fun string(): Encoder<String> = error("unused")
          @Provides fun <T> list(element: Encoder<T>): Encoder<List<T>> = error("unused")
        }
        """,
      )

    assertEquals(emptyList<KaBinding>(), fixture.bindingsFor("strings"))
  }

  companion object {
    val DERIVED_BINDINGS_OPTION = "enable-derived-bindings" to "true"
  }
}
