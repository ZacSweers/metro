// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import java.util.Optional
import kotlin.jvm.optionals.getOrNull
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
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
  private val defaultBindingLookup: (IrDeclarationWithName, IrClass) -> IrType?,
) {

  private val implicitBoundTypeCache = mutableMapOf<ClassId, Optional<IrType>>()

  /**
   * Resolves the bound type for [contributingClass] given its contributing [annotation].
   *
   * Handles explicit binding parameters, implicit single supertypes, and `@DefaultBinding`
   * fallback. Returns null if no bound type could be resolved.
   *
   * @param contributingClass The class annotated with a contributing annotation.
   * @param annotation The contributing annotation (e.g., `@ContributesBinding`).
   */
  fun resolveBoundType(
    contributingClass: IrClass,
    annotation: IrConstructorCall,
  ): BoundTypeResult? {
    val (explicitBindingType, ignoreQualifier) =
      with(pluginContext) { annotation.bindingTypeOrNull() }

    val boundType =
      explicitBindingType ?: resolveImplicitBoundType(contributingClass) ?: return null
    return BoundTypeResult(
      type = boundType,
      explicitBindingType = explicitBindingType,
      ignoreQualifier = ignoreQualifier,
    )
  }

  /**
   * Resolves the bound type for [contributingClass] with an already-resolved [explicitBindingType]
   * (e.g., from FIR annotation processing). Falls back to implicit resolution if
   * [explicitBindingType] is null.
   */
  fun resolveBoundType(contributingClass: IrClass, explicitBindingType: IrType?): IrType? {
    return explicitBindingType ?: resolveImplicitBoundType(contributingClass)
  }

  private fun resolveImplicitBoundType(clazz: IrClass): IrType? {
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
        // Check @DefaultBinding first — it takes priority over implicit single-supertype
        // resolution and establishes IC links. Fall back to single supertype if no default found.
        val result =
          resolveDefaultBinding(clazz, supertypesExcludingAny)
            ?: supertypesExcludingAny.keys.singleOrNull()
        Optional.ofNullable(result)
      }
      .getOrNull()
  }

  /** Finds the first supertype with a `@DefaultBinding`. Ambiguity is checked in FIR. */
  private fun resolveDefaultBinding(
    caller: IrDeclarationWithName,
    supertypes: Map<IrType, IrClass>,
  ): IrType? {
    for ((_, supertypeClass) in supertypes) {
      val bindingType = defaultBindingLookup(caller, supertypeClass) ?: continue
      return bindingType
    }
    return null
  }

  /**
   * @property type The resolved bound type.
   * @property explicitBindingType The explicit binding type from the annotation's `binding`
   *   parameter, or null if the type was implicitly resolved.
   * @property ignoreQualifier Whether the qualifier should be ignored (Anvil interop).
   */
  data class BoundTypeResult(
    val type: IrType,
    val explicitBindingType: IrType?,
    val ignoreQualifier: Boolean,
  )
}
