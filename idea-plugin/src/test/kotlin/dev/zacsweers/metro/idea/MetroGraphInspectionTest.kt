// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.zacsweers.metro.compiler.diagnostics.MetroSeverity
import dev.zacsweers.metro.idea.diagnostics.MetroGraphInspection
import dev.zacsweers.metro.idea.diagnostics.MetroGraphWarningInspection
import dev.zacsweers.metro.idea.diagnostics.metroDiagnosticsForFile
import dev.zacsweers.metro.idea.graph.CachedValidation
import dev.zacsweers.metro.idea.graph.KaGraphDiagnostic
import dev.zacsweers.metro.idea.graph.KaGraphValidationResult
import dev.zacsweers.metro.idea.graph.MetroGraphValidationService
import dev.zacsweers.metro.idea.index.MetroResolutionService
import org.jetbrains.kotlin.psi.KtFile

/** Exercises the inspection against retained validation results and ordinary source edits. */
class MetroGraphInspectionTest : BasePlatformTestCase() {
  override fun setUp() {
    super.setUp()
    project.enableImmediateAutomaticRefresh()
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

  fun testNestedMissingBindingHighlightsItsNearestCrossFileRequest() {
    val dependencies =
      myFixture.addFileToProject(
        "Dependencies.kt",
        """
        package test

        import dev.zacsweers.metro.Inject

        interface Missing
        @Inject class Inner(val missing: Missing)
        @Inject class Outer(val inner: Inner)
        """
          .trimIndent(),
      ) as KtFile
    val graph =
      myFixture.configureMetroFile("@DependencyGraph interface AppGraph { val outer: Outer }")
    validate(graph)

    assertEmpty(inspect(graph))
    val problem = inspect(dependencies).single()
    assertSame(
      dependencies.declarationsIncludingNested().klass("Inner").nameIdentifier,
      problem.psiElement,
    )
    assertTrue(problem.descriptionTemplate, "AppGraph" in problem.descriptionTemplate)
  }

  fun testCompiledChildDiagnosticHighlightsItsSourceParent() {
    module.withMetroLibFixtureLibrary {
      val file =
        myFixture.configureMetroFile(
          "@DependencyGraph interface AppGraph { val child: libtest.LibDirectChildGraph }"
        )
      val index = project.service<MetroResolutionService>().awaitIndex(file)
      val parent = index.graphs.single { it.name == "AppGraph" }
      project.service<MetroGraphValidationService>().validateWithExtensions(file, parent)

      val problem = inspect(file).single()
      assertEquals("AppGraph", problem.psiElement.text)
      assertTrue(problem.descriptionTemplate, "LibDirectChildGraph" in problem.descriptionTemplate)
    }
  }

  fun testNativeHighlightUsesTheInspectionProfileSeverity() {
    val file =
      myFixture.configureMetroFile("@DependencyGraph interface AppGraph { val value: String }")
    myFixture.enableInspections(MetroGraphInspection())
    validate(file)
    val description = inspect(file).single().descriptionTemplate
    val key = checkNotNull(HighlightDisplayKey.find("MetroGraph"))
    val profile = InspectionProjectProfileManager.getInstance(project).currentProfile
    val previousLevel = profile.getErrorLevel(key, file)
    try {
      profile.setErrorLevel(key, HighlightDisplayLevel.WEAK_WARNING, project)
      val highlight = myFixture.doHighlighting().single { it.description == description }
      assertEquals(HighlightDisplayLevel.WEAK_WARNING.severity, highlight.severity)
    } finally {
      profile.setErrorLevel(key, previousLevel, project)
    }
  }

  fun testSeverityAdaptersKeepErrorsAndWarningsSeparate() {
    val file =
      myFixture.configureMetroFile("@DependencyGraph interface AppGraph { val value: String }")
    val result = validate(file) as KaGraphValidationResult.Completed
    val error = result.diagnostics.single()
    val warning =
      KaGraphDiagnostic(
        error.diagnostic.copy(severity = MetroSeverity.WARNING),
        error.stack,
        error.related,
      )
    val mixed =
      KaGraphValidationResult.Completed(
        result.context,
        listOf(error, warning),
        result.topology,
        result.bindings,
        result.suspendKeys,
        result.parentReservations,
      )
    val cached = listOf(CachedValidation(mixed, stale = false))

    assertSame(
      error,
      metroDiagnosticsForFile(file, cached, MetroSeverity.ERROR).single().diagnostic,
    )
    assertSame(
      warning,
      metroDiagnosticsForFile(file, cached, MetroSeverity.WARNING).single().diagnostic,
    )
    val holder = ProblemsHolder(InspectionManager.getInstance(project), file, true)
    assertSame(
      PsiElementVisitor.EMPTY_VISITOR,
      MetroGraphWarningInspection().buildVisitor(holder, true),
    )
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

  fun testDumbModeDoesNotShowRetainedDiagnostics() {
    val file =
      myFixture.configureMetroFile("@DependencyGraph interface AppGraph { val value: String }")
    validate(file)
    assertNotEmpty(inspect(file))

    DumbModeTestUtils.runInDumbModeSynchronously(project) {
      val holder = ProblemsHolder(InspectionManager.getInstance(project), file, true)
      assertSame(PsiElementVisitor.EMPTY_VISITOR, MetroGraphInspection().buildVisitor(holder, true))
    }
    assertNotEmpty(inspect(file))
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
