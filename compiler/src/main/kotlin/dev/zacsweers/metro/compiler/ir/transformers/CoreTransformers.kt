// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.ir.IrContributionData
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.isCompanionObject
import dev.zacsweers.metro.compiler.tracing.TraceScope
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.isLocal
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.visitors.IrTransformer

internal data class FirstPassData(
  val contributionData: IrContributionData
  // TODO
  //  generated graphs
  //  seen graphs for later processing (including generated)
)

/**
 * An [IrTransformer] that runs all of Metro's core transformers _before_ Graph validation. This
 * covers
 */
internal class CoreTransformers(
  private val context: IrMetroContext,
  traceScope: TraceScope,
  private val contributionTransformer: ContributionTransformer,
  private val membersInjectorTransformer: MembersInjectorTransformer,
  private val injectedClassTransformer: InjectedClassTransformer,
  private val assistedFactoryTransformer: AssistedFactoryTransformer,
  private val bindingContainerTransformer: BindingContainerTransformer,
  private val contributionHintIrTransformer: Lazy<ContributionHintIrTransformer>,
) : IrTransformer<FirstPassData>(), IrMetroContext by context, TraceScope by traceScope {
  override fun visitSimpleFunction(
    declaration: IrSimpleFunction,
    data: FirstPassData,
  ): IrStatement {
    if (options.generateContributionHintsInFir) {
      contributionHintIrTransformer.value.visitFunction(declaration)
    }
    return super.visitSimpleFunction(declaration, data)
  }

  override fun visitClass(declaration: IrClass, data: FirstPassData): IrStatement {
    val shouldNotProcess =
      declaration.isLocal ||
        declaration.kind == ClassKind.ENUM_CLASS ||
        declaration.kind == ClassKind.ENUM_ENTRY

    if (shouldNotProcess) {
      return super.visitClass(declaration, data)
    }

    log("Reading ${declaration.kotlinFqName}")

    contributionTransformer.visitClass(declaration, data.contributionData)

    // TODO need to better divvy these
    // TODO can we eagerly check for known metro types and skip?
    // Native/WASM/JS compilation hint gen can't be done in IR
    // https://youtrack.jetbrains.com/issue/KT-75865
    val generateHints = options.generateContributionHints && !options.generateContributionHintsInFir
    if (generateHints) {
      contributionHintIrTransformer.value.visitClass(declaration)
    }
    membersInjectorTransformer.visitClass(declaration)
    injectedClassTransformer.visitClass(declaration)
    assistedFactoryTransformer.visitClass(declaration)

    if (!declaration.isCompanionObject) {
      // Companion objects are only processed in the context of their parent classes
      @Suppress("RETURN_VALUE_NOT_USED") bindingContainerTransformer.findContainer(declaration)
    }

    return super.visitClass(declaration, data)
  }
}
