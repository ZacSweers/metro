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
package dev.zacsweers.lattice.transformers

import dev.zacsweers.lattice.LatticeOrigin
import dev.zacsweers.lattice.LatticeSymbols
import dev.zacsweers.lattice.capitalizeUS
import dev.zacsweers.lattice.exitProcessing
import dev.zacsweers.lattice.ir.addCompanionObject
import dev.zacsweers.lattice.ir.addOverride
import dev.zacsweers.lattice.ir.assignConstructorParamsToFields
import dev.zacsweers.lattice.ir.buildFactoryCreateFunction
import dev.zacsweers.lattice.ir.checkNotNullCall
import dev.zacsweers.lattice.ir.createIrBuilder
import dev.zacsweers.lattice.ir.irInvoke
import dev.zacsweers.lattice.ir.isAnnotatedWithAny
import dev.zacsweers.lattice.ir.isCompanionObject
import dev.zacsweers.lattice.ir.parametersAsProviderArguments
import dev.zacsweers.lattice.joinSimpleNames
import dev.zacsweers.lattice.unsafeLazy
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.addSimpleDelegatingConstructor
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.createImplicitParameterDeclarationWithWrappedDescriptor
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.getPackageFragment
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.packageFqName
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames

internal val isWordPrefixRegex = "^is([^a-z].*)".toRegex()

