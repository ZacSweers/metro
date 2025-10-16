// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import org.jetbrains.kotlin.backend.common.ScopeWithIr
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrFile

internal interface TransformerContextAccess {
  val currentFileAccess: IrFile
  val currentScriptAccess: ScopeWithIr?
  val currentClassAccess: ScopeWithIr?
  val currentFunctionAccess: ScopeWithIr?
  val currentPropertyAccess: ScopeWithIr?
  val currentAnonymousInitializerAccess: ScopeWithIr?
  val currentValueParameterAccess: ScopeWithIr?
  val currentScopeAccess: ScopeWithIr?
  val parentScopeAccess: ScopeWithIr?
  val allScopesAccess: MutableList<ScopeWithIr>
  val currentDeclarationParentAccess: IrDeclarationParent?
}
