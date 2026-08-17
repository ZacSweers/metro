// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.testFramework.utils.inlays.declarative.DeclarativeInlayHintsProviderTestCase
import dev.zacsweers.metro.idea.index.MetroInjectedImplementationInlayProvider

class MetroInlayProviderTest : DeclarativeInlayHintsProviderTestCase() {

  override fun setUp() {
    super.setUp()
    project.setMetroOptions("enable-circuit-codegen" to "true")
    module.addMetroRuntimeLibrary()
    myFixture.addCircuitStubs()
  }

  fun testImplementationAndAssistedInlays() {
    doTestProvider(
      "CircuitImpl.kt",
      """
      package test

      import com.slack.circuit.codegen.annotations.CircuitInject
      import com.slack.circuit.runtime.CircuitUiState
      import com.slack.circuit.runtime.screen.Screen
      import dev.zacsweers.metro.AppScope
      import dev.zacsweers.metro.Assisted
      import dev.zacsweers.metro.ContributesBinding
      import dev.zacsweers.metro.Inject
      import dev.zacsweers.metro.SingleIn

      class AreaScreen : Screen
      class AreaState : CircuitUiState

      interface Repo

      @SingleIn(AppScope::class)
      @ContributesBinding(AppScope::class)
      class RepoImpl(private val name: String) : Repo {
        @Inject constructor(count: Int) : this(count.toString())
      }

      @CircuitInject(AreaScreen::class, AppScope::class)
      fun AreaPresenter(/*<# assisted #>*/screen: AreaScreen, @Assisted tag: String, repo: Repo/*<#  RepoImpl #>*/): AreaState {
        return AreaState()
      }
      """
        .trimIndent(),
      MetroInjectedImplementationInlayProvider(),
    )
  }

  fun testContextDependentResolutionHasNoImplementationInlay() {
    doTestProvider(
      "ContextDependent.kt",
      """
      package test

      import dev.zacsweers.metro.*

      abstract class OtherScope

      interface Repo

      @Inject
      @ContributesBinding(AppScope::class)
      class AppRepo : Repo

      @Inject
      @ContributesBinding(OtherScope::class)
      class OtherRepo : Repo

      @Inject class Consumer(val repo: Repo)

      @DependencyGraph(AppScope::class)
      interface AppGraph {
        val consumer: Consumer
      }

      @DependencyGraph(OtherScope::class)
      interface OtherGraph {
        val consumer: Consumer
      }
      """
        .trimIndent(),
      MetroInjectedImplementationInlayProvider(),
    )
  }

  fun testGenericProviderParameterUsesItsConcreteImplementationInlay() {
    doTestProvider(
      "GenericProvider.kt",
      """
      package test

      import dev.zacsweers.metro.*

      interface Api

      @Inject
      @ContributesBinding(AppScope::class)
      class RealApi : Api

      interface GenericBase<T> {
        @Provides fun provideText(value: T/*<#  RealApi #>*/): String = value.toString()
      }

      @DependencyGraph(AppScope::class)
      interface AppGraph : GenericBase<Api> {
        val text: String
      }
      """
        .trimIndent(),
      MetroInjectedImplementationInlayProvider(),
    )
  }

  fun testIdenticalGenericSpecializationsKeepTheirImplementationInlay() {
    doTestProvider(
      "MatchingGenericProviders.kt",
      """
      package test

      import dev.zacsweers.metro.*

      interface Api

      @Inject @ContributesBinding(AppScope::class)
      class RealApi : Api

      interface GenericBase<T> {
        @Provides fun provideText(value: T/*<#  RealApi #>*/): String = value.toString()
      }

      @DependencyGraph(AppScope::class)
      interface FirstGraph : GenericBase<Api> {
        val text: String
      }

      @DependencyGraph(AppScope::class)
      interface SecondGraph : GenericBase<Api> {
        val text: String
      }
      """
        .trimIndent(),
      MetroInjectedImplementationInlayProvider(),
    )
  }

  fun testParentAndChildSpecializedAliasesKeepTheirImplementationInlay() {
    doTestProvider(
      "InheritedParentChildBindings.kt",
      """
      package test

      import dev.zacsweers.metro.*

      interface Api
      @Inject class RealApi : Api

      interface GenericBase<T : Api> {
        @Binds fun bindApi(value: T): Api

        @Provides fun provideText(value: Api/*<#  RealApi #>*/): String = value.toString()
      }

      @GraphExtension
      interface ChildGraph : GenericBase<RealApi> {
        val text: String
      }

      @DependencyGraph
      interface AppGraph : GenericBase<RealApi> {
        val text: String
        val child: ChildGraph
      }
      """
        .trimIndent(),
      MetroInjectedImplementationInlayProvider(),
    )
  }

  fun testDifferentAliasTargetsDoNotShowImplementationInlays() {
    doTestProvider(
      "DifferentAliasTargets.kt",
      """
      package test

      import dev.zacsweers.metro.*

      interface Api
      @Inject class RealA : Api
      @Inject class RealB : Api

      interface GenericBindings<T : Api> {
        @Binds fun bindApi(value: T): Api

        @Provides fun provideText(value: Api): String = value.toString()
      }

      interface GenericConsumers<T> {
        @Provides fun provideCount(value: T): Int = value.hashCode()
      }

      @DependencyGraph
      interface FirstGraph : GenericBindings<RealA>, GenericConsumers<Api> {
        val text: String
        val count: Int
      }

      @DependencyGraph
      interface SecondGraph : GenericBindings<RealB>, GenericConsumers<Api> {
        val text: String
        val count: Int
      }
      """
        .trimIndent(),
      MetroInjectedImplementationInlayProvider(),
    )
  }

  fun testDifferentGenericProviderSpecializationsDoNotShowAnImplementationInlay() {
    doTestProvider(
      "ContextDependentGenericProvider.kt",
      """
      package test

      import dev.zacsweers.metro.*

      abstract class OtherScope
      interface AppApi
      interface OtherApi

      @Inject
      @ContributesBinding(AppScope::class)
      class AppImplementation : AppApi

      @Inject
      @ContributesBinding(OtherScope::class)
      class OtherImplementation : OtherApi

      interface GenericBase<T> {
        @Provides fun provideText(value: T): String = value.toString()
      }

      @DependencyGraph(AppScope::class)
      interface AppGraph : GenericBase<AppApi> {
        val text: String
      }

      @DependencyGraph(OtherScope::class)
      interface OtherGraph : GenericBase<OtherApi> {
        val text: String
      }
      """
        .trimIndent(),
      MetroInjectedImplementationInlayProvider(),
    )
  }
}
