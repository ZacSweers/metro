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

/**
 * Framework-agnostic API for converting between different `Provider` and `Lazy` types across
 * frameworks.
 *
 * To add a new framework (e.g., Guice, Functions), implement [ProviderFramework] and register it in
 * the [frameworks] list.
 *
 * Supported frameworks:
 * - Metro (`dev.zacsweers.metro.Provider`, canonical representation)
 * - Dagger `(dagger.Lazy`, `dagger.internal.Provider`, `javax.inject.Provider`,
 *   `jakarta.inject.Provider`)
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
  internal fun IrExpression.convertTo(targetKey: IrContextualTypeKey): IrExpression {
    val provider = this
    val sourceFramework = frameworkFor(provider.type)
    val targetFramework = frameworkFor(targetKey.rawType)

    // Fast path: same framework, no conversion needed
    if (sourceFramework == targetFramework) {
      return with(sourceFramework) { provider.handleSameFramework(targetKey) }
    }

    // Convert through Metro as canonical representation
    // Source -> Metro -> Target
    val metroProvider = with(sourceFramework) { provider.toMetroProvider(provider.type) }

    return with(targetFramework) { metroProvider.fromMetroProvider(targetKey) }
  }

  // TODO this currently only checks raw class IDs and not supertypes
  private fun frameworkFor(type: IrType?): ProviderFramework {
    type?.classOrNull?.owner?.classId?.let { classId ->
      return frameworks.firstOrNull { it.isApplicable(classId) }
        ?: metroFramework // Default to Metro
    }
    return metroFramework
  }
}
