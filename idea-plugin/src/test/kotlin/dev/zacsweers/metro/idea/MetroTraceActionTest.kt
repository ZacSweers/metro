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
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.zacsweers.metro.idea.tracing.IdeTraceOutput
import dev.zacsweers.metro.idea.tracing.IdeTraceRecorder
import dev.zacsweers.metro.idea.tracing.IdeTraceState
import dev.zacsweers.metro.idea.tracing.MetroIdeTracingService
import dev.zacsweers.metro.idea.tracing.RecordingIdeTraceSink
import dev.zacsweers.metro.idea.tracing.StartMetroTraceAction
import dev.zacsweers.metro.idea.tracing.StopMetroTraceAction
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
    for (action in listOf(StartMetroTraceAction(), StopMetroTraceAction())) {
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
        assertEquals(IdeTraceState.STOPPING, tracing.state.value)
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

  private fun registeredAction(id: String): AnAction =
    checkNotNull(ActionManager.getInstance().getAction(id))

  private fun event(action: AnAction, includeProject: Boolean = true): AnActionEvent {
    val context = DataContext { dataId ->
      if (includeProject && CommonDataKeys.PROJECT.`is`(dataId)) project else null
    }
    return AnActionEvent.createFromAnAction(action, null, ActionPlaces.UNKNOWN, context)
  }
}
