// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnosticId
import dev.zacsweers.metro.idea.graph.KaGraphValidationResult
import dev.zacsweers.metro.idea.graph.MetroGraphValidationService
import dev.zacsweers.metro.idea.index.MetroResolutionService
import dev.zacsweers.metro.idea.model.KaBinding
import org.jetbrains.kotlin.psi.KtFile

/** Seals graphs that use derived bindings and checks the derived binding diagnostics. */
class MetroDerivedBindingValidationTest : BasePlatformTestCase() {

  override fun setUp() {
    super.setUp()
    project.setMetroOptions(MetroDerivedBindingResolutionTest.DERIVED_BINDINGS_OPTION)
    module.addMetroRuntimeLibrary()
    project.service<MetroGraphValidationService>().clearResults()
  }

  private fun validate(
    source: String,
    graphName: String = "AppGraph",
  ): KaGraphValidationResult.Completed {
    val file = myFixture.configureMetroFile(source)
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val graph = index.graphs.single { it.name == graphName }
    return project
      .service<MetroGraphValidationService>()
      .validate(file, index.contextsFor(graph).single())
      .requireCompleted()
  }

  private fun KaGraphValidationResult.Completed.assertNoDiagnostics() {
    assertTrue(diagnostics.joinToString { it.render() }, diagnostics.isEmpty())
  }

  private fun KaGraphValidationResult.Completed.binding(renderedKey: String): KaBinding {
    return bindings.asMap().values.single { it.typeKey.renderedType == renderedKey }
  }

  fun testNestedDerivationAndGenericBindsValidate() {
    val result =
      validate(
        """
        interface Encoder<T>
        interface Cache<T>
        @Inject class LruCache<T> : Cache<T>

        @BindingContainer
        interface Bindings {
          @Binds fun <T> lru(impl: LruCache<T>): Cache<T>

          companion object {
            @Provides fun string(): Encoder<String> = error("unused")
            @Provides fun <T> list(element: Encoder<T>): Encoder<List<T>> = error("unused")
          }
        }

        @DependencyGraph(bindingContainers = [Bindings::class])
        interface AppGraph {
          val nested: Encoder<List<List<String>>>
          val cache: Cache<String>
        }
        """,
      )

    result.assertNoDiagnostics()
    val nested =
      result.binding(
        "test.Encoder<kotlin.collections.List<kotlin.collections.List<kotlin.String>>>",
      )
    assertNotNull(nested.derivation)
    assertNotNull(result.binding("test.Encoder<kotlin.collections.List<kotlin.String>>").derivation)
    assertTrue(result.binding("test.LruCache<kotlin.String>") is KaBinding.ConstructorInjected)
  }

  fun testMissingParameterOfDerivedBindingIsAnOrdinaryMissingBinding() {
    val result =
      validate(
        """
        interface Encoder<T>
        class User

        @BindingContainer
        object Encoders {
          @Provides fun <T> list(element: Encoder<T>): Encoder<List<T>> = error("unused")
        }

        @DependencyGraph(bindingContainers = [Encoders::class])
        interface AppGraph {
          val users: Encoder<List<User>>
        }
        """,
      )

    val diagnostic = result.diagnostics.single()
    assertEquals(MetroDiagnosticId.MISSING_BINDING, diagnostic.id)
    assertTrue(
      diagnostic.render(),
      diagnostic.render().contains("No binding found for Encoder<User>"),
    )
  }

  fun testMissingBindingNamesTheBoundThatRejectedAGenericProvider() {
    val result =
      validate(
        """
        interface Encoder<T>

        class Plain

        @BindingContainer
        object Encoders {
          @Provides fun <T : Number> numbers(): Encoder<List<T>> = error("unused")
        }

        @DependencyGraph(bindingContainers = [Encoders::class])
        interface AppGraph {
          val ints: Encoder<List<Int>>
          val plain: Encoder<List<Plain>>
        }
        """,
      )

    val diagnostic = result.diagnostics.single()
    assertEquals(MetroDiagnosticId.MISSING_BINDING, diagnostic.id)
    val notes = diagnostic.diagnostic.notes.map { it.text.toString() }
    assertTrue(
      diagnostic.render(),
      "`test.Encoders.numbers` can't provide `Encoder<List<Plain>>` because `T = Plain` " +
        "doesn't satisfy `T : Number`" in notes,
    )
  }

