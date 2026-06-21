// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.binding
import dev.zacsweers.metro.compiler.MetroAnnotations
import dev.zacsweers.metro.compiler.NameAllocator
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.capitalizeUS
import dev.zacsweers.metro.compiler.exitProcessing
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.generatedClass
import dev.zacsweers.metro.compiler.ir.IrAnnotation
import dev.zacsweers.metro.compiler.ir.IrCallableMetadata
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrInlinedProvider
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrScope
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.MemberNamer
import dev.zacsweers.metro.compiler.ir.MetroSimpleFunction
import dev.zacsweers.metro.compiler.ir.ProviderFactory
import dev.zacsweers.metro.compiler.ir.ProviderFactory.Companion.lookupRealDeclaration
import dev.zacsweers.metro.compiler.ir.addBackingFieldTo
import dev.zacsweers.metro.compiler.ir.addHiddenFromObjCAnnotation
import dev.zacsweers.metro.compiler.ir.allocateName
import dev.zacsweers.metro.compiler.ir.annotationClass
import dev.zacsweers.metro.compiler.ir.annotationsIn
import dev.zacsweers.metro.compiler.ir.buildAnnotation
import dev.zacsweers.metro.compiler.ir.createIrBuilder
import dev.zacsweers.metro.compiler.ir.createMetroMetadata
import dev.zacsweers.metro.compiler.ir.deepRemapperFor
import dev.zacsweers.metro.compiler.ir.dispatchReceiverFor
import dev.zacsweers.metro.compiler.ir.finalizeFakeOverride
import dev.zacsweers.metro.compiler.ir.findAnnotations
import dev.zacsweers.metro.compiler.ir.generateDefaultConstructorBody
import dev.zacsweers.metro.compiler.ir.graph.IrBinding
import dev.zacsweers.metro.compiler.ir.implements
import dev.zacsweers.metro.compiler.ir.includedClasses
import dev.zacsweers.metro.compiler.ir.irCallableMetadata
import dev.zacsweers.metro.compiler.ir.irExprBodySafe
import dev.zacsweers.metro.compiler.ir.irInvoke
import dev.zacsweers.metro.compiler.ir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.ir.isBindingContainer
import dev.zacsweers.metro.compiler.ir.isCompanionObject
import dev.zacsweers.metro.compiler.ir.isExternalParent
import dev.zacsweers.metro.compiler.ir.lookupClass
import dev.zacsweers.metro.compiler.ir.metroAnnotationsOf
import dev.zacsweers.metro.compiler.ir.metroDumpKotlinLike
import dev.zacsweers.metro.compiler.ir.metroFunctionOf
import dev.zacsweers.metro.compiler.ir.metroGraphOrNull
import dev.zacsweers.metro.compiler.ir.metroMetadata
import dev.zacsweers.metro.compiler.ir.originOrNull
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.dedupeParameters
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.parametersAsProviderArguments
import dev.zacsweers.metro.compiler.ir.rawTypeOrNull
import dev.zacsweers.metro.compiler.ir.regularParameters
import dev.zacsweers.metro.compiler.ir.reportCompat
import dev.zacsweers.metro.compiler.ir.requireSimpleFunction
import dev.zacsweers.metro.compiler.ir.setDispatchReceiver
import dev.zacsweers.metro.compiler.ir.subcomponentsArgument
import dev.zacsweers.metro.compiler.ir.thisReceiverOrFail
import dev.zacsweers.metro.compiler.ir.toClassReferences
import dev.zacsweers.metro.compiler.ir.toProto
import dev.zacsweers.metro.compiler.ir.transformIfIntoMultibinding
import dev.zacsweers.metro.compiler.ir.writeDiagnostic
import dev.zacsweers.metro.compiler.isPlatformType
import dev.zacsweers.metro.compiler.mapNotNullToSet
import dev.zacsweers.metro.compiler.mapToSet
import dev.zacsweers.metro.compiler.memoize
import dev.zacsweers.metro.compiler.metroAnnotations
import dev.zacsweers.metro.compiler.proto.DependencyGraphProto
import dev.zacsweers.metro.compiler.proto.ProviderFactoryProto
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.symbols.DaggerSymbols
import dev.zacsweers.metro.compiler.symbols.Symbols
import dev.zacsweers.metro.compiler.tracing.TraceScope
import dev.zacsweers.metro.compiler.tracing.trace
import java.util.EnumSet
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import kotlin.jvm.optionals.getOrNull
import org.jetbrains.kotlin.backend.jvm.ir.getJvmNameFromAnnotation
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrOverridableDeclaration
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeAlias
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.types.typeWithParameters
import org.jetbrains.kotlin.ir.util.TypeRemapper
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.addSimpleDelegatingConstructor
import org.jetbrains.kotlin.ir.util.callableId
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.copyTypeParametersFrom
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isFakeOverride
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.util.isPropertyAccessor
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.nestedClasses
import org.jetbrains.kotlin.ir.util.packageFqName
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.propertyIfAccessor
import org.jetbrains.kotlin.ir.util.simpleFunctions
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.platform.jvm.isJvm

