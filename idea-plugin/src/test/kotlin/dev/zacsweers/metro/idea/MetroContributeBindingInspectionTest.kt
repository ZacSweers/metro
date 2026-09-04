// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModChooseAction
import com.intellij.modcommand.ModCommand
import com.intellij.modcommand.ModCommandExecutor
import com.intellij.modcommand.ModStartTemplate
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.zacsweers.metro.idea.intentions.contributions.ContributeBindingAction
import dev.zacsweers.metro.idea.intentions.contributions.ContributionPickerStep
import dev.zacsweers.metro.idea.intentions.contributions.MetroContributeBindingInspection
import org.jetbrains.kotlin.analysis.api.permissions.allowAnalysisOnEdt
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile

/** Exercises picker steps before execution and checks the resulting editor command. */
class MetroContributeBindingInspectionTest : BasePlatformTestCase() {
  override fun setUp() {
    super.setUp()
    TemplateManagerImpl.setTemplateTesting(testRootDisposable)
    project.setMetroOptions()
    module.addMetroRuntimeLibrary()
    module.addKotlinStdlibLibrary()
    myFixture.enableInspections(MetroContributeBindingInspection())
  }

  override fun tearDown() {
    try {
      val state = TemplateManagerImpl.getTemplateState(myFixture.editor)
      if (state != null) WriteCommandAction.runWriteCommandAction(project) { state.gotoEnd() }
    } finally {
      super.tearDown()
    }
  }

  fun testRegularContributionPreviewAndUndo() {
    val file = configure()
    val before = file.text
    val intention = myFixture.findSingleIntention("Contribute Metro binding")
    myFixture.checkIntentionPreviewHtml(intention, PICKER_PREVIEW)
    assertEquals(before, file.text)

    val scopes = choose(start(file), "ContributesBinding") as ModChooseAction
    val selectedScope = scopes.actions().single { it.familyName == "test.AppScope" }
    assertTrue(
      checkNotNull(myFixture.getIntentionPreviewText(selectedScope.asIntention()))
        .contains("@ContributesBinding(AppScope::class)")
    )
    assertEquals(before, file.text)
    val command = choose(scopes, "test.AppScope")
    execute(command)
    assertTrue(file.text.contains("@ContributesBinding(AppScope::class)"))
    assertEmpty(myFixture.filterAvailableIntentions("Contribute Metro binding"))
    myFixture.performEditorAction(IdeActions.ACTION_UNDO)
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    assertEquals(before, file.text)
  }

  fun testUnresolvedKindAndMapKeyPreviewsDoNotSelectFirstScopeOrKey() {
    val file =
      configure(
        supertypes =
          """
      interface Service
      abstract class AccountUserScope
      @DependencyGraph(AccountUserScope::class) interface AccountGraph
    """
      )
    val before = file.text
    val kinds = start(file) as ModChooseAction
    val binding = kinds.actions().single { it.familyName == "ContributesBinding" }
    myFixture.checkIntentionPreviewHtml(binding.asIntention(), PICKER_PREVIEW)
    val scopes = choose(kinds, "ContributesIntoMap") as ModChooseAction
    val scope = scopes.actions().single { it.familyName == "test.AccountUserScope" }
    myFixture.checkIntentionPreviewHtml(scope.asIntention(), PICKER_PREVIEW)
    assertEquals(before, file.text)
  }

  fun testUnresolvedPreviewDoesNotEvaluateItsNextStep() {
    val file = configure()
    val owner = implementation(file)
    val step =
      ContributionPickerStep(owner, owner.text, "Next choice", hasRemainingChoices = true) { _, _ ->
        error("The next chooser must wait for an explicit selection")
      }
    assertTrue(step.generatePreview(context()) is IntentionPreviewInfo.Html)
  }

  fun testBoundTypePickerPreservesConcreteTypeArguments() {
    val file =
      configure(
        "@Inject class <caret>Impl : Service<String>, Other",
        "interface Service<T>\ninterface Other",
      )
    val types = choose(start(file), "ContributesIntoSet") as ModChooseAction
    assertEquals(setOf("Service<String>", "Other"), types.actions().map { it.familyName }.toSet())
    val command = choose(choose(types, "Service<String>"), "test.AppScope")
    execute(command)
    assertTrue(
      file.text,
      file.text.contains(
        "@ContributesIntoSet(AppScope::class, binding = binding<Service<String>>())"
      ),
    )
  }

  fun testClassQualifierRemainsOnTheClass() {
    val file =
      configure(
        "@Named(\"service\") @Inject class <caret>Impl : Service, Other",
        """
      interface Service
      interface Other
      @Qualifier
      @Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
      annotation class Named(val value: String)
      """,
      )
    val types = choose(start(file), "ContributesBinding")
    val command = choose(chooseContaining(types, "Service"), "test.AppScope")
    execute(command)
    assertTrue(file.text, file.text.contains("binding = binding<Service>()"))
    assertTrue(file.text, file.text.contains("@Named(\"service\")"))
  }

