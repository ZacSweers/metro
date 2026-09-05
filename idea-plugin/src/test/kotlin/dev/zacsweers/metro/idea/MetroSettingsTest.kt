// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import com.intellij.util.xmlb.XmlSerializer
import javax.swing.JCheckBox
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import junit.framework.TestCase
import org.jdom.Element

/** Covers safe project defaults and persisted refresh and debugging choices. */
class MetroSettingsTest : TestCase() {
  fun testNewProjectsDefaultToManualRefresh() {
    assertFalse(MetroSettingsState().automaticallyRefreshGraphData)
  }

  fun testExistingExplicitRefreshChoicesArePreserved() {
    for (automatic in listOf(false, true)) {
      val stored =
        Element("MetroSettingsState").apply {
          addContent(
            Element("option")
              .setAttribute("name", "automaticallyRefreshGraphData")
              .setAttribute("value", automatic.toString())
          )
        }
      val settings = MetroSettings()
      settings.loadState(XmlSerializer.deserialize(stored, MetroSettingsState::class.java))
      assertEquals(automatic, settings.state.automaticallyRefreshGraphData)
    }
  }

  fun testDebuggingOptionsDefaultToDisabled() {
    assertFalse(MetroSettingsState().enableDebuggingOptions)
    assertFalse(MetroSettingsState().includeThreadActivity)
    val existing =
      XmlSerializer.deserialize(Element("MetroSettingsState"), MetroSettingsState::class.java)
    assertFalse(existing.enableDebuggingOptions)
    assertFalse(existing.includeThreadActivity)
  }

  fun testThreadActivityChoiceSurvivesSerializationIndependentlyOfDebugging() {
    for (enabled in listOf(false, true)) {
      val state = MetroSettingsState().apply { includeThreadActivity = enabled }
      val stored = XmlSerializer.serialize(state)
      val settings = MetroSettings()
      settings.loadState(XmlSerializer.deserialize(stored, MetroSettingsState::class.java))
      assertEquals(enabled, settings.state.includeThreadActivity)
      assertFalse(settings.state.enableDebuggingOptions)
    }
  }

  fun testDebuggingChoiceSurvivesSerialization() {
    for (enabled in listOf(false, true)) {
      val state = MetroSettingsState().apply { enableDebuggingOptions = enabled }
      val stored = XmlSerializer.serialize(state)
      val settings = MetroSettings()
      settings.loadState(XmlSerializer.deserialize(stored, MetroSettingsState::class.java))
      assertEquals(enabled, settings.state.enableDebuggingOptions)
    }
  }

  fun testSourceAnalysisDefaultsToOneWorkerForNewAndExistingProjects() {
    val existing =
      XmlSerializer.deserialize(Element("MetroSettingsState"), MetroSettingsState::class.java)
    for (state in listOf(MetroSettingsState(), existing)) {
      assertEquals(1, state.sourceScanPoolSize)
      assertEquals(1, state.effectiveSourceScanPoolSize)
      state.enableDebuggingOptions = true
      assertEquals(1, state.effectiveSourceScanPoolSize)
    }
  }

  fun testSourceAnalysisPoolSizeIsPreservedWhileDebuggingIsDisabled() {
    val state = MetroSettingsState().apply { sourceScanPoolSize = 4 }
    val stored = XmlSerializer.serialize(state)
    val settings = MetroSettings()
    settings.loadState(XmlSerializer.deserialize(stored, MetroSettingsState::class.java))
    assertEquals(4, settings.state.sourceScanPoolSize)
    assertEquals(1, settings.state.effectiveSourceScanPoolSize)
    settings.state.enableDebuggingOptions = true
    assertEquals(4, settings.state.effectiveSourceScanPoolSize(availableProcessors = 8))
    assertEquals(listOf("sourceScanPoolSize"), stored.children.map { it.getAttributeValue("name") })
  }

  fun testSourceAnalysisClampsStoredPoolSizes() {
    for ((stored, expected) in listOf(-10 to 1, 0 to 1, 1 to 1, 4 to 4, 8 to 8, 64 to 8)) {
      val state =
        MetroSettingsState().apply {
          enableDebuggingOptions = true
          sourceScanPoolSize = stored
        }
      assertEquals(expected, state.effectiveSourceScanPoolSize(availableProcessors = 16))
      assertEquals(stored, state.sourceScanPoolSize)
      state.enableDebuggingOptions = false
      assertEquals(1, state.effectiveSourceScanPoolSize)
    }
  }

