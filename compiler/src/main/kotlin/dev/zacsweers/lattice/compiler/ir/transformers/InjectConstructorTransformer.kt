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

import dev.zacsweers.lattice.compiler.LatticeOrigin
import dev.zacsweers.lattice.compiler.LatticeSymbols
import dev.zacsweers.lattice.compiler.ir.LatticeTransformerContext
import dev.zacsweers.lattice.compiler.ir.addCompanionObject
import dev.zacsweers.lattice.compiler.ir.addOverride
import dev.zacsweers.lattice.compiler.ir.addStaticCreateFunction
import dev.zacsweers.lattice.compiler.ir.assignConstructorParamsToFields
import dev.zacsweers.lattice.compiler.ir.copyParameterDefaultValues
import dev.zacsweers.lattice.compiler.ir.createIrBuilder
import dev.zacsweers.lattice.compiler.ir.irBlockBody
import dev.zacsweers.lattice.compiler.ir.irInvoke
import dev.zacsweers.lattice.compiler.ir.irTemporary
import dev.zacsweers.lattice.compiler.ir.isAnnotatedWithAny
import dev.zacsweers.lattice.compiler.ir.parameters.ConstructorParameter
import dev.zacsweers.lattice.compiler.ir.parameters.Parameter
import dev.zacsweers.lattice.compiler.ir.parameters.Parameters
import dev.zacsweers.lattice.compiler.ir.parameters.parameters
import dev.zacsweers.lattice.compiler.ir.parametersAsProviderArguments
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.types.typeWithParameters
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.addSimpleDelegatingConstructor
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.copyTypeParameters
import org.jetbrains.kotlin.ir.util.createImplicitParameterDeclarationWithWrappedDescriptor
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.name.ClassId