  fun testMatchingBoundTypeNamesAreQualifiedInPicker() {
    myFixture.addFileToProject("one/Service.kt", "package one\ninterface Service")
    myFixture.addFileToProject("two/Service.kt", "package two\ninterface Service")
    val file = configure("@Inject class <caret>Impl : one.Service, two.Service", "")
    val types = choose(start(file), "ContributesBinding") as ModChooseAction
    assertEquals(setOf("one.Service", "two.Service"), types.actions().map { it.familyName }.toSet())
    execute(choose(choose(types, "two.Service"), "test.AppScope"))
    assertTrue(file.text, file.text.contains("import two.Service"))
    assertTrue(file.text, file.text.contains("binding = binding<Service>()"))
  }

  fun testAbandonedPickerDoesNotEditSource() {
    val file = configure()
    val before = file.text
    val kinds = start(file) as ModChooseAction
    assertEquals(
      listOf("ContributesBinding", "ContributesIntoSet", "ContributesIntoMap"),
      kinds.actions().map { it.familyName },
    )
    val scopes = choose(kinds, "ContributesIntoMap")
    assertTrue(scopes is ModChooseAction)
    val keys = choose(scopes, "test.AppScope")
    assertTrue(keys is ModChooseAction)
    assertEquals(before, file.text)
  }

  fun testMapKeyValueIsAnEditorTemplateField() {
    val file = configure()
    val keys = choose(choose(start(file), "ContributesIntoMap"), "test.AppScope")
    val selectedKey = (keys as ModChooseAction).actions().single { "StringKey" in it.familyName }
    val preview = checkNotNull(myFixture.getIntentionPreviewText(selectedKey.asIntention()))
    assertTrue(preview, preview.contains("@StringKey(value = \"key\")"))
    assertTrue(preview, preview.contains("@ContributesIntoMap(AppScope::class)"))
    val command = chooseContaining(keys, "StringKey")
    val template = command.unpack().filterIsInstance<ModStartTemplate>().single()
    assertTrue(
      template.fields().filterIsInstance<ModStartTemplate.ExpressionField>().any {
        it.varName() == "mapKey_value"
      }
    )
    execute(command)
    assertTrue(file.text, file.text.contains("@ContributesIntoMap(AppScope::class)"))
    assertTrue(file.text, file.text.contains("@StringKey(value = \"key\")"))
  }

  fun testImplicitBinaryClassKeyHasNoValueTemplate() {
    module.withMetroLibFixtureLibrary {
      project.setMetroOptions("custom-map-key" to "libtest/LibMapKeyContract")
      val file = configure()
      val keys = choose(choose(start(file), "ContributesIntoMap"), "test.AppScope")
      val command = chooseContaining(keys, "LibImplicitClassKey")
      assertEmpty(command.unpack().filterIsInstance<ModStartTemplate>())
      execute(command)
      assertTrue(file.text, file.text.contains("@LibImplicitClassKey"))
      assertFalse(file.text, file.text.contains("@LibImplicitClassKey("))
    }
  }

  fun testExistingMapKeyIsPreserved() {
    val file = configure("@StringKey(\"existing\") @Inject class <caret>Impl : Service")
    val kinds = start(file) as ModChooseAction
    assertEquals(listOf("ContributesIntoMap"), kinds.actions().map { it.familyName })
    val command = choose(choose(kinds, "ContributesIntoMap"), "test.AppScope")
    assertFalse(command is ModChooseAction)
    execute(command)
    val implementation = implementation(file)
    assertEquals(
      1,
      implementation.annotationEntries.count { it.shortName?.asString() == "StringKey" },
    )
    assertTrue(file.text.contains("@StringKey(\"existing\")"))
  }

  fun testUnknownScopeCanBeEnteredInTemplate() {
    val file = configure()
    val command = choose(choose(start(file), "ContributesBinding"), "Enter scope in editor")
    val template = command.unpack().filterIsInstance<ModStartTemplate>().single()
    assertTrue(
      template.fields().filterIsInstance<ModStartTemplate.ExpressionField>().any {
        it.varName() == "scope"
      }
    )
  }

  fun testScopeAndMapKeyCanBeEditedInOneTemplate() {
    val file = configure()
    val before = file.text
    val keys = choose(choose(start(file), "ContributesIntoMap"), "Enter scope in editor")
    val command = chooseContaining(keys, "StringKey")
    ModCommandExecutor.executeInteractively(
      context(),
      "Contribute Metro binding",
      myFixture.editor,
    ) {
      command
    }
    assertEquals("YourScope", myFixture.editor.selectionModel.selectedText)
    myFixture.type("AppScope")
    myFixture.performEditorAction("NextTemplateVariable")
    assertEquals("key", myFixture.editor.selectionModel.selectedText)
    myFixture.type("checkout")
    myFixture.performEditorAction("NextTemplateVariable")
    assertNull(TemplateManagerImpl.getTemplateState(myFixture.editor))
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    assertTrue(file.text, file.text.contains("@ContributesIntoMap(AppScope::class)"))
    assertTrue(file.text, file.text.contains("@StringKey(value = \"checkout\")"))
    // Template navigation and typed values participate in the editor's ordinary undo history.
    repeat(6) {
      if (file.text != before) {
        myFixture.performEditorAction(IdeActions.ACTION_UNDO)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
      }
    }
    assertEquals(before, file.text)
  }

