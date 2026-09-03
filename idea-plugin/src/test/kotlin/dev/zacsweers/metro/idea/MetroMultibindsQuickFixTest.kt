// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.zacsweers.metro.compiler.diagnostics.MetroDiagnosticId
import dev.zacsweers.metro.idea.diagnostics.MetroGraphInspection
import dev.zacsweers.metro.idea.graph.KaGraphValidationResult
import dev.zacsweers.metro.idea.graph.MetroGraphValidationService
import dev.zacsweers.metro.idea.index.MetroResolutionService
import dev.zacsweers.metro.idea.model.KaBinding
import org.jetbrains.kotlin.psi.KtFile

/**
 * Exercises source edits through the platform's preview, command, and retained-diagnostic paths.
 */
class MetroMultibindsQuickFixTest : BasePlatformTestCase() {
  override fun setUp() {
    super.setUp()
    project.enableImmediateAutomaticRefresh()
    project.setMetroOptions()
    module.addMetroRuntimeLibrary()
    module.addKotlinStdlibLibrary()
    project.service<MetroGraphValidationService>().clearResults()
    myFixture.enableInspections(MetroGraphInspection())
  }

  fun testPreviewApplyUndoAndRevalidation() {
    val file = configure("@Multibinds")
    assertEmptyMultibinding(validate(file))
    val before = file.text
    val after = before.replace("@Multibinds", "@Multibinds(allowEmpty = true)")
    val action = myFixture.findSingleIntention(FIX_NAME)
    assertEquals(after, myFixture.getIntentionPreviewText(action))
    assertEquals(before, file.text)
    assertFalse(project.service<MetroGraphValidationService>().retainedResults().single().stale)

    // The fixture also checks the platform's read-only-file handling during application.
    myFixture.checkPreviewAndLaunchAction(action)
    assertEquals(after, file.text)
    val service = project.service<MetroGraphValidationService>()
    assertTrue(service.retainedResults().single().stale)
    assertEmpty(myFixture.filterAvailableIntentions(FIX_NAME))
    assertEmpty(validate(file).diagnostics)

    myFixture.performEditorAction(IdeActions.ACTION_UNDO)
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    assertEquals(before, file.text)
    assertEmptyMultibinding(validate(file))
  }

  fun testEmptyParentheses() {
    assertAnnotationEdit("@Multibinds()", "@Multibinds(allowEmpty = true)")
  }

  fun testPositionalFalse() {
    assertAnnotationEdit("@Multibinds(false)", "@Multibinds(true)")
  }

  fun testNamedFalsePreservesComment() {
    assertAnnotationEdit(
      "@Multibinds(allowEmpty = /* optional plugins */ false)",
      "@Multibinds(allowEmpty = /* optional plugins */ true)",
    )
  }

  fun testGetterUseSiteTarget() {
    assertAnnotationEdit(
      "@get:Multibinds",
      "@get:Multibinds(allowEmpty = true)",
      declaration = "val <caret>values: Set<String>",
    )
  }

  fun testImportAliasKeepsItsSpelling() {
    assertAnnotationEdit(
      "@EmptySet",
      "@EmptySet(allowEmpty = true)",
      prelude = "import dev.zacsweers.metro.Multibinds as EmptySet",
    )
  }

  fun testTypeAliasKeepsItsSpelling() {
    assertAnnotationEdit(
      "@EmptySet",
      "@EmptySet(allowEmpty = true)",
      prelude = "typealias EmptySet = Multibinds",
    )
  }

  fun testConstantArgumentDoesNotOfferFix() {
    val file = configure("@Multibinds(allowEmpty = ALLOW)", prelude = "const val ALLOW = false")
    assertEmptyMultibinding(validate(file))
    assertEmpty(myFixture.filterAvailableIntentions(FIX_NAME))
  }

  fun testCustomAnnotationDoesNotOfferFix() {
    project.setMetroOptions("custom-multibinds" to "test/CustomMultibinds")
    val file =
      configure(
        "@CustomMultibinds",
        prelude = "annotation class CustomMultibinds(val allowEmpty: Boolean = false)",
      )
    assertNoEditTarget(validate(file))
    assertEmpty(myFixture.filterAvailableIntentions(FIX_NAME))
  }

