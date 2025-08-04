// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.NameAllocator
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.capitalizeUS
import dev.zacsweers.metro.compiler.decapitalizeUS
import dev.zacsweers.metro.compiler.exitProcessing
import org.jetbrains.kotlin.backend.jvm.codegen.AnnotationCodegen.Companion.annotationClass
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
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
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.addFakeOverrides
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.copyAnnotationsFrom
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.getValueArgument
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.superClass
import org.jetbrains.kotlin.name.ClassId

internal class IrContributedGraphGenerator(
  context: IrMetroContext,
  private val contributionData: IrContributionData,
  private val parentGraph: IrClass,
) : IrMetroContext by context {

  private val nameAllocator = NameAllocator(mode = NameAllocator.Mode.COUNT)

  @OptIn(DelicateIrParameterIndexSetter::class)
  fun generateContributedGraph(
    sourceGraph: IrClass,
    sourceFactory: IrClass,
    factoryFunction: IrSimpleFunction,
  ): IrClass {
    val contributesGraphExtensionAnno =
      sourceGraph.annotationsIn(symbols.classIds.contributesGraphExtensionAnnotations).first()

    // If the parent graph is not extendable, error out here
    val parentIsContributed = parentGraph.origin === Origins.ContributedGraph
    val realParent =
      if (parentIsContributed) {
        parentGraph.superTypes.first().rawType()
      } else {
        parentGraph
      }
    val parentGraphAnno = realParent.annotationsIn(symbols.classIds.graphLikeAnnotations).single()
    val parentIsExtendable = parentGraphAnno.isExtendable()
    if (!parentIsExtendable) {
      with(metroContext) {
        val message = buildString {
          append("Contributed graph extension '")
          append(sourceGraph.kotlinFqName)
          append("' contributes to parent graph ")
          append('\'')
          append(realParent.kotlinFqName)
          append("' (scope '")
          append(parentGraphAnno.scopeOrNull()!!.asSingleFqName())
          append("'), but ")
          append(realParent.name)
          append(" is not extendable.")
          if (!parentIsContributed) {
            appendLine()
            appendLine()
            append("Either mark ")
            append(realParent.name)
            append(" as extendable (`@")
            append(parentGraphAnno.annotationClass.name)
            append("(isExtendable = true)`), or exclude it from ")
            append(realParent.name)
            append(" (`@")
            append(parentGraphAnno.annotationClass.name)
            append("(excludes = [")
            append(sourceGraph.name)
            append("::class])`).")
          }
        }
        // TODO in kotlin 2.2.20 remove message collector
        if (sourceGraph.fileOrNull == null) {
          messageCollector.report(CompilerMessageSeverity.ERROR, message, sourceGraph.location())
        } else {
          diagnosticReporter.at(sourceGraph).report(MetroIrErrors.METRO_ERROR, message)
        }
        exitProcessing()
      }
    }

    val sourceScope = contributesGraphExtensionAnno.scopeClassOrNull()

    // Source is a `@ContributesGraphExtension`-annotated class, we want to generate a header impl
    // class
    val contributedGraph =
      pluginContext.irFactory
        .buildClass {
          // Ensure a unique name
          name =
            nameAllocator
              .newName(
                "${Symbols.StringNames.CONTRIBUTED_GRAPH_PREFIX}${sourceGraph.name.asString().capitalizeUS()}"
              )
              .asName()
          origin = Origins.ContributedGraph
          kind = ClassKind.CLASS
        }
        .apply {
          createThisReceiverParameter()
          // Add a @DependencyGraph(...) annotation
          annotations +=
            buildAnnotation(symbol, symbols.metroDependencyGraphAnnotationConstructor) { annotation
              ->
              // scope
              sourceScope?.let { annotation.arguments[0] = kClassReference(it.symbol) }

              // additionalScopes
              contributesGraphExtensionAnno.additionalScopes().copyToIrVararg()?.let {
                annotation.arguments[1] = it
              }

              // excludes
              contributesGraphExtensionAnno.excludedClasses().copyToIrVararg()?.let {
                annotation.arguments[2] = it
              }

              // isExtendable
              annotation.arguments[3] =
                irBoolean(
                  contributesGraphExtensionAnno.getConstBooleanArgumentOrNull(
                    Symbols.Names.isExtendable
                  ) ?: false
                )

              // bindingContainers
              contributesGraphExtensionAnno
                .bindingContainerClasses(includeModulesArg = options.enableDaggerRuntimeInterop)
                .copyToIrVararg()
                ?.let { annotation.arguments[4] = it }
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
            this.annotations += buildAnnotation(symbol, symbols.metroExtendsAnnotationConstructor)
          }
        // Copy over any creator params
        factoryFunction.regularParameters.forEach { param ->
          addValueParameter(param.name, param.type).apply { this.copyAnnotationsFrom(param) }
        }

        body = generateDefaultConstructorBody(this)
      }

    // Merge contributed types
    val graphExtensionAnno =
      sourceGraph.annotationsIn(symbols.classIds.contributesGraphExtensionAnnotations).first()

    val scope = graphExtensionAnno.scopeOrNull()

    if (scope != null) {
      val additionalScopes =
        graphExtensionAnno.additionalScopes().map { it.classType.rawType().classIdOrFail }

      val allScopes = (additionalScopes + scope).toSet()

      // Get all contributions and binding containers
      val allContributions =
        allScopes
          .flatMap { contributionData.getContributions(it) }
          .groupByTo(mutableMapOf()) {
            // For Metro contributions, we need to check the parent class ID
            // This is always the $$MetroContribution, the contribution's parent is the actual class
            it.rawType().classIdOrFail.parentClassId!!
          }
      val bindingContainers =
        allScopes
          .flatMap { contributionData.getBindingContainerContributions(it) }
          .associateByTo(mutableMapOf()) { it.classIdOrFail }

      // Process excludes
      val excluded = graphExtensionAnno.excludedClasses()
      for (excludedClass in excluded) {
        val excludedClassId = excludedClass.classType.rawType().classIdOrFail

        // Remove excluded binding containers - they won't contribute their bindings
        bindingContainers.remove(excludedClassId)

        // Remove contributions from excluded classes that have nested $$MetroContribution classes
        // (binding containers don't have these, so this only affects @ContributesBinding etc.)
        allContributions.remove(excludedClassId)
      }

      // Apply replacements from remaining (non-excluded) binding containers
      bindingContainers.values.forEach { bindingContainer ->
        bindingContainer
          .annotationsIn(symbols.classIds.allContributesAnnotations)
          .flatMap { annotation -> annotation.replacedClasses() }
          .mapNotNull { replacedClass -> replacedClass.classType.rawType().classId }
          .forEach { replacedClassId -> allContributions.remove(replacedClassId) }
      }

      // Process rank-based replacements if Dagger-Anvil interop is enabled
      if (options.enableDaggerAnvilInterop) {
        val rankReplacements = processRankBasedReplacements(allScopes, allContributions)
        for (replacedClassId in rankReplacements) {
          allContributions.remove(replacedClassId)
        }
      }

      // Add only non-binding-container contributions as supertypes
      contributedGraph.superTypes +=
        allContributions.values
          .flatten()
          // Deterministic sort
          .sortedBy { it.render(short = false) }
    }

    parentGraph.addChild(contributedGraph)

    contributedGraph.addFakeOverrides(irTypeSystemContext)

    return contributedGraph
  }

  private fun generateDefaultConstructorBody(declaration: IrConstructor): IrBody? {
    val returnType = declaration.returnType as? IrSimpleType ?: return null
    val parentClass = declaration.parent as? IrClass ?: return null
    val superClassConstructor =
      parentClass.superClass?.primaryConstructor
        ?: metroContext.irBuiltIns.anyClass.owner.primaryConstructor
        ?: return null

    return metroContext.createIrBuilder(declaration.symbol).irBlockBody {
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

  /**
   * This provides `ContributesBinding.rank` interop for users migrating from Dagger-Anvil to make
   * the migration to Metro more feasible.
   *
   * @return The bindings which have been outranked and should not be included in the merged graph.
   */
  private fun processRankBasedReplacements(
    allScopes: Set<ClassId>,
    contributions: Map<ClassId, List<IrType>>,
  ): Set<ClassId> {
    val pendingRankReplacements = mutableSetOf<ClassId>()

    val rankedBindings =
      contributions.values
        .flatten()
        .map { it.rawType().parentAsClass }
        .distinctBy { it.classIdOrFail }
        .flatMap { contributingType ->
          contributingType
            .annotationsIn(symbols.classIds.contributesBindingAnnotations)
            .mapNotNull { annotation ->
              val scope = annotation.scopeOrNull() ?: return@mapNotNull null
              if (scope !in allScopes) return@mapNotNull null

              val explicitBindingMissingMetadata =
                annotation.getValueArgument(Symbols.Names.binding)

              if (explicitBindingMissingMetadata != null) {
                // This is a case where an explicit binding is specified but we receive the argument
                // as FirAnnotationImpl without the metadata containing the type arguments so we
                // short-circuit since we lack the info to compare it against other bindings.
                null
              } else {
                val (explicitBindingType, ignoreQualifier) = annotation.bindingTypeOrNull()
                val boundType =
                  explicitBindingType
                    ?: contributingType.implicitBoundTypeOrNull()!! // Checked in FIR

                ContributedIrBinding(
                  contributingType = contributingType,
                  typeKey =
                    IrTypeKey(
                      boundType,
                      if (ignoreQualifier) null else contributingType.qualifierAnnotation(),
                    ),
                  rank = annotation.rankValue(),
                )
              }
            }
        }

    val bindingGroups =
      rankedBindings
        .groupBy { binding -> binding.typeKey }
        .filter { bindingGroup -> bindingGroup.value.size > 1 }

    for (bindingGroup in bindingGroups.values) {
      val topBindings =
        bindingGroup
          .groupBy { binding -> binding.rank }
          .toSortedMap()
          .let { it.getValue(it.lastKey()) }

      // These are the bindings that were outranked and should not be processed further
      bindingGroup.minus(topBindings).forEach {
        pendingRankReplacements += it.contributingType.classIdOrFail
      }
    }

    return pendingRankReplacements
  }

  private data class ContributedIrBinding(
    val contributingType: IrClass,
    val typeKey: IrTypeKey,
    val rank: Long,
  )
}
