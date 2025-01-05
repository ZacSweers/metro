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

import dev.zacsweers.lattice.compiler.LatticeOrigins
import dev.zacsweers.lattice.compiler.LatticeSymbols
import dev.zacsweers.lattice.compiler.exitProcessing
import dev.zacsweers.lattice.compiler.ir.LatticeTransformerContext
import dev.zacsweers.lattice.compiler.ir.assignConstructorParamsToFields
import dev.zacsweers.lattice.compiler.ir.createIrBuilder
import dev.zacsweers.lattice.compiler.ir.dispatchReceiverFor
import dev.zacsweers.lattice.compiler.ir.irInvoke
import dev.zacsweers.lattice.compiler.ir.irTemporary
import dev.zacsweers.lattice.compiler.ir.isExternalParent
import dev.zacsweers.lattice.compiler.ir.parameters.ConstructorParameter
import dev.zacsweers.lattice.compiler.ir.parameters.Parameter
import dev.zacsweers.lattice.compiler.ir.parameters.Parameters
import dev.zacsweers.lattice.compiler.ir.parameters.parameters
import dev.zacsweers.lattice.compiler.ir.parametersAsProviderArguments
import dev.zacsweers.lattice.compiler.ir.requireSimpleFunction
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.typeWithParameters
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.copyTypeParameters
import org.jetbrains.kotlin.ir.util.createImplicitParameterDeclarationWithWrappedDescriptor
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.nestedClasses
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.name.ClassId