  fun testAmbiguousDerivedBindingIsReported() {
    val result =
      validate(
        """
        interface Encoder<T>
        class Pair<A, B>

        @BindingContainer
        object Encoders {
          @Provides fun <A> left(): Encoder<Pair<A, Int>> = error("unused")
          @Provides fun <B> right(): Encoder<Pair<String, B>> = error("unused")
        }

        @DependencyGraph(bindingContainers = [Encoders::class])
        interface AppGraph {
          val pair: Encoder<Pair<String, Int>>
        }
        """,
      )

    val diagnostic = result.diagnostics.single()
    assertEquals(MetroDiagnosticId.AMBIGUOUS_DERIVED_BINDING, diagnostic.id)
    assertEquals(
      "`Encoder<Pair<String, Int>>` matches the generic providers `test.Encoders.left` and " +
        "`test.Encoders.right`. None of them is more specific than all the others. Add an exact " +
        "binding for `Encoder<Pair<String, Int>>`, or make one provider's return type more " +
        "specific.",
      diagnostic.diagnostic.title.toString(),
    )
  }

  fun testGrowingDerivationIsReportedAsDivergent() {
    val result =
      validate(
        """
        interface Encoder<T>

        @BindingContainer
        object Encoders {
          @Provides fun <T> wrapped(inner: Encoder<List<T>>): Encoder<T> = error("unused")
        }

        @DependencyGraph(bindingContainers = [Encoders::class])
        interface AppGraph {
          val encoder: Encoder<String>
        }
        """,
      )

    val diagnostic = result.diagnostics.single()
    assertEquals(MetroDiagnosticId.DIVERGENT_DERIVED_BINDING, diagnostic.id)
    assertEquals(
      "The generic provider `test.Encoders.wrapped` keeps requesting a larger key, so derivation " +
        "would never end. It derived `Encoder<String>` and `Encoder<List<String>>`, then needed " +
        "`Encoder<List<List<String>>>`. Check the parameter types of `test.Encoders.wrapped`.",
      diagnostic.diagnostic.title.toString(),
    )
  }

  fun testExtensionUsesParentUnscopedAndParentScopedGenericProviders() {
    val source =
      """
      interface Encoder<T>
      class Cache<T>

      @BindingContainer
      object Providers {
        @Provides fun string(): Encoder<String> = error("unused")
        @Provides fun <T> list(element: Encoder<T>): Encoder<List<T>> = error("unused")
        @Provides @SingleIn(AppScope::class) fun <T> cache(): Cache<T> = Cache()
      }

      @GraphExtension
      interface ChildGraph {
        val strings: Encoder<List<String>>
        val cache: Cache<String>
      }

      @DependencyGraph(AppScope::class, bindingContainers = [Providers::class])
      interface AppGraph {
        val child: ChildGraph
      }
      """

    val child = validate(source, graphName = "ChildGraph")
    child.assertNoDiagnostics()
    assertNotNull(child.binding("test.Encoder<kotlin.collections.List<kotlin.String>>").derivation)
    val cache = child.binding("test.Cache<kotlin.String>")
    assertTrue(cache is KaBinding.GraphDependency)
    assertEquals(
      setOf("test.Cache<kotlin.String>"),
      child.parentReservations.keys.mapTo(mutableSetOf()) { it.renderedType },
    )

    val parent = validate(source)
    parent.assertNoDiagnostics()
  }

  fun testNearerGenericProviderShadowsAnEquivalentParentProvider() {
    val result =
      validate(
        """
        interface Encoder<T>

        @BindingContainer
        object ParentEncoders {
          @Provides fun <T> parent(): Encoder<T> = error("unused")
        }

        @BindingContainer
        object ChildEncoders {
          @Provides fun <E> child(): Encoder<E> = error("unused")
        }

        @GraphExtension(bindingContainers = [ChildEncoders::class])
        interface ChildGraph {
          val strings: Encoder<String>
        }

        @DependencyGraph(bindingContainers = [ParentEncoders::class])
        interface AppGraph {
          val child: ChildGraph
        }
        """,
        graphName = "ChildGraph",
      )

    result.assertNoDiagnostics()
    val binding = result.binding("test.Encoder<kotlin.String>")
    assertEquals("test.ChildEncoders.child", binding.derivation?.signature?.providerName)
  }

