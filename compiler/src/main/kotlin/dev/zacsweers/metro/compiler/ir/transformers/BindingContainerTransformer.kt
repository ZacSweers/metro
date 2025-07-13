// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.drewhamilton.poko.Poko
import dev.zacsweers.metro.compiler.METRO_VERSION
import dev.zacsweers.metro.compiler.MetroAnnotations
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.capitalizeUS
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.flatMapToSet
import dev.zacsweers.metro.compiler.ir.Binding
import dev.zacsweers.metro.compiler.ir.IrAnnotation
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.MetroSimpleFunction
import dev.zacsweers.metro.compiler.ir.ProviderFactory
import dev.zacsweers.metro.compiler.ir.annotationsIn
import dev.zacsweers.metro.compiler.ir.assignConstructorParamsToFields
import dev.zacsweers.metro.compiler.ir.createIrBuilder
import dev.zacsweers.metro.compiler.ir.dispatchReceiverFor
import dev.zacsweers.metro.compiler.ir.finalizeFakeOverride
import dev.zacsweers.metro.compiler.ir.includedClasses
import dev.zacsweers.metro.compiler.ir.irExprBodySafe
import dev.zacsweers.metro.compiler.ir.irInvoke
import dev.zacsweers.metro.compiler.ir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.ir.isCompanionObject
import dev.zacsweers.metro.compiler.ir.isExternalParent
import dev.zacsweers.metro.compiler.ir.metroAnnotationsOf
import dev.zacsweers.metro.compiler.ir.metroFunctionOf
import dev.zacsweers.metro.compiler.ir.metroGraphOrNull
import dev.zacsweers.metro.compiler.ir.metroMetadata
import dev.zacsweers.metro.compiler.ir.parameters.Parameter
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.parametersAsProviderArguments
import dev.zacsweers.metro.compiler.ir.rawTypeOrNull
import dev.zacsweers.metro.compiler.ir.regularParameters
import dev.zacsweers.metro.compiler.ir.requireSimpleFunction
import dev.zacsweers.metro.compiler.ir.thisReceiverOrFail
import dev.zacsweers.metro.compiler.ir.toProto
import dev.zacsweers.metro.compiler.isWordPrefixRegex
import dev.zacsweers.metro.compiler.mapNotNullToSet
import dev.zacsweers.metro.compiler.mapToSet
import dev.zacsweers.metro.compiler.metroAnnotations
import dev.zacsweers.metro.compiler.proto.DependencyGraphProto
import dev.zacsweers.metro.compiler.proto.MetroMetadata
import dev.zacsweers.metro.compiler.unsafeLazy
import java.util.Optional
import kotlin.jvm.optionals.getOrNull
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeAlias
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.callableId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.ir.util.isFakeOverride
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.util.isPropertyAccessor
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.nestedClasses
import org.jetbrains.kotlin.ir.util.nonDispatchParameters
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.propertyIfAccessor
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

internal class BindingContainerTransformer(context: IrMetroContext) : IrMetroContext by context {

  private val references = mutableMapOf<CallableId, CallableReference>()
  private val generatedFactories = mutableMapOf<CallableId, ProviderFactory>()

  /**
   * A cache of binding container fqnames to a [BindingContainer] representation of them. If the key
   * is present but the value is an empty optional, it means this is just not a binding container.
   */
  private val cache = mutableMapOf<FqName, Optional<BindingContainer>>()

  /**
   * Cache for transitive closure of all included binding containers. Maps [ClassId] ->
   * [Set<BindingContainer>][BindingContainer] where the values represent all transitively included
   * binding containers starting from the given [ClassId].
   */
  private val transitiveBindingContainerCache = mutableMapOf<ClassId, Set<BindingContainer>>()

