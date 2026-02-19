// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.ir.GraphToProcess
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.allScopes
import dev.zacsweers.metro.compiler.ir.annotationsIn
import dev.zacsweers.metro.compiler.ir.isCompanionObject
import dev.zacsweers.metro.compiler.ir.nestedClassOrNull
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.tracing.TraceScope
import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.ScopeWithIr
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.isLocal
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.visitors.IrTransformer

/**
 * An [IrTransformer] that runs all of Metro's core transformers _before_ Graph validation. This
 * covers
 */
internal class CoreTransformers(
  private val context: IrMetroContext,
  traceScope: TraceScope,
  private val data: MutableMetroGraphData,
  private val contributionTransformer: ContributionTransformer,
  private val membersInjectorTransformer: MembersInjectorTransformer,
  private val injectedClassTransformer: InjectedClassTransformer,
  private val assistedFactoryTransformer: AssistedFactoryTransformer,
  private val bindingContainerTransformer: BindingContainerTransformer,
  private val contributionHintIrTransformer: Lazy<ContributionHintIrTransformer>,
  private val createGraphTransformer: CreateGraphTransformer,
) :
  IrElementTransformerVoidWithContext(),
  TransformerContextAccess,
  IrMetroContext by context,
  TraceScope by traceScope {

  override val currentFileAccess: IrFile
    get() = currentFile

  override val currentScriptAccess: ScopeWithIr?
    get() = currentScript

  override val currentClassAccess: ScopeWithIr?
    get() = currentClass

  override val currentFunctionAccess: ScopeWithIr?
    get() = currentFunction

  override val currentPropertyAccess: ScopeWithIr?
    get() = currentProperty

  override val currentAnonymousInitializerAccess: ScopeWithIr?
    get() = currentAnonymousInitializer

  override val currentValueParameterAccess: ScopeWithIr?
    get() = currentValueParameter

  override val currentScopeAccess: ScopeWithIr?
    get() = currentScope

  override val parentScopeAccess: ScopeWithIr?
    get() = parentScope

  override val allScopesAccess: MutableList<ScopeWithIr>
    get() = allScopes

  override val currentDeclarationParentAccess: IrDeclarationParent?
    get() = currentDeclarationParent

  override fun visitCall(expression: IrCall): IrExpression {
    return createGraphTransformer.visitCall(expression)
      ?: AsContributionTransformer.visitCall(expression, metroContext)
      // Optimization: skip intermediate visit methods (visitFunctionAccessExpression, etc.)
      // since we don't override them. Call visitExpression directly to save stack frames.
      ?: super.visitExpression(expression)
  }

  override fun visitSimpleFunction(declaration: IrSimpleFunction): IrStatement {
    if (options.generateContributionHintsInFir) {
      contributionHintIrTransformer.value.visitFunction(declaration)
    }
    return super.visitSimpleFunction(declaration)
  }

  override fun visitClassNew(declaration: IrClass): IrStatement {
    visitClassInner(declaration)
    return super.visitClassNew(declaration)
  }

  private fun visitClassInner(declaration: IrClass) {
    val shouldNotProcess =
      declaration.isLocal ||
        declaration.kind == ClassKind.ENUM_CLASS ||
        declaration.kind == ClassKind.ENUM_ENTRY

    if (shouldNotProcess) {
      return
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
    val memberTransformed = membersInjectorTransformer.visitClass(declaration)
    val injectTransformed = injectedClassTransformer.visitClass(declaration)
    // Need to always run member and class inject both
    if (memberTransformed || injectTransformed) {
      return
    }
    if (assistedFactoryTransformer.visitClass(declaration)) return

    if (!declaration.isCompanionObject) {
      // Companion objects are only processed in the context of their parent classes
      bindingContainerTransformer.findContainer(declaration)?.let {
        if (!it.isGraph) {
          return
        }
      }
    }

    val dependencyGraphAnno =
      declaration.annotationsIn(metroSymbols.dependencyGraphAnnotations).singleOrNull() ?: return

    val graphImpl =
      if (declaration.origin == Origins.GeneratedGraphExtension) {
        // If it's a contributed graph, we process that directly while processing the parent. Do
        // nothing
        return
      } else {
        declaration.nestedClassOrNull(Origins.GraphImplClassDeclaration)
          ?: reportCompilerBug(
            "Expected generated dependency graph for ${declaration.classIdOrFail}"
          )
      }

    data.graphs +=
      GraphToProcess(declaration, dependencyGraphAnno, graphImpl, dependencyGraphAnno.allScopes())

    return
  }
}