@Inject
@SingleIn(IrScope::class)
@ContributesIntoSet(IrScope::class, binding<Lockable>())
internal class BindingContainerTransformer(
  context: IrMetroContext,
  private val bindsMirrorClassTransformer: BindsMirrorClassTransformer,
  traceScope: TraceScope,
) : IrMetroContext by context, TraceScope by traceScope, Lockable by Lockable() {

  // Thread-safe for concurrent access during parallel graph validation.
  private val references = ConcurrentHashMap<CallableId, CallableReference>()
  private val generatedFactories = ConcurrentHashMap<CallableId, ProviderFactory>()

  /**
   * A cache of binding container fqnames to a [BindingContainer] representation of them. If the key
   * is present but the value is an empty optional, it means this is just not a binding container.
   *
   * Thread-safe for concurrent access during parallel graph validation.
   */
  private val cache = ConcurrentHashMap<FqName, Optional<BindingContainer>>()

  @IgnorableReturnValue
  fun findContainer(
    declaration: IrClass,
    declarationFqName: FqName = declaration.kotlinFqName,
    graphProto: DependencyGraphProto? = null,
  ): BindingContainer? {
    cache[declarationFqName]?.let {
      return it.getOrNull()
    }

    // Platform types (java.*, kotlin.*, android.*, etc.) can never be binding containers.
    // Short-circuit before we start iterating declarations / computing binds mirrors / etc.
    // This matters in callers that walk class hierarchies or module declarations, where
    // platform classes show up repeatedly and each first-touch would otherwise do real work.
    if (declaration.classId?.isPlatformType() == true) {
      cache.putIfAbsent(declarationFqName, Optional.empty())
      return null
    }

    if (declaration.isExternalParent) {
      return loadExternalBindingContainer(declaration, declarationFqName, graphProto)
    } else if (declaration.name == Symbols.Names.BindsMirrorClass) {
      cache.putIfAbsent(declarationFqName, Optional.empty())
      return null
    }

    val providerFactories = mutableMapOf<CallableId, ProviderFactory>()

    val graphAnnotation =
      declaration.annotationsIn(metroSymbols.classIds.graphLikeAnnotations).firstOrNull()
    val isContributedGraph =
      (graphAnnotation?.annotationClass?.classId in
        metroSymbols.classIds.graphExtensionAnnotations) &&
        declaration.isAnnotatedWithAny(metroSymbols.classIds.contributesToAnnotations)
    val isGraph = graphAnnotation != null

    trace("Iterate declarations") {
      declaration.declarations
        .toList() // Defensive copy as we may add to this list if we generate provider factories
        .asSequence()
        // Skip (fake) overrides, we care only about the original declaration because those have
        // default values
        .filterNot { it.isFakeOverride }
        .filterNot { it is IrConstructor || it is IrTypeAlias }
        .forEach { nestedDeclaration ->
          when (nestedDeclaration) {
            is IrProperty -> {
              val getter = nestedDeclaration.getter ?: return@forEach
              val metroFunction =
                trace("metroFunctionOf property ${nestedDeclaration.name}") {
                  metroFunctionOf(getter)
                }
              if (metroFunction.annotations.isProvides) {
                providerFactories[nestedDeclaration.callableId] =
                  trace("visitProperty ${nestedDeclaration.name}") {
                    visitProperty(nestedDeclaration, metroFunction)
                  }
              }
            }
            is IrSimpleFunction -> {
              val metroFunction =
                trace("metroFunctionOf function ${nestedDeclaration.name}") {
                  metroFunctionOf(nestedDeclaration)
                }
              if (metroFunction.annotations.isProvides) {
                providerFactories[nestedDeclaration.callableId] =
                  trace("visitFunction ${nestedDeclaration.name}") {
                    visitFunction(nestedDeclaration, metroFunction)
                  }
              }
            }
            is IrClass if
              (nestedDeclaration.isCompanionObject &&
                !(isGraph && nestedDeclaration.implements(declaration.classIdOrFail)))
             -> {
              // Include companion object refs
              trace("Companion recursion ${nestedDeclaration.name}") {
                findContainer(nestedDeclaration)?.providerFactories?.let {
                  providerFactories.putAll(it.values.associateBy { it.callableId })
                }
              }
            }
          }
        }
    }

    val bindingContainerAnnotation =
      declaration.annotationsIn(metroSymbols.classIds.bindingContainerAnnotations).singleOrNull()
    val includes =
      bindingContainerAnnotation?.includedClasses()?.mapNotNullToSet {
        it.classType.rawTypeOrNull()?.classIdOrFail
      }

    val bindsMirror =
      trace("Compute binds mirror") {
        bindsMirrorClassTransformer.getOrComputeBindsMirror(declaration)
      }

    val container =
      BindingContainer(
        isGraph = isGraph,
        canBeManaged =
          bindingContainerAnnotation != null &&
            declaration.isManageableBindingContainerClass(requireFinal = true),
        ir = declaration,
        includes = includes.orEmpty(),
        providerFactories = providerFactories,
        bindsMirror = bindsMirror,
      )

    // If it's got providers but _not_ a @DependencyGraph, generate factory information onto this
    // class's metadata. This allows consumers in downstream compilations to know if there are
    // providers to consume here even if they are private.
    // We always generate metadata for binding containers because they can be included in graphs
    // without inheritance
    val shouldGenerateMetadata =
      bindingContainerAnnotation != null || isContributedGraph || !container.isEmpty()

    if (shouldGenerateMetadata) {
      trace("Generate metadata") {
        checkNotLocked()
        val metroMetadata =
          createMetroMetadata(
            dependency_graph = container.toProto(generateClassesInIr = options.generateClassesInIr)
          )
        declaration.metroMetadata = metroMetadata
      }
    }

    return if (container.isEmpty() && bindingContainerAnnotation == null) {
      cache.putIfAbsent(declarationFqName, Optional.empty())
      null
    } else {
      // putIfAbsent avoids overwriting if a concurrent caller already populated the slot.
      val existing = cache.putIfAbsent(declarationFqName, Optional.of(container))
      generatedFactories.putAll(providerFactories)
      existing?.getOrNull() ?: container
    }
  }

  private fun visitProperty(
    declaration: IrProperty,
    metroFunction: MetroSimpleFunction,
  ): ProviderFactory {
    val reference =
      trace("getOrPutCallableReference") {
        getOrPutCallableReference(declaration, metroFunction.annotations)
      }
    return trace("getOrLookupProviderFactory") { getOrLookupProviderFactory(reference) }
  }

  private fun visitFunction(
    declaration: IrSimpleFunction,
    metroFunction: MetroSimpleFunction,
  ): ProviderFactory {
    val reference =
      trace("getOrPutCallableReference") {
        getOrPutCallableReference(declaration, declaration.parentAsClass, metroFunction.annotations)
      }
    return trace("getOrLookupProviderFactory") { getOrLookupProviderFactory(reference) }
  }

  fun getOrLookupProviderFactory(binding: IrBinding.Provided): ProviderFactory? {
    // Eager cache check using the factory's callable ID
    generatedFactories[binding.providerFactory.callableId]?.let {
      return it
    }

    // If the parent hasn't been checked before, visit it and look again
    // Note the parent may be just a package if this is a Dagger-generated module provider
    val parent = binding.providerFactory.factoryClass.parent
    if (parent is IrClass) {
      @Suppress("RETURN_VALUE_NOT_USED")
      findContainer(binding.providerFactory.factoryClass.parentAsClass)
    }

    // If it's still not present after, there's nothing here
    return generatedFactories[binding.providerFactory.callableId]
  }

  fun getOrLookupProviderFactory(reference: CallableReference): ProviderFactory {
    generatedFactories[reference.callableId]?.let {
      return it
    }

    checkNotLocked()

    val generatedClassId = reference.generatedClassId

    val factoryCls =
      trace("Find factory class") {
        reference.parent.owner.nestedClasses.singleOrNull {
          it.origin == Origins.ProviderFactoryClassDeclaration &&
            it.classIdOrFail == generatedClassId
        }
          ?: run {
            val parentClass = reference.parent.owner
            if (parentClass.shouldGenerateProviderFactoryInIr()) {
              createContributionProviderFactory(parentClass, generatedClassId, reference)
            } else {
              reportCompilerBug(
                "No expected factory class generated for ${reference.callableId}. Report this bug with a repro case at https://github.com/zacsweers/metro/issues/new"
              )
            }
          }
      }

    val invokeFunction =
      trace("Add factory supertype + invoke shell") {
        // Add factory supertype. It won't be visible in metadata but that's ok, we don't need to
        // read directly since we'll read the mirror function to get the target type
        factoryCls.superTypes += metroSymbols.metroFactory.typeWith(reference.typeKey.type)
        // Cannot call addFakeOverrides because FIR2IR has already done that, so we need to add
        // the invoke override directly later
        factoryCls
          .addFunction(Symbols.StringNames.INVOKE, reference.typeKey.type, isFakeOverride = true)
          .apply {
            overriddenSymbols = listOf(metroSymbols.providerInvoke)
            isOperator = true
          }
          .also {
            addHiddenFromObjCAnnotation(it)
            if (
              options.generateClassesInIr ||
                !reference.parent.owner.hasAnnotation(Symbols.ClassIds.irOnlyFactories)
            ) {
              metadataDeclarationRegistrarCompat.registerFunctionAsMetadataVisible(it)
            }
          }
      }

    val sourceParameters =
      trace("Copy source parameters") {
        reference.parameters.copy(
          dispatchReceiverParameter = null,
          regularParameters =
            buildList {
              reference.parameters.dispatchReceiverParameter?.let {
                add(
                  it.copy(
                    kind = IrParameterKind.Regular,
                    ir =
                      it.asValueParameter.deepCopyWithSymbols(reference.parameters.ir).apply {
                        this.name = Symbols.Names.instance
                        this.origin = Origins.InstanceParameter
                      },
                  )
                )
              }
              addAll(reference.parameters.regularParameters)
            },
        )
      }

    // De-duped source params used by the constructor and create() function
    val dedupedSourceParameters =
      trace("Dedupe parameters") {
        sourceParameters.copy(
          regularParameters = sourceParameters.regularParameters.dedupeParameters()
        )
      }

    // Use parameter name as the primary field key to correctly handle multiple parameters
    // with the same type key (e.g., two String params with different defaults).
    // The typeKey map is kept as a fallback for dedup cases.
    val nameToField = mutableMapOf<Name, IrField>()
    val typeKeyToField = mutableMapOf<IrTypeKey, IrField>()
    val ctor: IrConstructor
    if (factoryCls.isObject) {
      // If it's got no parameters we'll generate it in FIR as an object
      ctor = factoryCls.primaryConstructor!!
    } else {
      ctor =
        factoryCls.primaryConstructor
          ?: factoryCls.addConstructor {
            visibility = DescriptorVisibilities.PRIVATE
            isPrimary = true
          }

      trace("Build factory constructor") {
        ctor.apply {
          val ownerClass = reference.parent.owner
          val typeRemapper = ownerClass.deepRemapperFor(factoryCls.defaultType)
          val fieldNameAllocator = NameAllocator(mode = NameAllocator.Mode.COUNT)
          addParameters(
            params = dedupedSourceParameters.allParameters,
            wrapInProvider = true,
            stubDefaults = false,
            typeRemapper = { type -> typeRemapper.remapType(type) },
          ) { typeKey, irParam ->
            val fieldName =
              fieldNameAllocator.allocateName(memberNamer, MemberNamer.Kind.PROVIDER) {
                irParam.name.asString()
              }
            val field = irParam.addBackingFieldTo(factoryCls, fieldName)
            nameToField[irParam.name] = field
            typeKeyToField[typeKey] = field
          }
          addHiddenFromObjCAnnotation(this)
          body = generateDefaultConstructorBody()
        }
      }
    }

    val inlinedValue = reference.inlinedValueIfEnabled()
    val bytecodeFunction =
      trace("Implement creator bodies") {
        implementCreatorBodies(factoryCls, ctor.symbol, reference, dedupedSourceParameters)
      }

    // Implement invoke()
    // TODO DRY this up with the constructor injection override
    trace("Implement invoke") {
      invokeFunction.finalizeFakeOverride(factoryCls.thisReceiverOrFail)
      invokeFunction.body =
        pluginContext.createIrBuilder(invokeFunction.symbol).run {
          irExprBodySafe(
            irInvoke(
              dispatchReceiver = dispatchReceiverFor(bytecodeFunction),
              callee = bytecodeFunction.symbol,
              args =
                parametersAsProviderArguments(
                  parameters = sourceParameters,
                  receiver = invokeFunction.dispatchReceiverParameter!!,
                  fields = typeKeyToField,
                  nameToField = nameToField,
                ),
            )
          )
        }
    }

    // Generate a metadata-visible function that matches the signature of the target provider
    // This is used in downstream compilations to read the provider's signature
    val sourceFunction = reference.callee?.owner as? IrSimpleFunction
    val mirrorFunction =
      trace("Generate mirror function") {
        generateMetadataVisibleMirrorFunction(
          factoryClass = factoryCls,
          target = sourceFunction,
          backingField = reference.backingField,
          annotations = reference.annotations,
          registerAsMetadataVisible =
            options.generateClassesInIr ||
              !reference.parent.owner.hasAnnotation(Symbols.ClassIds.irOnlyFactories),
        )
      }

    // For in-compilation, use direct reference to source function to avoid round-tripping
    // through @CallableMetadata annotation
    val callableMetadata =
      trace("Build callable metadata") {
        if (sourceFunction != null) {
          IrCallableMetadata.forInCompilation(
            sourceFunction = sourceFunction,
            mirrorFunction = mirrorFunction,
            annotations = reference.annotations,
            isPropertyAccessor = reference.isPropertyAccessor,
          )
        } else if (reference.backingField != null) {
          val sourceFunction =
            mirrorFunction.deepCopyWithSymbols().apply {
              name = reference.callableId.callableName
              setDispatchReceiver(reference.parent.owner.thisReceiverOrFail.copyTo(this))
              parent = reference.parent.owner
            }
          IrCallableMetadata(
            callableId = reference.callableId,
            mirrorCallableId = mirrorFunction.callableId,
            annotations = reference.annotations,
            isPropertyAccessor = reference.isPropertyAccessor,
            newInstanceName = reference.name,
            function = sourceFunction,
            mirrorFunction = mirrorFunction,
          )
        } else {
          factoryCls.irCallableMetadata(mirrorFunction, reference.annotations, isInterop = false)
        }
      }

    // For in-compilation, we already have the real declaration from the reference
    val realDeclaration = reference.callee?.owner ?: reference.backingField

    val providerFactory =
      trace("Construct ProviderFactory") {
        ProviderFactory(
          contextKey = IrContextualTypeKey.from(mirrorFunction),
          clazz = factoryCls,
          mirrorFunction = mirrorFunction,
          sourceAnnotations = reference.annotations,
          callableMetadata = callableMetadata,
          realDeclaration = realDeclaration,
          inlinedValue = inlinedValue,
          computeInlinedValue = false,
        ) ?: exitProcessing()
      }

    markComptimeOnlyIfInlined(providerFactory)

    factoryCls.dumpToMetroLog()

    trace("Write provider-factory diagnostic") {
      val factoryPath =
        factoryCls.packageFqName?.let { packageName ->
          val fileName = factoryCls.kotlinFqName.toString().replace("$packageName.", "")
          "${packageName.pathSegments().joinToString("/")}/$fileName"
        } ?: factoryCls.kotlinFqName.asString()

      // Relative path example: provider-factories/dev/zac/feature/Outer.Inner$$Factory.kt
      writeDiagnostic("provider-factories", "$factoryPath.kt") { factoryCls.metroDumpKotlinLike() }
    }

    generatedFactories[reference.callableId] = providerFactory
    return providerFactory
  }

  private fun getOrPutCallableReference(
    function: IrSimpleFunction,
    parent: IrClass,
    annotations: MetroAnnotations<IrAnnotation>,
  ): CallableReference {
    return references.computeIfAbsent(function.callableId) {
      val typeKey =
        trace("IrContextualTypeKey.from(function)") { IrContextualTypeKey.from(function).typeKey }
      val isPropertyAccessor = function.isPropertyAccessor
      val callableId =
        if (isPropertyAccessor) {
          function.propertyIfAccessor.expectAs<IrProperty>().callableId
        } else {
          function.callableId
        }
      val parameters = trace("function.parameters()") { function.parameters() }
      CallableReference(
        callableId = callableId,
        name = function.name,
        isPropertyAccessor = isPropertyAccessor,
        parameters = parameters,
        typeKey = typeKey,
        isNullable = typeKey.type.isMarkedNullable(),
        parent = parent.symbol,
        callee = function.symbol,
        backingField = null,
        annotations = annotations,
      )
    }
  }

  private fun getOrPutCallableReference(
    property: IrProperty,
    annotations: MetroAnnotations<IrAnnotation> = metroAnnotationsOf(property),
  ): CallableReference {
    val callableId = property.callableId
    return references.computeIfAbsent(callableId) {
      val parent = property.parentAsClass

      // Check if property has @JvmField - if so, we use backing field instead of getter
      val backingField = property.backingField
      val hasJvmField = backingField?.hasAnnotation(Symbols.ClassIds.JvmField) == true

      // Prefer getter if available, otherwise use backing field
      val getter = property.getter
      val callee: IrFunctionSymbol?
      val useBackingField: IrField?

      if (getter != null && !hasJvmField) {
        // Use getter
        callee = getter.symbol
        useBackingField = null
      } else if (backingField != null) {
        // Use backing field (for @JvmField or no getter)
        callee = null
        useBackingField = backingField
      } else {
        reportCompilerBug("No getter or backing field found for property $callableId.")
      }

      val typeKey =
        if (getter != null) {
          IrContextualTypeKey.from(getter).typeKey
        } else {
          IrTypeKey(backingField!!.type)
        }

      CallableReference(
        callableId = callableId,
        name = property.name,
        isPropertyAccessor = true,
        parameters = getter?.parameters() ?: Parameters.empty(),
        typeKey = typeKey,
        isNullable = typeKey.type.isMarkedNullable(),
        parent = parent.symbol,
        callee = callee,
        backingField = useBackingField,
        annotations = annotations,
      )
    }
  }

  private fun implementCreatorBodies(
    factoryCls: IrClass,
    factoryConstructor: IrConstructorSymbol,
    reference: CallableReference,
    factoryParameters: Parameters,
  ): IrSimpleFunction {
    // If this is an object, we can generate directly into this object
    val isObject = factoryCls.kind == ClassKind.OBJECT
    val classToGenerateCreatorsIn =
      if (isObject) {
        factoryCls
      } else {
        factoryCls.companionObject()!!
      }

    // Generate create()
    @Suppress("RETURN_VALUE_NOT_USED")
    generateStaticCreateFunction(
      objectClassToGenerateIn = classToGenerateCreatorsIn,
      factoryClass = factoryCls,
      targetConstructor = factoryConstructor,
      parameters = factoryParameters,
      sourceFunction = reference.callee?.owner,
      returnTypeProvider = { typeParams ->
        factoryCls.symbol.typeWithParameters(typeParams)
      },
      sourceTypeParameters = reference.parent.owner,
    )

    // Generate the named newInstance function
    val newInstanceFunction =
      generateStaticNewInstanceFunction(
        parentClass = classToGenerateCreatorsIn,
        factoryClass = factoryCls,
        targetFunction = reference.callee?.owner,
        sourceMetroParameters = reference.parameters,
        sourceParameters = reference.parameters.regularParameters.map { it.asValueParameter },
        sourceTypeParameters = reference.parent.owner,
        returnTypeProvider = { reference.typeKey.type },
        functionName = reference.name.asString(),
      ) { function ->
        val parameters = function.regularParameters

        val dispatchReceiver =
          if (reference.isInObject) {
            null
          } else {
            // Instance graph call
            // exampleGraph.$callableName$arguments
            irGet(parameters[0])
          }

        if (reference.backingField != null) {
          // Backing field case - read field directly
          irGetField(dispatchReceiver, reference.backingField)
        } else {
          // Function call case
          val callParameters = parameters.filter { it.origin == Origins.RegularParameter }
          var index = 0
          val extensionReceiver =
            reference.parameters.extensionReceiverParameter?.let { irGet(callParameters[index++]) }
          val contextArgs =
            reference.parameters.contextParameters.map { irGet(callParameters[index++]) }
          val args = reference.parameters.regularParameters.map { irGet(callParameters[index++]) }
          irInvoke(
            dispatchReceiver = dispatchReceiver,
            extensionReceiver = extensionReceiver,
            callee = reference.callee!!,
            contextArgs = contextArgs,
            args = args,
          )
        }
      }

    return newInstanceFunction
  }

  private fun CallableReference.inlinedValueIfEnabled(): IrInlinedProvider? {
    if (!options.enableProviderInlining) return null
    return IrInlinedProvider.fromProviderFactory(
      annotations = annotations,
      parameters = parameters,
      realDeclaration = callee?.owner ?: backingField,
    )
  }

  internal class CallableReference(
    val callableId: CallableId,
    val name: Name,
    val isPropertyAccessor: Boolean,
    val parameters: Parameters,
    val typeKey: IrTypeKey,
    val isNullable: Boolean,
    val parent: IrClassSymbol,
    /**
     * The function to call (getter for properties, function itself for functions). Null if
     * [backingField] is used instead.
     */
    val callee: IrFunctionSymbol?,
    /**
     * The backing field to read from (for @JvmField properties). Null if [callee] is used instead.
     */
    val backingField: IrField?,
    val annotations: MetroAnnotations<IrAnnotation>,
  ) {
    val isInObject: Boolean
      get() = parent.owner.isObject

    val containerClass =
      if (parent.owner.isCompanionObject) {
        parent.owner.parentAsClass
      } else {
        parent.owner
      }

    private val simpleName by lazy {
      buildString {
        append(name.capitalizeUS())
        append(Symbols.Names.MetroFactory.asString())
      }
    }

    val generatedClassId by lazy {
      parent.owner.classIdOrFail.createNestedClassId(Name.identifier(simpleName))
    }

    private val cachedToString by lazy {
      buildString {
        append(callableId.asSingleFqName().asString())
        if (!isPropertyAccessor) {
          append('(')
          for (parameter in parameters.allParameters) {
            append('(')
            append(parameter.kind)
            append(')')
            append(parameter.name)
            append(": ")
            append(parameter.typeKey)
          }
          append(')')
        }
        append(": ")
        append(typeKey.toString())
      }
    }

    override fun toString(): String = cachedToString

    companion object // For extension
  }

  fun factoryClassesFor(parent: IrClass): List<Pair<IrTypeKey, ProviderFactory>> {
    val container = findContainer(parent)
    return container?.providerFactories.orEmpty().values.map { providerFactory ->
      providerFactory.typeKey to providerFactory
    }
  }

  private fun externalProviderFactoryFor(
    factoryCls: IrClass,
    entry: ProviderFactoryProto,
  ): ProviderFactory.Metro {
    // Extract IrTypeKey from Factory supertype
    // Qualifier will be populated in ProviderFactory construction
    val mirrorFunction = factoryCls.requireSimpleFunction(Symbols.StringNames.MIRROR_FUNCTION).owner
    val sourceAnnotations = mirrorFunction.metroAnnotations(metroSymbols.classIds)
    val callableMetadata =
      factoryCls.irCallableMetadata(mirrorFunction, sourceAnnotations, isInterop = false)
    val contextKey = IrContextualTypeKey.from(mirrorFunction)
    return ProviderFactory(
      contextKey,
      factoryCls,
      mirrorFunction,
      sourceAnnotations,
      callableMetadata,
      inlinedValue = entry.inlinedValueIfEnabled(),
      computeInlinedValue = false,
    ) ?: exitProcessing()
  }

  private fun loadExternalBindingContainer(
    declaration: IrClass,
    declarationFqName: FqName,
    graphProto: DependencyGraphProto?,
  ): BindingContainer? {
    // Cache check intentionally omitted: the only caller ([findContainer]) has already looked
    // up the cache before delegating here.

    // Look up the external class metadata
    val metadataDeclaration = declaration.metroGraphOrNull ?: declaration
    val graphProto = graphProto ?: metadataDeclaration.metroMetadata?.dependency_graph

    if (graphProto == null) {
      if (options.enableDaggerRuntimeInterop) {
        val moduleAnno =
          declaration.findAnnotations(DaggerSymbols.ClassIds.DAGGER_MODULE).firstOrNull()

        if (moduleAnno != null) {
          // It's a dagger module! Iterate over its Provides and Binds
          // Add any provider factories
          val providerFactories = mutableMapOf<CallableId, ProviderFactory.Dagger>()
          val bindsCollector = BindsMirrorCollector(isInterop = true)

          for (decl in declaration.declarations) {
            if (decl !is IrSimpleFunction && decl !is IrProperty) continue

            val annotations =
              decl.metroAnnotations(
                metroSymbols.classIds,
                kinds =
                  EnumSet.of(
                    MetroAnnotations.Kind.Provides,
                    MetroAnnotations.Kind.Binds,
                    MetroAnnotations.Kind.Multibinds,
                    MetroAnnotations.Kind.BindsOptionalOf,
                    MetroAnnotations.Kind.IntoSet,
                    MetroAnnotations.Kind.ElementsIntoSet,
                    MetroAnnotations.Kind.IntoMap,
                    MetroAnnotations.Kind.MapKey,
                  ),
              )
            if (
              annotations.isProvides ||
                annotations.isBinds ||
                annotations.isMultibinds ||
                annotations.isBindsOptionalOf
            ) {
              val isProperty = decl is IrProperty || decl.isPropertyAccessor
              val callableId: CallableId
              val contextKey: IrContextualTypeKey
              val parameters: Parameters
              val function: IrFunction
              when (decl) {
                is IrProperty -> {
                  callableId = decl.callableId
                  contextKey = IrContextualTypeKey.from(decl.getter!!)
                  parameters =
                    if (annotations.isBinds) Parameters.empty() else decl.getter!!.parameters()
                  function = decl.getter!!
                }
                is IrSimpleFunction -> {
                  callableId =
                    if (decl.isPropertyAccessor) {
                      decl.propertyIfAccessor.expectAs<IrProperty>().callableId
                    } else {
                      decl.callableId
                    }
                  contextKey = IrContextualTypeKey.from(decl)
                  parameters = if (annotations.isBinds) Parameters.empty() else decl.parameters()
                  function = decl
                }
              }

              if (annotations.isProvides) {
                // Look up the expected provider factory class
                // Try both with and without the declaration's `@JvmName` (if present). Dagger
                // doesn't seem to read this in KSP but would implicitly in KAPT
                val factoryClassWithoutJvmName =
                  declaration.lookupClass(daggerFactoryClassIdOf(decl, useJvmName = false))
                val factoryClassWithJvmName =
                  declaration.lookupClass(daggerFactoryClassIdOf(decl, useJvmName = true))

                val factoryClass = factoryClassWithoutJvmName ?: factoryClassWithJvmName
                val factoryClassUsesJvmName =
                  factoryClassWithoutJvmName == null && factoryClassWithJvmName != null

                val newInstanceNameFromFactory =
                  factoryClass?.owner?.daggerProviderNewInstanceNameOrNull()
                val newInstanceName =
                  if (newInstanceNameFromFactory != null) {
                    newInstanceNameFromFactory
                  } else {
                    daggerProviderFunctionNameOf(decl, useJvmName = factoryClassUsesJvmName)
                  }

                if (factoryClass == null) {
                  reportCompat(
                    decl,
                    MetroDiagnostics.METRO_ERROR,
                    "Couldn't find Dagger-generated provider factory class for $declaration.$decl",
                  )
                  // Cache the failure so subsequent lookups don't re-report the same diagnostic.
                  cache.putIfAbsent(declarationFqName, Optional.empty())
                  return null
                }
                val transformedTypeKey = contextKey.typeKey.transformIfIntoMultibinding(annotations)

                providerFactories[callableId] =
                  ProviderFactory.Dagger(
                    factoryClass = factoryClass.owner,
                    typeKey = transformedTypeKey,
                    contextualTypeKey = contextKey.withIrTypeKey(transformedTypeKey),
                    rawTypeKey = contextKey.typeKey,
                    callableId = callableId,
                    annotations = annotations,
                    parameters = parameters,
                    function = function,
                    isPropertyAccessor = isProperty,
                    realDeclaration = lookupRealDeclaration(isProperty, function) as IrFunction,
                    newInstanceName = newInstanceName,
                  )
              } else {
                // binds or multibinds or bindsOptionalOf
                val function = metroFunctionOf(function, annotations)
                bindsCollector += function
              }
            }
          }

          val includedModules =
            moduleAnno.includedClasses().mapNotNullToSet {
              it.classType.rawTypeOrNull()?.classIdOrFail
            }

          // If subcomponents isn't empty, report a warning
          val subcomponents = moduleAnno.subcomponentsArgument()?.toClassReferences().orEmpty()
          if (subcomponents.isNotEmpty()) {
            reportCompat(
              declaration,
              MetroDiagnostics.METRO_WARNING,
              "Included Dagger module '${declarationFqName}' declares a `subcomponents` parameter but this will be ignored by Metro in interop.",
            )
          }

          val container =
            BindingContainer(
              isGraph = false,
              canBeManaged = declaration.isManageableBindingContainerClass(requireFinal = false),
              ir = declaration,
              includes = includedModules,
              providerFactories = providerFactories,
              bindsMirror = bindsCollector.buildMirror(declaration),
            )
          val existing = cache.putIfAbsent(declarationFqName, Optional.of(container))
          generatedFactories.putAll(providerFactories)
          return existing?.getOrNull() ?: container
        }
      }

      val requireMetadata =
        declaration.isAnnotatedWithAny(metroSymbols.classIds.dependencyGraphAnnotations) ||
          declaration.isBindingContainer()
      if (requireMetadata) {
        val message =
          "No metadata found for ${metadataDeclaration.kotlinFqName} from " +
            "another module. Did you run the Metro compiler plugin on this module?"
        reportCompat(declaration, MetroDiagnostics.METRO_ERROR, message)
        // Cache the failure so subsequent lookups don't re-report the same diagnostic.
        cache.putIfAbsent(declarationFqName, Optional.empty())
        return null
      }
      cache.putIfAbsent(declarationFqName, Optional.empty())
      return null
    }

    // Add any provider factories
    val providerFactories =
      trace("Load external provider factories") {
        graphProto.provider_factories
          .mapNotNull { entry ->
            val classId = ClassId.fromString(entry.class_id)
            if (entry.invisible) {
              if (options.generateClassesInIr) {
                reportCompat(
                  declaration,
                  MetroDiagnostics.METRO_ERROR,
                  "Invisible provider factory metadata for $classId is not supported when " +
                    "generateClassesInIr is enabled. Recompile upstream modules with the same " +
                    "Metro compiler options.",
                )
                return@mapNotNull null
              }
              val providerFactory =
                trace("Load invisible factory ${classId.shortClassName}") {
                  loadInvisibleProviderFactory(declaration, classId, entry)
                } ?: return@mapNotNull null
              providerFactory.callableId to providerFactory
            } else {
              trace("External factory ${classId.shortClassName}") {
                val factoryClass = declaration.lookupClass(classId)!!.owner
                val providerFactory = externalProviderFactoryFor(factoryClass, entry)
                providerFactory.callableId to providerFactory
              }
            }
          }
          .toMap()
      }

    // Add any binds callables
    val bindsMirror =
      trace("Compute external binds mirror") {
        bindsMirrorClassTransformer.getOrComputeBindsMirror(declaration)
      }
    val includedBindingContainers =
      graphProto.included_binding_containers.mapToSet { ClassId.fromString(it) }

    val container =
      BindingContainer(
        isGraph = graphProto.is_graph,
        canBeManaged =
          declaration.isBindingContainer() &&
            declaration.isManageableBindingContainerClass(requireFinal = true),
        ir = declaration,
        includes = includedBindingContainers,
        providerFactories = providerFactories,
        bindsMirror = bindsMirror,
      )

    // Cache the results (putIfAbsent so a concurrent caller doesn't lose their entry).
    val existing = cache.putIfAbsent(declarationFqName, Optional.of(container))
    generatedFactories.putAll(providerFactories)

    return existing?.getOrNull() ?: container
  }

  /**
   * Loads an invisible provider factory from proto metadata. Creates a stub class with a mirror
   * function and builds a [ProviderFactory] from proto values without needing a `@CallableMetadata`
   * annotation.
   */
  private fun loadInvisibleProviderFactory(
    container: IrClass,
    classId: ClassId,
    entry: ProviderFactoryProto,
  ): ProviderFactory.Metro? {
    val providesFunction = findProvidesForInvisibleFactory(container, entry.callable_name)

    val existingFactory = container.lookupClass(classId)?.owner
    val stub =
      existingFactory
        ?: createContributionProviderFactoryStub(container, classId, isObject = entry.is_object)

    val mirrorFunction =
      if (existingFactory != null) {
        existingFactory.requireSimpleFunction(Symbols.StringNames.MIRROR_FUNCTION).owner
      } else {
        val target = providesFunction ?: return null
        generateMetadataVisibleMirrorFunction(
          factoryClass = stub,
          target = target,
          backingField = null,
          annotations = target.metroAnnotations(metroSymbols.classIds),
          registerAsMetadataVisible = false,
        )
      }

    val sourceFunction =
      providesFunction
        ?: mirrorFunction.deepCopyWithSymbols().apply {
          name = Name.identifier(entry.callable_name)
          setDispatchReceiver(container.thisReceiverOrFail.copyTo(this))
          parent = container
        }
    val sourceAnnotations = sourceFunction.metroAnnotations(metroSymbols.classIds)

    // Add creator functions to the stub so IrMetroFactory can find them
    if (existingFactory == null) {
      generateStubCreatorFunctions(
        factoryClass = stub,
        callableName = entry.callable_name,
        returnType = sourceFunction.returnType,
        sourceFunction = sourceFunction,
      )
    }

    val callableId = CallableId(container.classIdOrFail, Name.identifier(entry.callable_name))
    val callableMetadata =
      IrCallableMetadata(
        callableId = callableId,
        mirrorCallableId = mirrorFunction.callableId,
        annotations = sourceAnnotations,
        isPropertyAccessor = entry.property_name.isNotEmpty(),
        newInstanceName = Name.identifier(entry.new_instance_name),
        function = sourceFunction,
        mirrorFunction = mirrorFunction,
      )

    // referenceClass bypasses the visibility filter that originClassOrNull() inherits, so
    // we can resolve internal origin classes from other modules for diagnostic locations.
    val originClass =
      container
        .annotationsIn(metroSymbols.classIds.originAnnotations)
        .firstOrNull()
        ?.originOrNull()
        ?.let { container.lookupClass(it)?.owner }

    return ProviderFactory(
      contextKey = IrContextualTypeKey.from(mirrorFunction),
      clazz = stub,
      mirrorFunction = mirrorFunction,
      sourceAnnotations = sourceAnnotations,
      callableMetadata = callableMetadata,
      realDeclaration = originClass ?: providesFunction,
      inlinedValue = entry.inlinedValueIfEnabled(),
      computeInlinedValue = false,
    )
  }

  private fun ProviderFactoryProto.inlinedValueIfEnabled(): IrInlinedProvider? {
    if (!options.enableProviderInlining) return null
    return IrInlinedProvider.fromProto(inlined)
  }

  private fun markComptimeOnlyIfInlined(providerFactory: ProviderFactory) {
    if (!pluginContext.platform.isJvm()) return
    if (providerFactory !is ProviderFactory.Metro) return
    if (providerFactory.inlinedValue == null) return

    val factoryClass = providerFactory.factoryClass
    // TODO the if check here is if/when we move factory gen totally to IR
    if (!factoryClass.hasAnnotation(Symbols.ClassIds.ComptimeOnly)) {
      factoryClass.addAnnotationCompat(
        buildAnnotation(factoryClass.symbol, metroSymbols.comptimeOnlyAnnotationConstructor)
      )
    }
  }

  /**
   * Creates a provider factory class stub in IR for contribution provider objects. The stub has its
   * parent set but is NOT added as a child declaration. Used for both in-compilation (where the
   * caller adds it) and external (where it's a phantom).
   */
  private fun createContributionProviderFactoryStub(
    parentClass: IrClass,
    classId: ClassId,
    isObject: Boolean,
  ): IrClass {
    val classKind = if (isObject) ClassKind.OBJECT else ClassKind.CLASS

    return pluginContext.irFactory
      .buildClass {
        name = classId.shortClassName
        kind = classKind
        visibility = DescriptorVisibilities.PUBLIC
        origin = Origins.ProviderFactoryClassDeclaration
      }
      .apply {
        // Set parent but do NOT add as child — caller decides
        this.parent = parentClass
        if (!isObject) {
          typeParameters = copyTypeParametersFrom(parentClass)
        }
        createThisReceiverParameter()

        if (isObject) {
          addSimpleDelegatingConstructor(
              irBuiltIns.anyClass.owner.primaryConstructor!!,
              irBuiltIns,
              isPrimary = true,
            )
            .apply { visibility = DescriptorVisibilities.PRIVATE }
        } else {
          // Non-objects need a companion for create()/newInstance() static methods
          val factoryCls = this
          pluginContext.irFactory
            .buildClass {
              name = org.jetbrains.kotlin.name.SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT
              kind = ClassKind.OBJECT
              visibility = DescriptorVisibilities.PUBLIC
              isCompanion = true
            }
            .apply {
              factoryCls.addChild(this)
              createThisReceiverParameter()
              addSimpleDelegatingConstructor(
                irBuiltIns.anyClass.owner.primaryConstructor!!,
                irBuiltIns,
                isPrimary = true,
              )
            }
        }
      }
  }

  /**
   * Creates a provider factory class for an in-compilation contribution provider object. The class
   * is created and added as a child of the parent.
   */
  private fun createContributionProviderFactory(
    parentClass: IrClass,
    generatedClassId: ClassId,
    reference: CallableReference,
  ): IrClass {
    val isObject = reference.parameters.allParameters.isEmpty()
    return createContributionProviderFactoryStub(parentClass, generatedClassId, isObject).also {
      it.addCallableMetadataAnnotation(reference)
      if (options.generateClassesInIr) {
        metadataDeclarationRegistrarCompat.registerClassAsMetadataVisible(it)
      }
      parentClass.declarations.add(it)
    }
  }

  private fun IrClass.addCallableMetadataAnnotation(reference: CallableReference) {
    val target = reference.callee?.owner?.propertyIfAccessor ?: reference.backingField
    val callableMetadata =
      buildAnnotation(symbol, metroSymbols.callableMetadataAnnotationConstructor) { annotation ->
        with(pluginContext.createIrBuilder(symbol)) {
          annotation.arguments[0] = irString(reference.callableId.callableName.asString())
          annotation.arguments[1] =
            irString(
              reference.callee
                ?.owner
                ?.propertyIfAccessor
                ?.expectAsOrNull<IrProperty>()
                ?.name
                ?.asString() ?: ""
            )
          annotation.arguments[2] = irInt(target?.startOffset ?: startOffset)
          annotation.arguments[3] = irInt(target?.endOffset ?: endOffset)
          annotation.arguments[4] = irString(reference.name.asString())
        }
      }
    addAnnotationCompat(callableMetadata)
  }

  private fun IrClass.shouldGenerateProviderFactoryInIr(): Boolean {
    return options.generateClassesInIr || hasAnnotation(Symbols.ClassIds.irOnlyFactories)
  }

  /**
   * Finds the `@Provides` function on the binding container that corresponds to an invisible
   * factory. The factory ClassId encodes the callable name (e.g., `ProvideImplMetroFactory`
   * corresponds to `provideImpl`).
   */
  private fun findProvidesForInvisibleFactory(
    container: IrClass,
    callableName: String,
  ): IrSimpleFunction? {
    return container.declarations.filterIsInstance<IrSimpleFunction>().find {
      it.name.asString() == callableName
    } ?: container.companionObject()?.functions?.find { it.name.asString() == callableName }
  }
}

