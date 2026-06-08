// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.compiler.NameAllocator
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.api.fir.MetroContributions
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.fir.generators.isContributionProviderWrapper
import dev.zacsweers.metro.compiler.ir.IrBoundTypeResolver
import dev.zacsweers.metro.compiler.ir.IrContributionData
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrScope
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.addDeprecatedHiddenAnnotation
import dev.zacsweers.metro.compiler.ir.allSupertypesSequence
import dev.zacsweers.metro.compiler.ir.annotationClass
import dev.zacsweers.metro.compiler.ir.annotationsCompat
import dev.zacsweers.metro.compiler.ir.annotationsIn
import dev.zacsweers.metro.compiler.ir.buildAnnotation
import dev.zacsweers.metro.compiler.ir.copyParameterDefaultValues
import dev.zacsweers.metro.compiler.ir.createIrBuilder
import dev.zacsweers.metro.compiler.ir.findAnnotations
import dev.zacsweers.metro.compiler.ir.findInjectableConstructor
import dev.zacsweers.metro.compiler.ir.getOrCreateGraphImplClassShell
import dev.zacsweers.metro.compiler.ir.irExprBodySafe
import dev.zacsweers.metro.compiler.ir.irInvoke
import dev.zacsweers.metro.compiler.ir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.ir.isBindingContainer
import dev.zacsweers.metro.compiler.ir.isExternalParent
import dev.zacsweers.metro.compiler.ir.isImplicitClassKeySentinel
import dev.zacsweers.metro.compiler.ir.isKiaIntoMultibinding
import dev.zacsweers.metro.compiler.ir.kClassReference
import dev.zacsweers.metro.compiler.ir.lookupClass
import dev.zacsweers.metro.compiler.ir.mapKeyAnnotation
import dev.zacsweers.metro.compiler.ir.originClassId
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.dedupeParameters
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.populateImplicitClassKey
import dev.zacsweers.metro.compiler.ir.rawType
import dev.zacsweers.metro.compiler.ir.rawTypeOrNull
import dev.zacsweers.metro.compiler.ir.regularParameters
import dev.zacsweers.metro.compiler.ir.replaceAnnotationsCompat
import dev.zacsweers.metro.compiler.ir.requireNestedClass
import dev.zacsweers.metro.compiler.ir.requireScope
import dev.zacsweers.metro.compiler.ir.scopeAnnotations
import dev.zacsweers.metro.compiler.ir.setDispatchReceiver
import dev.zacsweers.metro.compiler.ir.singleAbstractFunction
import dev.zacsweers.metro.compiler.ir.typeAsProviderArgument
import dev.zacsweers.metro.compiler.ir.usesContributionProviderPath
import dev.zacsweers.metro.compiler.joinSimpleNames
import dev.zacsweers.metro.compiler.memoize
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.reserveName
import dev.zacsweers.metro.compiler.symbols.Symbols
import dev.zacsweers.metro.compiler.tracing.TraceScope
import dev.zacsweers.metro.compiler.tracing.trace
import java.util.Objects
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irImplicitCast
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.overrides.isEffectivelyPrivate
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.addFakeOverrides
import org.jetbrains.kotlin.ir.util.addSimpleDelegatingConstructor
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.file
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.isLocal
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.util.nestedClasses
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.visitors.IrTransformer
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

private const val SCOPED_PROVIDER_PREFIX = "metro_scoped_"

/**
 * A transformer that does three things:
 * 1. Generates `@Binds` properties into FIR-generated `MetroContribution` interfaces.
 * 2. Transforms extenders of these generated interfaces _in this compilation_ to add new fake
 *    overrides of them.
 * 3. Collects contribution data while transforming for use by the dependency graph.
 */
