// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.METRO_VERSION
import dev.zacsweers.metro.compiler.MetroAnnotations
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.PLUGIN_ID
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.capitalizeUS
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.ir.Binding
import dev.zacsweers.metro.compiler.ir.IrAnnotation
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.ProviderFactory
import dev.zacsweers.metro.compiler.ir.assignConstructorParamsToFields
import dev.zacsweers.metro.compiler.ir.createIrBuilder
import dev.zacsweers.metro.compiler.ir.dispatchReceiverFor
import dev.zacsweers.metro.compiler.ir.finalizeFakeOverride
import dev.zacsweers.metro.compiler.ir.irExprBodySafe
import dev.zacsweers.metro.compiler.ir.irInvoke
import dev.zacsweers.metro.compiler.ir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.ir.isCompanionObject
import dev.zacsweers.metro.compiler.ir.isExternalParent
import dev.zacsweers.metro.compiler.ir.metroAnnotationsOf
import dev.zacsweers.metro.compiler.ir.parameters.Parameter
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.parametersAsProviderArguments
import dev.zacsweers.metro.compiler.ir.regularParameters
import dev.zacsweers.metro.compiler.ir.requireSimpleFunction
import dev.zacsweers.metro.compiler.ir.thisReceiverOrFail
import dev.zacsweers.metro.compiler.isWordPrefixRegex
import dev.zacsweers.metro.compiler.metroAnnotations
import dev.zacsweers.metro.compiler.proto.DependencyGraphProto
import dev.zacsweers.metro.compiler.proto.MetroMetadata
import dev.zacsweers.metro.compiler.unsafeLazy
import org.jetbrains.kotlin.DeprecatedForRemovalCompilerApi
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
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
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isFakeOverride
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.util.isPropertyAccessor
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.nestedClasses
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.propertyIfAccessor
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

internal class ProvidesTransformer(context: IrMetroContext) : IrMetroContext by context {

  private val references = mutableMapOf<FqName, CallableReference>()
  private val generatedFactories = mutableMapOf<FqName, ProviderFactory>()
  private val generatedFactoriesByClass = mutableMapOf<FqName, MutableList<ProviderFactory>>()

  fun visitClass(declaration: IrClass): List<ProviderFactory> {
    val declarationFqName = declaration.kotlinFqName

    generatedFactoriesByClass[declarationFqName]?.let {
      return it
    }

    // Skip fake overrides, we care only about the original declaration because those have
    // default values
    declaration.declarations
      .filterNot { it.isFakeOverride }
      .forEach { nestedDeclaration ->
        when (nestedDeclaration) {
          is IrProperty -> visitProperty(nestedDeclaration)
          is IrSimpleFunction -> visitFunction(nestedDeclaration)
          is IrClass -> {
            if (nestedDeclaration.isCompanionObject) {
              // Include companion object refs
              visitClass(nestedDeclaration)
            }
          }
        }
      }

    // If it's got providers but _not_ a @DependencyGraph, generate factory information onto this
    // class's metadata. This allows consumers in downstream compilations to know if there are
    // providers to consume here even if they are private.
    val generatedFactories = generatedFactoriesByClass[declarationFqName].orEmpty()
    val shouldGenerateMetadata =
      generatedFactories.isNotEmpty() &&
        !declaration.isAnnotatedWithAny(symbols.classIds.dependencyGraphAnnotations)
    if (shouldGenerateMetadata) {
      val metroMetadata =
        MetroMetadata(
          METRO_VERSION,
          dependency_graph =
            DependencyGraphProto(
              is_graph = false,
              provider_factory_classes =
                generatedFactories.map { it.clazz.classIdOrFail.asString() }.sorted(),
            ),
        )
      val serialized = MetroMetadata.ADAPTER.encode(metroMetadata)
      pluginContext.metadataDeclarationRegistrar.addCustomMetadataExtension(
        declaration,
        PLUGIN_ID,
        serialized,
      )
    }
    return generatedFactories
  }

  private fun visitProperty(declaration: IrProperty) {
    val annotations = metroAnnotationsOf(declaration)
    if (!annotations.isProvides) {
      return
    }

    getOrLookupFactoryClass(getOrPutCallableReference(declaration, annotations))
  }

  private fun visitFunction(declaration: IrSimpleFunction) {
    val annotations = metroAnnotationsOf(declaration)
    if (!annotations.isProvides) {
      return
    }
    getOrLookupFactoryClass(
      getOrPutCallableReference(declaration, declaration.parentAsClass, annotations)
    )
  }

