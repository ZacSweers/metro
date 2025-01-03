/*
 * Copyright (C) 2024 Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.zacsweers.lattice.compiler.ir.transformers

import dev.zacsweers.lattice.compiler.LatticeAnnotations
import dev.zacsweers.lattice.compiler.LatticeOrigin
import dev.zacsweers.lattice.compiler.LatticeSymbols
import dev.zacsweers.lattice.compiler.capitalizeUS
import dev.zacsweers.lattice.compiler.exitProcessing
import dev.zacsweers.lattice.compiler.ir.Binding
import dev.zacsweers.lattice.compiler.ir.BindingStack
import dev.zacsweers.lattice.compiler.ir.ContextualTypeKey
import dev.zacsweers.lattice.compiler.ir.IrAnnotation
import dev.zacsweers.lattice.compiler.ir.LatticeTransformerContext
import dev.zacsweers.lattice.compiler.ir.TypeKey
import dev.zacsweers.lattice.compiler.ir.addCompanionObject
import dev.zacsweers.lattice.compiler.ir.addOverride
import dev.zacsweers.lattice.compiler.ir.assignConstructorParamsToFields
import dev.zacsweers.lattice.compiler.ir.checkNotNullCall
import dev.zacsweers.lattice.compiler.ir.createIrBuilder
import dev.zacsweers.lattice.compiler.ir.dispatchReceiverFor
import dev.zacsweers.lattice.compiler.ir.irBlockBody
import dev.zacsweers.lattice.compiler.ir.irInvoke
import dev.zacsweers.lattice.compiler.ir.isCompanionObject
import dev.zacsweers.lattice.compiler.ir.latticeAnnotationsOf
import dev.zacsweers.lattice.compiler.ir.parameters.ConstructorParameter
import dev.zacsweers.lattice.compiler.ir.parameters.Parameter
import dev.zacsweers.lattice.compiler.ir.parameters.Parameters
import dev.zacsweers.lattice.compiler.ir.parameters.parameters
import dev.zacsweers.lattice.compiler.ir.parametersAsProviderArguments
import dev.zacsweers.lattice.compiler.ir.thisReceiverOrFail
import dev.zacsweers.lattice.compiler.isWordPrefixRegex
import dev.zacsweers.lattice.compiler.unsafeLazy
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.addSimpleDelegatingConstructor
import org.jetbrains.kotlin.ir.util.callableId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.createImplicitParameterDeclarationWithWrappedDescriptor
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isFakeOverride
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.packageFqName
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

internal class ProvidesTransformer(context: LatticeTransformerContext) :
  LatticeTransformerContext by context {

  private val references = mutableMapOf<FqName, CallableReference>()
  private val generatedFactories = mutableMapOf<FqName, IrClass>()

  @OptIn(UnsafeDuringIrConstructionAPI::class)
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
  }

  fun visitProperty(declaration: IrProperty) {
    val annotations = latticeAnnotationsOf(declaration)
    if (!annotations.isProvides) {
      return
    }

    getOrGenerateFactoryClass(getOrPutCallableReference(declaration, annotations))
  }

  fun visitFunction(declaration: IrSimpleFunction) {
    val annotations = latticeAnnotationsOf(declaration)
    if (!annotations.isProvides) {
      return
    }
    getOrGenerateFactoryClass(getOrPutCallableReference(declaration, annotations))
  }

  // TODO what about inherited/overridden providers?
  //  https://github.com/evant/kotlin-inject?tab=readme-ov-file#component-inheritance
  @OptIn(UnsafeDuringIrConstructionAPI::class)
  fun getOrGenerateFactoryClass(binding: Binding.Provided): IrClass {
    val reference =
      binding.providerFunction.correspondingPropertySymbol?.owner?.let {
        getOrPutCallableReference(it)
      } ?: getOrPutCallableReference(binding.providerFunction)

    // If it's from another module, look up its already-generated factory
    // TODO this doesn't work as expected in KMP, where things compiled in common are seen as
    //  external but no factory is found?
    //    if (binding.providerFunction.origin == IR_EXTERNAL_DECLARATION_STUB) {
    //      // Look up the external class
    //      // TODO do we generate it here + warn like dagger does?
    //      val generatedClass =
    //        pluginContext.referenceClass(reference.generatedClassId)
    //          ?: error(
    //            "Could not find generated factory for ${reference.fqName} in upstream module where
    // it's defined. Run the Lattice compiler over that module too."
    //          )
    //      generatedFactories[reference.fqName] = generatedClass.owner
    //      generatedClass.owner
    //    }
    return getOrGenerateFactoryClass(reference)
  }

  @OptIn(UnsafeDuringIrConstructionAPI::class)
  fun getOrGenerateFactoryClass(reference: CallableReference): IrClass {
    // TODO if declaration is external to this compilation, look
    //  up its factory or warn if it doesn't exist
    generatedFactories[reference.fqName]?.let {
      return it
    }

    // TODO unimplemented for now
    if (reference.parameters.extensionReceiver != null) {
      // Checked in FIR
      reference.parameters.ir!!.reportError(
        "Unexpected extension receiver. This is a bug in Lattice, please file a bug report at https://github.com/zacsweers/lattice/issues/new"
      )
      exitProcessing()
    }

    // TODO FIR check parent class (if any) is a graph. What about (companion) objects?
    // TODO FIR check function is not abstract
    // TODO FIR check for duplicate functions (by name, params don't count). Does this matter in FIR
    //  tho

    // TODO Private functions need to be visible downstream. To do this we use a new API to add
    // custom metadata
    //    if (!reference.callee.owner.visibility.isVisibleOutside()) {
    //      pluginContext.metadataDeclarationRegistrar
    //    }

    val valueParameters = reference.parameters.valueParameters

    val returnType = reference.typeKey.type

    val generatedClassId = reference.generatedClassId

    val byteCodeFunctionName =
      when {
        reference.useGetPrefix -> "get" + reference.name.capitalizeUS()
        else -> reference.name.asString()
      }

    val canGenerateAnObject = reference.isInObject && valueParameters.isEmpty()
    val factoryCls =
      pluginContext.irFactory
        .buildClass {
          name = generatedClassId.relativeClassName.shortName()
          kind = if (canGenerateAnObject) ClassKind.OBJECT else ClassKind.CLASS
          visibility = DescriptorVisibilities.PUBLIC
          origin = LatticeOrigin
        }
        .apply {
          // Add as a nested class of the origin graph. This is important so that default value
          // expressions can access private members.
          reference.graphParent.addChild(this)

          createImplicitParameterDeclarationWithWrappedDescriptor()
          superTypes += symbols.latticeFactory.typeWith(returnType)
        }

    val factoryClassParameterized = factoryCls.typeWith()

    // Implement constructor w/ params if necessary
    val ctor =
      factoryCls.addSimpleDelegatingConstructor(
        symbols.anyConstructor,
        pluginContext.irBuiltIns,
        isPrimary = true,
        origin = LatticeOrigin,
      )

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

    val factoryParameters =
      Parameters(
        reference.callee.owner.callableId,
        instance = instanceParam,
        extensionReceiver = null,
        valueParameters = valueParameters,
        ir = null, // Will set later
      )

    val parametersToFields =
      assignConstructorParamsToFields(ctor, factoryCls, factoryParameters.allParameters)

    val bytecodeFunction =
      generateCreators(
        factoryCls,
        ctor.symbol,
        reference,
        factoryClassParameterized,
        factoryParameters,
        byteCodeFunctionName,
      )

    // Implement invoke()
    // TODO DRY this up with the constructor injection override
    factoryCls
      .addOverride(
        baseFqName = symbols.providerInvoke.owner.kotlinFqName,
        simpleName = symbols.providerInvoke.owner.name,
        returnType = returnType,
        overriddenSymbols = listOf(symbols.providerInvoke),
      )
      .apply {
        this.dispatchReceiverParameter = factoryCls.thisReceiverOrFail
        body =
          pluginContext.createIrBuilder(symbol).run {
            irBlockBody(
              symbol,
              irInvoke(
                dispatchReceiver = dispatchReceiverFor(bytecodeFunction),
                callee = bytecodeFunction.symbol,
                args =
                  parametersAsProviderArguments(
                    latticeContext,
                    parameters = factoryParameters,
                    receiver = factoryCls.thisReceiverOrFail,
                    parametersToFields = parametersToFields,
                  ),
              ),
            )
          }
      }

    factoryCls.dumpToLatticeLog()

    generatedFactories[reference.fqName] = factoryCls
    return factoryCls
  }

  fun getOrPutCallableReference(function: IrSimpleFunction, annotations: LatticeAnnotations<IrAnnotation> = latticeAnnotationsOf(function)): CallableReference {
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
        isInternal = function.visibility == DescriptorVisibilities.INTERNAL,
        name = function.name,
        isProperty = false,
        parameters = function.parameters(this),
        typeKey = typeKey,
        isNullable = typeKey.type.isMarkedNullable(),
        isPublishedApi = function.hasAnnotation(LatticeSymbols.ClassIds.PublishedApi),
        reportableNode = function,
        parent = parent.symbol,
        callee = function.symbol,
        annotations = annotations,
      )
    }
  }

  fun getOrPutCallableReference(property: IrProperty, annotations: LatticeAnnotations<IrAnnotation> = latticeAnnotationsOf(property)): CallableReference {
    val fqName = property.fqNameWhenAvailable ?: error("No FqName for property ${property.name}")
    return references.getOrPut(fqName) {
      // TODO FIR error if it has a receiver param
      // TODO FIR check property is not var
      // TODO FIR check property is visible
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
        isInternal = property.visibility == DescriptorVisibilities.INTERNAL,
        name = property.name,
        isProperty = true,
        parameters = property.getter?.parameters(this) ?: Parameters.empty(),
        typeKey = typeKey,
        isNullable = typeKey.type.isMarkedNullable(),
        isPublishedApi = property.hasAnnotation(LatticeSymbols.ClassIds.PublishedApi),
        reportableNode = property,
        parent = parent.symbol,
        callee = property.getter!!.symbol,
        annotations = annotations,
      )
    }
  }

  @OptIn(UnsafeDuringIrConstructionAPI::class)
  private fun generateCreators(
    factoryCls: IrClass,
    factoryConstructor: IrConstructorSymbol,
    reference: CallableReference,
    factoryClassParameterized: IrType,
    factoryParameters: Parameters<ConstructorParameter>,
    byteCodeFunctionName: String,
  ): IrSimpleFunction {
    val targetTypeParameterized = reference.typeKey.type
    val returnTypeIsNullable = reference.isNullable

    // If this is an object, we can generate directly into this object
    val isObject = factoryCls.kind == ClassKind.OBJECT
    val classToGenerateCreatorsIn =
      if (isObject) {
        factoryCls
      } else {
        pluginContext.irFactory.addCompanionObject(symbols, parent = factoryCls)
      }

    // Generate create()
    generateStaticCreateFunction(
      context = latticeContext,
      parentClass = classToGenerateCreatorsIn,
      targetClass = factoryCls,
      targetClassParameterized = factoryClassParameterized,
      targetConstructor = factoryConstructor,
      parameters = factoryParameters,
      providerFunction = reference.callee.owner,
    )

    // Generate the named newInstance function
    val newInstanceFunction =
      generateStaticNewInstanceFunction(
        latticeContext,
        classToGenerateCreatorsIn,
        byteCodeFunctionName,
        targetTypeParameterized,
        factoryParameters,
        targetFunction = reference.callee.owner,
        sourceParameters = reference.parameters.valueParameters.map { it.ir },
      ) { function ->
        val valueParameters = function.valueParameters

        val argumentsWithoutGraph: IrBuilderWithScope.() -> List<IrExpression> = {
          valueParameters.drop(1).map { irGet(it) }
        }
        val arguments: IrBuilderWithScope.() -> List<IrExpression> = {
          valueParameters.map { irGet(it) }
        }

        when {
          isObject && returnTypeIsNullable -> {
            // Static graph call, allows nullable returns
            // ExampleGraph.$callableName$arguments
            irInvoke(
              dispatchReceiver = irGetObject(reference.parent),
              extensionReceiver = null, // TODO unimplemented
              callee = reference.callee,
              args = arguments(),
            )
          }
          isObject && !returnTypeIsNullable -> {
            // Static graph call that doesn't allow nullable
            // checkNotNull(ExampleGraph.$callableName$arguments) {
            //   "Cannot return null from a non-@Nullable @Provides method"
            // }
            checkNotNullCall(
              latticeContext,
              function,
              irInvoke(
                dispatchReceiver = irGetObject(reference.parent),
                extensionReceiver = null, // TODO unimplemented
                callee = reference.callee,
                args = arguments(),
              ),
              "Cannot return null from a non-@Nullable @Provides method",
            )
          }
          !isObject && returnTypeIsNullable -> {
            // Instance graph call, allows nullable returns
            // exampleGraph.$callableName$arguments
            irInvoke(
              dispatchReceiver = irGet(valueParameters[0]),
              extensionReceiver = null, // TODO unimplemented
              reference.callee,
              args = argumentsWithoutGraph(),
            )
          }
          // !isObject && !returnTypeIsNullable
          else -> {
            // Instance graph call, does not allow nullable returns
            // exampleGraph.$callableName$arguments
            checkNotNullCall(
              latticeContext,
              function,
              irInvoke(
                dispatchReceiver = irGet(valueParameters[0]),
                extensionReceiver = null, // TODO unimplemented
                callee = reference.callee,
                args = argumentsWithoutGraph(),
              ),
              "Cannot return null from a non-@Nullable @Provides method",
            )
          }
        }
      }

    return newInstanceFunction
  }

  internal class CallableReference(
    val fqName: FqName,
    val isInternal: Boolean,
    val name: Name,
    val isProperty: Boolean,
    val parameters: Parameters<ConstructorParameter>,
    val typeKey: TypeKey,
    val isNullable: Boolean,
    val isPublishedApi: Boolean,
    val reportableNode: Any,
    val parent: IrClassSymbol,
    val callee: IrFunctionSymbol,
    val annotations: LatticeAnnotations<IrAnnotation>
  ) {
    @OptIn(UnsafeDuringIrConstructionAPI::class)
    val isInCompanionObject: Boolean
      get() = parent.owner.isCompanionObject

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    val isInObject: Boolean
      get() = parent.owner.isObject

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    val graphParent =
      if (parent.owner.isCompanionObject) {
        parent.owner.parentAsClass
      } else {
        parent.owner
      }

    // omit the `get-` prefix for property names starting with the *word* `is`, like `isProperty`,
    // but not for names which just start with those letters, like `issues`.
    // TODO still necessary in IR?
    val useGetPrefix by unsafeLazy { isProperty && !isWordPrefixRegex.matches(name.asString()) }

    @OptIn(UnsafeDuringIrConstructionAPI::class) val packageName = graphParent.packageFqName!!
    @OptIn(UnsafeDuringIrConstructionAPI::class)
    val simpleName by lazy {
      buildString {
        if (isInCompanionObject) {
          append("Companion_")
        }
        if (useGetPrefix) {
          append("Get")
        }
        append(name.capitalizeUS())
        append(LatticeSymbols.Names.LatticeFactory.asString())
      }
    }

    val generatedClassId by lazy {
      graphParent.classIdOrFail.createNestedClassId(Name.identifier(simpleName))
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
