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
import dev.zacsweers.lattice.ir.addOverride
import dev.zacsweers.lattice.ir.createIrBuilder
import dev.zacsweers.lattice.ir.irInvoke
import dev.zacsweers.lattice.ir.irTemporary
import dev.zacsweers.lattice.ir.isAnnotatedWithAny
import dev.zacsweers.lattice.joinSimpleNames
import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addTypeParameter
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildValueParameter
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.declarations.addMember
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.types.typeWithParameters
import org.jetbrains.kotlin.ir.util.addSimpleDelegatingConstructor
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.copyTypeParameters
import org.jetbrains.kotlin.ir.util.createImplicitParameterDeclarationWithWrappedDescriptor
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.file
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

internal class InjectConstructorTransformer(context: LatticeTransformerContext) :
  IrElementTransformerVoidWithContext(), LatticeTransformerContext by context {

  @OptIn(UnsafeDuringIrConstructionAPI::class)
  override fun visitClassNew(declaration: IrClass): IrStatement {
    log("Reading <$declaration>")

    // Check for inject annotation on the class or primary constructor
    // TODO FIR error if primary constructor is missing but class annotated with inject
    val primaryConstructor =
      declaration.primaryConstructor ?: return super.visitClassNew(declaration)
    val isInjectable =
      declaration.isAnnotatedWithAny(symbols.injectAnnotations) ||
        primaryConstructor.isAnnotatedWithAny(symbols.injectAnnotations)

    // TODO FIR check for multiple inject constructors or annotations
    // TODO FIR check constructor visibility

    if (isInjectable) {
      val typeParams = declaration.typeParameters
      val constructorParameters =
        primaryConstructor.valueParameters.map { valueParameter ->
          valueParameter.toConstructorParameter(symbols, valueParameter.name)
        }
      generateFactoryClass(
        declaration,
        declaration.classIdOrFail,
        primaryConstructor,
        typeParams,
        constructorParameters,
      )
    }
    return super.visitClassNew(declaration)
  }

  @OptIn(UnsafeDuringIrConstructionAPI::class)
  private fun generateFactoryClass(
    declaration: IrClass,
    injectedClassId: ClassId,
    targetConstructor: IrConstructor,
    typeParameters: List<IrTypeParameter>,
    constructorParameters: List<ConstructorParameter>,
    // TODO
    //    memberInjectParameters: List<MemberInjectParameter>,
  ) {
    val generatedClassName = injectedClassId.joinSimpleNames(suffix = "_Factory")

    val allParameters = constructorParameters // + memberInjectParameters
    val canGenerateAnObject = allParameters.isEmpty() && typeParameters.isEmpty()

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
          name = generatedClassName.relativeClassName.shortName()
          modality = Modality.FINAL
          kind = if (canGenerateAnObject) ClassKind.OBJECT else ClassKind.CLASS
          visibility = DescriptorVisibilities.PUBLIC
        }
        .apply { origin = LatticeOrigin }

    for (typeVariable in typeParameters) {
      factoryCls.addTypeParameter(
        typeVariable.name.asString(),
        upperBound = typeVariable.superTypes.single(),
        variance = typeVariable.variance,
      )
    }

    val factoryReceiver =
      buildValueParameter(factoryCls) {
        name = Name.special("<this>")
        type =
          IrSimpleTypeImpl(
            classifier = factoryCls.symbol,
            hasQuestionMark = false,
            arguments = emptyList(),
            annotations = emptyList(),
          )
        origin = IrDeclarationOrigin.INSTANCE_RECEIVER
      }

    factoryCls.thisReceiver = factoryReceiver
    factoryCls.superTypes = listOf(symbols.latticeFactory.typeWith(declaration.defaultType))

    val factoryClassParameterized = factoryCls.symbol.typeWithParameters(typeParameters)
    val targetTypeParameterized = declaration.symbol.typeWithParameters(typeParameters)

    val ctor =
      factoryCls.addSimpleDelegatingConstructor(
        symbols.anyConstructor,
        pluginContext.irBuiltIns,
        isPrimary = true,
        origin = LatticeOrigin,
      )

    // Add a constructor parameter + field for every parameter. This should be the provider type.
    val parametersToFields = mutableMapOf<Parameter, IrField>()
    for (parameter in allParameters) {
      val irParameter =
        ctor.addValueParameter(parameter.name, parameter.providerTypeName, LatticeOrigin)
      val irField =
        factoryCls
          .addField(irParameter.name, irParameter.type, DescriptorVisibilities.PRIVATE)
          .apply {
            isFinal = true
            initializer =
              pluginContext.createIrBuilder(symbol).run { irExprBody(irGet(irParameter)) }
          }
      parametersToFields[parameter] = irField
      // TODO add private property? Does it matter?
    }

    val newInstanceFunctionSymbol =
      generateCreators(
        factoryCls,
        ctor.symbol,
        targetConstructor.symbol,
        targetTypeParameterized,
        factoryClassParameterized,
        allParameters,
      )

    /*
    Override and implement the Provider.value property

    // Simple
    override fun invoke(): Example = newInstance(valueProvider.value)

    // Generic
    override fun invoke(): Example<T> = newInstance(valueProvider.value)

    // TODO doc Provider<T>, Lazy<T>, Provider<Lazy<T>>
    */
    factoryCls
      .addOverride(
        baseFqName = symbols.providerInvoke.owner.kotlinFqName,
        name = symbols.providerInvoke.owner.name.asString(),
        returnType = targetTypeParameterized,
      )
      .apply {
        this.dispatchReceiverParameter = factoryReceiver
        this.overriddenSymbols += symbols.providerInvoke
        body =
          pluginContext.createIrBuilder(symbol).irBlockBody {
            val instance =
              irTemporary(
                irInvoke(
                  callee = newInstanceFunctionSymbol,
                  args =
                    allParameters
                      .map { parameter ->
                        // When calling value getter on Provider<T>, make sure the dispatch
                        // receiver is the Provider instance itself
                        val providerInstance =
                          irGetField(irGet(factoryReceiver), parametersToFields.getValue(parameter))
                        when {
                          parameter.isLazyWrappedInProvider -> {
                            // ProviderOfLazy.create(provider)
                            irInvoke(
                              dispatchReceiver = irGetObject(symbols.providerOfLazyCompanionObject),
                              callee = symbols.providerOfLazyCreate,
                              args = arrayOf(providerInstance),
                              typeHint = parameter.typeName,
                            )
                          }
                          parameter.isWrappedInProvider -> providerInstance
                          // Normally Dagger changes Lazy<Type> parameters to a Provider<Type>
                          // (usually the container is a joined type), therefore we use
                          // `.lazy(..)` to convert the Provider to a Lazy. Assisted
                          // parameters behave differently and the Lazy type is not changed
                          // to a Provider and we can simply use the parameter name in the
                          // argument list.
                          parameter.isWrappedInLazy && parameter.isAssisted -> providerInstance
                          parameter.isWrappedInLazy -> {
                            // DoubleCheck.lazy(...)
                            irInvoke(
                              dispatchReceiver = irGetObject(symbols.providerOfLazyCompanionObject),
                              callee = symbols.providerOfLazyCreate,
                              args = arrayOf(providerInstance),
                              typeHint = parameter.typeName,
                            )
                            irInvoke(
                              dispatchReceiver = irGetObject(symbols.doubleCheckCompanionObject),
                              callee = symbols.doubleCheckLazy,
                              args = arrayOf(providerInstance),
                              typeHint = parameter.typeName,
                            )
                          }
                          parameter.isAssisted -> providerInstance
                          else -> {
                            irInvoke(
                              dispatchReceiver = providerInstance,
                              callee = symbols.providerInvoke,
                              typeHint = parameter.typeName,
                            )
                          }
                        }
                      }
                      .toTypedArray(),
                )
              )
            // TODO members injector goes here
            +irReturn(irGet(instance))
          }
      }

    factoryCls.dumpToLatticeLog()

    factoryCls.parent = declaration.file
    declaration.declarations.add(factoryCls)
  }

  private fun generateCreators(
    factoryCls: IrClass,
    factoryConstructor: IrConstructorSymbol,
    targetConstructor: IrConstructorSymbol,
    targetTypeParameterized: IrType,
    factoryClassParameterized: IrType,
    allParameters: List<ConstructorParameter>,
  ): IrSimpleFunctionSymbol {
    // If this is an object, we can generate directly into this object
    val isObject = factoryCls.kind == ClassKind.OBJECT
    val classToGenerateCreatorsIn =
      if (isObject) {
        factoryCls
      } else {
        pluginContext.irFactory
          .buildClass {
            this.name = Name.identifier("Companion")
            this.modality = Modality.FINAL
            this.kind = ClassKind.OBJECT
            this.visibility = DescriptorVisibilities.PUBLIC
            this.isCompanion = true
          }
          .also { companionObject ->
            factoryCls.addMember(companionObject)
            companionObject.origin = LatticeOrigin
            companionObject.parent = factoryCls
            companionObject.createImplicitParameterDeclarationWithWrappedDescriptor()
            companionObject.addSimpleDelegatingConstructor(
              symbols.anyConstructor,
              pluginContext.irBuiltIns,
              isPrimary = true,
              origin = LatticeOrigin,
            )
          }
      }

    /*
     Implement a static create() function

     // Simple
     @JvmStatic // JVM only
     fun create(valueProvider: Provider<String>): Example_Factory = Example_Factory(valueProvider)

     // Generic
     @JvmStatic // JVM only
     fun <T> create(valueProvider: Provider<T>): Example_Factory<T> = Example_Factory<T>(valueProvider)
    */
    classToGenerateCreatorsIn
      .addFunction("create", factoryClassParameterized, isStatic = true)
      .apply {
        copyTypeParameters(typeParameters)
        this.origin = LatticeOrigin
        this.visibility = DescriptorVisibilities.PUBLIC
        markJvmStatic()
        for (parameter in allParameters) {
          addValueParameter(parameter.name, parameter.providerTypeName, LatticeOrigin)
        }
        body =
          pluginContext.createIrBuilder(symbol).run {
            irExprBody(
              if (isObject) {
                irGetObject(factoryCls.symbol)
              } else {
                irCall(factoryConstructor).apply {
                  for (parameter in valueParameters) {
                    putValueArgument(parameter.index, irGet(parameter))
                  }
                }
              }
            )
          }
      }

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
    val newInstanceFunction =
      classToGenerateCreatorsIn
        .addFunction("newInstance", targetTypeParameterized, isStatic = true)
        .apply {
          copyTypeParameters(typeParameters)
          this.origin = LatticeOrigin
          this.visibility = DescriptorVisibilities.PUBLIC
          markJvmStatic()
          for (parameter in allParameters) {
            addValueParameter(parameter.name, parameter.originalTypeName, LatticeOrigin)
          }
          body =
            pluginContext.createIrBuilder(symbol).run {
              irExprBody(
                // TODO members injector goes here
                irCallConstructor(targetConstructor, emptyList()).apply {
                  for (parameter in valueParameters) {
                    putValueArgument(parameter.index, irGet(parameter))
                  }
                }
              )
            }
        }

    return newInstanceFunction.symbol
  }
}