  fun testNearerGenericProviderWithDifferentBoundsKeepsTheParentProvider() {
    val result =
      validate(
        """
        interface Encoder<T>

        class Plain

        @BindingContainer
        object ParentEncoders {
          @Provides fun <T> parent(): Encoder<T> = error("unused")
        }

        @BindingContainer
        object ChildEncoders {
          @Provides fun <E : Number> child(): Encoder<E> = error("unused")
        }

        @GraphExtension(bindingContainers = [ChildEncoders::class])
        interface ChildGraph {
          val plain: Encoder<Plain>
        }

        @DependencyGraph(bindingContainers = [ParentEncoders::class])
        interface AppGraph {
          val child: ChildGraph
        }
        """,
        graphName = "ChildGraph",
      )

    // `Plain` isn't a `Number`, so only the parent's provider can supply it.
    result.assertNoDiagnostics()
    val binding = result.binding("test.Encoder<test.Plain>")
    assertEquals("test.ParentEncoders.parent", binding.derivation?.signature?.providerName)
  }

  /**
   * Validates [graphName] in [file] twice. The EDT run checks bounds from snapshots only. The
   * background run lets an analysis session settle what the snapshots couldn't.
   */
  private fun validateBothWays(file: KtFile, graphName: String): BoundCheckRuns {
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val graph = index.graphs.single { it.name == graphName }
    val context = index.contextsFor(graph).single()
    val service = project.service<MetroGraphValidationService>()
    val snapshotsOnly = service.validateOnEdt(file, context).requireCompleted()
    val withSession = service.validateOffEdt(file, context).requireCompleted()
    return BoundCheckRuns(snapshotsOnly, withSession)
  }

  private class BoundCheckRuns(
    val snapshotsOnly: KaGraphValidationResult.Completed,
    val withSession: KaGraphValidationResult.Completed,
  )

  /**
   * Checks that the snapshots alone couldn't decide [renderedKey]'s bound and the session settled
   * it as holding.
   */
  private fun BoundCheckRuns.assertSessionVerifiedBound(renderedKey: String) {
    snapshotsOnly.assertNoDiagnostics()
    val unchecked = checkNotNull(snapshotsOnly.binding(renderedKey).derivation)
    assertFalse("The snapshots decided the bound", unchecked.boundsVerified)
    withSession.assertNoDiagnostics()
    val verified = checkNotNull(withSession.binding(renderedKey).derivation)
    assertTrue("The session didn't decide the bound", verified.boundsVerified)
  }

  /**
   * Checks that the snapshots alone kept the generic provider and the session rejected it with
   * [expectedNote].
   */
  private fun BoundCheckRuns.assertSessionRejectedBound(expectedNote: String) {
    snapshotsOnly.assertNoDiagnostics()
    val diagnostic = withSession.diagnostics.single()
    assertEquals(MetroDiagnosticId.MISSING_BINDING, diagnostic.id)
    val notes = diagnostic.diagnostic.notes.map { it.text.toString() }
    assertTrue(diagnostic.render(), expectedNote in notes)
  }

  fun testSessionSettlesATypeArgumentThatOnlyAppearsInASupertype() {
    val file =
      myFixture.configureMetroFile(
        """
        interface Encoder<T>
        interface Ints : List<Int>

        @BindingContainer
        object NumberEncoders {
          @Provides fun <T : Iterable<Number>> numbers(): Encoder<T> = error("unused")
        }

        @BindingContainer
        object StringEncoders {
          @Provides fun <T : Iterable<String>> strings(): Encoder<T> = error("unused")
        }

        @DependencyGraph(bindingContainers = [NumberEncoders::class])
        interface HoldsGraph {
          val ints: Encoder<Ints>
        }

        @DependencyGraph(bindingContainers = [StringEncoders::class])
        interface FailsGraph {
          val ints: Encoder<Ints>
        }
        """,
      )

    validateBothWays(file, "HoldsGraph").assertSessionVerifiedBound("test.Encoder<test.Ints>")
    validateBothWays(file, "FailsGraph")
      .assertSessionRejectedBound(
        "`test.StringEncoders.strings` can't provide `Encoder<Ints>` because `T = Ints` " +
          "doesn't satisfy `T : Iterable<String>`",
      )
  }

