// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.mapNotNullToSet
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrFail
import org.jetbrains.kotlin.ir.util.defaultType

internal class IrScopedInjectClassData(private val metroContext: IrMetroContext) {
  private val scopeToClasses = mutableMapOf<IrAnnotation, MutableSet<IrType>>()
  private val externalScopeToClasses = mutableMapOf<IrAnnotation, Set<IrType>>()

  fun addContribution(scope: IrAnnotation, contribution: IrType) {
    scopeToClasses.getOrPut(scope) { mutableSetOf() }.add(contribution)
  }

  operator fun get(scope: IrAnnotation): Set<IrType> = buildSet {
    scopeToClasses[scope]?.let(::addAll)
    addAll(findExternalContributions(scope))
  }

  private fun findExternalContributions(scope: IrAnnotation): Set<IrType> {
    return externalScopeToClasses.getOrPut(scope) {
      val unfilteredScopedInjectClasses =
        metroContext.pluginContext
          .referenceFunctions(Symbols.CallableIds.scopedInjectClassHint(scope))
          .map { hintFunction ->
            hintFunction.owner.regularParameters.single().type.classOrFail.owner
          }

      return unfilteredScopedInjectClasses.mapNotNullToSet { clazz ->
        val classScopes =
          clazz.annotationsAnnotatedWithAny(metroContext.symbols.classIds.scopeAnnotations).map {
            IrAnnotation(it)
          }
        if (scope in classScopes) {
          clazz.defaultType
        } else {
          null
        }
      }
    }
  }
}
