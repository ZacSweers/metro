// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.METRO_VERSION
import dev.zacsweers.metro.compiler.MetroAnnotations
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.capitalizeUS
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.generatedClass
import dev.zacsweers.metro.compiler.ir.IrAnnotation
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.MetroSimpleFunction
import dev.zacsweers.metro.compiler.ir.ProviderFactory
import dev.zacsweers.metro.compiler.ir.annotationClass
import dev.zacsweers.metro.compiler.ir.annotationsIn
import dev.zacsweers.metro.compiler.ir.assignConstructorParamsToFields
import dev.zacsweers.metro.compiler.ir.createIrBuilder
import dev.zacsweers.metro.compiler.ir.dispatchReceiverFor
import dev.zacsweers.metro.compiler.ir.finalizeFakeOverride
import dev.zacsweers.metro.compiler.ir.findAnnotations
import dev.zacsweers.metro.compiler.ir.graph.IrBinding
import dev.zacsweers.metro.compiler.ir.includedClasses
import dev.zacsweers.metro.compiler.ir.irCallableMetadata
import dev.zacsweers.metro.compiler.ir.irExprBodySafe
import dev.zacsweers.metro.compiler.ir.irInvoke
import dev.zacsweers.metro.compiler.ir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.ir.isBindingContainer
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
import dev.zacsweers.metro.compiler.ir.render
import dev.zacsweers.metro.compiler.ir.reportCompat
import dev.zacsweers.metro.compiler.ir.requireSimpleFunction
import dev.zacsweers.metro.compiler.ir.requireSimpleType
import dev.zacsweers.metro.compiler.ir.subcomponentsArgument
import dev.zacsweers.metro.compiler.ir.thisReceiverOrFail
import dev.zacsweers.metro.compiler.ir.toClassReferences
import dev.zacsweers.metro.compiler.ir.toProto
import dev.zacsweers.metro.compiler.ir.writeDiagnostic
import dev.zacsweers.metro.compiler.isWordPrefixRegex
import dev.zacsweers.metro.compiler.mapNotNullToSet
import dev.zacsweers.metro.compiler.mapToSet
import dev.zacsweers.metro.compiler.memoize
import dev.zacsweers.metro.compiler.metroAnnotations
import dev.zacsweers.metro.compiler.proto.DependencyGraphProto
import dev.zacsweers.metro.compiler.proto.MetroMetadata
import dev.zacsweers.metro.compiler.reportCompilerBug
import dev.zacsweers.metro.compiler.symbols.DaggerSymbols
import dev.zacsweers.metro.compiler.symbols.Symbols
import java.util.EnumSet
import java.util.Optional
import kotlin.jvm.optionals.getOrNull
import org.jetbrains.kotlin.backend.jvm.ir.getJvmNameFromAnnotation
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetObject
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
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.callableId
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.ir.util.isFakeOverride
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.util.isPropertyAccessor
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.nestedClasses
import org.jetbrains.kotlin.ir.util.packageFqName
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

  private val bindsMirrorClassTransformer = BindsMirrorClassTransformer(context)

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
    } else if (declaration.name == Symbols.Names.BindsMirrorClass) {
      cache[declarationFqName] = Optional.empty()
      return null
    }

    val providerFactories = mutableMapOf<CallableId, ProviderFactory>()

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
            }
          }
          is IrSimpleFunction -> {
            val metroFunction = metroFunctionOf(nestedDeclaration)
            if (metroFunction.annotations.isProvides) {
              providerFactories[nestedDeclaration.callableId] =
                visitFunction(nestedDeclaration, metroFunction)
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

    val bindingContainerAnnotation =
      declaration.annotationsIn(metroSymbols.classIds.bindingContainerAnnotations).singleOrNull()
    val includes =
      bindingContainerAnnotation?.includedClasses()?.mapNotNullToSet {
        it.classType.rawTypeOrNull()?.classIdOrFail
      }

    val bindsMirror = bindsMirrorClassTransformer.getOrComputeBindsMirror(declaration)

    val graphAnnotation =
      declaration.annotationsIn(metroSymbols.classIds.graphLikeAnnotations).firstOrNull()
    val isContributedGraph =
      (graphAnnotation?.annotationClass?.classId in
        metroSymbols.classIds.graphExtensionAnnotations) &&
        declaration.isAnnotatedWithAny(metroSymbols.classIds.contributesToAnnotations)
    val isGraph = graphAnnotation != null
    val container =
      BindingContainer(
        isGraph = isGraph,
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
      val metroMetadata = MetroMetadata(METRO_VERSION, dependency_graph = container.toProto())
      declaration.metroMetadata = metroMetadata
    }

    return if (container.isEmpty()) {
      cache[declarationFqName] = Optional.empty()
      null
    } else {
      cache[declarationFqName] = Optional.of(container)
      generatedFactories.putAll(providerFactories)
      container
    }
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

  fun getOrLookupProviderFactory(binding: IrBinding.Provided): ProviderFactory? {
    // Eager cache check using the factory's callable ID
    generatedFactories[binding.providerFactory.callableId]?.let {
      return it
    }

    // If the parent hasn't been checked before, visit it and look again
    // Note the parent may be just a package if this is a Dagger-generated module provider
    val parent = binding.providerFactory.factoryClass.parent
    if (parent is IrClass) {
      findContainer(binding.providerFactory.factoryClass.parentAsClass)
    }

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
        ?: reportCompilerBug(
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
            reportCompilerBug(
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
              ?: reportCompilerBug(
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
          irInvoke(
            dispatchReceiver = dispatchReceiverFor(bytecodeFunction),
            callee = bytecodeFunction.symbol,
            args =
              parametersAsProviderArguments(
                parameters = sourceParameters,
                receiver = invokeFunction.owner.dispatchReceiverParameter!!,
                parametersToFields = sourceParametersToFields,
              ),
          )
        )
      }

    val providesFunction = reference.callee.owner

    // Generate a metadata-visible function that matches the signature of the target provider
    // This is used in downstream compilations to read the provider's signature
    val mirrorFunction =
      generateMetadataVisibleMirrorFunction(
        factoryClass = factoryCls,
        target = providesFunction,
        annotations = reference.annotations,
      )

    val providerFactory =
      ProviderFactory(
        sourceTypeKey = reference.typeKey,
        clazz = factoryCls,
        mirrorFunction = mirrorFunction,
        sourceAnnotations = reference.annotations,
        callableMetadata =
          factoryCls.irCallableMetadata(mirrorFunction, reference.annotations, isInterop = false),
      )

    factoryCls.dumpToMetroLog()

    val factoryPath =
      factoryCls.packageFqName?.let { packageName ->
        val fileName = factoryCls.kotlinFqName.toString().replace("$packageName.", "")
        "${packageName.pathSegments().joinToString("/")}/$fileName"
      } ?: factoryCls.kotlinFqName.asString()

    // Relative path example: provider-factories/dev/zac/feature/Outer.Inner$$Factory.kt
    writeDiagnostic("provider-factories/$factoryPath.kt") { factoryCls.dumpKotlinLike() }

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
        parameters = function.parameters(),
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
          ?: reportCompilerBug(
            "No getter found for property $callableId. Note that field properties are not supported"
          )

      val typeKey = IrContextualTypeKey.from(getter).typeKey

      val parent = property.parentAsClass
      return CallableReference(
        callableId = callableId,
        name = property.name,
        isPropertyAccessor = true,
        parameters = property.getter?.parameters() ?: Parameters.empty(),
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
      parentClass = classToGenerateCreatorsIn,
      targetClass = factoryCls,
      targetConstructor = factoryConstructor,
      parameters = factoryParameters,
      providerFunction = reference.callee.owner,
    )

    // Generate the named newInstance function
    val newInstanceFunction =
      generateStaticNewInstanceFunction(
        parentClass = classToGenerateCreatorsIn,
        targetFunction = reference.callee.owner,
        sourceMetroParameters = reference.parameters,
        sourceParameters = reference.parameters.regularParameters.map { it.asValueParameter },
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
    private val useGetPrefix by memoize {
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

    // Map to track direct containers (before computing transitive closure)
    val directContainers = mutableMapOf<ClassId, BindingContainer?>()

    queue += root

    // First pass: collect all direct binding containers
    while (queue.isNotEmpty()) {
      val bindingContainerClass = queue.removeFirst()
      val classId = bindingContainerClass.classIdOrFail

      // Skip if we've already processed this class locally
      if (classId in localVisited) continue
      localVisited += classId

      // Check if we already have the transitive closure cached
      if (classId in globalVisited) {
        transitiveBindingContainerCache[classId]?.let { cachedResult -> result += cachedResult }
        continue
      }

      globalVisited += classId

      // Check if already cached (shouldn't happen but be defensive)
      transitiveBindingContainerCache[classId]?.let { cachedResult ->
        result += cachedResult
        continue
      }

      // Find the direct container for this class
      val bindingContainer = findContainer(bindingContainerClass)
      directContainers[classId] = bindingContainer

      // Add included binding containers to the queue for processing
      bindingContainer?.includes?.forEach { includedClassId ->
        pluginContext.referenceClass(includedClassId)?.owner?.let { includedClass ->
          if (includedClassId !in localVisited && includedClassId !in globalVisited) {
            queue += includedClass
          }
        }
      }
    }

    // Second pass: compute transitive closures with proper caching
    fun computeTransitiveClosure(
      classId: ClassId,
      visited: MutableSet<ClassId> = mutableSetOf(),
    ): Set<BindingContainer> {
      // Check cache first
      transitiveBindingContainerCache[classId]?.let {
        return it
      }

      // Prevent cycles
      if (classId in visited) return emptySet()

      visited += classId

      val transitiveClosure = mutableSetOf<BindingContainer>()

      // Add the direct container if it exists
      directContainers[classId]?.let { container ->
        transitiveClosure += container

        // Recursively add transitive closures of includes
        container.includes.forEach { includedClassId ->
          // First check if we have it in the cache
          val includedClosure =
            transitiveBindingContainerCache[includedClassId]
              ?: if (includedClassId in directContainers.keys) {
                // We have the direct container, compute its closure
                computeTransitiveClosure(includedClassId, visited)
              } else {
                // Not in our local processing, might be cached from global
                emptySet()
              }
          transitiveClosure += includedClosure
        }
      }

      // Cache the result
      transitiveBindingContainerCache[classId] = transitiveClosure
      return transitiveClosure
    }

    // Compute transitive closures for all processed containers
    for (classId in directContainers.keys) {
      val closure = computeTransitiveClosure(classId)
      result += closure
    }

    return result
  }

  private fun externalProviderFactoryFor(factoryCls: IrClass): ProviderFactory.Metro {
    // Extract IrTypeKey from Factory supertype
    // Qualifier will be populated in ProviderFactory construction
    val mirrorFunction = factoryCls.requireSimpleFunction(Symbols.StringNames.MIRROR_FUNCTION).owner
    val sourceAnnotations = mirrorFunction.metroAnnotations(metroSymbols.classIds)
    val callableMetadata =
      factoryCls.irCallableMetadata(mirrorFunction, sourceAnnotations, isInterop = false)
    val factoryType =
      factoryCls.superTypes
        .first { it.classOrNull == metroSymbols.metroFactory }
        .requireSimpleType(factoryCls) {
          appendLine()
          appendLine("(hint)")
          val shortPath = buildString {
            append(callableMetadata.callableId.classId!!.shortClassName)
            append(".")
            append(callableMetadata.callableId.callableName)
            if (!callableMetadata.isPropertyAccessor) {
              append("(...)")
            }
            append(": ")
            append(callableMetadata.function.returnType.render(short = true))
          }
          appendLine(
            "This is a generated factory class for the '$shortPath' @Provides declaration, which is likely where the missing type is originally exposed."
          )
        }

    val typeKey = IrTypeKey(factoryType.requireSimpleType(factoryCls).arguments.first().typeOrFail)
    return ProviderFactory(typeKey, factoryCls, mirrorFunction, sourceAnnotations, callableMetadata)
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
                  ),
              )
            if (
              annotations.isProvides ||
                annotations.isBinds ||
                annotations.isMultibinds ||
                annotations.isBindsOptionalOf
            ) {
              val isProperty = decl is IrProperty
              val callableId: CallableId
              val typeKey: IrTypeKey
              val parameters: Parameters
              val function: IrFunction
              when (decl) {
                is IrProperty -> {
                  callableId = decl.callableId
                  typeKey = IrContextualTypeKey.from(decl.getter!!).typeKey
                  parameters =
                    if (annotations.isBinds) Parameters.empty() else decl.getter!!.parameters()
                  function = decl.getter!!
                }
                is IrSimpleFunction -> {
                  callableId = decl.callableId
                  typeKey = IrContextualTypeKey.from(decl).typeKey
                  parameters = if (annotations.isBinds) Parameters.empty() else decl.parameters()
                  function = decl
                }
              }

              if (annotations.isProvides) {
                // Look up the expected provider factory class
                // Try both with and without the declaration's `@JvmName` (if present). Dagger
                // doesn't seem to read this in KSP but would implicitly in KAPT
                val factoryClass =
                  referenceClass(daggerFactoryClassIdOf(decl, useJvmName = false))
                    ?: referenceClass(daggerFactoryClassIdOf(decl, useJvmName = true))

                if (factoryClass == null) {
                  reportCompat(
                    decl,
                    MetroDiagnostics.METRO_ERROR,
                    "Couldn't find Dagger-generated provider factory class for $declaration.$decl",
                  )
                  return null
                }
                providerFactories[callableId] =
                  ProviderFactory.Dagger(
                    factoryClass = factoryClass.owner,
                    typeKey = typeKey,
                    callableId = callableId,
                    annotations = annotations,
                    parameters = parameters,
                    function = function,
                    isPropertyAccessor = isProperty,
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
              false,
              declaration,
              includedModules,
              providerFactories,
              bindsCollector.buildMirror(declaration),
            )
          cache[declarationFqName] = Optional.of(container)
          generatedFactories.putAll(providerFactories)
          return container
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
        return null
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
    val bindsMirror = bindsMirrorClassTransformer.getOrComputeBindsMirror(declaration)
    val includedBindingContainers =
      graphProto.included_binding_containers.mapToSet { ClassId.fromString(it) }

    val container =
      BindingContainer(
        graphProto.is_graph,
        declaration,
        includedBindingContainers,
        providerFactories,
        bindsMirror,
      )

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
  val bindsMirror: BindsMirror?,
) {
  private val classId = ir.classIdOrFail

  /**
   * Simple classes with a public, no-arg constructor can be managed directly by the consuming
   * graph.
   */
  val canBeManaged by memoize { ir.kind == ClassKind.CLASS && ir.modality != Modality.ABSTRACT }

  fun isEmpty() =
    includes.isEmpty() && providerFactories.isEmpty() && (bindsMirror?.isEmpty() ?: true)

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
  val isProperty = declaration is IrProperty
  val containingClass = declaration.parentAsClass
  val nameToUse =
    if (useJvmName) {
      declaration.getJvmNameFromAnnotation() ?: declaration.name.asString()
    } else {
      declaration.name.asString()
    }
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
