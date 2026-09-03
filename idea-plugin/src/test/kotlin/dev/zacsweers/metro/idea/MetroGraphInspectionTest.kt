// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.zacsweers.metro.idea.diagnostics.MetroGraphInspection
import dev.zacsweers.metro.idea.graph.KaGraphValidationResult
import dev.zacsweers.metro.idea.graph.MetroGraphValidationService
import dev.zacsweers.metro.idea.index.MetroResolutionService
import org.jetbrains.kotlin.psi.KtFile

/** Exercises the inspection against retained validation results and ordinary source edits. */
class MetroGraphInspectionTest : BasePlatformTestCase() {
  override fun setUp() {
    super.setUp()
    project.setMetroOptions()
    module.addMetroRuntimeLibrary()
    project.service<MetroGraphValidationService>().clearResults()
  }

  fun testHighlightingDoesNotStartValidation() {
    val file =
      myFixture.configureMetroFile("@DependencyGraph interface AppGraph { val value: String }")
    val holder = ProblemsHolder(InspectionManager.getInstance(project), file, true)
    val service = project.service<MetroGraphValidationService>()
    service.setBeforeGraphSealObserver { error("Highlighting started graph validation") }
    try {
      assertSame(PsiElementVisitor.EMPTY_VISITOR, MetroGraphInspection().buildVisitor(holder, true))
      assertEmpty(service.retainedResults())
    } finally {
      service.setBeforeGraphSealObserver(null)
    }
  }

  fun testMissingBindingHighlightsItsRequestAndClearsWithResults() {
    val file =
      myFixture.configureMetroFile("@DependencyGraph interface AppGraph { val value: String }")
    validate(file)
    val problem = inspect(file).single()
    assertEquals("value", problem.psiElement.text)
    assertTrue(problem.descriptionTemplate, "AppGraph" in problem.descriptionTemplate)

    project.service<MetroGraphValidationService>().clearResults()
    assertEmpty(inspect(file))
  }

  fun testDuplicateBindingsHighlightEachProvider() {
    val file =
      myFixture.configureMetroFile(
        """
      @DependencyGraph
      interface AppGraph {
        val value: String
        @Provides fun first(): String = "first"
        @Provides fun second(): String = "second"
      }
      """
      )
    validate(file)
    assertEquals(setOf("first", "second"), inspect(file).map { it.psiElement.text }.toSet())
  }

  fun testEditsHideStaleDiagnosticsBeforeAnotherValidation() {
    val file =
      myFixture.configureMetroFile("@DependencyGraph interface AppGraph { val value: String }")
    validate(file)
    assertNotEmpty(inspect(file))

    WriteCommandAction.runWriteCommandAction(project) {
      val document = myFixture.editor.document
      val offset = document.text.indexOf("String")
      document.replaceString(offset, offset + "String".length, "Int")
      PsiDocumentManager.getInstance(project).commitAllDocuments()
    }
    assertEmpty(inspect(file))
    assertTrue(project.service<MetroGraphValidationService>().retainedResults().single().stale)

    validate(file)
    assertEquals("value", inspect(file).single().psiElement.text)
  }

  fun testSharedRequestKeepsEachGraphContext() {
    val file =
      myFixture.configureMetroFile(
        """
      interface Accessors { val value: String }
      @DependencyGraph interface FirstGraph : Accessors
      @DependencyGraph interface SecondGraph : Accessors
      """
      )
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val service = project.service<MetroGraphValidationService>()
    for (graph in index.graphs) service.validate(file, index.contextsFor(graph).single())
    val problems = inspect(file)
    assertEquals(2, problems.size)
    assertEquals(setOf("value"), problems.map { it.psiElement.text }.toSet())
    assertTrue(problems.any { "FirstGraph" in it.descriptionTemplate })
    assertTrue(problems.any { "SecondGraph" in it.descriptionTemplate })
  }

  fun testDisabledModuleDoesNotShowRetainedDiagnostics() {
    val file =
      myFixture.configureMetroFile("@DependencyGraph interface AppGraph { val value: String }")
    validate(file)
    project.setMetroOptions("enabled" to "false")
    assertEmpty(inspect(file))
  }

  private fun validate(file: KtFile): KaGraphValidationResult {
    val index = project.service<MetroResolutionService>().awaitIndex(file)
    val context = index.contextsFor(index.graphs.single()).single()
    return project.service<MetroGraphValidationService>().validate(file, context)
  }

  private fun inspect(file: KtFile): List<ProblemDescriptor> {
    val holder = ProblemsHolder(InspectionManager.getInstance(project), file, true)
    val visitor = MetroGraphInspection().buildVisitor(holder, true)
    PsiTreeUtil.processElements(file) {
      it.accept(visitor)
      true
    }
    return holder.results
  }
}
