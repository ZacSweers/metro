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
import dev.zacsweers.lattice.ir.createIrBuilder
import dev.zacsweers.lattice.ir.irInvoke
import dev.zacsweers.lattice.ir.irTemporary
import dev.zacsweers.lattice.ir.isAnnotatedWithAny
import dev.zacsweers.lattice.joinSimpleNames
import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addGetter
import org.jetbrains.kotlin.ir.builders.declarations.addProperty
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
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.declarations.addMember
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.types.typeWithParameters
import org.jetbrains.kotlin.ir.util.addSimpleDelegatingConstructor
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.copyTypeParameters
import org.jetbrains.kotlin.ir.util.createImplicitParameterDeclarationWithWrappedDescriptor
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.file
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
        pluginContext.irBuiltIns.anyClass.owner.constructors.single(),
        pluginContext.irBuiltIns,
        isPrimary = true,
        origin = LatticeOrigin,
      )

    /*
     * Add a constructor parameter + field for every parameter. This should be the provider type.
     *
     * TODO example snippet
     */
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

    // Implement the `value` property
    // This invokes newInstance() with all the
    val classToGenerateCreatorsIn =
      if (canGenerateAnObject) {
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
              pluginContext.irBuiltIns.anyClass.owner.constructors.single(),
              pluginContext.irBuiltIns,
              isPrimary = true,
              origin = LatticeOrigin,
            )
          }
      }

    val newInstanceFunction =
      classToGenerateCreatorsIn
        .addFunction("newInstance", targetTypeParameterized, isStatic = true)
        .apply {
          copyTypeParameters(typeParameters)
          this.origin = LatticeOrigin
          this.visibility = DescriptorVisibilities.PUBLIC
          markJvmStatic()
          for (parameter in allParameters) {
            addValueParameter(parameter.name, parameter.typeName, LatticeOrigin)
          }
          body =
            pluginContext.createIrBuilder(symbol).run {
              irExprBody(
                // TODO members injector goes here
                irCallConstructor(targetConstructor.symbol, emptyList()).apply {
                  for (parameter in valueParameters) {
                    putValueArgument(parameter.index, irGet(parameter))
                  }
                }
              )
            }
        }

    factoryCls
      .addProperty {
        name = symbols.providerValueProperty.owner.name
        origin = LatticeOrigin
      }
      .apply {
        parent = factoryCls
        overriddenSymbols += symbols.providerValueProperty
        addGetter {
            // TODO what goes here?
            returnType = targetTypeParameterized
            origin = LatticeOrigin
          }
          .apply {
            this.dispatchReceiverParameter = factoryReceiver
            this.overriddenSymbols += symbols.providerValuePropertyGetter
            body =
              DeclarationIrBuilder(pluginContext, symbol).irBlockBody {
                val instance =
                  irTemporary(
                    irInvoke(
                      callee = newInstanceFunction.symbol,
                      args =
                        allParameters
                          .map { parameter ->
                            // When calling value getter on Provider<T>, make sure the dispatch
                            // receiver
                            // is the Provider instance itself
                            val providerInstance =
                              irGetField(
                                irGet(factoryReceiver),
                                parametersToFields.getValue(parameter),
                              )
                            irInvoke(
                              dispatchReceiver = providerInstance,
                              callee = symbols.providerValuePropertyGetter,
                              typeHint = parameter.typeName,
                            )
                          }
                          .toTypedArray(),
                    )
                  )
                // TODO members injector goes here
                +irReturn(irGet(instance))
              }
          }
      }

    // Implement a create()
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
              irCall(ctor.symbol).apply {
                for ((index, _) in allParameters.withIndex()) {
                  putValueArgument(index, irGet(valueParameters[index]))
                }
              }
            )
          }
      }
      .symbol

    factoryCls.dumpToLatticeLog()

    factoryCls.parent = declaration.file
    declaration.declarations.add(factoryCls)
  }
}
