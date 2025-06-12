// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.ir.IrAnnotation
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.annotationsAnnotatedWithAny
import dev.zacsweers.metro.compiler.ir.regularParameters
import dev.zacsweers.metro.compiler.mapNotNullToSet
import org.jetbrains.kotlin.ir.types.classOrFail

internal class IrScopedInjectClassData(private val metroContext: IrMetroContext) {
  private val scopeToClasses = mutableMapOf<IrAnnotation, MutableSet<IrTypeKey>>()
  private val externalScopeToClasses = mutableMapOf<IrAnnotation, Set<IrTypeKey>>()

  fun addContribution(scope: IrAnnotation, contribution: IrTypeKey) {
    scopeToClasses.getOrPut(scope, ::mutableSetOf).add(contribution)
  }

  operator fun get(scope: IrAnnotation): Set<IrTypeKey> = buildSet {
    scopeToClasses[scope]?.let(::addAll)
    addAll(findExternalContributions(scope))
  }

  private fun findExternalContributions(scope: IrAnnotation): Set<IrTypeKey> {
    return externalScopeToClasses.getOrPut(scope) {
      val unfilteredScopedInjectClasses =
        metroContext.pluginContext
          .referenceFunctions(Symbols.CallableIds.scopedInjectClassHint(scope))
          .map { hintFunction ->
            hintFunction.owner.regularParameters.single().type.classOrFail.owner
          }

      return unfilteredScopedInjectClasses.mapNotNullToSet { clazz ->
        // TODO isn't this implicit?
        val classScopes =
          clazz.annotationsAnnotatedWithAny(metroContext.symbols.classIds.scopeAnnotations).map {
            IrAnnotation(it)
          }
        if (scope in classScopes) {
          with(metroContext) { IrTypeKey(clazz) }
        } else {
          null
        }
      }
    }
  }
}