internal class InjectConstructorTransformer(
  context: LatticeTransformerContext,
  private val membersInjectorTransformer: MembersInjectorTransformer,
) : LatticeTransformerContext by context {

  private val generatedFactories = mutableMapOf<ClassId, IrClass>()

  fun visitClass(declaration: IrClass) {
    val injectableConstructor = declaration.findInjectableConstructor()
    if (injectableConstructor != null) {
      getOrGenerateFactoryClass(declaration, injectableConstructor)
    }
  }

  @OptIn(UnsafeDuringIrConstructionAPI::class)
  fun getOrGenerateFactoryClass(declaration: IrClass, targetConstructor: IrConstructor): IrClass {
    // TODO if declaration is external to this compilation, look
    //  up its factory or warn if it doesn't exist
    val injectedClassId: ClassId = declaration.classIdOrFail
    generatedFactories[injectedClassId]?.let {
      return it
    }

    val targetTypeParameters: List<IrTypeParameter> = declaration.typeParameters

    val injectors = membersInjectorTransformer.getOrGenerateAllInjectorsFor(declaration)
    val memberInjectParameters = injectors.flatMap { it.parameters.values.flatten() }

    val canGenerateAnObject =
      targetConstructor.valueParameters.isEmpty() &&
        memberInjectParameters.all { it.valueParameters.isEmpty() } &&
        targetTypeParameters.isEmpty()

    val isAssistedInject = targetConstructor.isAnnotatedWithAny(symbols.assistedInjectAnnotations)

    /*
    Create a simple Factory class that takes all injected values as providers

    // Simple
    class Example_Factory(private val valueProvider: Provider<String>) : Factory<Example_Factory>

    // Generic
    class Example_Factory<T>(private val valueProvider: Provider<T>) : Factory<Example_Factory<T>>
    */
    val factoryCls =
      pluginContext.irFactory
        .buildClass {
          name = LatticeSymbols.Names.LatticeFactory
          kind = if (canGenerateAnObject) ClassKind.OBJECT else ClassKind.CLASS
          visibility = DescriptorVisibilities.PUBLIC
          origin = LatticeOrigin
        }
        .apply {
          // Add as a nested class of the origin class. This is important so that default value
          // expressions can access private members.
          declaration.addChild(this)
        }

    val typeParameters = factoryCls.copyTypeParameters(targetTypeParameters)

    val constructorParameters =
      targetConstructor.parameters(latticeContext, factoryCls, declaration)
    val allParameters =
      buildList {
          add(constructorParameters)
          addAll(memberInjectParameters)
        }
        .distinct()

    factoryCls.createImplicitParameterDeclarationWithWrappedDescriptor()

    if (!isAssistedInject) {
      factoryCls.superTypes =
        listOf(
          symbols.latticeFactory.typeWith(declaration.symbol.typeWithParameters(typeParameters))
        )
    }

    val factoryClassParameterized = factoryCls.symbol.typeWithParameters(typeParameters)
    val targetTypeParameterized = declaration.symbol.typeWithParameters(typeParameters)

    val ctor =
      factoryCls.addSimpleDelegatingConstructor(
        symbols.anyConstructor,
        pluginContext.irBuiltIns,
        isPrimary = true,
        origin = LatticeOrigin,
      )

    val parametersToFields =
      assignConstructorParamsToFields(ctor, factoryCls, allParameters.flatMap { it.allParameters })

    val newInstanceFunctionSymbol =
      generateCreators(
        factoryCls,
        ctor.symbol,
        targetConstructor.symbol,
        targetTypeParameterized,
        factoryClassParameterized,
        constructorParameters,
        allParameters,
      )

    /*
    Normal provider - override + implement the Provider.value property

    // Simple
    override fun invoke(): Example = newInstance(valueProvider())

    // Generic
    override fun invoke(): Example<T> = newInstance(valueProvider())

    // Provider
    override fun invoke(): Example<T> = newInstance(valueProvider)

    // Lazy
    override fun invoke(): Example<T> = newInstance(DoubleCheck.lazy(valueProvider))

    // Provider<Lazy<T>>
    override fun invoke(): Example<T> = newInstance(ProviderOfLazy.create(valueProvider))
    */
    val invokeOrGet =
      if (isAssistedInject) {
        // Assisted inject type, implement a get() with all the assisted params
        val assistedParams = constructorParameters.valueParameters.filter { it.isAssisted }
        factoryCls
          .addFunction(name = "get", returnType = targetTypeParameterized, origin = LatticeOrigin)
          .apply {
            for (assistedParam in assistedParams) {
              addValueParameter(assistedParam.name, assistedParam.originalType, LatticeOrigin)
            }
          }
      } else {
        factoryCls.addOverride(
          baseFqName = symbols.providerInvoke.owner.kotlinFqName,
          simpleName = symbols.providerInvoke.owner.name,
          returnType = targetTypeParameterized,
          overriddenSymbols = listOf(symbols.providerInvoke),
        )
      }

    implementInvokeOrGetBody(
      invokeOrGet,
      newInstanceFunctionSymbol,
      constructorParameters,
      injectors,
      factoryCls.thisReceiver!!,
      parametersToFields,
    )

    factoryCls.dumpToLatticeLog()

    generatedFactories[injectedClassId] = factoryCls
    return factoryCls
  }

  private fun implementInvokeOrGetBody(
    function: IrFunction,
    newInstanceFunctionSymbol: IrSimpleFunctionSymbol,
    constructorParameters: Parameters<ConstructorParameter>,
    injectors: List<MembersInjectorTransformer.MemberInjectClass>,
    factoryReceiver: IrValueParameter,
    parametersToFields: Map<Parameter, IrField>,
  ) {
    function.dispatchReceiverParameter = factoryReceiver
    function.body =
      pluginContext.createIrBuilder(function.symbol).irBlockBody {
        val assistedArgs = function.valueParameters.map { irGet(it) }
        val instance =
          irTemporary(
            irInvoke(
              callee = newInstanceFunctionSymbol,
              args =
                assistedArgs +
                  parametersAsProviderArguments(
                    context = latticeContext,
                    parameters = constructorParameters,
                    receiver = factoryReceiver,
                    parametersToFields = parametersToFields,
                  ),
            )
          )

        if (injectors.isNotEmpty()) {
          for (injector in injectors) {
            for ((function, parameters) in injector.injectFunctions) {
              +irInvoke(
                dispatchReceiver = irGetObject(function.parentAsClass.symbol),
                callee = function.symbol,
                args =
                  buildList {
                    add(irGet(instance))
                    addAll(
                      parametersAsProviderArguments(
                        latticeContext,
                        parameters,
                        factoryReceiver,
                        parametersToFields,
                      )
                    )
                  },
              )
            }
          }
        }

        +irReturn(irGet(instance))
      }
  }

  @OptIn(UnsafeDuringIrConstructionAPI::class)
  private fun generateCreators(
    factoryCls: IrClass,
    factoryConstructor: IrConstructorSymbol,
    targetConstructor: IrConstructorSymbol,
    targetTypeParameterized: IrType,
    factoryClassParameterized: IrType,
    constructorParameters: Parameters<ConstructorParameter>,
    allParameters: List<Parameters<out Parameter>>,
  ): IrSimpleFunctionSymbol {
    // If this is an object, we can generate directly into this object
    val isObject = factoryCls.kind == ClassKind.OBJECT
    val classToGenerateCreatorsIn =
      if (isObject) {
        factoryCls
      } else {
        pluginContext.irFactory.addCompanionObject(symbols, parent = factoryCls)
      }

    // TODO
    //  Dagger will de-dupe these by type key to shrink the code. We could do the same but only for
    // parameters
    //  that don't have default values. For those cases, we would need to keep them as-is.
    //  Something for another day.
    val mergedParameters =
      allParameters.reduce { current, next -> current.mergeValueParametersWithUntyped(next) }

    // Generate create()
    classToGenerateCreatorsIn.addStaticCreateFunction(
      context = this,
      targetClass = factoryCls,
      targetClassParameterized = factoryClassParameterized,
      targetConstructor = factoryConstructor,
      parameters = mergedParameters,
      providerFunction = null,
    )

    check(constructorParameters.instance == null) {
      "wat"
    }
    val newInstanceFunction = generateNewInstanceFunction(
      latticeContext,
      classToGenerateCreatorsIn,
      "newInstance",
      targetTypeParameterized,
      constructorParameters, // TODO ensure no instance or extension
      sourceParameters = constructorParameters.valueParameters.map { it.ir },
      sourceTypeParameters = targetConstructor.owner.typeParameters,
    ) { function ->
      irCallConstructor(targetConstructor, emptyList()).apply {
        for (index in constructorParameters.allParameters.indices) {
          val parameter = function.valueParameters[index]
          putValueArgument(parameter.index, irGet(parameter))
        }
      }
    }
    return newInstanceFunction.symbol
  }
}
