// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.MetroAnnotations
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.capitalizeUS
import dev.zacsweers.metro.compiler.exitProcessing
import dev.zacsweers.metro.compiler.ir.Binding
import dev.zacsweers.metro.compiler.ir.BindingStack
import dev.zacsweers.metro.compiler.ir.ContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrAnnotation
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.TypeKey
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
import dev.zacsweers.metro.compiler.ir.parameters.ConstructorParameter
import dev.zacsweers.metro.compiler.ir.parameters.Parameter
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.parametersAsProviderArguments
import dev.zacsweers.metro.compiler.ir.requireSimpleFunction
import dev.zacsweers.metro.compiler.ir.thisReceiverOrFail
import dev.zacsweers.metro.compiler.isWordPrefixRegex
import dev.zacsweers.metro.compiler.proto.DependencyGraphProto
import dev.zacsweers.metro.compiler.proto.MetroMetadata
import dev.zacsweers.metro.compiler.unsafeLazy
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.callableId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isFakeOverride
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.nestedClasses
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

internal class ProvidesTransformer(context: IrMetroContext) : IrMetroContext by context {

  private val references = mutableMapOf<FqName, CallableReference>()
  private val generatedFactories = mutableMapOf<FqName, IrClass>()
  private val generatedFactoriesByClass = mutableMapOf<FqName, MutableList<ClassId>>()

