// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.usages

import com.intellij.find.findUsages.AbstractFindUsagesDialog
import com.intellij.find.findUsages.FindUsagesHandler
import com.intellij.find.findUsages.FindUsagesHandlerFactory
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor

/** Warms Metro usage relationships before delegating ordinary Kotlin usage discovery. */
class MetroFindUsagesHandlerFactory : FindUsagesHandlerFactory() {
  override fun canFindUsages(element: PsiElement): Boolean {
    return element.metroSourceDeclaration()?.hasPotentialMetroContext() == true
  }

  override fun createFindUsagesHandler(
    element: PsiElement,
    forHighlightUsages: Boolean,
  ): FindUsagesHandler? {
    val delegate = delegateHandler(element, forHighlightUsages) ?: return null
    if (delegate === FindUsagesHandler.NULL_HANDLER || forHighlightUsages) return delegate
    return MetroFindUsagesHandler(element, delegate)
  }

  private fun delegateHandler(
    element: PsiElement,
    forHighlightUsages: Boolean,
  ): FindUsagesHandler? {
    for (factory in EP_NAME.getExtensionList(element.project)) {
      if (factory.javaClass == javaClass || !factory.canFindUsages(element)) continue
      val handler = factory.createFindUsagesHandler(element, forHighlightUsages)
      if (handler != null) return handler
    }
    return null
  }
}

private class MetroFindUsagesHandler(
  element: PsiElement,
  private val delegate: FindUsagesHandler,
) : FindUsagesHandler(element) {
  override fun getFindUsagesDialog(
    isSingleFile: Boolean,
    toShowInNewTab: Boolean,
    mustOpenInNewTab: Boolean,
  ): AbstractFindUsagesDialog {
    return delegate.getFindUsagesDialog(isSingleFile, toShowInNewTab, mustOpenInNewTab)
  }

  override fun getHelpId(): String? = delegate.helpId

  override fun getPrimaryElements(): Array<PsiElement> = delegate.primaryElements

  override fun getSecondaryElements(): Array<PsiElement> = delegate.secondaryElements

  override fun getFindUsagesOptions(): FindUsagesOptions = delegate.findUsagesOptions

  override fun getFindUsagesOptions(dataContext: DataContext?): FindUsagesOptions {
    return delegate.getFindUsagesOptions(dataContext)
  }

  override fun processElementUsages(
    element: PsiElement,
    processor: Processor<in UsageInfo>,
    options: FindUsagesOptions,
  ): Boolean {
    runBlockingCancellable { collectMetroUsages(element, options) }
    return delegate.processElementUsages(element, processor, options)
  }

  override fun processUsagesInText(
    psiElement: PsiElement,
    processor: Processor<in UsageInfo>,
    searchScope: GlobalSearchScope,
  ): Boolean {
    return delegate.processUsagesInText(psiElement, processor, searchScope)
  }

  override fun findReferencesToHighlight(
    target: PsiElement,
    searchScope: SearchScope,
  ): Collection<PsiReference> {
    return delegate.findReferencesToHighlight(target, searchScope)
  }
}