  fun findContainer(
    declaration: IrClass,
    declarationFqName: FqName = declaration.kotlinFqName,
    graphProto: DependencyGraphProto? = null,
  ): BindingContainer? {
    cache[declarationFqName]?.let {
      return it.getOrNull()
    }

    if (declaration.isExternalParent) {
      return loadExternalBindingContainer(declaration, declarationFqName, graphProto)
    }

    val providerFactories = mutableMapOf<CallableId, ProviderFactory>()
    val bindsCallables = mutableSetOf<BindsCallable>()

    declaration.declarations
      .asSequence()
      // Skip (fake) overrides, we care only about the original declaration because those have
      // default values
      .filterNot { it.isFakeOverride }
      .filterNot { it is IrConstructor || it is IrTypeAlias }
      .forEach { nestedDeclaration ->
        when (nestedDeclaration) {
          is IrProperty -> {
            val getter = nestedDeclaration.getter ?: return@forEach
            val metroFunction = metroFunctionOf(getter)
            if (metroFunction.annotations.isProvides) {
              providerFactories[nestedDeclaration.callableId] =
                visitProperty(nestedDeclaration, metroFunction)
            } else if (metroFunction.annotations.isBinds) {
              // Check if it's @Binds
              if (metroFunction.annotations.isBinds) {
                bindsCallables += metroFunction.toBindsCallable()
              }
            }
          }
          is IrSimpleFunction -> {
            val metroFunction = metroFunctionOf(nestedDeclaration)
            if (metroFunction.annotations.isProvides) {
              providerFactories[nestedDeclaration.callableId] =
                visitFunction(nestedDeclaration, metroFunction)
            } else if (metroFunction.annotations.isBinds) {
              // Check if it's @Binds
              if (metroFunction.annotations.isBinds) {
                bindsCallables += metroFunction.toBindsCallable()
              }
            }
          }
          is IrClass if (nestedDeclaration.isCompanionObject) -> {
            // Include companion object refs
            findContainer(nestedDeclaration)?.providerFactories?.let {
              providerFactories.putAll(it.values.associateBy { it.callableId })
            }
          }
        }
      }

    val includes =
      declaration
        .annotationsIn(symbols.classIds.bindingContainerAnnotations)
        .singleOrNull()
        ?.includedClasses()
        ?.mapNotNullToSet { it.classType.rawTypeOrNull()?.classIdOrFail }

    if (includes.isNullOrEmpty() && providerFactories.isEmpty() && bindsCallables.isEmpty()) {
      cache[declarationFqName] = Optional.empty()
      return null
    }

    val isGraph = declaration.isAnnotatedWithAny(symbols.classIds.graphLikeAnnotations)
    val container =
      BindingContainer(isGraph, declaration, includes.orEmpty(), providerFactories, bindsCallables)

    // If it's got providers but _not_ a @DependencyGraph, generate factory information onto this
    // class's metadata. This allows consumers in downstream compilations to know if there are
    // providers to consume here even if they are private.
    val shouldGenerateMetadata =
      !container.isEmpty() &&
        !declaration.isAnnotatedWithAny(symbols.classIds.dependencyGraphAnnotations)

    if (shouldGenerateMetadata) {
      val metroMetadata = MetroMetadata(METRO_VERSION, dependency_graph = container.toProto())
      declaration.metroMetadata = metroMetadata
    }

    cache[declarationFqName] = Optional.of(container)
    generatedFactories.putAll(providerFactories)
    return container
  }

  private fun visitProperty(
    declaration: IrProperty,
    metroFunction: MetroSimpleFunction,
  ): ProviderFactory {
    return getOrLookupProviderFactory(
      getOrPutCallableReference(declaration, metroFunction.annotations)
    )
  }

  private fun visitFunction(
    declaration: IrSimpleFunction,
    metroFunction: MetroSimpleFunction,
  ): ProviderFactory {
    return getOrLookupProviderFactory(
      getOrPutCallableReference(declaration, declaration.parentAsClass, metroFunction.annotations)
    )
  }

