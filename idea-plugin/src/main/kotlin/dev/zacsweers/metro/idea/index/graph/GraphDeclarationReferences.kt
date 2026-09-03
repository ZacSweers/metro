// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index.graph

import dev.zacsweers.metro.idea.model.GraphReference
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

/** Retains the declaration file so equal class names from separate modules stay distinct. */
internal fun KaClassType.graphReference(): GraphReference {
  return GraphReference(classId, symbol.psi?.containingFile?.virtualFile)
}

/** Companion members belong to the enclosing container class, mirroring the compiler. */
internal fun KtClassOrObject.containerClassId(): ClassId? {
  return if (this is KtObjectDeclaration && isCompanion()) {
    containingClassOrObject?.getClassId() ?: getClassId()
  } else {
    getClassId()
  }
}
