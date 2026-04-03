// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName

/**
 * A [classNameTransformer][betterDumpKotlinLike] that renders nested class names relative to the
 * current scope.
 *
 * If [declaration] is nested inside the same enclosing class as [context], only the simple name is
 * returned (e.g., just `MetroFactory` instead of `NotReused.MetroFactory` when we're already inside
 * `NotReused`). Otherwise, returns the full chain from the outermost enclosing class.
 *
 * Companion objects render as `ParentClass/* companion */` instead of `ParentClass.Companion`.
 */
internal fun nestedClassNameRenderer(
  context: IrDeclaration?,
  declaration: IrDeclarationWithName,
): String {
  if (declaration !is IrClass) return declaration.name.asString()
  if (declaration.isCompanion && declaration.parent is IrClass) {
    val parent = declaration.parent as IrClass
    val parentName = nestedClassNameRenderer(context, parent)
    return "$parentName/* companion */"
  }

  // Build the full chain of enclosing class names (innermost first)
  val names = mutableListOf(declaration.name.asString())
  var current: IrDeclarationParent = declaration.parent
  while (current is IrClass) {
    names.add(current.name.asString())
    current = current.parent
  }
  // names is now [declaration, parent, grandparent, ...] innermost-first

  // Find the nearest enclosing class of the context
  val contextClass = context?.nearestEnclosingClass()

  // If the declaration's nearest enclosing class matches the context's enclosing class,
  // just use the simple name
  if (contextClass != null && names.size > 1) {
    val declarationParent = declaration.parent
    if (declarationParent is IrClass && isInScope(contextClass, declarationParent)) {
      return declaration.name.asString()
    }
  }

  return names.asReversed().joinToString(".")
}

/** Walk up from [this] to find its nearest enclosing [IrClass], or itself if it is one. */
private fun IrDeclaration.nearestEnclosingClass(): IrClass? {
  if (this is IrClass) return this
  val p = parent
  return p as? IrClass
}

/** Returns true if [target] is [scope] or an ancestor of [scope]. */
private fun isInScope(scope: IrClass, target: IrClass): Boolean {
  var current: IrDeclarationParent = scope
  while (current is IrClass) {
    if (current == target) return true
    current = current.parent
  }
  return false
}
