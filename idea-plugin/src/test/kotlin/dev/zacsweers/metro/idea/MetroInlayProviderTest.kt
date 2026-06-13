// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.testFramework.utils.inlays.declarative.DeclarativeInlayHintsProviderTestCase

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
}
