// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jetbrains.kotlin.psi.KtFile

class MetroKaptCompilerPluginInspectionSuppressorTest : BasePlatformTestCase() {

  private val suppressor = MetroKaptCompilerPluginInspectionSuppressor()

  override fun setUp() {
    super.setUp()
    project.setMetroOptions("enabled" to "true")
  }

  fun testSuppressesKaptConfigurationWarningByDefault() {
    val file = configureKotlinFile()

    assertTrue(suppressor.isSuppressedFor(file, KAPT_INSPECTION_ID))
  }

  fun testDoesNotSuppressWhenSettingIsDisabled() {
    val settings = MetroSettings.getInstance(project).state
    settings.suppressKaptConfigurationWarning = false
    try {
      val file = configureKotlinFile()

      assertFalse(suppressor.isSuppressedFor(file, KAPT_INSPECTION_ID))
    } finally {
      settings.suppressKaptConfigurationWarning = true
    }
  }

  fun testDoesNotSuppressWhenMetroIsDisabled() {
    project.setMetroOptions("enabled" to "false")
    val file = configureKotlinFile()

    assertFalse(suppressor.isSuppressedFor(file, KAPT_INSPECTION_ID))
  }

  fun testDoesNotSuppressWhenMetroIsNotConfigured() {
    project.clearMetroOptions()
    val file = configureKotlinFile()

    assertFalse(suppressor.isSuppressedFor(file, KAPT_INSPECTION_ID))
  }

  fun testDoesNotSuppressOtherInspections() {
    val file = configureKotlinFile()

    assertFalse(suppressor.isSuppressedFor(file, "UnusedSymbol"))
  }

  private fun configureKotlinFile(): KtFile {
    return myFixture.configureByText("Test.kt", "class Test") as KtFile
  }

  private companion object {
    const val KAPT_INSPECTION_ID = "KaptKotlinCompilerPlugin"
  }
}