internal class InjectConstructorTransformer(
  context: LatticeTransformerContext,
  private val membersInjectorTransformer: MembersInjectorTransformer,
) : LatticeTransformerContext by context {

  private val generatedFactories = mutableMapOf<ClassId, IrClass>()

  fun visitClass(declaration: IrClass) {
    val injectableConstructor =
      declaration.findInjectableConstructor(onlyUsePrimaryConstructor = false)
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

    val isExternal = declaration.isExternalParent

    /*
    Implement a simple Factory class that takes all injected values as providers

    // Simple
    class Example_Factory(private val valueProvider: Provider<String>) : Factory<Example_Factory>

    // Generic
    class Example_Factory<T>(private val valueProvider: Provider<T>) : Factory<Example_Factory<T>>
    */
    val factoryCls =
      declaration.nestedClasses.singleOrNull {
        val isLatticeFactory = it.name == LatticeSymbols.Names.latticeFactory
        // If not external, double check its origin
        if (isLatticeFactory && !isExternal) {
          if (it.origin != LatticeOrigins.InjectConstructorFactoryClassDeclaration) {
            declaration.reportError(
              "Found a Lattice factory declaration in ${declaration.kotlinFqName} but with an unexpected origin ${it.origin}"
            )
            exitProcessing()
          }
        }
        isLatticeFactory
      }

    if (factoryCls == null) {
      if (isExternal) {
        declaration.reportError(
          "Could not find generated factory for '${declaration.kotlinFqName}' in upstream module where it's defined. Run the Lattice compiler over that module too."
        )
        exitProcessing()
      } else {
        error(
          "No expected factory class generated for '${declaration.kotlinFqName}'. Report this bug with a repro case at https://github.com/zacsweers/lattice/issues/new"
        )
      }
    }

    // If it's from another module, we're done!
    // TODO this doesn't work as expected in KMP, where things compiled in common are seen as
    //  external but no factory is found?
    if (isExternal) {
      generatedFactories[injectedClassId] = factoryCls
      return factoryCls
    }

    val targetTypeParameters: List<IrTypeParameter> = declaration.typeParameters

    val injectors = membersInjectorTransformer.getOrGenerateAllInjectorsFor(declaration)
    val memberInjectParameters = injectors.flatMap { it.parameters.values.flatten() }

    val typeParameters = factoryCls.copyTypeParameters(targetTypeParameters)

    val constructorParameters =
      targetConstructor.parameters(latticeContext, factoryCls, declaration)
    val allParameters =
      buildList {
          add(constructorParameters)
          addAll(memberInjectParameters)
        }
        .distinct()
    val allValueParameters = allParameters.flatMap { it.valueParameters }
    val nonAssistedParameters = allValueParameters.filterNot { it.isAssisted }

    val factoryClassParameterized = factoryCls.symbol.typeWithParameters(typeParameters)
    val targetTypeParameterized = declaration.symbol.typeWithParameters(typeParameters)

    val ctor = factoryCls.primaryConstructor!!

    val constructorParametersToFields = assignConstructorParamsToFields(ctor, factoryCls)

    // TODO This is ugly. Can we just source all the params directly from the FIR class now?
    val sourceParametersToFields: Map<Parameter, IrField> =
      constructorParametersToFields.entries
        .withIndex()
        .associate { (index, pair) ->
          val (irParam, field) = pair
        val sourceParam = nonAssistedParameters[index]
        sourceParam to field
      }

    val newInstanceFunction =
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
    val invoke = factoryCls.requireSimpleFunction(LatticeSymbols.StringNames.invoke)

    implementInvokeOrGetBody(
      invoke.owner,
      newInstanceFunction,
      constructorParameters,
      injectors,
      sourceParametersToFields,
    )

    factoryCls.dumpToLatticeLog()

    generatedFactories[injectedClassId] = factoryCls
    return factoryCls
  }

  private fun implementInvokeOrGetBody(
    invokeFunction: IrFunction,
    newInstanceFunction: IrSimpleFunction,
    constructorParameters: Parameters<ConstructorParameter>,
    injectors: List<MembersInjectorTransformer.MemberInjectClass>,
    parametersToFields: Map<Parameter, IrField>,
  ) {
    invokeFunction.body =
      pluginContext.createIrBuilder(invokeFunction.symbol).irBlockBody {
        val assistedArgs = invokeFunction.valueParameters.map { irGet(it) }
        val newInstance =
          irInvoke(
            dispatchReceiver = dispatchReceiverFor(newInstanceFunction),
            callee = newInstanceFunction.symbol,
            args =
              assistedArgs +
                parametersAsProviderArguments(
                  context = latticeContext,
                  parameters = constructorParameters,
                  receiver = invokeFunction.dispatchReceiverParameter!!,
                  parametersToFields = parametersToFields,
                ),
          )

        if (injectors.isNotEmpty()) {
          val instance = irTemporary(newInstance)
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
                        invokeFunction.dispatchReceiverParameter!!,
                        parametersToFields,
                      )
                    )
                  },
              )
            }
          }

          +irReturn(irGet(instance))
        } else {
          +irReturn(newInstance)
        }
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
  ): IrSimpleFunction {
    // If this is an object, we can generate directly into this object
    val isObject = factoryCls.kind == ClassKind.OBJECT
    val classToGenerateCreatorsIn =
      if (isObject) {
        factoryCls
      } else {
        factoryCls.companionObject()!!
      }

    // TODO
    //  Dagger will de-dupe these by type key to shrink the code. We could do the same but only for
    //  parameters that don't have default values. For those cases, we would need to keep them
    //  as-is. Something for another day.
    val mergedParameters =
      allParameters.reduce { current, next -> current.mergeValueParametersWithUntyped(next) }

    // Generate create()
    generateStaticCreateFunction(
      context = latticeContext,
      parentClass = classToGenerateCreatorsIn,
      targetClass = factoryCls,
      targetClassParameterized = factoryClassParameterized,
      targetConstructor = factoryConstructor,
      parameters = mergedParameters,
      providerFunction = null,
    )

    val newInstanceFunction =
      generateStaticNewInstanceFunction(
        context = latticeContext,
        parentClass = classToGenerateCreatorsIn,
        sourceParameters = constructorParameters.valueParameters
          .map { it.ir },
      ) { function ->
        irCallConstructor(targetConstructor, emptyList()).apply {
          for (index in constructorParameters.allParameters.indices) {
            val parameter = function.valueParameters[index]
            putValueArgument(parameter.index, irGet(parameter))
          }
        }
      }
    return newInstanceFunction
  }
}
