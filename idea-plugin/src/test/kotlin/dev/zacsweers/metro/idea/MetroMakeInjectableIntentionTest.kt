// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModChooseAction
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.zacsweers.metro.compiler.MetroClassIds
import dev.zacsweers.metro.idea.intentions.injection.MakeClassInjectableIntention
import dev.zacsweers.metro.idea.intentions.injection.injectionAnnotationTargets
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtFile

/** Exercises annotation placement, constructor choices, and ModCommand preview and undo. */
class MetroMakeInjectableIntentionTest : BasePlatformTestCase() {
  override fun setUp() {
    super.setUp()
    project.setMetroOptions()
    module.addMetroRuntimeLibrary()
    module.addKotlinStdlibLibrary()
  }

  fun testPreviewApplyAndUndoWithNewImport() {
    val file =
      myFixture.configureByText(
        "Service.kt",
        "package test\n\nclass <caret>Service(val value: String)\n",
      ) as KtFile
    val before = file.text
    val expected =
      "package test\n\nimport dev.zacsweers.metro.Inject\n\n@Inject\nclass Service(val value: String)\n"
    val action = intention()
    assertEquals(expected, myFixture.getIntentionPreviewText(action))
    assertEquals(before, file.text)
    myFixture.checkPreviewAndLaunchAction(action)
    assertEquals(expected, file.text)
    assertUnavailable()

    myFixture.performEditorAction(IdeActions.ACTION_UNDO)
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    assertEquals(before, file.text)
    intention()
  }

  fun testImplicitPrimaryConstructorUsesClassAnnotation() {
    val file = myFixture.configureMetroFile("class <caret>Service")
    val expected = file.text.replace("class Service", "@Inject\nclass Service")
    myFixture.checkPreviewAndLaunchAction(intention())
    assertEquals(expected, file.text)
  }

  fun testPrimaryConstructorChoicePreviewsAppliesAndUndoes() {
    val file =
      myFixture.configureMetroFile(
        """
        class <caret>Service(val value: String) {
          constructor() : this("default")
        }
        """
      )
    val before = file.text
    myFixture.checkIntentionPreviewHtml(
      intention(),
      "<p>Choose the constructor Metro should call.</p>",
    )
    assertEquals(before, file.text)
    val choice = constructorChoice(file)
    assertEquals(
      listOf("primary constructor(val value: String)", "constructor()"),
      choice.actions.map { it.familyName },
    )
    val action = choice.actions.first().asIntention()
    val expected = before.replace("class Service(", "class Service @Inject constructor(")
    assertEquals(expected, myFixture.getIntentionPreviewText(action))
    assertEquals(before, file.text)
    myFixture.checkPreviewAndLaunchAction(action)
    assertEquals(expected, file.text)
    assertUnavailable()

    myFixture.performEditorAction(IdeActions.ACTION_UNDO)
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    assertEquals(before, file.text)
    intention()
  }

  fun testSecondaryConstructorChoiceWithPrimaryConstructor() {
    val file =
      myFixture.configureMetroFile(
        """
        class <caret>Service(val value: String) {
          constructor() : this("default")
        }
        """
      )
    val action = constructorChoice(file).actions[1].asIntention()
    myFixture.checkPreviewAndLaunchAction(action)
    val klass = file.declarations.single() as KtClass
    assertEmpty(klass.annotationEntries)
    assertEmpty(klass.primaryConstructor!!.annotationEntries)
    assertEquals(
      "Inject",
      klass.secondaryConstructors.single().annotationEntries.single().shortName?.asString(),
    )
  }

  fun testVisibleSecondaryConstructorWithHiddenPrimaryConstructor() {
    for (visibility in listOf("private", "protected")) {
      val file =
        myFixture.configureMetroFile(
          """
          class <caret>Service $visibility constructor(val value: String) {
            constructor() : this("default")
          }
          """
        )
      myFixture.checkPreviewAndLaunchAction(intention())
      val klass = file.declarations.single() as KtClass
      assertEmpty(klass.annotationEntries)
      assertEmpty(klass.primaryConstructor!!.annotationEntries)
      assertEquals(
        "Inject",
        klass.secondaryConstructors.single().annotationEntries.single().shortName?.asString(),
      )
    }
  }

  fun testSoleVisiblePrimaryConstructorIsAnnotatedExplicitly() {
    val file =
      myFixture.configureMetroFile(
        """
        class <caret>Service(val value: String) {
          private constructor() : this("default")
        }
        """
      )
    val expected = file.text.replace("class Service(", "class Service @Inject constructor(")
    myFixture.checkPreviewAndLaunchAction(intention())
    assertEquals(expected, file.text)
  }

  fun testSecondaryConstructorPickerAppliesTheSelectedConstructor() {
    val file =
      myFixture.configureMetroFile(
        """
        class <caret>Service {
          constructor(value: String) {}
          constructor(value: Int) {}
          private constructor() {}
        }
        """
      )
    val choice = constructorChoice(file)
    assertEquals(
      listOf("constructor(value: String)", "constructor(value: Int)"),
      choice.actions.map { it.familyName },
    )
    myFixture.checkPreviewAndLaunchAction(choice.actions[1].asIntention())
    val klass = file.declarations.single() as KtClass
    assertEmpty(klass.annotationEntries)
    assertEmpty(klass.secondaryConstructors[0].annotationEntries)
    assertEquals(
      "Inject",
      klass.secondaryConstructors[1].annotationEntries.single().shortName?.asString(),
    )
    assertEmpty(klass.secondaryConstructors[2].annotationEntries)
  }

