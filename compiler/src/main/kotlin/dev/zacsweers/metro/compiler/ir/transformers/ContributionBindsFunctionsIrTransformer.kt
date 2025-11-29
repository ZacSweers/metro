// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.ir.IrContributionData
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.allSupertypesSequence
import dev.zacsweers.metro.compiler.ir.annotationClass
import dev.zacsweers.metro.compiler.ir.annotationsIn
import dev.zacsweers.metro.compiler.ir.bindingTypeOrNull
import dev.zacsweers.metro.compiler.ir.buildAnnotation
import dev.zacsweers.metro.compiler.ir.findAnnotations
import dev.zacsweers.metro.compiler.ir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.ir.isBindingContainer
import dev.zacsweers.metro.compiler.ir.isExternalParent
import dev.zacsweers.metro.compiler.ir.mapKeyAnnotation
import dev.zacsweers.metro.compiler.ir.qualifierAnnotation
import dev.zacsweers.metro.compiler.ir.rawType
import dev.zacsweers.metro.compiler.ir.rawTypeOrNull
import dev.zacsweers.metro.compiler.ir.requireNestedClass
import dev.zacsweers.metro.compiler.ir.requireScope
import dev.zacsweers.metro.compiler.ir.setDispatchReceiver
import dev.zacsweers.metro.compiler.joinSimpleNames
import dev.zacsweers.metro.compiler.memoize
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.isLocal
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.visitors.IrTransformer
import org.jetbrains.kotlin.name.ClassId

/**
 * A transformer that does three things:
 * 1. Generates `@Binds` properties into FIR-generated `MetroContribution` interfaces.
 * 2. Transforms extenders of these generated interfaces _in this compilation_ to add new fake
 *    overrides of them.
 * 3. Collects contribution data while transforming for use by the dependency graph.
 */