  fun testScopeNamesAreEscapedAndSuspiciousScopesAreOmitted() {
    val file =
      configure(
        supertypes =
          """
      interface Service
      abstract class `when` private constructor()
      @DependencyGraph(`when`::class) interface EscapedGraph
      @Scope annotation class CustomScope
      @DependencyGraph(CustomScope::class) interface SuspiciousGraph
      @DependencyGraph(AppGraph::class) interface GraphScopeGraph
    """
      )
    val scopes = choose(start(file), "ContributesBinding") as ModChooseAction
    val labels = scopes.actions().map { it.familyName }
    assertContainsElements(labels, "test.`when`", "test.AppScope")
    assertFalse("test.CustomScope" in labels)
    assertFalse("test.AppGraph" in labels)
    execute(choose(scopes, "test.`when`"))
    assertTrue(file.text, file.text.contains("@ContributesBinding(`when`::class)"))
  }

  fun testStalePickerDoesNotEditSource() {
    val file = configure()
    val scopes = choose(start(file), "ContributesBinding")
    WriteCommandAction.runWriteCommandAction(project) {
      val declaration = implementation(file)
      declaration.setName("Changed")
    }
    val afterRename = file.text
    val command = choose(scopes, "test.AppScope")
    assertTrue(command.isEmpty)
    assertEquals(afterRename, file.text)
  }

  fun testChangedSelectedScopeIsRejected() {
    val file = configure()
    val scopes = choose(start(file), "ContributesBinding")
    WriteCommandAction.runWriteCommandAction(project) {
      file.declarations
        .filterIsInstance<KtClassOrObject>()
        .single { it.name == "AppScope" }
        .setName("OtherScope")
    }
    val before = file.text
    assertTrue(choose(scopes, "test.AppScope").isEmpty)
    assertEquals(before, file.text)
  }

  fun testChangedSelectedKeyIsRejected() {
    val keyFile =
      myFixture.addFileToProject(
        "keys/EntryKey.kt",
        "package keys\n@dev.zacsweers.metro.MapKey annotation class EntryKey(val value: String)",
      ) as KtFile
    val file = configure()
    val keys = choose(choose(start(file), "ContributesIntoMap"), "test.AppScope")
    WriteCommandAction.runWriteCommandAction(project) {
      keyFile.declarations
        .filterIsInstance<KtClassOrObject>()
        .single()
        .annotationEntries
        .single()
        .delete()
    }
    val before = file.text
    assertTrue(chooseContaining(keys, "EntryKey").isEmpty)
    assertEquals(before, file.text)
  }

  fun testDisabledModuleDoesNotOfferContribution() {
    configure()
    project.setMetroOptions("enabled" to "false")
    assertEmpty(myFixture.filterAvailableIntentions("Contribute Metro binding"))
  }

  private fun configure(
    declaration: String = "@Inject class <caret>Impl : Service",
    supertypes: String = "interface Service",
  ): KtFile =
    myFixture.configureMetroFile(
      """
    $supertypes
    abstract class AppScope private constructor()
    @DependencyGraph(AppScope::class) interface AppGraph
    $declaration
  """
    )

  private fun implementation(file: KtFile): KtClassOrObject =
    file.declarations.filterIsInstance<KtClassOrObject>().last()

  private fun context(): ActionContext = ActionContext.from(myFixture.editor, myFixture.file)

  private fun start(file: KtFile): ModCommand = allowAnalysisOnEdt {
    ContributeBindingAction(implementation(file)).perform(context())
  }

  private fun choose(command: ModCommand, title: String): ModCommand = allowAnalysisOnEdt {
    (command as ModChooseAction).actions().single { it.familyName == title }.perform(context())
  }

  private fun chooseContaining(command: ModCommand, title: String): ModCommand =
    allowAnalysisOnEdt {
      (command as ModChooseAction).actions().single { title in it.familyName }.perform(context())
    }

  private fun execute(command: ModCommand) {
    CommandProcessor.getInstance()
      .executeCommand(
        project,
        {
          ModCommandExecutor.getInstance().executeInBatch(context(), command)
        },
        "Contribute Metro binding",
        null,
      )
    PsiDocumentManager.getInstance(project).commitAllDocuments()
  }

  companion object {
    private const val PICKER_PREVIEW =
      "<p>Choose a contribution kind and scope. Metro also asks for a bound type or map key when required. " +
        "The final choice previews the annotation change.</p>"
  }
}