@Inject
@SingleIn(IrScope::class)
internal class ContributionIrTransformer(
  private val context: IrMetroContext,
  private val boundTypeResolver: IrBoundTypeResolver,
  private val bindingContainerTransformer: BindingContainerTransformer,
  private val assistedFactoryTransformer: AssistedFactoryTransformer,
  private val injectedClassTransformer: InjectedClassTransformer,
  traceScope: TraceScope,
) : IrTransformer<IrContributionData>(), IrMetroContext by context, TraceScope by traceScope {

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
      return declaration
    }

    return trace("Visit ${declaration.name}") {
      trace("Transform ${declaration.name} bindings") {
        val isBindingContainer by memoize { declaration.isBindingContainer() }

        // First, perform transformations
        if (declaration.origin == Origins.MetroContributionClassDeclaration) {
          trace("Transform and collect contribution") {
            val metroContributionAnno =
              declaration.findAnnotations(Symbols.ClassIds.metroContribution).firstOrNull()
            if (metroContributionAnno != null) {
              val scope = metroContributionAnno.requireScope()

              val isContributionProviderNested =
                context.options.generateContributionProviders &&
                  declaration.parentClassOrNull?.origin ==
                    Origins.ContributionProviderHolderDeclaration

              if (isContributionProviderNested) {
                // Contribution interface inside a provider holder class: @Provides functions are
                // already declared in FIR, just need to add bodies
                trace("Transform contribution provider") {
                  transformTopLevelContributionProvider(declaration)
                }
              } else {
                // Nested contribution: generate @Binds functions
                trace("Transform class") { transformContributionClass(declaration, scope) }
              }
              trace("Collect contribution data") {
                collectContributionDataFromContribution(
                  declaration,
                  data,
                  scope,
                  isBindingContainer,
                )
              }
            }
          }
        } else if (
          declaration.isAnnotatedWithAny(context.metroSymbols.classIds.graphLikeAnnotations)
        ) {
          trace("Transform graphlike") { transformGraphLike(declaration) }
        } else if (isBindingContainer) {
          trace("Collect contributions from container") {
            collectContributionDataFromContainer(declaration, data)
          }
        } else if (options.generateClassesInIr) {
          trace("Collect contributions from source class") {
            collectContributionDataFromSourceClass(declaration, data)
          }
        }
      }

      return@trace super.visitClass(declaration, data)
    }
  }

  private fun collectContributionDataFromContribution(
    declaration: IrClass,
    data: IrContributionData,
    scope: ClassId,
    isBindingContainer: Boolean,
  ) {
    if (declaration.isEffectivelyPrivate()) {
      // Should be caught in FIR but just in case
      return
    }
    if (isBindingContainer) {
      val container = bindingContainerTransformer.findContainer(declaration)
      if (container != null) {
        data.addBindingContainerContribution(scope, declaration)
      }
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

  /**
   * IR-only class generation skips hidden FIR marker classes, so the contribution transformer has
   * to collect and create the generated contribution declarations from the annotated source class.
   */
  private fun collectContributionDataFromSourceClass(
    declaration: IrClass,
    data: IrContributionData,
  ) {
    if (declaration.isEffectivelyPrivate()) return

    val contributions = findContributions(declaration).orEmpty()
    if (contributions.isEmpty()) return

    val contributionsByScope = contributions.groupBy { it.annotation.requireScope() }
    for ((scope, scopedContributions) in contributionsByScope) {
      if (declaration.shouldContributeDirectSupertype(scopedContributions)) {
        // Preserve user-visible @ContributesTo interface supertypes in FIR/IDE. Hidden
        // MetroContribution markers are generated below only for metadata and replacement logic.
        data.addDirectSupertypeContribution(scope, declaration)
        if (bindingContainerTransformer.findContainer(declaration) != null) {
          data.addBindingContainerContribution(scope, declaration)
        }
      }

      val bindingContributions =
        scopedContributions.filterIsInstance<Contribution.BindingContribution>()
      if (bindingContributions.isNotEmpty()) {
        val useContributionProviderPath =
          declaration.usesContributionProviderPath(options, metroSymbols.classIds)
        if (useContributionProviderPath) {
          // Contribution-provider mode represents binding contributions as a generated
          // @BindingContainer object instead of nested MetroContribution marker functions.
          val container = declaration.getOrCreateContributionProviderContainer(scope)
          generateContributionProviderFunctions(container, declaration, bindingContributions)
          transformTopLevelContributionProvider(container)
          bindingContainerTransformer.findContainer(container)
          data.addBindingContainerContribution(scope, container)
        } else {
          // Regular IR-only contribution markers mirror the old FIR-generated declarations. They
          // are metadata-visible but hidden from users with Deprecated(HIDDEN).
          val generateAsContainer =
            shouldGenerateIrContributionMarkerAsContainer(scopedContributions)
          val marker =
            declaration.getOrCreateIrContributionMarker(
              scope,
              bindingContainer = generateAsContainer,
            )
          transformContributionClass(marker, scope)
          if (generateAsContainer) {
            bindingContainerTransformer.findContainer(marker)
            data.addBindingContainerContribution(scope, marker)
          } else {
            data.addContribution(scope, marker.defaultType)
          }
        }
      }
    }
  }

  private fun IrClass.shouldContributeDirectSupertype(
    scopedContributions: List<Contribution>
  ): Boolean {
    if (kind != ClassKind.INTERFACE) return false
    if (isAnnotatedWithAny(metroSymbols.classIds.graphExtensionFactoryAnnotations)) return false
    return scopedContributions.any { it is Contribution.ContributesTo }
  }

  private fun shouldGenerateIrContributionMarkerAsContainer(
    scopedContributions: List<Contribution>
  ): Boolean {
    return options.bindingContributionsAsContainers &&
      scopedContributions.none { it is Contribution.ContributesTo }
  }

  /** Creates the IR-only equivalent of FIR's generated contribution-provider holder/container. */
  private fun IrClass.getOrCreateContributionProviderContainer(scope: ClassId): IrClass {
    val holderClassId = MetroContributions.holderClassId(classIdOrFail)
    val sourceFile = file
    val holder =
      sourceFile.declarations.filterIsInstance<IrClass>().firstOrNull {
        it.classId == holderClassId
      }
        ?: irFactory
          .buildClass {
            name = holderClassId.shortClassName
            origin = Origins.ContributionProviderHolderDeclaration
            kind = ClassKind.CLASS
            visibility = DescriptorVisibilities.PUBLIC
            modality = Modality.ABSTRACT
          }
          .apply {
            createThisReceiverParameter()
            addDeprecatedHiddenAnnotation()
            sourceFile.addChild(this)
            metadataDeclarationRegistrarCompat.registerClassAsMetadataVisible(this)
          }

    val containerClassId = MetroContributions.containerObjectClassId(classIdOrFail, scope)
    holder.nestedClasses
      .firstOrNull {
        it.origin == Origins.MetroContributionClassDeclaration && it.classId == containerClassId
      }
      ?.let {
        return it
      }

    return irFactory
      .buildClass {
        name = containerClassId.shortClassName
        origin = Origins.MetroContributionClassDeclaration
        kind = ClassKind.OBJECT
        visibility = DescriptorVisibilities.PUBLIC
        modality = Modality.FINAL
      }
      .apply {
        createThisReceiverParameter()
        addDeprecatedHiddenAnnotation()
        holder.addChild(this)
        addMetroContributionAnnotation(scope)
        addBindingContainerAnnotation()
        addContributesToAnnotation(scope)
        addOriginAnnotation(this@getOrCreateContributionProviderContainer)
        metadataDeclarationRegistrarCompat.registerClassAsMetadataVisible(this)
        addSimpleDelegatingConstructor(
            irBuiltIns.anyClass.owner.primaryConstructor!!,
            irBuiltIns,
            isPrimary = true,
          )
          .apply {
            visibility = DescriptorVisibilities.PRIVATE
            metadataDeclarationRegistrarCompat.registerConstructorAsMetadataVisible(this)
          }
      }
  }

  private fun IrClass.getOrCreateIrContributionMarker(
    scope: ClassId,
    bindingContainer: Boolean,
  ): IrClass {
    // This is the hidden nested MetroContributionTo<Scope> interface that FIR used to generate.
    // In IR-only mode we create it here and register it as metadata-visible.
    val name = MetroContributions.metroContributionName(scope)
    nestedClasses
      .firstOrNull {
        it.origin == Origins.MetroContributionClassDeclaration && it.name == name
      }
      ?.let {
        return it
      }

    return irFactory
      .buildClass {
        this.name = name
        origin = Origins.MetroContributionClassDeclaration
        kind = ClassKind.INTERFACE
        visibility = DescriptorVisibilities.PUBLIC
        modality = Modality.ABSTRACT
      }
      .apply {
        createThisReceiverParameter()
        addDeprecatedHiddenAnnotation()
        this@getOrCreateIrContributionMarker.addChild(this)
        addMetroContributionAnnotation(scope)
        if (bindingContainer) {
          addBindingContainerAnnotation()
          addOriginAnnotation(this@getOrCreateIrContributionMarker)
          addComptimeOnlyAnnotation()
        }
        metadataDeclarationRegistrarCompat.registerClassAsMetadataVisible(this)
      }
  }

  private fun IrClass.addMetroContributionAnnotation(scope: ClassId) {
    addAnnotationCompat(
      buildAnnotation(symbol, metroSymbols.metroContributionConstructor) { annotation ->
        lookupClass(scope)?.let { scopeSymbol ->
          annotation.arguments[0] =
            pluginContext.createIrBuilder(symbol).kClassReference(scopeSymbol)
        }
      }
    )
  }

  private fun IrClass.addBindingContainerAnnotation() {
    addAnnotationCompat(buildAnnotation(symbol, metroSymbols.bindingContainerConstructor))
  }

  private fun IrClass.addContributesToAnnotation(scope: ClassId) {
    addAnnotationCompat(
      buildAnnotation(symbol, metroSymbols.contributesToConstructor) { annotation ->
        lookupClass(scope)?.let { scopeSymbol ->
          annotation.arguments[0] =
            pluginContext.createIrBuilder(symbol).kClassReference(scopeSymbol)
        }
      }
    )
  }

  private fun IrClass.addOriginAnnotation(originClass: IrClass) {
    addAnnotationCompat(
      buildAnnotation(symbol, metroSymbols.originConstructor) { annotation ->
        annotation.arguments[0] =
          pluginContext.createIrBuilder(symbol).kClassReference(originClass.symbol)
        annotation.arguments[1] =
          pluginContext
            .createIrBuilder(symbol)
            .irString(Symbols.StringNames.CONTRIBUTION_PROVIDER_ORIGIN_CONTEXT)
      }
    )
  }

  private fun IrClass.addComptimeOnlyAnnotation() {
    addAnnotationCompat(buildAnnotation(symbol, metroSymbols.comptimeOnlyAnnotationConstructor))
  }

  private fun generateContributionProviderFunctions(
    container: IrClass,
    originClass: IrClass,
    contributions: List<Contribution.BindingContribution>,
  ) {
    // Mirrors ContributionsFirGenerator's provider functions for classes that no longer get FIR
    // generated contribution-provider containers in IR-only mode.
    val useSyntheticScopedProvider = originClass.scopeAnnotations().any() && contributions.size > 1
    val syntheticQualifierName =
      if (useSyntheticScopedProvider) {
        syntheticScopedQualifierName(originClass.classIdOrFail)
      } else {
        null
      }
    val isAssistedFactory =
      originClass.isAnnotatedWithAny(metroSymbols.classIds.assistedFactoryAnnotations)
    val injectConstructor =
      if (originClass.isObject) {
        null
      } else if (isAssistedFactory) {
        originClass.assistedFactoryTargetConstructor()
      } else {
        originClass.findInjectableConstructor(onlyUsePrimaryConstructor = false)
          ?: reportCompilerBug(
            "No inject constructor found in IR for contribution provider ${originClass.fqNameWhenAvailable}"
          )
      }
    val nameAllocator = NameAllocator(mode = COUNT)
    for (declaration in container.declarations) {
      if (declaration is IrFunction) {
        nameAllocator.reserveName(declaration.name)
      }
    }

    if (
      useSyntheticScopedProvider &&
        container.functions.none { it.name == syntheticScopedFunctionName(originClass) }
    ) {
      container
        .addFunction {
          name = syntheticScopedFunctionName(originClass)
          returnType = irBuiltIns.anyNType
          modality = Modality.FINAL
          origin = Origins.MetroContributionCallableDeclaration
        }
        .apply {
          addContributionProviderParameters(injectConstructor, isAssistedFactory)
          addAnnotationCompat(buildProvidesAnnotation())
          originClass.scopeAnnotations().firstOrNull()?.let {
            addAnnotationCompat(it.ir.deepCopyWithSymbols())
          }
          addAnnotationCompat(buildNamedAnnotation(syntheticQualifierName!!))
        }
    }

    for (contribution in contributions) {
      val (bindingTypeKey, explicitBindingType) =
        boundTypeResolver.resolveBoundType(originClass, contribution.annotation)
          ?: reportCompilerBug(
            "Could not resolve bound type for ${originClass.classIdOrFail}. This should have been caught in FIR."
          )
      val name =
        nameAllocator
          .newName(
            contribution.callableName.replace("bind", "provide") +
              originClass.classIdOrFail
                .joinSimpleNames(separator = "", camelCase = true)
                .shortClassName
          )
          .asName()

      container
        .addFunction {
          this.name = name
          returnType = bindingTypeKey.type
          modality = Modality.FINAL
          origin = Origins.MetroContributionCallableDeclaration
        }
        .apply {
          if (useSyntheticScopedProvider) {
            addValueParameter(Symbols.Names.instance, irBuiltIns.anyNType).apply {
              addAnnotationCompat(buildNamedAnnotation(syntheticQualifierName!!))
            }
            body =
              context.createIrBuilder(symbol).run {
                irExprBodySafe(irImplicitCast(irGet(regularParameters.single()), returnType))
              }
          } else {
            addContributionProviderParameters(injectConstructor, isAssistedFactory)
            if (isAssistedFactory) {
              body =
                context.createIrBuilder(symbol).run {
                  irExprBodySafe(
                    createAssistedFactoryContribution(
                      originClass = originClass,
                      targetConstructor = injectConstructor!!,
                      function = this@apply,
                    )
                  )
                }
            }
          }
          addAnnotationCompat(buildProvidesAnnotation())
          when (contribution) {
            is Contribution.ContributesIntoSetBinding ->
              addAnnotationCompat(buildIntoSetAnnotation())
            is Contribution.ContributesIntoMapBinding -> {
              addAnnotationCompat(buildIntoMapAnnotation())
              val mapKey =
                explicitBindingType?.originalType?.mapKeyAnnotation()
                  ?: originClass.mapKeyAnnotation()
              mapKey?.let { mk ->
                val copied = mk.ir.deepCopyWithSymbols()
                if (isImplicitClassKeySentinel(copied)) {
                  populateImplicitClassKey(copied, originClass.defaultType)
                }
                addAnnotationCompat(copied)
              }
            }
            is Contribution.ContributesBinding -> {}
          }
          if (!useSyntheticScopedProvider) {
            originClass.scopeAnnotations().firstOrNull()?.let {
              addAnnotationCompat(it.ir.deepCopyWithSymbols())
            }
          }
          val qualifier = explicitBindingType?.qualifier ?: bindingTypeKey.qualifier
          qualifier?.let { addAnnotationCompat(it.ir.deepCopyWithSymbols()) }
        }
    }
  }

  private fun IrFunction.addContributionProviderParameters(
    injectConstructor: IrConstructor?,
    isAssistedFactory: Boolean,
  ) {
    if (injectConstructor == null) return
    if (isAssistedFactory) {
      addParameters(
        injectConstructor
          .parameters()
          .regularParameters
          .filterNot { it.isAssisted }
          .dedupeParameters(),
        wrapInProvider = false,
        copyQualifiers = true,
      )
    } else {
      for (parameter in injectConstructor.regularParameters) {
        addValueParameter(parameter.name, parameter.type).apply {
          replaceAnnotationsCompat(parameter.annotationsCompat())
        }
      }
    }
  }

  private fun IrClass.assistedFactoryTargetConstructor(): IrConstructor {
    val targetClass = singleAbstractFunction().returnType.rawType()
    return targetClass.findInjectableConstructor(onlyUsePrimaryConstructor = false)
      ?: reportCompilerBug(
        "No inject constructor found in IR for assisted factory contribution provider ${fqNameWhenAvailable}"
      )
  }

  private fun syntheticScopedQualifierName(contributingClassId: ClassId): String =
    "$SCOPED_PROVIDER_PREFIX${contributingClassId.asString()}"

  private fun syntheticScopedFunctionName(contributingClass: IrClass): Name {
    val baseName =
      contributingClass.classIdOrFail
        .joinSimpleNames(separator = "", camelCase = true)
        .shortClassName
        .asString()
    return "provideScopedInstance$baseName".asName()
  }

  private fun IrFunction.buildNamedAnnotation(name: String): IrConstructorCall {
    val namedClassId = ClassId.topLevel(FqName("${Symbols.FqNames.metroRuntimePackage}.Named"))
    val constructor =
      lookupClass(namedClassId)?.owner?.primaryConstructor?.symbol
        ?: reportCompilerBug("No constructor found for @Named")
    return buildAnnotation(symbol, constructor) { annotation ->
      annotation.arguments[0] = context.createIrBuilder(symbol).irString(name)
    }
  }

  private fun transformContributionClass(declaration: IrClass, scope: ClassId) {
    val classId = declaration.classIdOrFail
    if (classId !in transformedContributions) {
      val contributor = declaration.parentAsClass
      val contributions = getOrFindContributions(contributor, scope).orEmpty()

      if (contributions.isNotEmpty()) {
        val bindsFunctions = mutableSetOf<IrSimpleFunction>()
        val nameAllocator = NameAllocator(mode = COUNT)
        contributor.functions.forEach { nameAllocator.reserveName(it.name) }
        declaration.functions.forEach { nameAllocator.reserveName(it.name) }
        for (contribution in contributions) {
          if (contribution !is Contribution.BindingContribution) continue
          with(contribution) {
            bindsFunctions +=
              declaration.generateBindingFunction(metroContext, nameAllocator, boundTypeResolver)
          }
        }
      }
      declaration.dumpToMetroLog()
    }
    transformedContributions += classId
  }

  /**
   * Transforms a top-level contribution provider class by adding bodies to its `@Provides`
   * functions. The function reads `@Origin` to find the contributing class, then generates a
   * constructor call body for each provides function.
   */
  private fun transformTopLevelContributionProvider(declaration: IrClass) {
    val classId = declaration.classIdOrFail
    if (classId in transformedContributions) return

    // @Origin is on the nested contribution interface itself
    val originClassId = declaration.originClassId() ?: return
    val originClass = declaration.lookupClass(originClassId)?.owner ?: return

    val isAssistedFactory by memoize {
      originClass.isAnnotatedWithAny(metroSymbols.classIds.assistedFactoryAnnotations)
    }

    val assistedFactoryTargetConstructor by memoize {
      if (isAssistedFactory) originClass.assistedFactoryTargetConstructor() else null
    }

    // Find the primary constructor of the origin class
    val injectConstructor by memoize {
      originClass.findInjectableConstructor(onlyUsePrimaryConstructor = false)
    }

    // Add bodies to all @Provides functions
    for (function in declaration.functions) {
      if (!function.isAnnotatedWithAny(metroSymbols.classIds.providesAnnotations)) continue
      if (function.body != null) continue

      // Check if this is a wrapper function via FIR attribute
      val isWrapper = function.isContributionProviderWrapper

      function.body =
        context.createIrBuilder(function.symbol).run {
          if (isWrapper) {
            // Wrapper: cast the instance parameter to the return type
            val instanceParam = function.regularParameters.single()
            irExprBodySafe(irImplicitCast(irGet(instanceParam), function.returnType))
          } else if (originClass.isObject) {
            // Object: just reference the singleton instance
            irExprBodySafe(irGetObject(originClass.symbol))
          } else if (isAssistedFactory) {
            irExprBodySafe(
              createAssistedFactoryContribution(
                originClass = originClass,
                targetConstructor = assistedFactoryTargetConstructor!!,
                function = function,
              )
            )
          } else {
            val calleeCtor =
              injectConstructor
                ?: reportCompilerBug(
                  "No inject constructor found in IR for provided contribution ${declaration.fqNameWhenAvailable}"
                )

            copyParameterDefaultValues(
              providerFunction = calleeCtor,
              sourceMetroParameters = Parameters.empty(),
              sourceParameters = calleeCtor.regularParameters,
              targetParameters = function.regularParameters,
              containerParameter = null,
              isTopLevelFunction = true,
            )

            // Constructor call (synthetic scoped or direct)
            val constructorCall =
              irCallConstructor(calleeCtor.symbol, emptyList()).apply {
                val functionParams = function.regularParameters
                for ((index, param) in functionParams.withIndex()) {
                  if (index < calleeCtor.regularParameters.size) {
                    arguments[index] = irGet(param)
                  }
                }
              }
            irExprBodySafe(constructorCall)
          }
        }
    }

    declaration.dumpToMetroLog()
    transformedContributions += classId
  }

  context(scope: IrBuilderWithScope)
  private fun createAssistedFactoryContribution(
    originClass: IrClass,
    targetConstructor: IrConstructor,
    function: IrSimpleFunction,
  ): IrExpression {
    // A contributed assisted factory binds the factory interface, not the assisted target. Reuse
    // the target factory and then wrap it in the generated assisted-factory impl provider.
    val targetClass = originClass.singleAbstractFunction().returnType.rawType()
    val targetParameters =
      targetConstructor
        .parameters()
        .regularParameters
        .filterNot { it.isAssisted }
        .dedupeParameters()
    val functionParams = function.regularParameters.associateBy { it.name }

    copyParameterDefaultValues(
      providerFunction = targetConstructor,
      sourceMetroParameters = targetConstructor.parameters(),
      sourceParameters = targetParameters.map { it.asValueParameter },
      targetParameters = function.regularParameters,
      containerParameter = null,
      isTopLevelFunction = true,
    )

    val delegateFactory =
      injectedClassTransformer
        .getOrGenerateFactory(targetClass, targetConstructor, doNotErrorOnMissing = false)
        ?.invokeCreateExpression(IrTypeKey(targetClass.defaultType)) { _, _ ->
          targetParameters.map { parameter ->
            val functionParam =
              functionParams[parameter.name]
                ?: reportCompilerBug(
                  "No contribution provider parameter ${parameter.name} in ${function.name}. Available: ${functionParams.keys}"
                )
            typeAsProviderArgument(
              contextKey = parameter.contextualTypeKey,
              bindingCode = irGet(functionParam),
              isAssisted = false,
              isGraphInstance = parameter.isGraphInstance,
            )
          }
        }
        ?: reportCompilerBug(
          "No generated target factory found for assisted factory contribution ${originClass.classIdOrFail}"
        )

    val factoryProvider =
      with(assistedFactoryTransformer.getOrGenerateImplClass(originClass)) {
        scope.invokeCreate(delegateFactory)
      }
    return scope.irInvoke(
      dispatchReceiver = factoryProvider,
      callee = metroSymbols.providerInvoke,
      typeHint = function.returnType,
    )
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
      val graphImpl =
        if (options.generateClassesInIr) {
          declaration.getOrCreateGraphImplClassShell()
        } else {
          declaration.requireNestedClass(Origins.GraphImplClassDeclaration)
        }
      graphImpl.addFakeOverrides(irTypeSystemContext)
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

      fun IrClass.generateBindingFunction(
        metroContext: IrMetroContext,
        nameAllocator: NameAllocator,
        boundTypeResolver: IrBoundTypeResolver,
      ): IrSimpleFunction =
        with(metroContext) {
          val (bindingTypeKey, explicitBindingType) =
            boundTypeResolver.resolveBoundType(annotatedType, annotation)
              ?: reportCompilerBug(
                "Could not resolve bound type for ${annotatedType.classIdOrFail}. This should have been caught in FIR."
              )

          val qualifier = explicitBindingType?.qualifier ?: bindingTypeKey.qualifier

          // Original type has the original annotations, if any
          val mapKey =
            explicitBindingType?.originalType?.mapKeyAnnotation()
              ?: annotatedType.mapKeyAnnotation()

          // For map key hashing, use the effective key value. For implicit class keys
          // (sentinel Nothing::class), incorporate the annotated type's class ID instead
          // so that different classes get unique function names.
          val mapKeyHash =
            if (
              mapKey != null &&
                this@BindingContribution is ContributesIntoMapBinding &&
                isImplicitClassKeySentinel(mapKey.ir)
            ) {
              Objects.hash(mapKey.hashCode(), annotatedType.classId).toUInt()
            } else {
              mapKey?.hashCode()?.toUInt()
            }

          val suffix = buildString {
            append("As")
            if (bindingTypeKey.type.isMarkedNullable()) {
              append("Nullable")
            }
            bindingTypeKey.type
              .rawType()
              .classIdOrFail
              .joinSimpleNames(separator = "", camelCase = true)
              .shortClassName
              .let(::append)
            qualifier?.hashCode()?.toUInt()?.let(::append)
            mapKeyHash?.let(::append)
          }

          // We need a unique name because addFakeOverrides() doesn't handle overloads with
          // different return types
          val name = nameAllocator.newName(callableName + suffix).asName()
          addFunction {
              this.name = name
              this.returnType = bindingTypeKey.type
              this.modality = Modality.ABSTRACT
            }
            .apply {
              addAnnotationsCompat(buildAnnotations())
              setDispatchReceiver(parentAsClass.thisReceiver?.copyTo(this))
              addValueParameter(Symbols.Names.instance, annotatedType.defaultType).apply {
                // TODO any qualifiers? What if we want to qualify the instance type but not the
                //  bound type?
              }
              qualifier?.let { addAnnotationCompat(it.ir.deepCopyWithSymbols()) }
              // TODO can we remove this and just rely on the copy in BindsMirrorTransformer?
              if (this@BindingContribution is ContributesIntoMapBinding) {
                mapKey?.let { mk ->
                  val copied = mk.ir.deepCopyWithSymbols()
                  if (isImplicitClassKeySentinel(copied)) {
                    populateImplicitClassKey(copied, annotatedType.defaultType)
                  }
                  addAnnotationCompat(copied)
                }
              }
              metadataDeclarationRegistrarCompat.registerFunctionAsMetadataVisible(this)
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
    for (annotation in contributingSymbol.annotationsCompat()) {
      val annotationClassId = annotation.annotationClass.classId ?: continue
      when (annotationClassId) {
        in contributesToAnnotations -> {
          contributions += Contribution.ContributesTo(contributingSymbol.classIdOrFail, annotation)
        }
        in contributesBindingAnnotations -> {
          contributions +=
            if (annotation.isKiaIntoMultibinding()) {
              Contribution.ContributesIntoSetBinding(contributingSymbol, annotation) {
                listOf(buildIntoSetAnnotation(), buildBindsAnnotation())
              }
            } else {
              Contribution.ContributesBinding(contributingSymbol, annotation) {
                listOf(buildBindsAnnotation())
              }
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

  private fun IrFunction.buildProvidesAnnotation(): IrConstructorCall {
    return buildAnnotation(symbol, metroSymbols.providesConstructor)
  }
}
