// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.util.xmlb.XmlSerializer
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
    val existing =
      XmlSerializer.deserialize(Element("MetroSettingsState"), MetroSettingsState::class.java)
    assertFalse(existing.enableDebuggingOptions)
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
