// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.graph

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.util.Computable
import com.intellij.psi.SmartPsiElementPointer
import dev.zacsweers.metro.idea.index.isSubtypeOfBound
import dev.zacsweers.metro.idea.model.BoundSubtypeCheck
import dev.zacsweers.metro.idea.model.KaTypeSnapshot
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.psi.KtElement

/**
 * Settles generic provider bounds in an analysis session anchored at [anchor], a graph declaration.
 * Types resolve as that graph's module sees them.
 *
 * Each call opens its own short session, and nothing from it is kept. A graph seal runs outside a
 * read action in the background, so the call takes a read action for its session. It returns null
 * on the EDT, in dumb mode, and when the graph declaration is gone. The caller then keeps the
 * snapshot check's answer.
 */
internal class AnalysisBoundSubtypeCheck(
  private val anchor: SmartPsiElementPointer<out KtElement>,
) : BoundSubtypeCheck {
  override fun isSubtype(argument: KaTypeSnapshot, bound: KaTypeSnapshot): Boolean? {
    val application = ApplicationManager.getApplication()
    // Analysis can be slow, so it never runs on the EDT.
    if (application.isDispatchThread) {
      return null
    }
    ProgressManager.checkCanceled()
    if (application.isReadAccessAllowed) {
      return checkInReadAction(argument, bound)
    }
    return application.runReadAction(Computable { checkInReadAction(argument, bound) })
  }

  private fun checkInReadAction(argument: KaTypeSnapshot, bound: KaTypeSnapshot): Boolean? {
    val element = anchor.element ?: return null
    if (DumbService.isDumb(element.project)) {
      return null
    }
    return analyze(element) { isSubtypeOfBound(argument, bound) }
  }
}
