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
package dev.zacsweers.lattice.ir.transformers

import dev.zacsweers.lattice.LatticeOrigin
import dev.zacsweers.lattice.LatticeSymbols
import dev.zacsweers.lattice.ir.LatticeTransformerContext
import dev.zacsweers.lattice.ir.Parameter
import dev.zacsweers.lattice.ir.Parameters
import dev.zacsweers.lattice.ir.addCompanionObject
import dev.zacsweers.lattice.ir.addOverride
import dev.zacsweers.lattice.ir.assignConstructorParamsToFields
import dev.zacsweers.lattice.ir.buildFactoryCreateFunction
import dev.zacsweers.lattice.ir.createIrBuilder
import dev.zacsweers.lattice.ir.irBlockBody
import dev.zacsweers.lattice.ir.irInvoke
import dev.zacsweers.lattice.ir.irTemporary
import dev.zacsweers.lattice.ir.isAnnotatedWithAny
import dev.zacsweers.lattice.ir.parameters
import dev.zacsweers.lattice.ir.parametersAsProviderArguments
import dev.zacsweers.lattice.ir.patchFactoryCreationParameters
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
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
import org.jetbrains.kotlin.name.ClassId

internal class InjectConstructorTransformer(context: LatticeTransformerContext) :
  LatticeTransformerContext by context {

  private val generatedFactories = mutableMapOf<ClassId, IrClass>()

  fun visitClass(declaration: IrClass) {
    val injectableConstructor = declaration.findInjectableConstructor()
    if (injectableConstructor != null) {
      getOrGenerateFactoryClass(declaration, injectableConstructor)
    }
  }

  @OptIn(UnsafeDuringIrConstructionAPI::class)
  fun getOrGenerateFactoryClass(
    declaration: IrClass,
    targetConstructor: IrConstructor,
    // TODO
    //    memberInjectParameters: List<MemberInjectParameter>,
  ): IrClass {
    // TODO if declaration is external to this compilation, look
    //  up its factory or warn if it doesn't exist
    val injectedClassId: ClassId = declaration.classIdOrFail
    generatedFactories[injectedClassId]?.let {
      return it
    }

    val targetTypeParameters: List<IrTypeParameter> = declaration.typeParameters

    val canGenerateAnObject =
      targetConstructor.valueParameters.isEmpty() &&
        //      memberInjectParameters.isEmpty() &&
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

    val constructorParameters = targetConstructor.parameters(this, factoryCls, declaration)
    // TODO + memberInjectParameters

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
      assignConstructorParamsToFields(ctor, factoryCls, constructorParameters)

    val newInstanceFunctionSymbol =
      generateCreators(
        factoryCls,
        ctor.symbol,
        targetConstructor.symbol,
        targetTypeParameterized,
        factoryClassParameterized,
        constructorParameters,
        emptyList(), // TODO member injection
      )

    if (isAssistedInject) {
      // Assisted inject type, implement a get() with all the assisted params
      val assistedParams = constructorParameters.valueParameters.filter { it.isAssisted }
      factoryCls
        .addFunction(name = "get", returnType = targetTypeParameterized, origin = LatticeOrigin)
        .apply {
          for (assistedParam in assistedParams) {
            addValueParameter(assistedParam.name, assistedParam.originalType, LatticeOrigin)
          }

          this.dispatchReceiverParameter = factoryCls.thisReceiver!!

          body =
            pluginContext.createIrBuilder(symbol).irBlockBody {
              val assistedArgs = this@apply.valueParameters.map { irGet(it) }
              val providerArgs =
                parametersAsProviderArguments(
                  context = this@InjectConstructorTransformer,
                  parameters = constructorParameters,
                  receiver = factoryCls.thisReceiver!!,
                  parametersToFields = parametersToFields,
                  symbols = symbols,
                )

              val instance =
                irTemporary(
                  irInvoke(callee = newInstanceFunctionSymbol, args = assistedArgs + providerArgs)
                )
              // TODO members injector goes here
              +irReturn(irGet(instance))
            }
        }
    } else {
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
      factoryCls
        .addOverride(
          baseFqName = symbols.providerInvoke.owner.kotlinFqName,
          simpleName = symbols.providerInvoke.owner.name,
          returnType = targetTypeParameterized,
          overriddenSymbols = listOf(symbols.providerInvoke),
        )
        .apply {
          this.dispatchReceiverParameter = factoryCls.thisReceiver!!
          body =
            pluginContext.createIrBuilder(symbol).irBlockBody {
              val instance =
                irTemporary(
                  irInvoke(
                    callee = newInstanceFunctionSymbol,
                    args =
                      parametersAsProviderArguments(
                        context = this@InjectConstructorTransformer,
                        parameters = constructorParameters,
                        receiver = factoryCls.thisReceiver!!,
                        parametersToFields = parametersToFields,
                        symbols = symbols,
                      ),
                  )
                )
              // TODO members injector goes here
              +irReturn(irGet(instance))
            }
        }
    }

    factoryCls.dumpToLatticeLog()

    generatedFactories[injectedClassId] = factoryCls
    return factoryCls
  }

  @OptIn(UnsafeDuringIrConstructionAPI::class)
  private fun generateCreators(
    factoryCls: IrClass,
    factoryConstructor: IrConstructorSymbol,
    targetConstructor: IrConstructorSymbol,
    targetTypeParameterized: IrType,
    factoryClassParameterized: IrType,
    constructorParameters: Parameters,
    memberInjectParameters: List<Parameter>,
  ): IrSimpleFunctionSymbol {
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
      context = this,
      factoryClass = factoryCls,
      factoryClassParameterized = factoryClassParameterized,
      factoryConstructor = factoryConstructor,
      parameters = constructorParameters,
      providerFunction = null,
    )

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
    // TODO dedupe with provider factory code gen
    val newInstanceFunction =
      classToGenerateCreatorsIn
        .addFunction(
          "newInstance",
          targetTypeParameterized,
          isStatic = true,
          origin = LatticeOrigin,
          visibility = DescriptorVisibilities.PUBLIC,
        )
        .apply {
          this.copyTypeParameters(targetConstructor.owner.typeParameters)
          markJvmStatic()

          for (parameter in constructorParameters.valueParameters) {
            addValueParameter(parameter.name, parameter.originalType, LatticeOrigin)
          }

          patchFactoryCreationParameters(
            providerFunction = null,
            sourceParameters = constructorParameters.valueParameters.map { it.ir },
            factoryParameters = valueParameters,
            factoryGraphParameter = null,
          )

          body =
            pluginContext.createIrBuilder(symbol).run {
              irBlockBody(
                symbol,
                // TODO members injector goes here
                irCallConstructor(targetConstructor, emptyList()).apply {
                  for (parameter in valueParameters) {
                    putValueArgument(parameter.index, irGet(parameter))
                  }
                },
              )
            }
        }

    return newInstanceFunction.symbol
  }
}
