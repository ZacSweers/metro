// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.zacsweers.metro.idea.intentions.contributions.ContributionCandidate
import dev.zacsweers.metro.idea.intentions.contributions.contributionCandidate
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile

/** Checks source-edit choices independently from graph indexing and the interactive picker. */
class MetroContributionCandidateTest : BasePlatformTestCase() {
  override fun setUp() {
    super.setUp()
    project.setMetroOptions()
    module.addMetroRuntimeLibrary()
    module.addKotlinStdlibLibrary()
  }

  fun testPrimaryImplicitAndSelectedSecondaryConstructors() {
    val file =
      myFixture.configureMetroFile(
        """
      interface Api
      @Inject class Implicit : Api
      class Primary @Inject constructor() : Api
      class Secondary : Api {
        @Inject constructor(value: String)
        constructor() : this("")
      }
      object Singleton : Api
      """
      )
    for (name in listOf("Implicit", "Primary", "Secondary", "Singleton")) {
      val candidate = requireNotNull(candidate(file, name)) { name }
      val boundType = candidate.boundTypes.single()
      assertEquals("test.Api", boundType.renderedType)
      assertEquals("Api", boundType.label)
      assertTrue(boundType.implicit)
    }
  }

  fun testConflictingAmbiguousAndInaccessibleConstructorsAreSkipped() {
    val file =
      myFixture.configureMetroFile(
        """
      interface Api
      class Unannotated : Api
      @Inject class SecondaryOnly : Api { constructor(value: String) }
      @Inject class Both @Inject constructor() : Api
      class Several : Api {
        @Inject constructor(value: String)
        @Inject constructor(value: Int)
      }
      class PrivateConstructor @Inject private constructor() : Api
      @Inject class ClassPrivateConstructor private constructor() : Api
      @Inject abstract class Abstract : Api
      @Inject sealed class Sealed : Api
      @Inject object InjectedObject : Api
      """
      )
    for (name in
      listOf(
        "Unannotated",
        "SecondaryOnly",
        "Both",
        "Several",
        "PrivateConstructor",
        "ClassPrivateConstructor",
        "Abstract",
        "Sealed",
        "InjectedObject",
      )) {
      assertNull(name, candidate(file, name))
    }
  }

  fun testAssistedTargetsAndFactoriesAreSkipped() {
    val file =
      myFixture.configureMetroFile(
        """
      interface Api
      @AssistedInject class ClassAssisted(@Assisted val value: String) : Api
      class ConstructorAssisted @AssistedInject constructor(@Assisted val value: String) : Api
      class AssistedParameter @Inject constructor(@Assisted val value: String) : Api
      @AssistedFactory interface Factory : Api { fun create(value: String): ClassAssisted }
      """
      )
    for (name in listOf("ClassAssisted", "ConstructorAssisted", "AssistedParameter", "Factory")) {
      assertNull(name, candidate(file, name))
    }
  }

  fun testClassAndContainingVisibilityLocalAndInnerClasses() {
    val file =
      myFixture.configureMetroFile(
        """
      interface Api
      @Inject private class Private : Api
      private class Hidden { @Inject class Nested : Api }
      open class Holder {
        @Inject protected class Protected : Api
        @Inject inner class Inner : Api
      }
      fun local() { @Inject class Local : Api }
      @Inject internal class Internal : Api
      """
      )
    for (name in listOf("Private", "Nested", "Protected", "Inner", "Local")) {
      assertNull(name, candidate(file, name))
    }
    assertNotNull(candidate(file, "Internal"))
  }

  fun testClosedGenericTypesRetainArgumentsAndSeveralTypesRequireExplicitChoice() {
    val candidate =
      requireNotNull(
        candidate(
          """
      interface Api<T>
      interface Marker
      @Inject class Candidate : Api<List<String?>>, Marker
      """
        )
      )
    assertEquals(
      listOf("test.Api<kotlin.collections.List<kotlin.String?>>", "test.Marker"),
      candidate.boundTypes.map { it.renderedType },
    )
    assertTrue(candidate.boundTypes.none { it.implicit })
  }

