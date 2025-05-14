// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.capitalizeUS
import dev.zacsweers.metro.compiler.decapitalizeUS
import dev.zacsweers.metro.compiler.exitProcessing
import org.jetbrains.kotlin.backend.jvm.codegen.AnnotationCodegen.Companion.annotationClass
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irBoolean
import org.jetbrains.kotlin.ir.builders.irDelegatingConstructorCall
import org.jetbrains.kotlin.ir.declarations.DelicateIrParameterIndexSetter
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.impl.IrInstanceInitializerCallImpl
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.addFakeOverrides
import org.jetbrains.kotlin.ir.util.copyAnnotationsFrom
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.superClass

internal class IrContributedGraphGenerator(
  context: IrMetroContext,
  private val contributionData: IrContributionData,
) : IrMetroContext by context {

  @OptIn(DelicateIrParameterIndexSetter::class)
  fun generateContributedGraph(
    parentGraph: IrClass,
    sourceFactory: IrClass,
    factoryFunction: IrSimpleFunction,
  ): IrClass {
    val sourceGraph = sourceFactory.parentAsClass
    val contributesGraphExtensionAnno =
      sourceGraph.annotationsIn(symbols.classIds.contributesGraphExtensionAnnotations).firstOrNull()

    // If the parent graph is not extendable, error out here
    val parentIsContributed = parentGraph.origin === Origins.ContributedGraph
    val realParent =
      if (parentIsContributed) {
        parentGraph.superTypes.first().rawType()
      } else {
        parentGraph
      }
    val parentGraphAnno = realParent.annotationsIn(symbols.classIds.graphLikeAnnotations).single()
    val parentIsExtendable =
      parentGraphAnno.getConstBooleanArgumentOrNull(Symbols.Names.isExtendable) ?: false
    if (!parentIsExtendable) {
      with(metroContext) {
        val message = buildString {
          append(
            "Contributed graph extension '${sourceGraph.kotlinFqName}' contributes to parent graph "
          )
          append(
            "'${realParent.kotlinFqName}' (scope '${parentGraphAnno.scopeOrNull()!!.asSingleFqName()}') "
          )
          append("but ${realParent.name} is not extendable.")
          if (!parentIsContributed) {
            appendLine()
            appendLine()
            append("Either mark ${realParent.name} as extendable ")
            append("(`@${parentGraphAnno.annotationClass.name}(isExtendable = true)`) or ")
            append("exclude it from ${realParent.name} ")
            append(
              "(`@${parentGraphAnno.annotationClass.name}(excludes = [${sourceGraph.name}::class])`)"
            )
          }
        }
        sourceGraph.reportError(message)
        exitProcessing()
      }
    }

    val sourceScope =
      contributesGraphExtensionAnno?.scopeClassOrNull()
        ?: error("No scope found for ${sourceGraph.name}: ${sourceGraph.dumpKotlinLike()}")

    // Source is a `@ContributesGraphExtension`-annotated class, we want to generate a header impl
    // class
    val contributedGraph =
      pluginContext.irFactory
        .buildClass {
          name = "$\$Contributed${sourceGraph.name.capitalizeUS()}".asName()
          origin = Origins.ContributedGraph
          kind = ClassKind.CLASS
        }
        .apply {
          parentGraph.addChild(this)
          createThisReceiverParameter()
          // Add a @DependencyGraph(...) annotation
          annotations +=
            pluginContext.buildAnnotation(
              symbol,
              symbols.metroDependencyGraphAnnotationConstructor,
            ) {
              // Copy over the scope annotation
              it.putValueArgument(0, kClassReference(sourceScope.symbol))
              // Pass on if it's extendable
              it.putValueArgument(
                3,
                irBoolean(
                  contributesGraphExtensionAnno.getConstBooleanArgumentOrNull(
                    Symbols.Names.isExtendable
                  ) ?: false
                ),
              )
            }
          superTypes += sourceGraph.defaultType
        }

    contributedGraph
      .addConstructor {
        isPrimary = true
        origin = Origins.Default
        // This will be finalized in DependencyGraphTransformer
        isFakeOverride = true
      }
      .apply {
        // Add the parent type
        val actualParentType =
          if (parentGraph.origin === Origins.ContributedGraph) {
            parentGraph.superTypes.first().type.rawType()
          } else {
            parentGraph
          }
        addValueParameter(
            actualParentType.name.asString().decapitalizeUS(),
            parentGraph.defaultType,
          )
          .apply {
            // Add `@Extends` annotation
            this.annotations +=
              pluginContext.buildAnnotation(symbol, symbols.metroExtendsAnnotationConstructor)
          }
        // Copy over any creator params
        factoryFunction.valueParameters.forEach { param ->
          addValueParameter(param.name, param.type).apply { this.copyAnnotationsFrom(param) }
        }

        body = generateDefaultConstructorBody(this)
      }

    // Merge contributed types
    val scope =
      sourceGraph.annotationsIn(symbols.classIds.contributesGraphExtensionAnnotations).first().let {
        it.scopeOrNull() ?: error("No scope found for ${sourceGraph.name}: ${it.dumpKotlinLike()}")
      }
    contributedGraph.superTypes += contributionData[scope]

    parentGraph.addChild(contributedGraph)

    contributedGraph.addFakeOverrides(irTypeSystemContext)

    return contributedGraph
  }

  private fun generateDefaultConstructorBody(declaration: IrConstructor): IrBody? {
    val returnType = declaration.returnType as? IrSimpleType ?: return null
    val parentClass = declaration.parent as? IrClass ?: return null
    val superClassConstructor =
      parentClass.superClass?.primaryConstructor
        ?: metroContext.pluginContext.irBuiltIns.anyClass.owner.primaryConstructor
        ?: return null

    return metroContext.pluginContext.createIrBuilder(declaration.symbol).irBlockBody {
      // Call the super constructor
      +irDelegatingConstructorCall(superClassConstructor)
      // Initialize the instance
      +IrInstanceInitializerCallImpl(
        UNDEFINED_OFFSET,
        UNDEFINED_OFFSET,
        parentClass.symbol,
        returnType,
      )
    }
  }
}
