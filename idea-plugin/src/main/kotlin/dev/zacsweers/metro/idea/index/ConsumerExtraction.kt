// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index

import com.intellij.psi.SmartPsiElementPointer
import dev.zacsweers.metro.compiler.MetroOptions
import dev.zacsweers.metro.idea.model.ConsumerEntry
import dev.zacsweers.metro.idea.model.GraphDeclarationId
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.symbols.KaCallableSymbol
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtElement

/** Captures a dependency's key and ownership while its symbol and substituted type are valid. */
internal fun KaSession.dependencyConsumer(
  pointer: SmartPsiElementPointer<out KtElement>,
  symbol: KaCallableSymbol,
  type: KaType,
  options: MetroOptions,
  originClassId: ClassId? = null,
  contributionScopes: Set<ClassId> = emptySet(),
  containerId: ClassId? = null,
  memberOwnerClassId: ClassId? = null,
  graphId: GraphDeclarationId? = null,
): ConsumerEntry {
  val site = consumedSite(type, symbol, options)
  return ConsumerEntry(
    pointer,
    site.contextKey,
    site.isAbstractType,
    site.multibindingId,
    site.typeClassId,
    originClassId = originClassId,
    contributionScopes = contributionScopes,
    containerId = containerId,
    graphId = graphId,
    memberOwnerClassId = memberOwnerClassId,
    isOptional = symbol.isOptionalConsumer(options),
  )
}