  fun testFreeTypeParametersAndUnresolvedNestedTypesAreSkipped() {
    val file =
      myFixture.configureMetroFile(
        """
      interface Api<T>
      @Inject class Generic<T> : Api<T>
      @Inject class Broken : Api<List<Missing>>
      @Inject class NoSupertype
      """
      )
    for (name in listOf("Generic", "Broken", "NoSupertype")) {
      assertNull(name, candidate(file, name))
    }
  }

  fun testDefaultBindingAddsItsStarProjectedChoice() {
    val candidate =
      requireNotNull(
        candidate(
          """
      @DefaultBinding<Api<*>> interface Api<T>
      interface Marker
      @Inject class Candidate : Api<String>, Marker
      """
        )
      )
    assertEquals("test.Api<*>", candidate.boundTypes.first().renderedType)
    assertTrue(candidate.boundTypes.first().implicit)
    assertTrue(candidate.boundTypes.first().isDefault)
    assertEquals(
      listOf("test.Api<*>", "test.Api<kotlin.String>", "test.Marker"),
      candidate.boundTypes.map { it.renderedType },
    )
    assertEquals(1, candidate.boundTypes.count { it.implicit })
  }

  fun testAmbiguousDefaultsRemainExplicitChoices() {
    val candidate =
      requireNotNull(
        candidate(
          """
      @DefaultBinding<First<*>> interface First<T>
      @DefaultBinding<Second<*>> interface Second<T>
      @Inject class Candidate : First<String>, Second<Int>
      """
        )
      )
    assertTrue(candidate.boundTypes.none { it.implicit })
    assertTrue(candidate.boundTypes.none { it.isDefault })
    assertTrue(candidate.boundTypes.any { it.renderedType == "test.First<*>" })
    assertTrue(candidate.boundTypes.any { it.renderedType == "test.Second<*>" })
  }

  fun testClassQualifierIsDisplayedAndInheritedFromTheClass() {
    val candidate =
      requireNotNull(
        candidate(
          """
      @Qualifier annotation class Blue
      interface Api
      interface Marker
      @Blue @Inject class Candidate : Api, Marker
      """
        )
      )
    assertEquals(
      listOf("test.Api", "test.Marker"),
      candidate.boundTypes.map { it.renderedType },
    )
    assertEquals(listOf("@Blue Api", "@Blue Marker"), candidate.boundTypes.map { it.label })
  }

  fun testClassOnlyQualifierWithEscapedNamesAndValuesStaysOnTheClass() {
    val file =
      myFixture.configureMetroFile(
        """
      @Qualifier @Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
      annotation class `is`(val `in`: String)
      interface Api
      interface Marker
      @`is`("line\nquote\"slash\\dollar${'$'}")
      @Inject class Candidate : Api, Marker
      """
      )
    val before = file.text
    val candidate = requireNotNull(candidate(file, "Candidate"))
    assertEquals(listOf("test.Api", "test.Marker"), candidate.boundTypes.map { it.renderedType })
    assertTrue(candidate.boundTypes.none { it.implicit })
    assertEquals(before, file.text)
  }

  fun testAnnotatedDefaultBindingIsPreservedExplicitly() {
    val candidate =
      requireNotNull(
        candidate(
          """
      @Qualifier @Target(AnnotationTarget.TYPE) annotation class Blue
      @DefaultBinding<@Blue Api<*>> interface Api<T>
      @Inject class Candidate : Api<String>
      """
        )
      )
    val annotated = candidate.boundTypes.first()
    assertEquals("@test.Blue test.Api<*>", annotated.renderedType)
    assertFalse(annotated.implicit)
    assertTrue(annotated.isDefault)
  }

  fun testNativeScopeAndExistingClassMapKeyAreResolved() {
    val candidate =
      requireNotNull(
        candidate(
          """
      abstract class ScopeKey
      interface Api
      @SingleIn(ScopeKey::class) @StringKey("candidate")
      @Inject class Candidate : Api
      """
        )
      )
    assertEquals("test.ScopeKey", candidate.existingScope)
    assertTrue(candidate.existingMapKey)
  }