  fun testDaggerAnnotationDoesNotOfferFix() {
    project.setMetroOptions("custom-multibinds" to "dagger.multibindings/Multibinds")
    myFixture.addFileToProject(
      "dagger/multibindings/Multibinds.kt",
      "package dagger.multibindings\nannotation class Multibinds",
    )
    val file = configure("@dagger.multibindings.Multibinds")
    assertNoEditTarget(validate(file))
    assertEmpty(myFixture.filterAvailableIntentions(FIX_NAME))
  }

  fun testSeveralDeclarationsDoNotOfferFix() {
    val file =
      myFixture.configureMetroFile(
        """
      interface First {
        @Multibinds fun <caret>values(): Set<String>
      }
      interface Second {
        @Multibinds fun values(): Set<String>
      }
      @DependencyGraph(bindingContainers = [First::class, Second::class])
      interface AppGraph { val values: Set<String> }
      """
      )
    assertNoEditTarget(validate(file))
    assertEmpty(myFixture.filterAvailableIntentions(FIX_NAME))
  }

  fun testCompiledDeclarationDoesNotOfferFix() {
    module.withMetroLibFixtureLibrary {
      val file =
        myFixture.configureMetroFile(
          """
        @DependencyGraph(bindingContainers = [libtest.LibMultibindsDeclarations::class])
        interface AppGraph { val <caret>values: Set<String> }
        """
        )
      assertNoEditTarget(validate(file))
      assertEmpty(myFixture.filterAvailableIntentions(FIX_NAME))
    }
  }

  fun testRetainedFixDoesNothingAfterResultsAreCleared() {
    val file = configure("@Multibinds")
    assertEmptyMultibinding(validate(file))
    val action = myFixture.findSingleIntention(FIX_NAME)
    val before = file.text
    project.service<MetroGraphValidationService>().clearResults()

    myFixture.launchAction(action)
    assertEquals(before, file.text)
  }

  fun testRetainedFixDoesNothingAfterSourceChanges() {
    val file = configure("@Multibinds(false)")
    assertEmptyMultibinding(validate(file))
    val action = myFixture.findSingleIntention(FIX_NAME)
    WriteCommandAction.runWriteCommandAction(project) {
      val document = myFixture.editor.document
      val offset = document.text.indexOf("false")
      document.replaceString(offset, offset + "false".length, "true")
      PsiDocumentManager.getInstance(project).commitAllDocuments()
    }
    val edited = file.text

    myFixture.launchAction(action)
    assertEquals(edited, file.text)
  }

  private fun assertAnnotationEdit(
    before: String,
    after: String,
    declaration: String = "fun <caret>values(): Set<String>",
    prelude: String = "",
  ) {
    val file = configure(before, declaration, prelude)
    assertEmptyMultibinding(validate(file))
    val expected = file.text.replace(before, after)
    val action = myFixture.findSingleIntention(FIX_NAME)
    myFixture.checkPreviewAndLaunchAction(action)
    assertEquals(expected, file.text)
    assertEmpty(validate(file).diagnostics)
  }

  private fun configure(
    annotation: String,
    declaration: String = "fun <caret>values(): Set<String>",
    prelude: String = "",
  ): KtFile =
    myFixture.configureMetroFile(
      """
    $prelude
    interface Declarations {
      $annotation
      $declaration
    }
    @DependencyGraph(bindingContainers = [Declarations::class])
    interface AppGraph { val values: Set<String> }
    """
    )

  private fun validate(file: KtFile): KaGraphValidationResult.Completed {
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val context = index.contextsFor(index.graphs.single()).single()
    return project.service<MetroGraphValidationService>().validate(file, context).requireCompleted()
  }

  private fun assertEmptyMultibinding(result: KaGraphValidationResult.Completed) {
    assertEquals(listOf(MetroDiagnosticId.EMPTY_MULTIBINDING), result.diagnostics.map { it.id })
  }

  private fun assertNoEditTarget(result: KaGraphValidationResult.Completed) {
    assertEmptyMultibinding(result)
    val binding = result.diagnostics.single().related.single() as KaBinding.Multibinding
    assertNull(binding.metroMultibindsAnnotation)
  }

  private companion object {
    const val FIX_NAME = "Allow an empty multibinding"
  }
}