internal class ContributionTransformer(private val context: IrMetroContext) :
  IrTransformer<IrContributionData>(), IrMetroContext by context {

  private val transformedContributions = mutableSetOf<ClassId>()

  /**
   * Lookup cache of contributions.
   *
   * ```
   * MutableMap<
   *   ClassId <-- contributor class id
   *   Map<
   *     ClassId <-- scope class id
   *     Set<Contribution> <-- contributions to that scope
   *   >
   * >
   * ```
   */
  private val contributionsByClass = mutableMapOf<ClassId, Map<ClassId, Set<Contribution>>>()

  override fun visitClass(declaration: IrClass, data: IrContributionData): IrStatement {
    // TODO others?
    val shouldSkip = declaration.isLocal
    if (shouldSkip) {
      return super.visitClass(declaration, data)
    }

    val isBindingContainer by memoize { declaration.isBindingContainer() }

    // First, perform transformations
    if (declaration.origin == Origins.MetroContributionClassDeclaration) {
      val metroContributionAnno =
        declaration.findAnnotations(Symbols.ClassIds.metroContribution).first()
      val scope = metroContributionAnno.requireScope()
      transformContributionClass(declaration, scope)
      collectContributionDataFromContribution(declaration, data, scope, isBindingContainer)
    } else if (declaration.isAnnotatedWithAny(context.metroSymbols.classIds.graphLikeAnnotations)) {
      transformGraphLike(declaration)
    } else if (isBindingContainer) {
      collectContributionDataFromContainer(declaration, data)
    }

    return super.visitClass(declaration, data)
  }

  private fun collectContributionDataFromContribution(
    declaration: IrClass,
    data: IrContributionData,
    scope: ClassId,
    isBindingContainer: Boolean,
  ) {
    if (isBindingContainer) {
      data.addBindingContainerContribution(scope, declaration)
    } else {
      data.addContribution(scope, declaration.defaultType)
    }
  }

  private fun collectContributionDataFromContainer(declaration: IrClass, data: IrContributionData) {
    if (declaration.isBindingContainer()) {
      for (contributesToAnno in
        declaration.annotationsIn(metroSymbols.classIds.contributesToAnnotations)) {
        val scope = contributesToAnno.requireScope()
        data.addBindingContainerContribution(scope, declaration)
      }
    }
  }

  private fun transformContributionClass(declaration: IrClass, scope: ClassId) {
    val classId = declaration.classIdOrFail
    if (classId !in transformedContributions) {
      val contributor = declaration.parentAsClass
      val contributions = getOrFindContributions(contributor, scope).orEmpty()
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
      .allSupertypesSequence()
      .filterNot { it.rawTypeOrNull()?.isExternalParent == true }
      .mapNotNull { it.rawTypeOrNull() }
      .forEach {
        val contributionMarker =
          it.findAnnotations(Symbols.ClassIds.metroContribution).singleOrNull() ?: return@forEach
        val scope = contributionMarker.requireScope()
        transformContributionClass(it, scope)
      }

    // Add fake overrides. This should only add missing ones
    declaration.addFakeOverrides(irTypeSystemContext)
    if (!declaration.isAnnotatedWithAny(metroSymbols.classIds.graphExtensionAnnotations)) {
      // Only DependencyGraph classes have an FIR-generated graph impl. Contributed GraphExtensions
      // will get implemented later in IR
      declaration
        .requireNestedClass(Origins.GraphImplClassDeclaration)
        .addFakeOverrides(irTypeSystemContext)
    }
    declaration.dumpToMetroLog()
  }

  sealed interface Contribution {
    val origin: ClassId
    val annotation: IrConstructorCall

    sealed interface BindingContribution : Contribution {
      val callableName: String
      val annotatedType: IrClass
      val buildAnnotations: IrFunction.() -> List<IrConstructorCall>
      override val origin: ClassId
        get() = annotatedType.classIdOrFail

      fun IrClass.generateBindingFunction(metroContext: IrMetroContext): IrSimpleFunction =
        with(metroContext) {
          val (explicitBindingType, ignoreQualifier) = annotation.bindingTypeOrNull()
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
            if (bindingType.isMarkedNullable()) {
              append("Nullable")
            }
            bindingType
              .rawType()
              .classIdOrFail
              .joinSimpleNames(separator = "", camelCase = true)
              .shortClassName
              .let(::append)
            qualifier?.hashCode()?.toUInt()?.let(::append)
            mapKey?.hashCode()?.toUInt()?.let(::append)
          }

          // We need a unique name because addFakeOverrides() doesn't handle overloads with
          // different return types
          val name = (callableName + suffix).asName()
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
              if (this@BindingContribution is ContributesIntoMapBinding) {
                mapKey?.let { annotations += it.ir.deepCopyWithSymbols() }
              }
              pluginContext.metadataDeclarationRegistrar.registerFunctionAsMetadataVisible(this)
            }
        }
    }

    data class ContributesTo(
      override val origin: ClassId,
      override val annotation: IrConstructorCall,
    ) : Contribution

    data class ContributesBinding(
      override val annotatedType: IrClass,
      override val annotation: IrConstructorCall,
      override val buildAnnotations: IrFunction.() -> List<IrConstructorCall>,
    ) : Contribution, BindingContribution {
      override val callableName: String = "binds"
    }

    data class ContributesIntoSetBinding(
      override val annotatedType: IrClass,
      override val annotation: IrConstructorCall,
      override val buildAnnotations: IrFunction.() -> List<IrConstructorCall>,
    ) : Contribution, BindingContribution {
      override val callableName: String = "bindIntoSet"
    }

    data class ContributesIntoMapBinding(
      override val annotatedType: IrClass,
      override val annotation: IrConstructorCall,
      override val buildAnnotations: IrFunction.() -> List<IrConstructorCall>,
    ) : Contribution, BindingContribution {
      override val callableName: String = "bindIntoMap"
    }
  }

  private fun getOrFindContributions(
    contributingSymbol: IrClass,
    scope: ClassId,
  ): Set<Contribution>? {
    val contributorClassId = contributingSymbol.classIdOrFail
    if (contributorClassId !in contributionsByClass) {
      val allContributions = findContributions(contributingSymbol)
      contributionsByClass[contributorClassId] =
        if (allContributions.isNullOrEmpty()) {
          emptyMap()
        } else {
          allContributions.groupBy { it.annotation.requireScope() }.mapValues { it.value.toSet() }
        }
    }
    return contributionsByClass[contributorClassId]?.get(scope)
  }

  private fun findContributions(contributingSymbol: IrClass): Set<Contribution>? {
    val contributesToAnnotations = metroSymbols.classIds.contributesToAnnotations
    val contributesBindingAnnotations = metroSymbols.classIds.contributesBindingAnnotations
    val contributesIntoSetAnnotations = metroSymbols.classIds.contributesIntoSetAnnotations
    val contributesIntoMapAnnotations = metroSymbols.classIds.contributesIntoMapAnnotations
    val contributions = mutableSetOf<Contribution>()
    for (annotation in contributingSymbol.annotations) {
      val annotationClassId = annotation.annotationClass.classId ?: continue
      when (annotationClassId) {
        in contributesToAnnotations -> {
          contributions += Contribution.ContributesTo(contributingSymbol.classIdOrFail, annotation)
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
        in metroSymbols.classIds.customContributesIntoSetAnnotations -> {
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
    return buildAnnotation(symbol, metroSymbols.bindsConstructor)
  }

  private fun IrFunction.buildIntoSetAnnotation(): IrConstructorCall {
    return buildAnnotation(symbol, metroSymbols.intoSetConstructor)
  }

  private fun IrFunction.buildIntoMapAnnotation(): IrConstructorCall {
    return buildAnnotation(symbol, metroSymbols.intoMapConstructor)
  }
}
