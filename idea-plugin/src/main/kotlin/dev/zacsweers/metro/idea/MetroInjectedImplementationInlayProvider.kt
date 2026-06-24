// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.HintMarginPadding
import com.intellij.codeInsight.hints.declarative.InlayActionData
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.PsiPointerInlayActionNavigationHandler
import com.intellij.codeInsight.hints.declarative.PsiPointerInlayActionPayload
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import dev.zacsweers.metro.idea.model.BindingKind
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty

/**
 * Shows the statically-resolved implementation next to injection sites whose declared type is an
 * interface or abstract class:
 * ```kotlin
 * @Inject class CheckoutFlow(
 *   private val api: HttpApi,        // <- RealHttpApi
 *   private val analytics: Set<Analytics> // <- 3 multibound
 * )
 * ```
 *
 * Single resolved implementations navigate to the binding on click; multibindings show the
 * contribution count and navigate to the aggregate's contributors via the consumer gutter icon.
 */
class MetroInjectedImplementationInlayProvider : InlayHintsProvider {

  override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? {
    val ktFile = file as? KtFile ?: return null
    if (!MetroSettings.getInstance(ktFile.project).state.enableBindingResolution) return null
    if (!ktFile.metroIdeState().options.enabled) return null
    return Collector
  }

  private object Collector : SharedBypassCollector {
    override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
      if (element !is KtParameter && element !is KtProperty && element !is KtNamedFunction) return
      element as KtElement
      val index = MetroResolutionService.getInstance(element.project).index(element)
      // Only implicitly assisted parameters get the inlay; explicit @Assisted already reads as
      // assisted in source.
      if (
        MetroSettings.getInstance(element.project).state.assistedParameterInlays &&
          index.assistedSiteAt(element)?.isImplicit == true
      ) {
        val nameOffset =
          (element as? KtParameter)?.nameIdentifier?.textRange?.startOffset
            ?: element.textRange.startOffset
        sink.addPresentation(
          InlineInlayPosition(nameOffset, relatedToPrevious = false),
          hintFormat =
            HintFormat.default.withHorizontalMargin(HintMarginPadding.MarginAndSmallerPadding),
        ) {
          text("assisted")
        }
        return
      }
      val consumer = index.consumerEntryAt(element) ?: return
      val bindings = index.resolveConsumer(consumer).effective
      if (bindings.isEmpty()) return

      val contributions = bindings.count { it.kind == BindingKind.MULTIBINDING_CONTRIBUTION }
      val hint: String
      val target: PsiPointerInlayActionPayload?
      if (contributions > 0) {
        val noun =
          when {
            consumer.typeClassId == MAP_CLASS_ID && contributions == 1 -> "entry"
            consumer.typeClassId == MAP_CLASS_ID -> "entries"
            contributions == 1 -> "element"
            else -> "elements"
          }
        hint = "$contributions $noun"
        target = bindings.singleOrNull()?.let { PsiPointerInlayActionPayload(it.pointer) }
      } else {
        if (!consumer.isAbstractType) return
        val binding = bindings.singleOrNull() ?: return
        val implementationName = binding.implementationName ?: return
        // An injected class consumed as its own type isn't worth an inlay.
        if (implementationName == consumer.key.render(short = true, includeQualifier = false)) {
          return
        }
        hint = implementationName
        target = PsiPointerInlayActionPayload(binding.pointer)
      }

      sink.addPresentation(
        InlineInlayPosition(element.textRange.endOffset, relatedToPrevious = true),
        hintFormat =
          HintFormat.default.withHorizontalMargin(HintMarginPadding.MarginAndSmallerPadding),
      ) {
        // The declarative API caps the real (outside-the-chip) margin at 2px, so approximate the
        // separation from the parameter text with a leading space inside the chip.
        text(
          " $hint",
          target?.let {
            InlayActionData(it, PsiPointerInlayActionNavigationHandler.HANDLER_ID)
          },
        )
      }
    }
  }
}
