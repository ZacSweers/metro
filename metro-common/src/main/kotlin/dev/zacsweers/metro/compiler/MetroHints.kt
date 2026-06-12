// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Naming scheme for Metro's generated contribution hint functions: top-level functions in
 * [PACKAGE_NAME], named after the scope class, whose single parameter type is the contributing
 * class.
 */
public object MetroHints {
  public const val PACKAGE_NAME: String = "metro.hints"

  public val packageFqName: FqName = FqName(PACKAGE_NAME)

  /** The hint function name for contributions to [scopeClassId]. */
  public fun hintFunctionName(scopeClassId: ClassId): Name {
    return scopeClassId.joinSimpleNames().shortClassName
  }

  /** The hint function callable id for contributions to [scopeClassId]. */
  public fun hintCallableId(scopeClassId: ClassId): CallableId {
    return CallableId(packageFqName, hintFunctionName(scopeClassId))
  }
}