internal class BindingContainer(
  val isGraph: Boolean,
  val canBeManaged: Boolean,
  val ir: IrClass,
  val includes: Set<ClassId>,
  /** Mapping of provider factories by their [CallableId]. */
  val providerFactories: Map<CallableId, ProviderFactory>,
  val bindsMirror: BindsMirror?,
) {
  val typeKey by memoize { IrTypeKey(ir.defaultType) }

  private val classId = ir.classIdOrFail

  fun isEmpty() =
    includes.isEmpty() && providerFactories.isEmpty() && (bindsMirror?.isEmpty() ?: true)

  /**
   * Creates a copy of this container with type-substituted provider factories. Used when a generic
   * binding container is included with concrete type arguments (e.g., `@Includes
   * TypedBindings<String>`).
   */
  fun withTypeSubstitution(remapper: TypeRemapper): BindingContainer {
    val remappedFactories = providerFactories.mapValues { (_, factory) ->
      when (factory) {
        is ProviderFactory.Metro -> factory.withRemappedTypes(remapper)
        is ProviderFactory.Dagger -> factory.withRemappedTypes(remapper)
      }
    }
    val remappedBindsMirror = bindsMirror?.let { mirror ->
      BindsMirror(
        ir = mirror.ir,
        bindsCallables = mirror.bindsCallables.mapTo(mutableSetOf()) { it.remapTypes(remapper) },
        multibindsCallables =
          mirror.multibindsCallables.mapTo(mutableSetOf()) { it.remapTypes(remapper) },
        optionalKeys = mirror.optionalKeys.mapTo(mutableSetOf()) { it.remapTypes(remapper) },
      )
    }
    return BindingContainer(
      isGraph = isGraph,
      canBeManaged = canBeManaged,
      ir = ir,
      includes = includes,
      providerFactories = remappedFactories,
      bindsMirror = remappedBindsMirror,
    )
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (javaClass != other?.javaClass) return false

    other as BindingContainer

    return classId == other.classId
  }

  override fun hashCode(): Int = classId.hashCode()

  override fun toString(): String = classId.asString()
}

