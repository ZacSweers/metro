// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.util.Optional
import kotlin.jvm.optionals.getOrNull
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.name.StandardClassIds

internal interface DefaultBindingLookup {
  fun lookupBinding(declaration: IrDeclarationWithName, clazz: IrClass): IrTypeKey?
}

/**
 * Resolves the bound type for a contributing class annotated with `@ContributesBinding`,
 * `@ContributesIntoSet`, or `@ContributesIntoMap`.
 *
 * Consolidates the resolution logic:
 * 1. Explicit `binding` parameter on the annotation
 * 2. Single supertype (excluding `Any`)
 * 3. `@DefaultBinding` on a supertype (via [defaultBindingLookup])
 */
@Inject
@SingleIn(IrScope::class)
internal class IrBoundTypeResolver(
  private val metroContext: IrMetroContext,
  private val defaultBindingLookup: DefaultBindingLookup,
) {

  private val implicitBoundTypeCache = mutableMapOf<IrTypeKey, Optional<IrTypeKey>>()

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
    val ignoreQualifier = annotation.anvilIgnoreQualifier()
    val explicitBindingType =
      with(metroContext) { annotation.bindingTypeOrNull(contributingClass, ignoreQualifier) }

    val boundType =
      explicitBindingType
        ?: resolveImplicitBoundType(contributingClass, ignoreQualifier)
        ?: return null
    return BoundTypeResult(typeKey = boundType, explicitBindingType = explicitBindingType)
  }

  /**
   * Resolves the bound type for [contributingClass] with an already-resolved [explicitBindingType]
   * (e.g., from FIR annotation processing). Falls back to implicit resolution if
   * [explicitBindingType] is null.
   */
  fun resolveBoundType(
    contributingClass: IrClass,
    explicitBindingType: IrTypeKey?,
    ignoreQualifier: Boolean,
  ): IrTypeKey? {
    return explicitBindingType ?: resolveImplicitBoundType(contributingClass, ignoreQualifier)
  }

  private fun resolveImplicitBoundType(clazz: IrClass, ignoreQualifier: Boolean): IrTypeKey? {
    val cacheKey =
      IrTypeKey(
        type = clazz.defaultType,
        qualifier =
          if (ignoreQualifier) {
            null
          } else {
            with(metroContext) { clazz.qualifierAnnotation() }
          },
      )
    return implicitBoundTypeCache
      .getOrPut(cacheKey) { // TODO iter once
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
            ?: supertypesExcludingAny.keys.singleOrNull()?.let {
              IrTypeKey(
                it,
                if (ignoreQualifier) {
                  null
                } else {
                  with(metroContext) { clazz.qualifierAnnotation() }
                },
              )
            }
        Optional.ofNullable(result)
      }
      .getOrNull()
  }

  /** Finds the first supertype with a `@DefaultBinding`. Ambiguity is checked in FIR. */
  private fun resolveDefaultBinding(
    caller: IrDeclarationWithName,
    supertypes: Map<IrType, IrClass>,
  ): IrTypeKey? {
    for ((_, supertypeClass) in supertypes) {
      val bindingTypeKey = defaultBindingLookup.lookupBinding(caller, supertypeClass) ?: continue
      return bindingTypeKey
    }
    return null
  }

  /**
   * @property typeKey The resolved bound type key.
   * @property explicitBindingType The explicit binding type from the annotation's `binding`
   *   parameter, or null if the type was implicitly resolved.
   */
  data class BoundTypeResult(val typeKey: IrTypeKey, val explicitBindingType: IrTypeKey?)
}
