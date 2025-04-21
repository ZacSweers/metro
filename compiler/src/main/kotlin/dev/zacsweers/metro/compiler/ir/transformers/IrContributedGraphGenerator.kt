package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.capitalizeUS
import dev.zacsweers.metro.compiler.decapitalizeUS
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.allCallableMembers
import dev.zacsweers.metro.compiler.ir.annotationsIn
import dev.zacsweers.metro.compiler.ir.scopeOrNull
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addGetter
import org.jetbrains.kotlin.ir.builders.declarations.addProperty
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.copyAnnotationsFrom
import org.jetbrains.kotlin.ir.util.copyParametersFrom
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.ir.util.isPropertyAccessor
import org.jetbrains.kotlin.ir.util.parentAsClass

internal class IrContributedGraphGenerator(
  context: IrMetroContext,
  private val contributionData: IrContributionData,
) : IrMetroContext by context {

  fun generateContributedGraph(
    parentGraph: IrClass,
    sourceFactory: IrClass,
    factoryFunction: IrSimpleFunction,
  ): IrClass {
    val sourceGraph = sourceFactory.parentAsClass
    // Source is a `@ContributesGraphExtension`-annotated class, we want to generate a header impl
    // class
    val contributedClass =
      pluginContext.irFactory
        .buildClass {
          name = "$\$Contributed${sourceGraph.name.capitalizeUS()}".asName()
          origin = Origins.Default
          kind = ClassKind.CLASS
        }
        .apply {
          sourceGraph.addChild(this)
          createThisReceiverParameter()
        }

    val ctor =
      sourceFactory
        .addConstructor {
          isPrimary = true
          origin = Origins.Default
          // This will be finalized in DependencyGraphTransformer
          isFakeOverride = true
        }
        .apply {
          // Add the parent type
          addValueParameter(parentGraph.name.asString().decapitalizeUS(), parentGraph.defaultType)
          // Copy over any creator params
          factoryFunction.valueParameters.forEach { param -> param.copyTo(this) }
        }

    // Merge contributed types
    val scope =
      sourceGraph.annotationsIn(symbols.classIds.contributesGraphExtensionAnnotations).first().let {
        it.scopeOrNull() ?: error("No scope found for ${sourceGraph.name}: ${it.dumpKotlinLike()}")
      }
    contributedClass.superTypes += contributionData[scope]
    contributedClass.addFakeOverrides()

    parentGraph.addChild(contributedClass)

    return contributedClass
  }

  private fun IrClass.addFakeOverrides() {
    // Iterate all abstract functions/properties from parents and add fake overrides of them here
    // TODO need to merge colliding overrides
    val abstractMembers =
      allCallableMembers(
          metroContext,
          excludeCompanionObjectMembers = true,
          // For interfaces do we need to just check if the parent is an interface?
          propertyFilter = { it.modality == Modality.ABSTRACT },
          functionFilter = { it.modality == Modality.ABSTRACT },
        )
        .distinctBy { it.ir.name }
    for (member in abstractMembers) {
      if (member.ir.isPropertyAccessor) {
        // Stub the property declaration + getter
        val originalGetter = member.ir
        val property = member.ir.correspondingPropertySymbol!!.owner
        addProperty {
            name = property.name
            updateFrom(property)
            isFakeOverride = true
          }
          .apply {
            overriddenSymbols += property.symbol
            copyAnnotationsFrom(property)
            addGetter {
                name = originalGetter.name
                visibility = originalGetter.visibility
                origin = Origins.Default
                isFakeOverride = true
              }
              .apply {
                overriddenSymbols += originalGetter.symbol
                copyAnnotationsFrom(member.ir)
              }
          }
      } else {
        addFunction {
            name = member.ir.name
            updateFrom(member.ir)
            isFakeOverride = true
            returnType = member.ir.returnType
          }
          .apply {
            overriddenSymbols += member.ir.symbol
            copyParametersFrom(member.ir)
            copyAnnotationsFrom(member.ir)
          }
      }
    }
  }
}
