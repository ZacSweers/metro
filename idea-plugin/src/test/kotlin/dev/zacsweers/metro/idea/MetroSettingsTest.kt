// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import com.intellij.util.xmlb.XmlSerializer
import javax.swing.JCheckBox
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
}

/** Exercises dependent visibility using the real settings panel and its bound state. */
class MetroSettingsConfigurableTest : BasePlatformTestCase() {
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
