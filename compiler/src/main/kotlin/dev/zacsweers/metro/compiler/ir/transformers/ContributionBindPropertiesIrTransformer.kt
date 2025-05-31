// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.buildAnnotation
import dev.zacsweers.metro.compiler.ir.getAllSuperTypes
import dev.zacsweers.metro.compiler.ir.getConstBooleanArgumentOrNull
import dev.zacsweers.metro.compiler.ir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.ir.isExternalParent
import dev.zacsweers.metro.compiler.ir.rawType
import dev.zacsweers.metro.compiler.ir.rawTypeOrNull
import dev.zacsweers.metro.compiler.ir.requireNestedClass
import dev.zacsweers.metro.compiler.ir.setDispatchReceiver
import dev.zacsweers.metro.compiler.joinSimpleNames
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.jvm.codegen.AnnotationCodegen.Companion.annotationClass
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.addFakeOverrides
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.getValueArgument
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.name.ClassId

/**
 * A transformer that does two things:
 * 1. Generates `@Binds` properties into FIR-generated `$$MetroContribution` interfaces.
 * 2. Transforms extenders of these generated interfaces _in this compilation_ to add new fake
 *    overrides of them.
 */
// TODO can we inline this into DependencyGraphTransformer for single-pass?
internal class ContributionBindPropertiesIrTransformer(private val context: IrMetroContext) :
  IrElementTransformerVoid(), IrMetroContext by context {

  private val transformedContributions = mutableSetOf<ClassId>()

  override fun visitClass(declaration: IrClass): IrStatement {
    if (declaration.origin == Origins.MetroContributionClassDeclaration) {
      transformContributionClass(declaration)
    } else if (declaration.isAnnotatedWithAny(context.symbols.classIds.graphLikeAnnotations)) {
      transformGraphLike(declaration)
    }

    return super.visitClass(declaration)
  }

  private fun transformContributionClass(declaration: IrClass) {
    val classId = declaration.classIdOrFail
    if (classId !in transformedContributions) {
      val contributor = declaration.parentAsClass
      // TODO If ContributesTo, return emptySet()?
      val contributions = findContributions(contributor).orEmpty()
      val bindsFunctions = mutableSetOf<IrSimpleFunction>()
      for (contribution in contributions) {
        if (contribution !is Contribution.BindingContribution) continue
        with(contribution) { bindsFunctions += declaration.generateBindingFunction(metroContext) }
      }
      declaration.dumpToMetroLog()
    }
    transformedContributions += classId
  }

  private fun transformGraphLike(declaration: IrClass) {
    // Find Contribution supertypes
    // Transform them if necessary
    // and add new fake overrides
    declaration
      .getAllSuperTypes(pluginContext)
      .filterNot { it.rawTypeOrNull()?.isExternalParent == true }
      .mapNotNull { it.rawTypeOrNull() }
      .filter { it.hasAnnotation(Symbols.ClassIds.metroContribution) }
      .forEach { transformContributionClass(it) }

    // Add fake overrides. This should only add missing ones
    declaration.addFakeOverrides(irTypeSystemContext)
    if (!declaration.isAnnotatedWithAny(symbols.classIds.contributesGraphExtensionAnnotations)) {
      // Only DependencyGraph classes have a $$MetroGraph. ContributesGraphExtension will get
      // implemented later in IR
      declaration.requireNestedClass(Symbols.Names.MetroGraph).addFakeOverrides(irTypeSystemContext)
    }
    declaration.dumpToMetroLog()
  }

  sealed interface Contribution {
    val origin: ClassId

    sealed interface BindingContribution : Contribution {
      val callableName: String
      val annotatedType: IrClass
      val annotation: IrConstructorCall
      val buildAnnotations: IrFunction.() -> List<IrConstructorCall>

      fun IrClass.generateBindingFunction(metroContext: IrMetroContext): IrSimpleFunction =
        with(metroContext) {
          val (explicitBindingType, ignoreQualifier) = annotation.bindingTypeOrNull(pluginContext)
          val bindingType =
            explicitBindingType ?: annotatedType.superTypes.single() // Checked in FIR

          val qualifier =
            if (!ignoreQualifier) {
              explicitBindingType?.qualifierAnnotation() ?: annotatedType.qualifierAnnotation()
            } else {
              null
            }

          val mapKey = explicitBindingType?.mapKeyAnnotation() ?: annotatedType.mapKeyAnnotation()

          val suffix = buildString {
            append("As")
            bindingType
              .rawType()
              .classIdOrFail
              .joinSimpleNames(separator = "", camelCase = true)
              .shortClassName
              .let(::append)
            qualifier?.hashCode()?.let(::append)
            mapKey?.hashCode()?.let(::append)
          }

          // We need a unique name because addFakeOverrides() doesn't handle overloads with
          // different return types
          val name = (callableName + "As" + suffix).asName()
          addFunction {
              this.name = name
              this.returnType = bindingType
              this.modality = Modality.ABSTRACT
            }
            .apply {
              annotations += buildAnnotations()
              setDispatchReceiver(parentAsClass.thisReceiver?.copyTo(this))
              addValueParameter(Symbols.Names.instance, annotatedType.defaultType).apply {
                // TODO any qualifiers? What if we want to qualify the instance type but not the
                //  bound type?
              }
              qualifier?.let { annotations += it.ir.deepCopyWithSymbols() }
              mapKey?.let { annotations += it.ir.deepCopyWithSymbols() }
              pluginContext.metadataDeclarationRegistrar.registerFunctionAsMetadataVisible(this)
            }
        }
    }

    data class ContributesTo(override val origin: ClassId) : Contribution

    data class ContributesBinding(
      override val annotatedType: IrClass,
      override val annotation: IrConstructorCall,
      override val buildAnnotations: IrFunction.() -> List<IrConstructorCall>,
    ) : Contribution, BindingContribution {
      override val origin: ClassId = annotatedType.classIdOrFail
      override val callableName: String = "binds"
    }

    data class ContributesIntoSetBinding(
      override val annotatedType: IrClass,
      override val annotation: IrConstructorCall,
      override val buildAnnotations: IrFunction.() -> List<IrConstructorCall>,
    ) : Contribution, BindingContribution {
      override val origin: ClassId = annotatedType.classIdOrFail
      override val callableName: String = "bindIntoSet"
    }

    data class ContributesIntoMapBinding(
      override val annotatedType: IrClass,
      override val annotation: IrConstructorCall,
      override val buildAnnotations: IrFunction.() -> List<IrConstructorCall>,
    ) : Contribution, BindingContribution {
      override val origin: ClassId = annotatedType.classIdOrFail
      override val callableName: String = "bindIntoMap"
    }
  }

  private fun findContributions(contributingSymbol: IrClass): Set<Contribution>? {
    val contributesToAnnotations = symbols.classIds.contributesToLikeAnnotations
    val contributesBindingAnnotations = symbols.classIds.contributesBindingAnnotations
    val contributesIntoSetAnnotations = symbols.classIds.contributesIntoSetAnnotations
    val contributesIntoMapAnnotations = symbols.classIds.contributesIntoMapAnnotations
    val contributions = mutableSetOf<Contribution>()
    for (annotation in contributingSymbol.annotations) {
      val annotationClassId = annotation.annotationClass.classId ?: continue
      when (annotationClassId) {
        in contributesToAnnotations -> {
          contributions += Contribution.ContributesTo(contributingSymbol.classIdOrFail)
        }
        in contributesBindingAnnotations -> {
          contributions +=
            Contribution.ContributesBinding(contributingSymbol, annotation) {
              listOf(buildBindsAnnotation())
            }
        }
        in contributesIntoSetAnnotations -> {
          contributions +=
            Contribution.ContributesIntoSetBinding(contributingSymbol, annotation) {
              listOf(buildIntoSetAnnotation(), buildBindsAnnotation())
            }
        }
        in contributesIntoMapAnnotations -> {
          contributions +=
            Contribution.ContributesIntoMapBinding(contributingSymbol, annotation) {
              listOf(buildIntoMapAnnotation(), buildBindsAnnotation())
            }
        }
        in symbols.classIds.customContributesIntoSetAnnotations -> {
          contributions +=
            if (contributingSymbol.mapKeyAnnotation() != null) {
              Contribution.ContributesIntoMapBinding(contributingSymbol, annotation) {
                listOf(buildIntoMapAnnotation(), buildBindsAnnotation())
              }
            } else {
              Contribution.ContributesIntoSetBinding(contributingSymbol, annotation) {
                listOf(buildIntoSetAnnotation(), buildBindsAnnotation())
              }
            }
        }
      }
    }

    return if (contributions.isEmpty()) {
      null
    } else {
      contributions
    }
  }

  private fun IrFunction.buildBindsAnnotation(): IrConstructorCall {
    return pluginContext.buildAnnotation(symbol, symbols.bindsConstructor)
  }

  private fun IrFunction.buildIntoSetAnnotation(): IrConstructorCall {
    return pluginContext.buildAnnotation(symbol, symbols.intoSetConstructor)
  }

  private fun IrFunction.buildIntoMapAnnotation(): IrConstructorCall {
    return pluginContext.buildAnnotation(symbol, symbols.intoMapConstructor)
  }
}

// Also check ignoreQualifier for interop after entering interop block to prevent unnecessary
// checks for non-interop
private fun IrConstructorCall.bindingTypeOrNull(
  pluginContext: IrPluginContext
): Pair<IrType?, Boolean> {
  // Return a binding defined using Metro's API
  getValueArgument(Symbols.Names.binding)?.expectAsOrNull<IrConstructorCall>()?.let { bindingType ->
    // bindingType is actually an annotation
    return bindingType.typeArguments.getOrNull(0)?.takeUnless {
      it == pluginContext.irBuiltIns.nothingType
    } to false
  }
  // Return a boundType defined using anvil KClass
  return anvilKClassBoundTypeArgument() to anvilIgnoreQualifier()
}

private fun IrConstructorCall.anvilKClassBoundTypeArgument(): IrType? {
  return getValueArgument(Symbols.Names.boundType)?.expectAsOrNull<IrClassReference>()?.classType
}

private fun IrConstructorCall.anvilIgnoreQualifier(): Boolean {
  return getConstBooleanArgumentOrNull(Symbols.Names.ignoreQualifier) ?: false
}