  fun testSessionSettlesAProjectedTypeArgument() {
    val file =
      myFixture.configureMetroFile(
        """
        interface Encoder<T>
        class Box<T>

        @BindingContainer
        object NumberEncoders {
          @Provides fun <T : Box<out Number>> numbers(): Encoder<T> = error("unused")
        }

        @BindingContainer
        object StringEncoders {
          @Provides fun <T : Box<out String>> strings(): Encoder<T> = error("unused")
        }

        @DependencyGraph(bindingContainers = [NumberEncoders::class])
        interface HoldsGraph {
          val ints: Encoder<Box<out Int>>
        }

        @DependencyGraph(bindingContainers = [StringEncoders::class])
        interface FailsGraph {
          val ints: Encoder<Box<out Int>>
        }
        """,
      )

    validateBothWays(file, "HoldsGraph")
      .assertSessionVerifiedBound("test.Encoder<test.Box<out kotlin.Int>>")
    validateBothWays(file, "FailsGraph")
      .assertSessionRejectedBound(
        "`test.StringEncoders.strings` can't provide `Encoder<Box<out Int>>` because " +
          "`T = Box<out Int>` doesn't satisfy `T : Box<out String>`",
      )
  }

  fun testSessionSettlesATypeThatSubstitutionBuilt() {
    val file =
      myFixture.configureMetroFile(
        """
        interface Encoder<T>
        interface Numbers<E> : List<E>
        class Holder<T>

        @BindingContainer
        object Holders {
          @Provides fun <T> holder(inner: Encoder<Numbers<T>>): Encoder<Holder<T>> = error("unused")
        }

        @BindingContainer
        object NumberEncoders {
          @Provides fun <N : Iterable<Number>> numbers(): Encoder<N> = error("unused")
        }

        @BindingContainer
        object StringEncoders {
          @Provides fun <N : Iterable<String>> strings(): Encoder<N> = error("unused")
        }

        @DependencyGraph(bindingContainers = [Holders::class, NumberEncoders::class])
        interface HoldsGraph {
          val holder: Encoder<Holder<Int>>
        }

        @DependencyGraph(bindingContainers = [Holders::class, StringEncoders::class])
        interface FailsGraph {
          val holder: Encoder<Holder<Int>>
        }
        """,
      )

    // `Numbers<Int>` comes from substituting `T` in `holder`'s dependency, so its supertypes are
    // gone.
    validateBothWays(file, "HoldsGraph")
      .assertSessionVerifiedBound("test.Encoder<test.Numbers<kotlin.Int>>")
    validateBothWays(file, "FailsGraph")
      .assertSessionRejectedBound(
        "`test.StringEncoders.strings` can't provide `Encoder<Numbers<Int>>` because " +
          "`N = Numbers<Int>` doesn't satisfy `N : Iterable<String>`",
      )
  }

  fun testAmbiguityIsReportedOnceTheSessionVerifiesEveryBound() {
    val file =
      myFixture.configureMetroFile(
        """
        interface Encoder<T>
        interface Ints : List<Int>

        @BindingContainer
        object Encoders {
          @Provides fun <T : Iterable<Number>> iterables(): Encoder<T> = error("unused")
          @Provides fun <T : Collection<Number>> collections(): Encoder<T> = error("unused")
        }

        @DependencyGraph(bindingContainers = [Encoders::class])
        interface AppGraph {
          val ints: Encoder<Ints>
        }
        """,
      )

    val runs = validateBothWays(file, "AppGraph")
    // The snapshots can't check either bound, so the ambiguity isn't claimed.
    runs.snapshotsOnly.assertNoDiagnostics()
    val diagnostic = runs.withSession.diagnostics.single()
    assertEquals(MetroDiagnosticId.AMBIGUOUS_DERIVED_BINDING, diagnostic.id)
  }
}
