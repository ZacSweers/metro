// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import androidx.tracing.wire.TraceDriver
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.components.service
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.zacsweers.metro.idea.index.MetroResolutionService
import dev.zacsweers.metro.idea.tracing.IdeTraceOutput
import dev.zacsweers.metro.idea.tracing.IdeTraceRecorder
import dev.zacsweers.metro.idea.tracing.IdeTraceState
import dev.zacsweers.metro.idea.tracing.IdeTraceStopReason
import dev.zacsweers.metro.idea.tracing.MetroIdeTracingService
import dev.zacsweers.metro.idea.tracing.RecordingIdeTraceSink
import dev.zacsweers.metro.idea.tracing.RefreshMetroTraceAction
import dev.zacsweers.metro.idea.tracing.StartMetroTraceAction
import dev.zacsweers.metro.idea.tracing.StopMetroTraceAction
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Exercises the debugging gate through registered actions and toolbar-owned actions. */
class MetroTraceActionTest : BasePlatformTestCase() {

  override fun setUp() {
    super.setUp()
    MetroSettings.getInstance(project).state.enableDebuggingOptions = false
  }

  override fun tearDown() {
    try {
      MetroSettings.getInstance(project).state.enableDebuggingOptions = false
    } finally {
      super.tearDown()
    }
  }

  fun testRegisteredActionsAreHiddenUntilDebuggingIsEnabled() {
    val start = registeredAction("Metro.StartPerformanceTrace")
    val stop = registeredAction("Metro.StopPerformanceTrace")
    val startEvent = event(start)
    val stopEvent = event(stop)

    start.update(startEvent)
    stop.update(stopEvent)
    assertFalse(startEvent.presentation.isVisible)
    assertFalse(startEvent.presentation.isEnabled)
    assertFalse(stopEvent.presentation.isVisible)
    assertFalse(stopEvent.presentation.isEnabled)

    MetroSettings.getInstance(project).state.enableDebuggingOptions = true
    start.update(startEvent)
    stop.update(stopEvent)
    assertTrue(startEvent.presentation.isEnabledAndVisible)
    assertTrue(stopEvent.presentation.isVisible)
    assertFalse(stopEvent.presentation.isEnabled)
  }

  fun testRefreshContextActionHonorsTheDebuggingGate() {
    assertNull(ActionManager.getInstance().getAction("Metro.RefreshWithTracing"))
    val action = RefreshMetroTraceAction(project)
    val event = event(action)
    action.update(event)
    assertFalse(event.presentation.isVisible)
    assertFalse(event.presentation.isEnabled)

    MetroSettings.getInstance(project).state.enableDebuggingOptions = true
    action.update(event)
    assertTrue(event.presentation.isEnabledAndVisible)

    MetroSettings.getInstance(project).state.enableDebuggingOptions = false
    action.actionPerformed(event)
    assertEquals(IdeTraceState.IDLE, project.service<MetroIdeTracingService>().state.value)
  }

  fun testToolbarActionsUseTheirProjectAndHonorDebuggingSettings() {
    val start = StartMetroTraceAction(project)
    val stop = StopMetroTraceAction(project)
    val startEvent = event(start, includeProject = false)
    val stopEvent = event(stop, includeProject = false)

    MetroSettings.getInstance(project).state.enableDebuggingOptions = true
    start.update(startEvent)
    stop.update(stopEvent)
    assertTrue(startEvent.presentation.isEnabledAndVisible)
    assertTrue(stopEvent.presentation.isVisible)

    MetroSettings.getInstance(project).state.enableDebuggingOptions = false
    start.update(startEvent)
    stop.update(stopEvent)
    assertFalse(startEvent.presentation.isEnabledAndVisible)
    assertFalse(stopEvent.presentation.isEnabledAndVisible)
  }

  fun testActionsWithoutAProjectAreHidden() {
    for (action in
      listOf(StartMetroTraceAction(), StopMetroTraceAction(), RefreshMetroTraceAction())) {
      val event = event(action, includeProject = false)
      action.update(event)
      assertFalse(event.presentation.isVisible)
      assertFalse(event.presentation.isEnabled)
    }
  }

  fun testStaleEnabledPresentationCannotStartRecordingAfterDebuggingIsDisabled() {
    val action = registeredAction("Metro.StartPerformanceTrace")
    val event = event(action)
    MetroSettings.getInstance(project).state.enableDebuggingOptions = true
    action.update(event)
    assertTrue(event.presentation.isEnabledAndVisible)

    MetroSettings.getInstance(project).state.enableDebuggingOptions = false
    action.actionPerformed(event)

    assertEquals(IdeTraceState.IDLE, project.service<MetroIdeTracingService>().state.value)
  }

