// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.intentions

import com.intellij.modcommand.ModPsiUpdater
import org.jetbrains.kotlin.idea.base.codeInsight.ShortenReferencesFacility
import org.jetbrains.kotlin.psi.KtAnnotationEntry
import org.jetbrains.kotlin.psi.KtModifierListOwner
import org.jetbrains.kotlin.psi.KtPsiFactory

/**
 * Adds fully qualified annotation text to an updater-owned copy and shortens its references.
 * Callers may pass the original declaration or reuse its writable copy for successive annotations.
 */
internal fun addMetroAnnotation(
  owner: KtModifierListOwner,
  annotationText: String,
  updater: ModPsiUpdater,
): KtAnnotationEntry {
  val writable = if (owner.isPhysical) updater.getWritable(owner) else owner
  val annotation =
    writable.addAnnotationEntry(KtPsiFactory(owner.project).createAnnotationEntry(annotationText))
  ShortenReferencesFacility.getInstance().shorten(annotation)
  return annotation
}