private fun daggerFactoryClassIdOf(
  declaration: IrOverridableDeclaration<*>,
  useJvmName: Boolean,
): ClassId {
  val isProperty = declaration.isDaggerPropertyProvider()
  val containingClass = declaration.parentAsClass
  val nameToUse = daggerProviderFunctionNameOf(declaration, useJvmName).asString()
  val suffix = buildString {
    append("_")
    if (isProperty) {
      append("Get")
    }
    append(nameToUse.capitalizeUS())
    append("Factory")
  }
  return containingClass.classIdOrFail.generatedClass(suffix)
}

private fun daggerProviderFunctionNameOf(
  declaration: IrOverridableDeclaration<*>,
  useJvmName: Boolean,
): Name {
  return Name.identifier(
    if (useJvmName) {
      declaration.getJvmNameFromAnnotation() ?: declaration.daggerProviderSourceName()
    } else {
      declaration.daggerProviderSourceName()
    }
  )
}

private fun IrClass.daggerProviderNewInstanceNameOrNull(): Name? {
  val newInstanceFunction =
    simpleFunctions().singleOrNull { function ->
      function.name != Symbols.Names.create && function.name != Symbols.Names.createFactoryProvider
    }
  return newInstanceFunction?.name
}

private fun IrOverridableDeclaration<*>.isDaggerPropertyProvider(): Boolean {
  return this is IrProperty || (this is IrSimpleFunction && isPropertyAccessor)
}

private fun IrOverridableDeclaration<*>.daggerProviderSourceName(): String {
  val name =
    when (this) {
      is IrProperty -> name.asString()
      is IrSimpleFunction ->
        if (isPropertyAccessor) {
          propertyIfAccessor.expectAs<IrProperty>().name.asString()
        } else {
          name.asString()
        }
      else -> reportCompilerBug("Unexpected Dagger provider declaration: $this")
    }
  return if (name.startsWith("<get-") && name.endsWith(">")) {
    name.removePrefix("<get-").removeSuffix(">")
  } else {
    name
  }
}

private fun IrClass.isManageableBindingContainerClass(requireFinal: Boolean): Boolean {
  if (kind != ClassKind.CLASS) return false
  if (requireFinal) {
    if (modality != Modality.FINAL) return false
  } else if (modality == Modality.ABSTRACT) {
    return false
  }
  return constructors.any { constructor ->
    constructor.parameters.isEmpty() && constructor.visibility == DescriptorVisibilities.PUBLIC
  }
}
