// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.ir.cache.IrCache
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrTypeParametersContainer
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.classifierOrFail
import org.jetbrains.kotlin.ir.types.impl.buildSimpleType
import org.jetbrains.kotlin.ir.types.impl.makeTypeProjection
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.TypeRemapper
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.name.ClassId

private const val CACHE_TYPE_REMAPPERS = "type-remappers"
/** Gets or computes a cached [TypeRemapper] for the given class and subtype. */
internal val IrMetroContext.typeRemapperCache: IrCache<IrType, TypeRemapper, IrClass>
  get() {
    return getOrCreateIrCache(CACHE_TYPE_REMAPPERS) { factory ->
      factory.createCache { type, targetClass ->
        // Build deep substitution map
        val substitutionMap = buildDeepSubstitutionMap(targetClass, type)
        if (substitutionMap.isEmpty()) {
          NOOP_TYPE_REMAPPER
        } else {
          DeepTypeSubstitutor(substitutionMap)
        }
      }
    }
  }

context(context: IrMetroContext)
internal fun IrClass.deepRemapperFor(subtype: IrType): TypeRemapper {
  return context.typeRemapperCache.getValue(subtype, this)
}

private fun buildDeepSubstitutionMap(
  targetClass: IrClass,
  concreteType: IrType,
): Map<IrTypeParameterSymbol, IrType> {
  val result = mutableMapOf<IrTypeParameterSymbol, IrType>()

  fun collectSubstitutions(currentClass: IrClass, currentType: IrType) {
    if (currentType !is IrSimpleType) return

    // Add substitutions for current class's type parameters
    currentClass.typeParameters.zip(currentType.arguments).forEach { (param, arg) ->
      if (arg is IrTypeProjection) {
        result[param.symbol] = arg.type
      }
    }

    // Walk up the hierarchy
    currentClass.superTypes.forEach { superType ->
      val superClass = superType.classOrNull?.owner ?: return@forEach

      // Apply current substitutions to the supertype
      val substitutedSuperType = superType.substitute(result)

      // Recursively collect from supertypes
      collectSubstitutions(superClass, substitutedSuperType)
    }
  }

  collectSubstitutions(targetClass, concreteType)
  return result
}

private class DeepTypeSubstitutor(private val substitutionMap: Map<IrTypeParameterSymbol, IrType>) :
  TypeRemapper {
  private val cache = mutableMapOf<IrType, IrType>()

  override fun enterScope(irTypeParametersContainer: IrTypeParametersContainer) {}

  override fun leaveScope() {}

  override fun remapType(type: IrType): IrType {
    return cache.getOrPut(type) {
      when (type) {
        is IrSimpleType -> {
          val classifier = type.classifier
          if (classifier is IrTypeParameterSymbol) {
            substitutionMap[classifier]?.let { remapType(it) } ?: type
          } else {
            val newArgs =
              type.arguments.map { arg ->
                when (arg) {
                  is IrTypeProjection -> makeTypeProjection(remapType(arg.type), arg.variance)
                  else -> arg
                }
              }
            if (newArgs == type.arguments) type else type.buildSimpleType { arguments = newArgs }
          }
        }

        else -> type
      }
    }
  }
}

// Extension to substitute types in an IrType
private fun IrType.substitute(substitutions: Map<IrTypeParameterSymbol, IrType>): IrType {
  if (substitutions.isEmpty()) return this
  val remapper = DeepTypeSubstitutor(substitutions)
  return remapper.remapType(this)
}

private const val CACHE_CLASS_SUPERTYPES = "ir-class-supertypes"
/** Gets or computes a cached set of all transitive supertypes for a given class. */
internal val IrMetroContext.classSupertypesCache: IrCache<IrClass, Set<IrType>, Unit>
  get() {
    return getOrCreateIrCache(CACHE_CLASS_SUPERTYPES) { factory ->
      factory.createCache { irClass, _ ->
        // Eagerly compute all transitive supertypes (excluding self and Any)
        val supertypes = mutableSetOf<IrType>()
        val visitedClasses = mutableSetOf<ClassId>()

        fun collectSupertypes(currentClass: IrClass) {
          for (superType in currentClass.superTypes) {
            // Always exclude Any from the cache
            if (superType == irBuiltIns.anyType) continue
            val clazz = superType.classifierOrFail.owner as IrClass
            if (visitedClasses.add(clazz.classIdOrFail)) {
              supertypes += superType
              val clazzSupertypes = classSupertypesCache.getValue(clazz, Unit)
              supertypes.addAll(clazzSupertypes)
            }
          }
        }

        collectSupertypes(irClass)
        supertypes
      }
    }
  }

private const val CACHE_CLASS_SUPERTYPE_CLASS_IDS = "ir-class-supertype-class-ids"
/** Gets or computes a cached set of all transitive supertype ClassIds for a given class. */
internal val IrMetroContext.classSupertypeClassIdsCache: IrCache<IrClass, Set<ClassId>, Unit>
  get() {
    return getOrCreateIrCache(CACHE_CLASS_SUPERTYPE_CLASS_IDS) { factory ->
      factory.createCache { irClass, _ ->
        // Derive ClassIds from the cached IrType supertypes
        classSupertypesCache.getValue(irClass, Unit).mapNotNullTo(mutableSetOf()) {
          it.rawTypeOrNull()?.classId
        }
      }
    }
  }

/**
 * Returns the set of all supertypes of this [IrClass] (excluding self and Any). Uses the cached
 * supertype computation for efficient repeated access.
 */
context(context: IrMetroContext)
internal fun IrClass.supertypes(): Set<IrType> {
  return context.classSupertypesCache.getValue(this, Unit)
}

/**
 * Returns the set of all supertype ClassIds for this [IrClass] (excluding self and Any). Uses the
 * cached ClassId computation for efficient O(1) membership checks.
 */
context(context: IrMetroContext)
internal fun IrClass.supertypeClassIds(): Set<ClassId> {
  return context.classSupertypeClassIdsCache.getValue(this, Unit)
}

/** Checks if this [IrClass] implements or extends the given [classId]. */
context(context: IrMetroContext)
internal fun IrClass.implements(classId: ClassId): Boolean {
  return this.classId == classId || classId in supertypeClassIds()
}

/** Checks if this [IrClass] implements or extends any of the given [classIds]. */
context(context: IrMetroContext)
internal fun IrClass.implementsAny(classIds: Set<ClassId>): Boolean {
  if (this.classId in classIds) return true
  val supertypes = supertypeClassIds()
  return classIds.any { it in supertypes }
}

/** Checks if this [IrType] implements or extends the given [classId]. */
context(context: IrMetroContext)
internal fun IrType.implements(classId: ClassId): Boolean {
  val rawClass = rawTypeOrNull() ?: return false
  return rawClass.implements(classId)
}

/**
 * Retrieves all supertypes of this [IrClass] as a sequence.
 *
 * @param excludeSelf If true, excludes this class itself from the results (default: true)
 * @param excludeAny If true, excludes kotlin.Any from the results (default: true)
 */
context(context: IrMetroContext)
internal fun IrClass.allSupertypesSequence(
  excludeSelf: Boolean = true,
  excludeAny: Boolean = true,
): Sequence<IrType> {
  // The cache already excludes self and Any, so we only need to add them back if requested
  return sequence {
    if (!excludeSelf) {
      yield(typeWith())
    }
    yieldAll(supertypes())
    if (!excludeAny) {
      yield(context.irBuiltIns.anyType)
    }
  }
}
