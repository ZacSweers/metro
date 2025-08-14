// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.bindingTypeOrNull
import dev.zacsweers.metro.compiler.ir.buildAnnotation
import dev.zacsweers.metro.compiler.ir.findAnnotations
import dev.zacsweers.metro.compiler.ir.getAllSuperTypes
import dev.zacsweers.metro.compiler.ir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.ir.isExternalParent
import dev.zacsweers.metro.compiler.ir.mapKeyAnnotation
import dev.zacsweers.metro.compiler.ir.qualifierAnnotation
import dev.zacsweers.metro.compiler.ir.rawType
import dev.zacsweers.metro.compiler.ir.rawTypeOrNull
import dev.zacsweers.metro.compiler.ir.requireNestedClass
import dev.zacsweers.metro.compiler.ir.requireScope
import dev.zacsweers.metro.compiler.ir.setDispatchReceiver
import dev.zacsweers.metro.compiler.joinSimpleNames
import org.jetbrains.kotlin.backend.jvm.codegen.AnnotationCodegen.Companion.annotationClass
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.util.addFakeOverrides
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.defaultType
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
internal class ContributionBindsFunctionsIrTransformer(private val context: IrMetroContext) :
  IrElementTransformerVoid(), IrMetroContext by context {

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

  override fun visitClass(declaration: IrClass): IrStatement {
    if (declaration.origin == Origins.MetroContributionClassDeclaration) {
      transformContributionClass(
        declaration,
        declaration.findAnnotations(Symbols.ClassIds.metroContribution).first(),
      )
    } else if (declaration.isAnnotatedWithAny(context.symbols.classIds.graphLikeAnnotations)) {
      transformGraphLike(declaration)
    }

    return super.visitClass(declaration)
  }

  private fun transformContributionClass(
    declaration: IrClass,
    contributionAnnotation: IrConstructorCall,
  ) {
    val classId = declaration.classIdOrFail
    if (classId !in transformedContributions) {
      val contributor = declaration.parentAsClass
      // TODO If ContributesTo, return emptySet()?
      val scope = contributionAnnotation.requireScope()
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
      .getAllSuperTypes()
      .filterNot { it.rawTypeOrNull()?.isExternalParent == true }
      .mapNotNull { it.rawTypeOrNull() }
      .forEach {
        val contributionMarker =
          it.findAnnotations(Symbols.ClassIds.metroContribution).singleOrNull() ?: return@forEach
        transformContributionClass(it, contributionMarker)
      }

    // Add fake overrides. This should only add missing ones
    declaration.addFakeOverrides(irTypeSystemContext)
    if (!declaration.isAnnotatedWithAny(symbols.classIds.graphExtensionAnnotations)) {
      // Only DependencyGraph classes have a $$MetroGraph. ContributesGraphExtension will get
      // implemented later in IR
      declaration.requireNestedClass(Symbols.Names.MetroGraph).addFakeOverrides(irTypeSystemContext)
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
    val contributesToAnnotations = symbols.classIds.contributesToAnnotations
    val contributesBindingAnnotations = symbols.classIds.contributesBindingAnnotations
    val contributesIntoSetAnnotations = symbols.classIds.contributesIntoSetAnnotations
    val contributesIntoMapAnnotations = symbols.classIds.contributesIntoMapAnnotations
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
    return buildAnnotation(symbol, symbols.bindsConstructor)
  }

  private fun IrFunction.buildIntoSetAnnotation(): IrConstructorCall {
    return buildAnnotation(symbol, symbols.intoSetConstructor)
  }

  private fun IrFunction.buildIntoMapAnnotation(): IrConstructorCall {
    return buildAnnotation(symbol, symbols.intoMapConstructor)
  }
}
