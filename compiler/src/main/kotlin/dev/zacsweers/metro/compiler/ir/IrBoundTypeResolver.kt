// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import java.util.Optional
import kotlin.jvm.optionals.getOrNull
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.StandardClassIds

/**
 * Resolves the bound type for a contributing class annotated with `@ContributesBinding`,
 * `@ContributesIntoSet`, or `@ContributesIntoMap`.
 *
 * Consolidates the resolution logic:
 * 1. Explicit `binding` parameter on the annotation
 * 2. Single supertype (excluding `Any`)
 * 3. `@DefaultBinding` on a supertype (via [defaultBindingLookup])
 */
internal class IrBoundTypeResolver(
  private val pluginContext: IrPluginContext,
  private val defaultBindingLookup: ((IrClass) -> IrType?)? = null,
) {

  private val implicitBoundTypeCache = mutableMapOf<ClassId, Optional<IrType>>()

  /**
   * Resolves the bound type for [contributingClass] given its contributing [annotation].
   *
   * Returns the resolved type and whether the qualifier should be ignored (Anvil interop), or null
   * if no bound type could be resolved.
   */
  fun resolveBoundType(
    contributingClass: IrClass,
    annotation: IrConstructorCall,
  ): BoundTypeResult? {
    val (explicitBindingType, ignoreQualifier) =
      with(pluginContext) { annotation.bindingTypeOrNull() }

    val boundType =
      explicitBindingType ?: resolveImplicitBoundType(contributingClass) ?: return null
    return BoundTypeResult(boundType, ignoreQualifier)
  }

  /**
   * Resolves the implicit bound type for [clazz] by checking:
   * 1. Single supertype (excluding `Any`)
   * 2. `@DefaultBinding` on a supertype
   */
  fun resolveImplicitBoundType(clazz: IrClass): IrType? {
    return implicitBoundTypeCache
      .getOrPut(clazz.classIdOrFail) {
        val supertypesExcludingAny =
          clazz.superTypes
            .mapNotNull {
              val rawType = it.rawTypeOrNull()
              if (rawType == null || rawType.classId == StandardClassIds.Any) {
                null
              } else {
                it to rawType
              }
            }
            .associate { it }
        val result =
          supertypesExcludingAny.keys.singleOrNull()
            ?: resolveDefaultBinding(supertypesExcludingAny)
        Optional.ofNullable(result)
      }
      .getOrNull()
  }

  /** Finds the first supertype with a `@DefaultBinding`. Ambiguity is checked in FIR. */
  private fun resolveDefaultBinding(supertypes: Map<IrType, IrClass>): IrType? {
    val lookup = defaultBindingLookup ?: return null
    for ((_, supertypeClass) in supertypes) {
      val bindingType = lookup(supertypeClass) ?: continue
      return bindingType
    }
    return null
  }

  data class BoundTypeResult(val type: IrType, val ignoreQualifier: Boolean)
}