  fun testOneSecondaryConstructorIsAnnotatedDirectly() {
    val file =
      myFixture.configureMetroFile(
        """
        class <caret>Service {
          constructor(value: String) {}
        }
        """
      )
    myFixture.checkPreviewAndLaunchAction(intention())
    val klass = file.declarations.single() as KtClass
    assertEmpty(klass.annotationEntries)
    assertEquals(
      "Inject",
      klass.secondaryConstructors.single().annotationEntries.single().shortName?.asString(),
    )
  }

  fun testImportAliasIsReused() {
    val file =
      myFixture.configureByText(
        "Service.kt",
        "package test\n\nimport dev.zacsweers.metro.Inject as Wire\n\nclass <caret>Service\n",
      ) as KtFile
    val expected = file.text.replace("class Service", "@Wire\nclass Service")
    myFixture.checkPreviewAndLaunchAction(intention())
    assertEquals(expected, file.text)
  }

  fun testUnrelatedInjectAnnotationKeepsItsMeaning() {
    val file =
      myFixture.configureMetroFile(
        """
        annotation class Inject

        @Inject
        class <caret>Service
        """
      )
    myFixture.checkPreviewAndLaunchAction(intention())
    val klass = file.declarations.filterIsInstance<KtClass>().last()
    val annotations = allowAnalysisOnEdt {
      analyze(klass) { klass.symbol.annotations.map { it.classId }.toSet() }
    }
    assertEquals(setOf(ClassId.topLevel(FqName("test.Inject")), MetroClassIds.inject), annotations)
  }

  fun testExistingInjectionAnnotationsHideTheAction() {
    for (declaration in
      listOf(
        "@Inject class <caret>Service",
        "class <caret>Service @Inject constructor()",
        "class <caret>Service { @Inject constructor(value: String) {} }",
        "@AssistedInject class <caret>Service(@Assisted val value: String)",
        "class <caret>Service(@Assisted val value: String)",
      )) {
      myFixture.configureMetroFile(declaration)
      assertUnavailable()
    }
  }

  fun testCustomInjectionAndItsAliasesHideTheAction() {
    project.setMetroOptions("custom-inject" to "test/CustomInject")
    myFixture.configureMetroFile(
      """
      annotation class CustomInject
      typealias Wired = CustomInject
      @Wired class <caret>Service
      """
    )
    assertUnavailable()
  }

  fun testInteropInjectionHidesTheAction() {
    project.setMetroOptions("interop-include-javax-annotations" to "true")
    myFixture.addFileToProject(
      "javax/inject/Inject.kt",
      "package javax.inject\nannotation class Inject",
    )
    myFixture.configureMetroFile("class <caret>Service @javax.inject.Inject constructor()")
    assertUnavailable()
  }

  fun testContributionInjectionHonorsModuleOptions() {
    myFixture.configureMetroFile("@ContributesBinding(AppScope::class) class <caret>Service")
    assertUnavailable()
    project.setMetroOptions("contributes-as-inject" to "false")
    intention()
  }

  fun testDisabledModuleHidesTheAction() {
    project.setMetroOptions("enabled" to "false")
    myFixture.configureMetroFile("class <caret>Service")
    assertUnavailable()
  }

  fun testUnsupportedClassesAndVisibilityHideTheAction() {
    for (declaration in
      listOf(
        "interface <caret>Service",
        "abstract class <caret>Service",
        "sealed class <caret>Service",
        "enum class <caret>Service { VALUE }",
        "annotation class <caret>Service",
        "object <caret>Service",
        "private class <caret>Service",
        "class <caret>Service private constructor()",
        "class <caret>Service protected constructor()",
        "class <caret>Service { private constructor(value: String) {} }",
        "private class Hidden { class <caret>Service }",
        "class Outer { inner class <caret>Service }",
        "fun example() { class <caret>Service }",
        "expect class <caret>Service()",
      )) {
      myFixture.configureMetroFile(declaration)
      assertUnavailable()
    }
  }

  fun testClassMemberCaretDoesNotOfferTheAction() {
    myFixture.configureMetroFile("class Service { fun <caret>work() {} }")
    assertUnavailable()
  }

  fun testCompiledClassDoesNotOfferTheAction() {
    module.withMetroLibFixtureLibrary {
      val file = myFixture.configureMetroFile("class <caret>Service")
      val klass = allowAnalysisOnEdt {
        analyze(file) {
          findClass(ClassId.topLevel(FqName("libtest.LibChildValue")))?.psi as KtClass
        }
      }
      assertTrue(klass.containingKtFile.isCompiled)
      assertEmpty(injectionAnnotationTargets(klass))
    }
  }

  fun testConstructorChoiceIsRecheckedAfterAnEdit() {
    val file =
      myFixture.configureMetroFile(
        """
        class <caret>Service(val value: String) {
          constructor() : this("default")
        }
        """
      )
    val choice = constructorChoice(file).actions.first()
    WriteCommandAction.runWriteCommandAction(project) {
      val document = myFixture.editor.document
      document.insertString(document.text.indexOf("class Service"), "@Inject\n")
      PsiDocumentManager.getInstance(project).commitAllDocuments()
    }
    val context = ActionContext.from(myFixture.editor, file)
    assertTrue(allowAnalysisOnEdt { choice.perform(context).isEmpty })
  }

  private fun constructorChoice(file: KtFile): ModChooseAction {
    intention()
    val context = ActionContext.from(myFixture.editor, file)
    return allowAnalysisOnEdt { MakeClassInjectableIntention().perform(context) } as ModChooseAction
  }

  private fun intention(): IntentionAction = myFixture.findSingleIntention(ACTION_NAME)

  private fun assertUnavailable() {
    assertEmpty(myFixture.filterAvailableIntentions(ACTION_NAME))
  }

  private companion object {
    const val ACTION_NAME = "Make class injectable"
  }
}
