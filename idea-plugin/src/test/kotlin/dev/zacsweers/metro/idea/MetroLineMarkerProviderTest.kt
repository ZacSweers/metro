// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertTrue

class MetroLineMarkerProviderTest : BasePlatformTestCase() {

  override fun setUp() {
    super.setUp()
    project.setMetroOptions()
    module.addMetroRuntimeLibrary()
  }

  private fun configureAndHighlight(): List<String> {
    myFixture.configureByText(
      "Test.kt",
      """
      package test

      import dev.zacsweers.metro.AppScope
      import dev.zacsweers.metro.Assisted
      import dev.zacsweers.metro.AssistedInject
      import dev.zacsweers.metro.Binds
      import dev.zacsweers.metro.DependencyGraph
      import dev.zacsweers.metro.Inject

      interface Service
      @Inject class ServiceImpl : Service

      interface ServiceBindings {
        @Binds fun bindService(impl: ServiceImpl): Service
      }

      @Inject
      class Consumer(
        val service: Service,
        val missing: Long,
      )

      @AssistedInject class Presenter(@Assisted val id: String, val service: Service)

      @DependencyGraph(AppScope::class)
      interface AppGraph {
        val consumer: Consumer
      }
      """
        .trimIndent(),
    )
    myFixture.doHighlighting()
    val metroIcons =
      setOf(
        MetroIcons.PROVIDER,
        MetroIcons.CONSUMER,
        MetroIcons.CONSUMER_UNRESOLVED,
        MetroIcons.CONSUMER_ASSISTED,
        MetroIcons.GRAPH,
        MetroIcons.GRAPH_VALIDATED,
        MetroIcons.GRAPH_PROBLEMS,
        MetroIcons.CONTRIBUTED,
      )
    return myFixture.findAllGutters().filter { it.icon in metroIcons }.mapNotNull { it.tooltipText }
  }

  fun testInjectorMarkerTargetsInjectedMembers() {
    myFixture.configureMetroFile(
      """
      interface Api
      interface Tracker

      class Target {
        @Inject lateinit var api: Api
        @Inject lateinit var tracker: Tracker
      }

      @DependencyGraph
      interface AppGraph {
        fun inject(target: Target)

        @Provides fun provideApi(): Api = object : Api {}
        @Provides fun provideTracker(): Tracker = object : Tracker {}
      }
      """
    )
    myFixture.doHighlighting()
    val tooltips =
      myFixture
        .findAllGutters()
        .filter { it.icon === MetroIcons.CONSUMER || it.icon === MetroIcons.CONSUMER_UNRESOLVED }
        .mapNotNull { it.tooltipText }
    assertTrue("Expected an injector marker in:\n$tooltips") {
      tooltips.any { it.startsWith("Metro injector: injects 2 dependencies into Target") }
    }
  }

  fun testProviderConsumerAndGraphMarkersArePresent() {
    val tooltips = configureAndHighlight()

    assertTrue("Expected a binds provider marker in:\n$tooltips") {
      tooltips.any { it.startsWith("Metro binds: Service") }
    }
    assertTrue("Expected an injected class provider marker in:\n$tooltips") {
      tooltips.any { it.startsWith("Metro injected class: Consumer") }
    }
    assertTrue("Expected a consumer marker for the service param in:\n$tooltips") {
      tooltips.any { it.startsWith("Metro dependency: Service") }
    }
    assertTrue("Expected a graph accessor consumer marker in:\n$tooltips") {
      tooltips.any { it.startsWith("Metro dependency: Consumer") }
    }
    assertTrue("Expected a graph contributions marker in:\n$tooltips") {
      tooltips.any { it.startsWith("Contributions to AppScope") }
    }
    assertTrue("Expected an unresolved-consumer marker for the missing param in:\n$tooltips") {
      tooltips.any {
        it.startsWith("Metro dependency: Long") && "no binding found in project sources" in it
      }
    }
    assertTrue("Expected no assisted gutter markers (inlay-only):\n$tooltips") {
      tooltips.none { it.startsWith("Metro: assisted parameter") }
    }
  }

  fun testNoMarkersWhenMetroDisabled() {
    project.setMetroOptions("enabled" to "false")
    val tooltips = configureAndHighlight()
    assertTrue("Expected no Metro markers in:\n$tooltips") {
      tooltips.none { it.startsWith("Metro ") }
    }
  }

  fun testNoMarkersWhenBindingResolutionSettingIsDisabled() {
    val settings = MetroSettings.getInstance(project).state
    settings.enableBindingResolution = false
    try {
      val tooltips = configureAndHighlight()
      assertTrue("Expected no Metro markers in:\n$tooltips") { tooltips.isEmpty() }
    } finally {
      settings.enableBindingResolution = true
    }
  }
}
