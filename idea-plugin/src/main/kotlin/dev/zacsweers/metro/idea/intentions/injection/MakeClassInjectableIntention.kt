// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.intentions.injection

import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModCommand
import com.intellij.modcommand.Presentation
import com.intellij.modcommand.PsiBasedModCommandAction
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import dev.zacsweers.metro.compiler.MetroClassIds
import dev.zacsweers.metro.idea.intentions.addMetroAnnotation
import org.jetbrains.kotlin.psi.KtBlockExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassBody
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtConstructor
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtModifierListOwner
import org.jetbrains.kotlin.psi.KtPrimaryConstructor
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

/**
 * Adds constructor injection from a class header, with a choice when several constructors are
 * eligible.
 */
class MakeClassInjectableIntention : PsiBasedModCommandAction<KtClass>(KtClass::class.java) {
  override fun getFamilyName(): String = "Make class injectable"

  override fun isFileAllowed(file: PsiFile): Boolean {
    return file is KtFile && isInjectionSourceFile(file)
  }

  override fun stopSearchAt(element: PsiElement, context: ActionContext): Boolean {
    return element is KtBlockExpression || element is KtClassBody || element is KtClassOrObject
  }

  override fun getPresentation(context: ActionContext, element: KtClass): Presentation? {
    if (injectionAnnotationTargets(element).isEmpty()) return null
    return Presentation.of(familyName)
  }

  override fun generatePreview(context: ActionContext, element: KtClass): IntentionPreviewInfo {
    // The platform previews the first picker entry by default. A constructor choice is still
    // needed.
    if (injectionAnnotationTargets(element).size > 1) {
      return IntentionPreviewInfo.Html("<p>Choose the constructor Metro should call.</p>")
    }
    return super.generatePreview(context, element)
  }

  override fun perform(context: ActionContext, element: KtClass): ModCommand {
    val targets = injectionAnnotationTargets(element)
    val target = targets.firstOrNull() ?: return ModCommand.nop()
    if (targets.size == 1) return addInject(context, target)
    return ModCommand.chooseAction(
      "Choose constructor to inject",
      targets.map { InjectConstructorAction(it as KtConstructor<*>) },
    )
  }
}

/** Each picker entry owns a smart pointer and checks the class again when the user selects it. */
private class InjectConstructorAction(constructor: KtConstructor<*>) :
  PsiBasedModCommandAction<KtConstructor<*>>(constructor) {
  private val label = buildString {
    if (constructor is KtPrimaryConstructor) append("primary ")
    append("constructor")
    append(constructor.valueParameterList?.text.orEmpty())
  }

  override fun getFamilyName(): String = label

  override fun getPresentation(
    context: ActionContext,
    element: KtConstructor<*>,
  ): Presentation? {
    if (!isCurrentTarget(element)) return null
    return Presentation.of(label)
  }

  override fun perform(context: ActionContext, element: KtConstructor<*>): ModCommand {
    if (!isCurrentTarget(element)) return ModCommand.nop()
    return addInject(context, element)
  }

  private fun isCurrentTarget(constructor: KtConstructor<*>): Boolean {
    val klass = constructor.containingClassOrObject as? KtClass ?: return false
    return injectionAnnotationTargets(klass).any { it == constructor }
  }
}

private fun addInject(context: ActionContext, owner: KtModifierListOwner): ModCommand {
  return ModCommand.psiUpdate(context) { updater ->
    addMetroAnnotation(owner, "@${MetroClassIds.inject.asFqNameString()}", updater)
  }
}