internal class ProvidesTransformer(context: LatticeTransformerContext) :
  LatticeTransformerContext by context {

  private val references = mutableMapOf<FqName, CallableReference>()
  private val generatedFactories = mutableMapOf<FqName, IrClass>()

  @OptIn(UnsafeDuringIrConstructionAPI::class)
  fun visitComponentClass(declaration: IrClass) {
    declaration.declarations.forEach { nestedDeclaration ->
      when (nestedDeclaration) {
        is IrProperty -> visitProperty(nestedDeclaration)
        is IrSimpleFunction -> visitFunction(nestedDeclaration)
        is IrClass -> {
          if (nestedDeclaration.isCompanionObject) {
            // Include companion object refs
            visitComponentClass(nestedDeclaration)
          }
        }
      }
    }
  }

  fun visitProperty(declaration: IrProperty) {
    if (
      !declaration.isAnnotatedWithAny(symbols.providesAnnotations) &&
        declaration.getter?.isAnnotatedWithAny(symbols.providesAnnotations) != true
    ) {
      return
    }

    getOrGenerateFactoryClass(getOrPutCallableReference(declaration))
  }

  fun visitFunction(declaration: IrSimpleFunction) {
    if (!declaration.isAnnotatedWithAny(symbols.providesAnnotations)) return
    getOrGenerateFactoryClass(getOrPutCallableReference(declaration))
  }

  fun visitClass(declaration: IrClass) {
    if (!declaration.isAnnotatedWithAny(symbols.componentAnnotations)) return
    visitComponentClass(declaration)
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
      reference.callee.owner.reportError(
        """
          Extension receivers are not currently supported.
        """
          .trimIndent()
      )
      exitProcessing()
    }

    // TODO FIR check parent class (if any) is a component. What about (companion) objects?
    // TODO FIR check function is not abstract
    // TODO FIR check for duplicate functions (by name, params don't count). Does this matter in FIR
    //  tho

    val parameters = reference.parameters.valueParameters

    val returnType = reference.typeKey.type

    val generatedClassId = reference.generatedClassId
    val byteCodeFunctionName =
      when {
        reference.useGetPrefix -> "get" + reference.name.capitalizeUS()
        else -> reference.name.asString()
      }

    val canGenerateAnObject = reference.isInObject && parameters.isEmpty()
    val factoryCls =
      pluginContext.irFactory
        .buildClass {
          name = generatedClassId.relativeClassName.shortName()
          kind = if (canGenerateAnObject) ClassKind.OBJECT else ClassKind.CLASS
          visibility = DescriptorVisibilities.PUBLIC
        }
        .apply { origin = LatticeOrigin }

    factoryCls.createImplicitParameterDeclarationWithWrappedDescriptor()
    factoryCls.superTypes += symbols.latticeFactory.typeWith(returnType)

    val factoryClassParameterized = factoryCls.typeWith()

    // Implement constructor w/ params if necessary
    val ctor =
      factoryCls.addSimpleDelegatingConstructor(
        symbols.anyConstructor,
        pluginContext.irBuiltIns,
        isPrimary = true,
        origin = LatticeOrigin,
      )

    val componentType = reference.componentParent.typeWith()

    val allParameters = buildList {
      if (!reference.isInObject) {
        val typeMetadata =
          TypeMetadata(
            typeKey = TypeKey(componentType),
            isWrappedInProvider = false,
            isWrappedInLazy = false,
            isLazyWrappedInProvider = false,
          )
        add(
          ConstructorParameter(
            kind = Parameter.Kind.VALUE,
            name = Name.identifier("component"),
            typeMetadata = typeMetadata,
            originalName = Name.identifier("component"),
            // This type is always the instance type
            providerType = componentType,
            lazyType = componentType,
            isAssisted = false,
            assistedIdentifier = SpecialNames.NO_NAME_PROVIDED,
            symbols = symbols,
            isComponentInstance = true,
            // TODO is this right/ever going to happen?
            bindingStackEntry = BindingStackEntry.simpleTypeRef(typeMetadata.typeKey),
            isBindsInstance = false,
          )
        )
      }
      addAll(parameters)
    }

    val parametersToFields = assignConstructorParamsToFields(ctor, factoryCls, allParameters)

    val bytecodeFunctionSymbol =
      generateCreators(
        factoryCls,
        ctor.symbol,
        reference,
        factoryClassParameterized,
        allParameters,
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
        this.dispatchReceiverParameter = factoryCls.thisReceiver!!
        body =
          pluginContext.createIrBuilder(symbol).run {
            irExprBody(
              irInvoke(
                callee = bytecodeFunctionSymbol,
                args =
                  parametersAsProviderArguments(
                    parameters = allParameters,
                    receiver = factoryCls.thisReceiver!!,
                    parametersToFields = parametersToFields,
                    symbols = symbols,
                  ),
              )
            )
          }
      }

    factoryCls.dumpToLatticeLog()

    reference.componentParent.getPackageFragment().addChild(factoryCls)
    generatedFactories[reference.fqName] = factoryCls
    return factoryCls
  }

  fun getOrPutCallableReference(function: IrSimpleFunction): CallableReference {
    // TODO report in FIR
    if (function.typeParameters.isNotEmpty()) {
      function.reportError("@Provides functions may not have type parameters")
      exitProcessing()
    }

    return references.getOrPut(function.kotlinFqName) {
      // TODO FIR error if it has a receiver param
      // TODO FIR error if it is top-level/not in component

      val parent = function.parentAsClass
      val typeKey = TypeMetadata.from(this, function).typeKey
      CallableReference(
        fqName = function.kotlinFqName,
        isInternal = function.visibility == Visibilities.Internal,
        name = function.name,
        isProperty = false,
        parameters = function.parameters(this),
        typeKey = typeKey,
        isNullable = typeKey.type.isMarkedNullable(),
        isPublishedApi = function.hasAnnotation(LatticeSymbols.ClassIds.PublishedApi),
        reportableNode = function,
        parent = parent.symbol,
        callee = function.symbol,
      )
    }
  }

  fun getOrPutCallableReference(property: IrProperty): CallableReference {
    val fqName = property.fqNameWhenAvailable ?: error("No FqName for property ${property.name}")
    return references.getOrPut(fqName) {
      // TODO FIR error if it has a receiver param
      // TODO FIR check property is not var
      // TODO FIR check property is visible
      // TODO enforce get:? enforce no site target?
      // TODO FIR error if it is top-level/not in component

      val getter =
        property.getter
          ?: error(
            "No getter found for property $fqName. Note that field properties are not supported"
          )

      val typeKey = TypeMetadata.from(this, getter).typeKey

      val parent = property.parentAsClass
      return CallableReference(
        fqName = fqName,
        isInternal = property.visibility == Visibilities.Internal,
        name = property.name,
        isProperty = true,
        parameters = property.getter?.parameters(this) ?: Parameters.EMPTY,
        typeKey = typeKey,
        isNullable = typeKey.type.isMarkedNullable(),
        isPublishedApi = property.hasAnnotation(LatticeSymbols.ClassIds.PublishedApi),
        reportableNode = property,
        parent = parent.symbol,
        callee = property.getter!!.symbol,
      )
    }
  }

  private fun generateCreators(
    factoryCls: IrClass,
    factoryConstructor: IrConstructorSymbol,
    reference: CallableReference,
    factoryClassParameterized: IrType,
    allParameters: List<Parameter>,
    byteCodeFunctionName: String,
  ): IrSimpleFunctionSymbol {
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
    classToGenerateCreatorsIn.buildFactoryCreateFunction(
      this,
      factoryCls,
      factoryClassParameterized,
      factoryConstructor,
      allParameters,
    )

    // Generate the named function

    /*
     Implement a static newInstance() function

     // Simple
     @JvmStatic // JVM only
     fun newInstance(value: T): Example = Example(value)

     // Generic
     @JvmStatic // JVM only
     fun <T> newInstance(value: T): Example<T> = Example<T>(value)

     // Provider
     @JvmStatic // JVM only
     fun newInstance(value: Provider<String>): Example = Example(value)
    */
    // TODO need to support either calling JvmDefault or DefaultImpls?
    val newInstanceFunction =
      classToGenerateCreatorsIn
        .addFunction(byteCodeFunctionName, targetTypeParameterized, isStatic = true)
        .apply {
          // No type params for this
          this.origin = LatticeOrigin
          this.visibility = DescriptorVisibilities.PUBLIC
          markJvmStatic()
          for (parameter in allParameters) {
            addValueParameter(parameter.name, parameter.originalType, LatticeOrigin)
          }
          val argumentsWithoutComponent: IrBuilderWithScope.() -> List<IrExpression> = {
            valueParameters.drop(1).map { irGet(it) }
          }
          val arguments: IrBuilderWithScope.() -> List<IrExpression> = {
            valueParameters.map { irGet(it) }
          }
          body =
            pluginContext.createIrBuilder(symbol).run {
              val expression: IrExpression =
                when {
                  isObject && returnTypeIsNullable -> {
                    // Static component call, allows nullable returns
                    // ExampleComponent.$callableName$arguments
                    irInvoke(
                      dispatchReceiver = irGetObject(reference.parent),
                      extensionReceiver = null, // TODO unimplemented
                      callee = reference.callee,
                      args = arguments(),
                    )
                  }
                  isObject && !returnTypeIsNullable -> {
                    // Static component call that doesn't allow nullable
                    // checkNotNull(ExampleComponent.$callableName$arguments) {
                    //   "Cannot return null from a non-@Nullable @Provides method"
                    // }
                    checkNotNullCall(
                      this@ProvidesTransformer,
                      this@apply.parent, // TODO this is obvi wrong
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
                    // Instance component call, allows nullable returns
                    // exampleComponent.$callableName$arguments
                    irInvoke(
                      dispatchReceiver = irGet(valueParameters[0]),
                      extensionReceiver = null, // TODO unimplemented
                      reference.callee,
                      args = argumentsWithoutComponent(),
                    )
                  }
                  // !isObject && !returnTypeIsNullable
                  else -> {
                    // Instance component call, does not allow nullable returns
                    // exampleComponent.$callableName$arguments
                    checkNotNullCall(
                      this@ProvidesTransformer,
                      this@apply.parent, // TODO this is obvi wrong
                      irInvoke(
                        dispatchReceiver = irGet(valueParameters[0]),
                        extensionReceiver = null, // TODO unimplemented
                        callee = reference.callee,
                        args = argumentsWithoutComponent(),
                      ),
                      "Cannot return null from a non-@Nullable @Provides method",
                    )
                  }
                }
              irExprBody(expression)
            }
        }

    return newInstanceFunction.symbol
  }

  internal class CallableReference(
    val fqName: FqName,
    val isInternal: Boolean,
    val name: Name,
    val isProperty: Boolean,
    val parameters: Parameters,
    val typeKey: TypeKey,
    val isNullable: Boolean,
    val isPublishedApi: Boolean,
    val reportableNode: Any,
    val parent: IrClassSymbol,
    val callee: IrFunctionSymbol,
  ) {
    @OptIn(UnsafeDuringIrConstructionAPI::class)
    val isInCompanionObject: Boolean
      get() = parent.owner.isCompanionObject

    @OptIn(UnsafeDuringIrConstructionAPI::class)
    val isInObject: Boolean
      get() = parent.owner.isObject

    @UnsafeDuringIrConstructionAPI
    val componentParent =
      if (parent.owner.isCompanionObject) {
        parent.owner.parentAsClass
      } else {
        parent.owner
      }

    // omit the `get-` prefix for property names starting with the *word* `is`, like `isProperty`,
    // but not for names which just start with those letters, like `issues`.
    // TODO still necessary in IR?
    val useGetPrefix by unsafeLazy { isProperty && !isWordPrefixRegex.matches(name.asString()) }

    @OptIn(UnsafeDuringIrConstructionAPI::class) val packageName = componentParent.packageFqName!!
    @OptIn(UnsafeDuringIrConstructionAPI::class)
    val simpleName by lazy {
      buildString {
        append(
          componentParent.classIdOrFail
            .joinSimpleNames()
            .relativeClassName
            .pathSegments()
            .joinToString("_")
        )
        append('_')
        if (isInCompanionObject) {
          append("Companion_")
        }
        if (useGetPrefix) {
          append("Get")
        }
        append(name.capitalizeUS())
        append("Factory")
      }
    }
    val generatedClassId by lazy { ClassId(packageName, Name.identifier(simpleName)) }

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
