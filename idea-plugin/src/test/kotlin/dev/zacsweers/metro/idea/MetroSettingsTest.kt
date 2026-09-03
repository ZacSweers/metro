// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.util.xmlb.XmlSerializer
import junit.framework.TestCase
import org.jdom.Element

/** Covers the default for new projects and explicit refresh choices stored by existing projects. */
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
}