  fun testSourceAnalysisLeavesOneCpuForTheIde() {
    val state =
      MetroSettingsState().apply {
        enableDebuggingOptions = true
        sourceScanPoolSize = 8
      }
    for ((cpus, expected) in listOf(1 to 1, 2 to 1, 3 to 2, 4 to 3, 8 to 7, 9 to 8, 16 to 8)) {
      assertEquals(expected, state.effectiveSourceScanPoolSize(availableProcessors = cpus))
    }
    val runtimeCpus = Runtime.getRuntime().availableProcessors()
    assertEquals(
      state.effectiveSourceScanPoolSize(availableProcessors = runtimeCpus),
      state.effectiveSourceScanPoolSize,
    )
  }
}

/** Exercises dependent visibility using the real settings panel and its bound state. */
class MetroSettingsConfigurableTest : BasePlatformTestCase() {
  fun testSourceAnalysisPoolSizeAppearsOnlyWhileDebuggingIsSelected() {
    val state = MetroSettings.getInstance(project).state
    state.enableDebuggingOptions = false
    state.sourceScanPoolSize = 1
    val configurable = MetroSettingsConfigurable(project)
    try {
      val panel = configurable.createPanel()
      val debugging =
        UIUtil.findComponentsOfType(panel, JCheckBox::class.java).single {
          it.text == "Enable debugging options"
        }
      val poolSize = UIUtil.findComponentsOfType(panel, JSpinner::class.java).single()
      assertFalse(poolSize.isVisible)
      assertEquals(1, poolSize.value)
      val model = poolSize.model as SpinnerNumberModel
      assertEquals(1, model.minimum)
      assertEquals(8, model.maximum)

      debugging.doClick()
      assertTrue(poolSize.isVisible)
      poolSize.value = 4
      panel.apply()
      assertEquals(4, state.sourceScanPoolSize)
      assertEquals(4, state.effectiveSourceScanPoolSize(availableProcessors = 8))

      debugging.doClick()
      assertFalse(poolSize.isVisible)
      panel.apply()
      assertEquals(4, state.sourceScanPoolSize)
      assertEquals(1, state.effectiveSourceScanPoolSize)

      debugging.doClick()
      assertTrue(poolSize.isVisible)
      assertEquals(4, poolSize.value)
      poolSize.value = 2
      panel.reset()
      assertEquals(4, poolSize.value)
      assertFalse(debugging.isSelected)
      assertFalse(poolSize.isVisible)
    } finally {
      configurable.disposeUIResources()
      state.enableDebuggingOptions = false
      state.sourceScanPoolSize = 1
    }
  }

  fun testThreadActivityAppearsOnlyWhileDebuggingOptionsAreSelected() {
    val state = MetroSettings.getInstance(project).state
    state.enableDebuggingOptions = false
    state.includeThreadActivity = false
    val configurable = MetroSettingsConfigurable(project)
    try {
      val panel = configurable.createPanel()
      val checkBoxes = UIUtil.findComponentsOfType(panel, JCheckBox::class.java)
      val debugging = checkBoxes.single { it.text == "Enable debugging options" }
      val threads = checkBoxes.single { it.text == "Include thread activity" }
      assertFalse(threads.isVisible)
      assertFalse(threads.isSelected)

      debugging.doClick()
      assertTrue(threads.isVisible)
      assertFalse(threads.isSelected)
      threads.doClick()
      panel.apply()
      assertTrue(state.enableDebuggingOptions)
      assertTrue(state.includeThreadActivity)

      debugging.doClick()
      assertFalse(threads.isVisible)
      panel.apply()
      assertFalse(state.enableDebuggingOptions)
      assertTrue(state.includeThreadActivity)

      debugging.doClick()
      assertTrue(threads.isVisible)
      assertTrue(threads.isSelected)
    } finally {
      configurable.disposeUIResources()
      state.enableDebuggingOptions = false
      state.includeThreadActivity = false
    }
  }
}