  fun getOrLookupProviderFactory(binding: Binding.Provided): ProviderFactory? {
    // Eager cache check using the factory's callable ID
    generatedFactories[binding.providerFactory.callableId]?.let {
      return it
    }

    // If the parent hasn't been checked before, visit it and look again
    findContainer(binding.providerFactory.clazz.parentAsClass)

    // If it's still not present after, there's nothing here
    return generatedFactories[binding.providerFactory.callableId]
  }

  fun getOrLookupProviderFactory(reference: CallableReference): ProviderFactory {
    generatedFactories[reference.callableId]?.let {
      return it
    }

    val sourceValueParameters = reference.parameters.regularParameters

    val generatedClassId = reference.generatedClassId

    val factoryCls =
      reference.parent.owner.nestedClasses.singleOrNull {
        it.origin == Origins.ProviderFactoryClassDeclaration && it.classIdOrFail == generatedClassId
      }
        ?: error(
          "No expected factory class generated for ${reference.callableId}. Report this bug with a repro case at https://github.com/zacsweers/metro/issues/new"
        )

    val ctor = factoryCls.primaryConstructor!!

    val graphType = reference.graphParent.typeWith()

    val instanceParam =
      if (!reference.isInObject) {
        val contextualTypeKey = IrContextualTypeKey.create(typeKey = IrTypeKey(graphType))
        Parameter.regular(
          kind = IrParameterKind.Regular,
          name = Name.identifier("graph"),
          contextualTypeKey = contextualTypeKey,
          isAssisted = false,
          assistedIdentifier = "",
          isGraphInstance = true,
          isExtends = false,
          isIncludes = false,
          isBindsInstance = false,
          ir = null,
        )
      } else {
        null
      }

    val sourceParameters =
      Parameters(
        reference.callee.owner.callableId,
        instance = instanceParam,
        extensionReceiver = null,
        regularParameters = sourceValueParameters,
        contextParameters = emptyList(),
        ir = null, // Will set later
      )

    val constructorParametersToFields = assignConstructorParamsToFields(ctor, factoryCls)

    val sourceParametersToFields: Map<Parameter, IrField> =
      constructorParametersToFields.entries.associate { (irParam, field) ->
        val sourceParam =
          if (irParam.origin == Origins.InstanceParameter) {
            sourceParameters.dispatchReceiverParameter!!
          } else if (irParam.indexInParameters == -1) {
            error(
              "No source parameter found for $irParam. Index was somehow -1.\n${reference.parent.owner.dumpKotlinLike()}"
            )
          } else {
            // Get all regular parameters from the source function
            val regularParams =
              reference.callee.owner.parameters.filter { it.kind == IrParameterKind.Regular }

            // Find the corresponding source parameter by matching names
            sourceParameters.regularParameters.getOrNull(
              regularParams.indexOfFirst { it.name == irParam.name }
            )
              ?: error(
                "No source parameter found for $irParam\nparam is ${irParam.name} in function ${ctor.dumpKotlinLike()}\n${reference.parent.owner.dumpKotlinLike()}"
              )
          }
        sourceParam to field
      }

    val bytecodeFunction =
      implementCreatorBodies(factoryCls, ctor.symbol, reference, sourceParameters)

    // Implement invoke()
    // TODO DRY this up with the constructor injection override
    val invokeFunction = factoryCls.requireSimpleFunction(Symbols.StringNames.INVOKE)
    invokeFunction.owner.finalizeFakeOverride(factoryCls.thisReceiverOrFail)
    invokeFunction.owner.body =
      pluginContext.createIrBuilder(invokeFunction).run {
        irExprBodySafe(
          invokeFunction,
          irInvoke(
            dispatchReceiver = dispatchReceiverFor(bytecodeFunction),
            callee = bytecodeFunction.symbol,
            args =
              parametersAsProviderArguments(
                metroContext,
                parameters = sourceParameters,
                receiver = invokeFunction.owner.dispatchReceiverParameter!!,
                parametersToFields = sourceParametersToFields,
              ),
          ),
        )
      }

    val providesFunction = reference.callee.owner

    // Generate a metadata-visible function that matches the signature of the target provider
    // This is used in downstream compilations to read the provider's signature
    val mirrorFunction =
      generateMetadataVisibleMirrorFunction(factoryClass = factoryCls, target = providesFunction)

    val providerFactory =
      ProviderFactory(
        context = metroContext,
        sourceTypeKey = reference.typeKey,
        clazz = factoryCls,
        mirrorFunction = mirrorFunction,
        sourceAnnotations = reference.annotations,
      )

    factoryCls.dumpToMetroLog()

    generatedFactories[reference.callableId] = providerFactory
    return providerFactory
  }

