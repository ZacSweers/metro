// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.memoize
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.util.TypeRemapper
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId

internal class BindingContainer(
  val isGraph: Boolean,
  val canBeManaged: Boolean,
  val ir: IrClass,
  val includes: Set<ClassId>,
  /** Mapping of provider factories by their [CallableId]. */
  val providerFactories: Map<CallableId, ProviderFactory>,
  val bindsMirror: BindsMirror?,
) {
  val typeKey by memoize { IrTypeKey(ir.defaultType) }

  private val classId = ir.classIdOrFail

  fun isEmpty() =
    includes.isEmpty() && providerFactories.isEmpty() && (bindsMirror?.isEmpty() ?: true)

  /**
   * Creates a copy of this container with type-substituted provider factories. Used when a generic
   * binding container is included with concrete type arguments (e.g., `@Includes
   * TypedBindings<String>`).
   */
  fun withTypeSubstitution(remapper: TypeRemapper): BindingContainer {
    val remappedFactories = providerFactories.mapValues { (_, factory) ->
      when (factory) {
        is ProviderFactory.Metro -> factory.withRemappedTypes(remapper)
        is ProviderFactory.Dagger -> factory.withRemappedTypes(remapper)
      }
    }
    val remappedBindsMirror = bindsMirror?.let { mirror ->
      BindsMirror(
        ir = mirror.ir,
        bindsCallables = mirror.bindsCallables.mapTo(mutableSetOf()) { it.remapTypes(remapper) },
        multibindsCallables =
          mirror.multibindsCallables.mapTo(mutableSetOf()) { it.remapTypes(remapper) },
        optionalKeys = mirror.optionalKeys.mapTo(mutableSetOf()) { it.remapTypes(remapper) },
      )
    }
    return BindingContainer(
      isGraph = isGraph,
      canBeManaged = canBeManaged,
      ir = ir,
      includes = includes,
      providerFactories = remappedFactories,
      bindsMirror = remappedBindsMirror,
    )
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as BindingContainer

    return classId == other.classId
  }

  override fun hashCode(): Int = classId.hashCode()

  override fun toString(): String = classId.asString()
}
