// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.symbols

import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.name.ClassId

/**
 * Framework-agnostic API for converting between different `Provider` and `Lazy` types across
 * frameworks.
 *
 * To add a new framework, implement [ProviderFramework] and register it in the [frameworks] list.
 *
 * Supported frameworks:
 * - Metro (`dev.zacsweers.metro.Provider`, canonical representation)
 * - Javax (`javax.inject.Provider`)
 * - Jakarta (`jakarta.inject.Provider`)
 * - Dagger (`dagger.Lazy`, `dagger.internal.Provider`)
 * - Guice (`com.google.inject.Provider`)
 */
internal class ProviderTypeConverter(
  private val metroFramework: MetroProviderFramework,
  private val frameworks: List<ProviderFramework>,
) {
  /**
   * Converts [this] provider expression to match the target contextual type.
   *
   * Automatically determines the conversion path:
   * 1. Identifies the source framework from the provider type.
   * 2. Identifies the target framework from the target type.
   * 3. Routes through Metro's first party intrinsics as the canonical representation if needed.
   */
  context(_: IrMetroContext, _: IrBuilderWithScope)
  internal fun IrExpression.convertTo(
    targetKey: IrContextualTypeKey,
    providerType: IrType = type,
  ): IrExpression {
    val provider = this
    val sourceClassId = providerType.classOrNull?.owner?.classId
    val targetClassId = targetKey.rawType?.classOrNull?.owner?.classId
    val sourceFramework = frameworkFor(sourceClassId)
    val targetFramework = frameworkFor(targetClassId)

    // Fast path: same framework, no conversion needed
    if (sourceFramework == targetFramework) {
      return with(sourceFramework) {
        provider.handleSameFramework(targetKey, sourceClassId, targetClassId)
      }
    }

    // Convert through Metro as canonical representation
    // Source -> Metro -> Target
    val metroProvider =
      with(sourceFramework) { provider.toMetroProvider(providerType, sourceClassId) }

    return with(targetFramework) { fromMetroProvider(metroProvider, targetKey, targetClassId) }
  }

  // TODO this currently only checks raw class IDs and not supertypes
  private fun frameworkFor(classId: ClassId?): ProviderFramework {
    classId?.let {
      return frameworks.firstOrNull { it.isApplicable(classId) }
        ?: metroFramework // Default to Metro
    }
    return metroFramework
  }
}