  private fun getOrPutCallableReference(
    function: IrSimpleFunction,
    parent: IrClass,
    annotations: MetroAnnotations<IrAnnotation>,
  ): CallableReference {
    return references.getOrPut(function.callableId) {
      val typeKey = IrContextualTypeKey.from(function).typeKey
      val isPropertyAccessor = function.isPropertyAccessor
      val callableId =
        if (isPropertyAccessor) {
          function.propertyIfAccessor.expectAs<IrProperty>().callableId
        } else {
          function.callableId
        }
      CallableReference(
        callableId = callableId,
        name = function.name,
        isPropertyAccessor = isPropertyAccessor,
        parameters = function.parameters(this),
        typeKey = typeKey,
        isNullable = typeKey.type.isMarkedNullable(),
        parent = parent.symbol,
        callee = function.symbol,
        annotations = annotations,
      )
    }
  }

  private fun getOrPutCallableReference(
    property: IrProperty,
    annotations: MetroAnnotations<IrAnnotation> = metroAnnotationsOf(property),
  ): CallableReference {
    val callableId = property.callableId
    return references.getOrPut(callableId) {
      val getter =
        property.getter
          ?: error(
            "No getter found for property $callableId. Note that field properties are not supported"
          )

      val typeKey = IrContextualTypeKey.from(getter).typeKey

      val parent = property.parentAsClass
      return CallableReference(
        callableId = callableId,
        name = property.name,
        isPropertyAccessor = true,
        parameters = property.getter?.parameters(this) ?: Parameters.empty(),
        typeKey = typeKey,
        isNullable = typeKey.type.isMarkedNullable(),
        parent = parent.symbol,
        callee = property.getter!!.symbol,
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
    generateStaticCreateFunction(
      context = metroContext,
      parentClass = classToGenerateCreatorsIn,
      targetClass = factoryCls,
      targetConstructor = factoryConstructor,
      parameters = factoryParameters,
      providerFunction = reference.callee.owner,
    )

    // Generate the named newInstance function
    val newInstanceFunction =
      generateStaticNewInstanceFunction(
        context = metroContext,
        parentClass = classToGenerateCreatorsIn,
        targetFunction = reference.callee.owner,
        sourceParameters = reference.parameters.regularParameters.map { it.ir },
      ) { function ->
        val parameters = function.regularParameters

        val args = parameters.filter { it.origin == Origins.RegularParameter }.map { irGet(it) }

        val dispatchReceiver =
          if (reference.isInObject) {
            // Static graph call
            // ExampleGraph.$callableName$arguments
            irGetObject(reference.parent)
          } else {
            // Instance graph call
            // exampleGraph.$callableName$arguments
            irGet(parameters[0])
          }

        irInvoke(
          dispatchReceiver = dispatchReceiver,
          extensionReceiver = null,
          callee = reference.callee,
          args = args,
        )
      }

    return newInstanceFunction
  }

  internal class CallableReference(
    val callableId: CallableId,
    val name: Name,
    val isPropertyAccessor: Boolean,
    val parameters: Parameters,
    val typeKey: IrTypeKey,
    val isNullable: Boolean,
    val parent: IrClassSymbol,
    val callee: IrFunctionSymbol,
    val annotations: MetroAnnotations<IrAnnotation>,
  ) {
    val isInObject: Boolean
      get() = parent.owner.isObject

    val graphParent =
      if (parent.owner.isCompanionObject) {
        parent.owner.parentAsClass
      } else {
        parent.owner
      }

    // omit the `get-` prefix for property names starting with the *word* `is`, like `isProperty`,
    // but not for names which just start with those letters, like `issues`.
    // TODO still necessary in IR?
    private val useGetPrefix by unsafeLazy {
      isPropertyAccessor && !isWordPrefixRegex.matches(name.asString())
    }

    private val simpleName by lazy {
      buildString {
        if (useGetPrefix) {
          append("Get")
        }
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

  /**
   * Resolves all binding containers transitively starting from the given roots. This method handles
   * caching and cycle detection to build the transitive closure of all included binding containers.
   */
  fun resolveAllBindingContainersCached(roots: Set<IrClass>): Set<BindingContainer> {
    val result = mutableSetOf<BindingContainer>()
    val visitedClasses = mutableSetOf<ClassId>()

    for (root in roots) {
      val classId = root.classIdOrFail

      // Check if we already have this in cache
      transitiveBindingContainerCache[classId]?.let { cachedResult ->
        result.addAll(cachedResult)
        continue
      }

      // Compute transitive closure for this root
      val rootTransitiveClosure = computeTransitiveBindingContainers(root, visitedClasses)

      // Cache the result
      transitiveBindingContainerCache[classId] = rootTransitiveClosure
      result.addAll(rootTransitiveClosure)
    }

    return result
  }

  private fun computeTransitiveBindingContainers(
    root: IrClass,
    globalVisited: MutableSet<ClassId>,
  ): Set<BindingContainer> {
    val result = mutableSetOf<BindingContainer>()
    val localVisited = mutableSetOf<ClassId>()
    val queue = ArrayDeque<IrClass>()

    queue += root

    while (queue.isNotEmpty()) {
      val bindingContainerClass = queue.removeFirst()
      val classId = bindingContainerClass.classIdOrFail

      // Skip if we've already processed this class in any context
      if (classId in globalVisited || classId in localVisited) continue
      localVisited += classId
      globalVisited += classId

      // Check cache first for this specific class
      transitiveBindingContainerCache[classId]?.let { cachedResult ->
        result += cachedResult
        continue
      }

      findContainer(bindingContainerClass)?.let { bindingContainer ->
        result += bindingContainer

        // Add included binding containers to the queue
        for (includedClassId in bindingContainer.includes) {
          val includedClass = pluginContext.referenceClass(includedClassId)?.owner
          if (includedClass != null && includedClassId !in localVisited) {
            queue += includedClass
          }
        }
      }
    }

    return result
  }

  private fun externalProviderFactoryFor(factoryCls: IrClass): ProviderFactory {
    // Extract IrTypeKey from Factory supertype
    // Qualifier will be populated in ProviderFactory construction
    val factoryType = factoryCls.superTypes.first { it.classOrNull == symbols.metroFactory }
    val typeKey = IrTypeKey(factoryType.expectAs<IrSimpleType>().arguments.first().typeOrFail)
    val mirrorFunction = factoryCls.requireSimpleFunction(Symbols.StringNames.MIRROR_FUNCTION).owner
    return ProviderFactory(
      metroContext,
      typeKey,
      factoryCls,
      mirrorFunction,
      mirrorFunction.metroAnnotations(symbols.classIds),
    )
  }

  private fun loadExternalBindingContainer(
    declaration: IrClass,
    declarationFqName: FqName,
    graphProto: DependencyGraphProto?,
  ): BindingContainer? {

    cache[declarationFqName]?.let {
      return it.getOrNull()
    }

    // Look up the external class metadata
    val metadataDeclaration = declaration.metroGraphOrNull ?: declaration
    val graphProto = graphProto ?: metadataDeclaration.metroMetadata?.dependency_graph

    if (graphProto == null) {
      val requireMetadata =
        declaration.isAnnotatedWithAny(symbols.dependencyGraphAnnotations) ||
          declaration.isAnnotatedWithAny(symbols.classIds.bindingContainerAnnotations)
      if (requireMetadata) {
        val message =
          "No metadata found for ${metadataDeclaration.kotlinFqName} from " +
            "another module. Did you run the Metro compiler plugin on this module?"
        error(message)
        // TODO kotlin 2.2.20
        //  diagnosticReporter
        //    .at(declaration)
        //    .report(
        //      MetroIrErrors.METRO_ERROR,
        //      message,
        //    )
      }
      cache[declarationFqName] = Optional.empty()
      return null
    }

    // Add any provider factories
    val providerFactories =
      graphProto.provider_factory_classes
        .map { ClassId.fromString(it) }
        .associate { classId ->
          val factoryClass = pluginContext.referenceClass(classId)!!.owner
          val providerFactory = externalProviderFactoryFor(factoryClass)
          providerFactory.callableId to providerFactory
        }

    // Add any binds callables
    val bindsCallables =
      graphProto.binds_callable_ids.flatMapToSet { bindsCallableId ->
        val classId = ClassId.fromString(bindsCallableId.class_id)
        val callableId = CallableId(classId, bindsCallableId.callable_name.asName())

        val functions =
          if (bindsCallableId.is_property) {
            pluginContext.referenceProperties(callableId).mapNotNull { it.owner.getter }
          } else {
            pluginContext.referenceFunctions(callableId).map { it.owner }
          }

        if (functions.isEmpty()) {
          val message = buildString {
            append("No function found for ")
            appendLine(callableId)
            callableId.classId?.let {
              pluginContext.referenceClass(it)?.let {
                appendLine("Class dump")
                appendLine(it.owner.dumpKotlinLike())
              }
            }
              ?: run {
                append("No class found for ")
                appendLine(callableId)
              }
          }
          error(message)
        }

        functions.map { function ->
          val metroFunction = metroFunctionOf(function)
          val source = IrContextualTypeKey.from(function.nonDispatchParameters.single()).typeKey
          val target = IrContextualTypeKey.from(function).typeKey
          BindsCallable(callableId, metroFunction, source, target)
        }
      }

    val includedBindingContainers =
      graphProto.included_binding_containers.mapToSet { ClassId.fromString(it) }

    val container =
      BindingContainer(graphProto.is_graph, declaration, includedBindingContainers, providerFactories, bindsCallables)

    // Cache the results
    cache[declarationFqName] = Optional.of(container)
    generatedFactories.putAll(providerFactories)

    return container
  }
}

internal class BindingContainer(
  val isGraph: Boolean,
  val ir: IrClass,
  val includes: Set<ClassId>,
  /** Mapping of provider factories by their [CallableId]. */
  val providerFactories: Map<CallableId, ProviderFactory>,
  /** Mapping of binds callables by their [CallableId]. */
  val bindsCallables: Set<BindsCallable>,
) {
  /**
   * Simple classes with a public, no-arg constructor can be managed directly by the consuming
   * graph.
   */
  val canBeManaged by unsafeLazy {
    // TODO Primary constructor is checked in FIR
    ir.kind == ClassKind.CLASS && ir.modality != Modality.ABSTRACT
  }

  fun isEmpty() = includes.isEmpty() && providerFactories.isEmpty() && bindsCallables.isEmpty()
}

@Poko
internal class BindsCallable(
  val callableId: CallableId,
  @Poko.Skip val function: MetroSimpleFunction,
  val source: IrTypeKey,
  val target: IrTypeKey,
)

context(context: IrMetroContext)
internal fun MetroSimpleFunction.toBindsCallable(): BindsCallable {
  val declarationToStore = ir.propertyIfAccessor
  val callableId =
    if (declarationToStore is IrProperty) {
      declarationToStore.callableId
    } else {
      callableId
    }
  return BindsCallable(
    callableId,
    this,
    IrContextualTypeKey.from(ir.nonDispatchParameters.single()).typeKey,
    IrContextualTypeKey.from(ir).typeKey,
  )
}