  fun testScopeAnnotationClassAndGraphAreNotSuggestedAsAggregationScope() {
    val file =
      myFixture.configureMetroFile(
        """
      @Scope annotation class Lifetime
      @DependencyGraph interface Graph
      interface Api
      @SingleIn(Lifetime::class) @Inject class AnnotationScope : Api
      @SingleIn(Graph::class) @Inject class GraphScope : Api
      @Lifetime @Inject class CustomScope : Api
      """
      )
    for (name in listOf("AnnotationScope", "GraphScope", "CustomScope")) {
      assertNull(requireNotNull(candidate(file, name)).existingScope)
    }
  }

  fun testEscapedScopeNamesUseKotlinSourceSyntax() {
    val candidate =
      requireNotNull(
        candidate(
          """
      abstract class `when`
      interface Api
      @SingleIn(`when`::class) @Inject class Candidate : Api
      """
        )
      )
    assertEquals("test.`when`", candidate.existingScope)
  }

  fun testInaccessibleScopeIsNotSuggested() {
    val candidate =
      requireNotNull(
        candidate(
          """
      class Other { private class Hidden }
      interface Api
      @SingleIn(Other.Hidden::class) @Inject class Candidate : Api
      """
        )
      )
    assertNull(candidate.existingScope)
  }

  fun testTypeMapKeyRequiresExplicitMapContribution() {
    val candidate =
      requireNotNull(
        candidate(
          """
      @DefaultBinding<@StringKey("api") Api<*>> interface Api<T>
      @Inject class Candidate : Api<String>
      """
        )
      )
    val type = candidate.boundTypes.first()
    assertTrue(type.hasMapKey)
    assertFalse(type.implicit)
    assertTrue(type.isDefault)
    assertFalse(candidate.existingMapKey)
    assertTrue(type.renderedType.contains("StringKey"))
    assertTrue(type.renderedType.contains("\"api\""))
  }

  fun testExistingNativeAndCustomContributionsSuppressTheSuggestion() {
    project.setMetroOptions("custom-contributes-binding" to "test/CustomBinding")
    val file =
      myFixture.configureMetroFile(
        """
      annotation class CustomBinding
      interface Api
      @ContributesBinding(AppScope::class) @Inject class Binding : Api
      @ContributesIntoSet(AppScope::class) @Inject class SetElement : Api
      @ContributesIntoMap(AppScope::class) @StringKey("map") @Inject class MapElement : Api
      @CustomBinding @Inject class Custom : Api
      """
      )
    for (name in listOf("Binding", "SetElement", "MapElement", "Custom")) {
      assertNull(name, candidate(file, name))
    }
  }

  fun testCustomInjectAnnotationAndAliasUseResolvedIdentity() {
    project.setMetroOptions("custom-inject" to "test/CustomInject")
    assertNotNull(
      candidate(
        """
      annotation class CustomInject
      typealias Make = CustomInject
      interface Api
      @Make class Candidate : Api
      """
      )
    )
  }

  fun testDisabledAndDumbModeDoNotResolveCandidates() {
    val file = myFixture.configureMetroFile("interface Api\n@Inject class Candidate : Api")
    assertNotNull(candidate(file, "Candidate"))
    DumbModeTestUtils.runInDumbModeSynchronously(project) {
      assertNull(candidate(file, "Candidate"))
    }
    project.clearMetroOptions()
    assertNull(candidate(file, "Candidate"))
  }

  private fun candidate(source: String): ContributionCandidate? {
    return candidate(myFixture.configureMetroFile(source), "Candidate")
  }

  private fun candidate(file: KtFile, name: String): ContributionCandidate? {
    val declaration =
      file.declarationsIncludingNested().filterIsInstance<KtClassOrObject>().single {
        it.name == name
      }
    return allowAnalysisOnEdt { contributionCandidate(declaration) }
  }
}