  fun testDisablingDebuggingHidesActionsAndDrainsAnActiveCapture() = runBlocking {
    val owner = SupervisorJob()
    val sink = RecordingIdeTraceSink()
    val recorder =
      IdeTraceRecorder(
        CoroutineScope(owner + Dispatchers.Default),
        { IdeTraceOutput(TraceDriver(sink)) },
      )
    val tracing = project.service<MetroIdeTracingService>()
    val previous = tracing.setRecorderForTest(recorder)
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val action = StartMetroTraceAction(project)
    val stop = StopMetroTraceAction(project)
    val refresh = RefreshMetroTraceAction(project)
    val refreshEvent = event(refresh)
    val startEvent = event(action)
    val stopEvent = event(stop)
    try {
      withTimeout(10_000) {
        MetroSettings.getInstance(project).state.enableDebuggingOptions = true
        action.actionPerformed(startEvent)
        tracing.state.first { it == IdeTraceState.RECORDING }
        action.update(startEvent)
        stop.update(stopEvent)
        assertFalse(startEvent.presentation.isEnabled)
        assertTrue(stopEvent.presentation.isEnabledAndVisible)
        refresh.update(refreshEvent)
        assertFalse(refreshEvent.presentation.isEnabled)
        assertTrue(refreshEvent.presentation.isVisible)
        val work =
          async(Dispatchers.Default) {
            tracing.traceSuspend("work") {
              entered.complete(Unit)
              release.await()
            }
          }
        entered.await()
        MetroSettings.getInstance(project).state.enableDebuggingOptions = false
        tracing.settingsChanged()
        action.update(startEvent)
        stop.update(stopEvent)
        assertFalse(startEvent.presentation.isVisible)
        assertFalse(stopEvent.presentation.isVisible)
        assertEquals(IdeTraceState.DRAINING, tracing.state.value)
        assertEquals(0, sink.closeCount)
        release.complete(Unit)
        work.await()
        tracing.state.first { it == IdeTraceState.IDLE }
        assertEquals(1, sink.closeCount)
      }
    } finally {
      release.complete(Unit)
      recorder.stop()
      owner.cancelAndJoin()
      tracing.setRecorderForTest(previous)
    }
  }

  fun testRefreshActionWaitsForRecordingAndManualStopKeepsRefreshRunning() {
    project.setMetroOptions()
    module.addMetroRuntimeLibrary()
    MetroSettings.getInstance(project).state.automaticallyRefreshGraphData = false
    MetroSettings.getInstance(project).state.enableDebuggingOptions = true
    val resolution = project.service<MetroResolutionService>()
    resolution.settingsChanged()
    val file = myFixture.configureMetroFile("@DependencyGraph interface TracedGraph")
    val owner = SupervisorJob()
    val creating = CompletableFuture<Unit>()
    val outputReady = CountDownLatch(1)
    val prepared = CompletableFuture<Unit>()
    val publish = CountDownLatch(1)
    val finished = CompletableFuture<IdeTraceStopReason>()
    val sink = RecordingIdeTraceSink()
    val recorder =
      IdeTraceRecorder(
        CoroutineScope(owner + Dispatchers.Default),
        {
          creating.complete(Unit)
          check(outputReady.await(30, TimeUnit.SECONDS))
          IdeTraceOutput(TraceDriver(sink))
        },
        onFinished = { _, failure, reason, _ ->
          if (failure != null) finished.completeExceptionally(failure)
          else finished.complete(reason)
        },
      )
    val tracing = project.service<MetroIdeTracingService>()
    val previous = tracing.setRecorderForTest(recorder)
    resolution.setResolutionCandidatePreparedObserver {
      prepared.complete(Unit)
      check(publish.await(30, TimeUnit.SECONDS))
    }
    try {
      val action = RefreshMetroTraceAction(project)
      action.actionPerformed(event(action))
      PlatformTestUtil.waitForFuture(creating, 30_000)
      assertEquals(IdeTraceState.STARTING, recorder.state.value)
      assertFalse(resolution.isExplicitGraphRefreshPending)
      outputReady.countDown()
      PlatformTestUtil.waitForFuture(prepared, 30_000)
      assertTrue(resolution.isExplicitGraphRefreshPending)
      assertEquals(IdeTraceState.RECORDING, recorder.state.value)
      tracing.stopCapture()
      assertEquals(IdeTraceState.DRAINING, recorder.state.value)
      assertTrue(resolution.isExplicitGraphRefreshPending)
      publish.countDown()
      assertEquals(IdeTraceStopReason.USER, PlatformTestUtil.waitForFuture(finished, 30_000))
      assertFalse(resolution.isExplicitGraphRefreshPending)
      assertEquals(listOf("TracedGraph"), resolution.cachedIndex(file).graphs.map { it.name })
      assertTrue(sink.results("index.candidate").any { it.metadata["outcome"] == "published" })
    } finally {
      outputReady.countDown()
      publish.countDown()
      resolution.setResolutionCandidatePreparedObserver(null)
      recorder.stop()
      runBlocking { withTimeout(30_000) { owner.cancelAndJoin() } }
      tracing.setRecorderForTest(previous)
    }
  }

  private fun registeredAction(id: String): AnAction =
    checkNotNull(ActionManager.getInstance().getAction(id))

  private fun event(action: AnAction, includeProject: Boolean = true): AnActionEvent {
    val context = DataContext { dataId ->
      if (includeProject && CommonDataKeys.PROJECT.`is`(dataId)) project else null
    }
    return AnActionEvent.createFromAnAction(action, null, ActionPlaces.UNKNOWN, context)
  }
}