  fun getOrLookupFactoryClass(binding: Binding.Provided): ProviderFactory? {
    // Eager cache check using the factory's callable ID
    val fqName = binding.providerFactory.callableId.asSingleFqName()
    generatedFactories[fqName]?.let {
      return it
    }

    // If it's from another module, look up its already-generated factory
    if (binding.providerFactory.clazz.isExternalParent) {
      val providerFactory = externalProviderFactoryFor(binding.providerFactory.clazz)
      generatedFactories[fqName] = providerFactory
      generatedFactoriesByClass.getOrPut(fqName, ::mutableListOf).add(providerFactory)
      return providerFactory
    }

    // For factories in the current module, we need to get or create the factory
    val function =
      if (binding.providerFactory.isPropertyAccessor) {
        metroContext.pluginContext
          .referenceProperties(binding.providerFactory.callableId)
          .firstOrNull()
          ?.owner
          ?.getter
      } else {
        metroContext.pluginContext
          .referenceFunctions(binding.providerFactory.callableId)
          .firstOrNull()
          ?.owner
      }

    checkNotNull(function) {
      "Could not find (getter) function for ${binding.providerFactory.callableId}"
    }

    val reference =
      getOrPutCallableReference(
        function,
        binding.providerFactory.clazz.parentAsClass,
        binding.providerFactory.clazz.metroAnnotations(symbols.classIds),
      )

    return getOrLookupFactoryClass(reference)
  }

  @OptIn(DeprecatedForRemovalCompilerApi::class)
  fun getOrLookupFactoryClass(reference: CallableReference): ProviderFactory {
    generatedFactories[reference.fqName]?.let {
      return it
    }

    val sourceValueParameters = reference.parameters.regularParameters

    val generatedClassId = reference.generatedClassId

    val factoryCls =
      reference.parent.owner.nestedClasses.singleOrNull {
        it.origin == Origins.ProviderFactoryClassDeclaration && it.classIdOrFail == generatedClassId
      }
        ?: error(
          "No expected factory class generated for ${reference.fqName}. Report this bug with a repro case at https://github.com/zacsweers/metro/issues/new"
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
      generateMetadataVisibleMirrorFunction(
        context = metroContext,
        factoryClass = factoryCls,
        target = providesFunction,
      )

    val providerFactory =
      ProviderFactory(
        context = metroContext,
        sourceTypeKey = reference.typeKey,
        clazz = factoryCls,
        mirrorFunction = mirrorFunction,
        sourceAnnotations = reference.annotations,
      )

    factoryCls.dumpToMetroLog()

    generatedFactories[reference.fqName] = providerFactory
    generatedFactoriesByClass
      .getOrPut(reference.graphParent.kotlinFqName, ::mutableListOf)
      .add(providerFactory)

    return providerFactory
  }

  private fun getOrPutCallableReference(
    function: IrSimpleFunction,
    parent: IrClass,
    annotations: MetroAnnotations<IrAnnotation>,
  ): CallableReference {
    return references.getOrPut(function.kotlinFqName) {
      val typeKey = IrContextualTypeKey.from(this, function).typeKey
      val isPropertyAccessor = function.isPropertyAccessor
      val fqName =
        if (isPropertyAccessor) {
          function.propertyIfAccessor.expectAs<IrProperty>().fqNameWhenAvailable!!
        } else {
          function.kotlinFqName
        }
      CallableReference(
        fqName = fqName,
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
    val fqName = property.fqNameWhenAvailable ?: error("No FqName for property ${property.name}")
    return references.getOrPut(fqName) {
      val getter =
        property.getter
          ?: error(
            "No getter found for property $fqName. Note that field properties are not supported"
          )

      val typeKey = IrContextualTypeKey.from(this, getter).typeKey

      val parent = property.parentAsClass
      return CallableReference(
        fqName = fqName,
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
    val fqName: FqName,
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
        append(fqName.asString())
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
    // Eager cache check
    val parentFqName = parent.kotlinFqName
    generatedFactoriesByClass[parentFqName]?.let { cachedFactories ->
      return cachedFactories.map { it.typeKey to it }
    }

    val metadataBytes =
      if (parent.isExternalParent) {
        // Look up the external class metadata
        pluginContext.metadataDeclarationRegistrar.getCustomMetadataExtension(parent, PLUGIN_ID)
      } else {
        null
      }

    val providerFactories: List<ProviderFactory> =
      if (metadataBytes == null) {
        if (parent.isAnnotatedWithAny(symbols.dependencyGraphAnnotations)) {
          if (parent.isExternalParent) {
            error(
              "No metadata found for ${parent.kotlinFqName} from " +
                "another module. This is likely a bug in the Metro compiler."
            )
          } else {
            // Current module, not a dependency graph, unvisited
            visitClass(parent)
          }
        } else {
          if (parent.isExternalParent) {
            // Just no data in this
            return emptyList()
          } else {
            // Current module, a dependency graph, unvisited
            visitClass(parent)
          }
        }
      } else {
        val metadata = MetroMetadata.ADAPTER.decode(metadataBytes)
        metadata.dependency_graph
          ?.provider_factory_classes
          .orEmpty()
          .map { ClassId.fromString(it) }
          .map { classId ->
            val factoryClass = pluginContext.referenceClass(classId)!!.owner
            externalProviderFactoryFor(factoryClass)
          }
          .also { providerFactories ->
            // Cache the results
            generatedFactoriesByClass[parent.kotlinFqName] = providerFactories.toMutableList()
          }
      }

    return providerFactories.map { providerFactory -> providerFactory.typeKey to providerFactory }
  }

  fun externalProviderFactoryFor(factoryCls: IrClass): ProviderFactory {
    val factoryType = factoryCls.superTypes.first { it.classOrNull == symbols.metroFactory }
    // Extract IrTypeKey from Factory supertype
    // Qualifier will be populated in ProviderFactory construction
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
}
