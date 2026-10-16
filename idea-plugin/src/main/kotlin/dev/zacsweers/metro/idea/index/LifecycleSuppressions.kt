// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotated
import org.jetbrains.kotlin.analysis.api.annotations.KaAnnotationValue
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtFile

/** Captures Kotlin warning suppressions before the graph is validated outside Analysis API. */
internal fun KaSession.lifecycleSuppressions(source: KtElement): Set<String> {
  val suppress = ClassId.fromString("kotlin/Suppress")
  val names = hashSetOf<String>()
  fun collect(value: KaAnnotationValue) {
    when (value) {
      is KaAnnotationValue.ArrayValue -> value.values.forEach(::collect)
      is KaAnnotationValue.ConstantValue -> (value.value.value as? String)?.let(names::add)
      else -> Unit
    }
  }
  var element: PsiElement? = source
  while (element != null) {
    val annotated =
      when (element) {
        is KtDeclaration -> element.symbol as? KaAnnotated
        is KtFile -> element.symbol
        else -> null
      }
    for (annotation in annotated?.annotations.orEmpty()) {
      if (annotation.classId == suppress) {
        annotation.arguments.forEach { collect(it.expression) }
      }
    }
    element = element.parent
  }
  return names
}