  fun visitClass(declaration: IrClass) {
    // Defensive copy because we add to this class in some factories!
    val sourceDeclarations =
      declaration.declarations
        .asSequence()
        // Skip fake overrides, we care only about the original declaration because those have
        // default values
        .filterNot { it.isFakeOverride }
        .toList()
    sourceDeclarations.forEach { nestedDeclaration ->
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
    val generatedFactories = generatedFactoriesByClass[declaration.kotlinFqName].orEmpty()
    val shouldGenerateMetadata =
      generatedFactories.isNotEmpty() &&
        !declaration.isAnnotatedWithAny(symbols.classIds.dependencyGraphAnnotations)
    if (shouldGenerateMetadata) {
      // TODO store metadata for graph's providers
      // TODO can we just return this view?
      //  need to copy qualifiers and scopes to the generated factory for reference
      val metroMetadata =
        MetroMetadata(
          DependencyGraphProto(
            is_graph = false,
            provider_factory_classes = generatedFactories.map { it.asString() }.sorted(),
          )
        )
      val serialized = MetroMetadata.ADAPTER.encode(metroMetadata)
      // TODO
      //  pluginContext.metadataDeclarationRegistrar.addCustomMetadataExtension(
      //    declaration,
      //    PLUGIN_ID,
      //    serialized
      //  )
    }
  }

  private fun visitProperty(declaration: IrProperty) {
    val annotations = metroAnnotationsOf(declaration)
    if (!annotations.isProvides) {
      return
    }

    getOrGenerateFactoryClass(getOrPutCallableReference(declaration, annotations))
  }

  private fun visitFunction(declaration: IrSimpleFunction) {
    val annotations = metroAnnotationsOf(declaration)
    if (!annotations.isProvides) {
      return
    }
    getOrGenerateFactoryClass(getOrPutCallableReference(declaration, annotations))
  }

  fun getOrGenerateFactoryClass(binding: Binding.Provided): IrClass? {
    val reference =
      binding.providerFunction.correspondingPropertySymbol?.owner?.let {
        getOrPutCallableReference(it)
      } ?: getOrPutCallableReference(binding.providerFunction)

    // Eager cache check
    generatedFactories[reference.fqName]?.let {
      return it
    }

    // If it's from another module, look up its already-generated factory
    // TODO this doesn't work as expected in KMP, where things compiled in common are seen as
    //  external but no factory is found?
    if (binding.providerFunction.parentAsClass.isExternalParent) {
      // Look up the external class
      val generatedClass =
        binding.providerFunction.parentAsClass.nestedClasses.find {
          it.name == reference.generatedClassId.shortClassName
        }
      if (generatedClass == null) {
        reference.callee.owner.reportError(
          "Could not find generated factory for ${reference.fqName} in upstream module where it's defined. Run the Metro compiler over that module too."
        )
        return null
      }
      generatedFactories[reference.fqName] = generatedClass
      generatedFactoriesByClass
        .getOrPut(reference.fqName, ::mutableListOf)
        .add(generatedClass.classIdOrFail)
    }
    return getOrGenerateFactoryClass(reference)
  }

  fun getOrGenerateFactoryClass(reference: CallableReference): IrClass {
    generatedFactories[reference.fqName]?.let {
      return it
    }

    // TODO FIR check for duplicate functions (by name, params don't count). Does this matter in FIR
    //  tho

    val sourceValueParameters = reference.parameters.valueParameters

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
        val contextualTypeKey =
          ContextualTypeKey(
            typeKey = TypeKey(graphType),
            isWrappedInProvider = false,
            isWrappedInLazy = false,
            isLazyWrappedInProvider = false,
            hasDefault = false,
          )
        ConstructorParameter(
          kind = Parameter.Kind.VALUE,
          name = Name.identifier("graph"),
          contextualTypeKey = contextualTypeKey,
          originalName = Name.identifier("graph"),
          // This type is always the instance type
          providerType = graphType,
          lazyType = graphType,
          isAssisted = false,
          assistedIdentifier = "",
          symbols = symbols,
          isGraphInstance = true,
          // TODO is this right/ever going to happen?
          bindingStackEntry = BindingStack.Entry.simpleTypeRef(contextualTypeKey),
          isBindsInstance = false,
          hasDefault = false,
          location = null,
        )
      } else {
        null
      }

    val sourceParameters =
      Parameters(
        reference.callee.owner.callableId,
        instance = instanceParam,
        extensionReceiver = null,
        valueParameters = sourceValueParameters,
        ir = null, // Will set later
      )

    val constructorParametersToFields = assignConstructorParamsToFields(ctor, factoryCls)

    // TODO This is ugly
    var parameterIndexOffset = 0
    if (!reference.isInObject) {
      // These will always have an instance parameter
      parameterIndexOffset++
    }
    if (reference.callee.owner.extensionReceiverParameter != null) {
      parameterIndexOffset++
    }
    val sourceParametersToFields: Map<Parameter, IrField> =
      constructorParametersToFields.entries.associate { (irParam, field) ->
        val sourceParam =
          if (irParam.origin == Origins.InstanceParameter) {
            sourceParameters.instance!!
          } else if (irParam.index == -1) {
            error(
              "No source parameter found for $irParam. Index was somehow -1.\n${reference.parent.owner.dumpKotlinLike()}"
            )
          } else {
            sourceParameters.valueParameters.getOrNull(irParam.index - parameterIndexOffset)
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

    factoryCls.dumpToMetroLog()

    generatedFactories[reference.fqName] = factoryCls
    generatedFactoriesByClass
      .getOrPut(reference.graphParent.kotlinFqName, ::mutableListOf)
      .add(generatedClassId)

    return factoryCls
  }

  private fun getOrPutCallableReference(
    function: IrSimpleFunction,
    annotations: MetroAnnotations<IrAnnotation> = metroAnnotationsOf(function),
  ): CallableReference {
    // TODO report in FIR
    if (function.typeParameters.isNotEmpty()) {
      function.reportError("@Provides functions may not have type parameters")
      exitProcessing()
    }

    return references.getOrPut(function.kotlinFqName) {
      // TODO FIR error if it has a receiver param
      // TODO FIR error if it is top-level/not in graph

      val parent = function.parentAsClass
      val typeKey = ContextualTypeKey.from(this, function, annotations).typeKey
      CallableReference(
        fqName = function.kotlinFqName,
        name = function.name,
        isProperty = false,
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
      // TODO FIR error if it has a receiver param
      // TODO FIR check property is not var
      // TODO enforce get:? enforce no site target?
      // TODO FIR error if it is top-level/not in graph

      val getter =
        property.getter
          ?: error(
            "No getter found for property $fqName. Note that field properties are not supported"
          )

      val typeKey = ContextualTypeKey.from(this, getter, annotations).typeKey

      val parent = property.parentAsClass
      return CallableReference(
        fqName = fqName,
        name = property.name,
        isProperty = true,
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
    factoryParameters: Parameters<ConstructorParameter>,
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
        sourceParameters = reference.parameters.valueParameters.map { it.ir },
      ) { function ->
        val valueParameters = function.valueParameters

        val args = valueParameters.filter { it.origin == Origins.ValueParameter }.map { irGet(it) }

        val dispatchReceiver =
          if (reference.isInObject) {
            // Static graph call
            // ExampleGraph.$callableName$arguments
            irGetObject(reference.parent)
          } else {
            // Instance graph call
            // exampleGraph.$callableName$arguments
            irGet(valueParameters[0])
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
    val isProperty: Boolean,
    val parameters: Parameters<ConstructorParameter>,
    val typeKey: TypeKey,
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
      isProperty && !isWordPrefixRegex.matches(name.asString())
    }

    private val simpleName by lazy {
      buildString {
        if (useGetPrefix) {
          append("Get")
        }
        append(name.capitalizeUS())
        append(Symbols.Names.metroFactory.asString())
      }
    }

    val generatedClassId by lazy {
      parent.owner.classIdOrFail.createNestedClassId(Name.identifier(simpleName))
    }

    private val cachedToString by lazy {
      buildString {
        append(fqName.asString())
        if (!isProperty) {
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
}
