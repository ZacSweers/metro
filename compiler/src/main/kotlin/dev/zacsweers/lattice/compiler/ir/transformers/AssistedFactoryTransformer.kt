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
import dev.zacsweers.lattice.compiler.ir.ContextualTypeKey
import dev.zacsweers.lattice.compiler.ir.LatticeTransformerContext
import dev.zacsweers.lattice.compiler.ir.assignConstructorParamsToFields
import dev.zacsweers.lattice.compiler.ir.createIrBuilder
import dev.zacsweers.lattice.compiler.ir.irExprBodySafe
import dev.zacsweers.lattice.compiler.ir.irInvoke
import dev.zacsweers.lattice.compiler.ir.isAnnotatedWithAny
import dev.zacsweers.lattice.compiler.ir.isExternalParent
import dev.zacsweers.lattice.compiler.ir.parameters.Parameter
import dev.zacsweers.lattice.compiler.ir.parameters.Parameter.AssistedParameterKey.Companion.toAssistedParameterKey
import dev.zacsweers.lattice.compiler.ir.parameters.parameters
import dev.zacsweers.lattice.compiler.ir.rawType
import dev.zacsweers.lattice.compiler.ir.requireSimpleFunction
import dev.zacsweers.lattice.compiler.ir.singleAbstractFunction
import dev.zacsweers.lattice.compiler.ir.transformers.AssistedFactoryTransformer.AssistedFactoryFunction.Companion.toAssistedFactoryFunction
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.nestedClasses
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.name.ClassId

internal class AssistedFactoryTransformer(
  context: LatticeTransformerContext,
  private val injectConstructorTransformer: InjectConstructorTransformer,
) : LatticeTransformerContext by context {

  private val generatedImpls = mutableMapOf<ClassId, IrClass>()

  fun visitClass(declaration: IrClass) {
    val isAssistedFactory = declaration.isAnnotatedWithAny(symbols.assistedFactoryAnnotations)
    if (isAssistedFactory) {
      getOrGenerateImplClass(declaration)
    }
  }

  internal fun getOrGenerateImplClass(declaration: IrClass): IrClass {
    // TODO if declaration is external to this compilation, look
    //  up its factory or warn if it doesn't exist
    val classId: ClassId = declaration.classIdOrFail
    generatedImpls[classId]?.let {
      return it
    }

    val isExternal = declaration.isExternalParent

    val implClass =
      declaration.nestedClasses.singleOrNull {
        val isLatticeImpl = it.name == LatticeSymbols.Names.latticeImpl
        // If not external, double check its origin
        if (isLatticeImpl && !isExternal) {
          if (it.origin != LatticeOrigins.AssistedFactoryImplClassDeclaration) {
            declaration.reportError(
              "Found a Lattice assisted factory impl declaration in ${declaration.kotlinFqName} but with an unexpected origin ${it.origin}"
            )
            exitProcessing()
          }
        }
        isLatticeImpl
      }

    if (implClass == null) {
      if (isExternal) {
        declaration.reportError(
          "Could not find generated assisted factory impl for '${declaration.kotlinFqName}' in upstream module where it's defined. Run the Lattice compiler over that module too."
        )
        exitProcessing()
      } else {
        error(
          "No expected assisted factory impl class generated for '${declaration.kotlinFqName}'. Report this bug with a repro case at https://github.com/zacsweers/lattice/issues/new"
        )
      }
    }

    // TODO generics asMemberOf()?
    val function =
      declaration.singleAbstractFunction(this).let { function ->
        function.toAssistedFactoryFunction(this, function)
      }

    val returnType = function.returnType
    val targetType = returnType.rawType()
    val injectConstructor =
      targetType.findInjectableConstructor(onlyUsePrimaryConstructor = false)!!

    val generatedFactory =
      injectConstructorTransformer.getOrGenerateFactoryClass(targetType, injectConstructor)

    val constructorParams = injectConstructor.parameters(this)
    val assistedParameters =
      constructorParams.valueParameters.filter { parameter -> parameter.isAssisted }
    val assistedParameterKeys =
      assistedParameters.mapIndexed { index, parameter ->
        injectConstructor.valueParameters[index].toAssistedParameterKey(symbols, parameter.typeKey)
      }

    val ctor = implClass.primaryConstructor!!
    implClass.apply {
      val delegateFactoryField = assignConstructorParamsToFields(ctor, implClass).values.single()

      val creatorFunction =
        implClass.functions.first {
          it.origin == LatticeOrigins.AssistedFactoryImplCreatorFunctionDeclaration
        }

      creatorFunction.apply {
        val functionParams =
          valueParameters.associateBy { valueParam ->
            val key = ContextualTypeKey.from(latticeContext, valueParam).typeKey
            valueParam.toAssistedParameterKey(symbols, key)
          }
        body =
          pluginContext.createIrBuilder(symbol).run {
            // We call the @Inject constructor. Therefore, find for each assisted
            // parameter the function parameter where the keys match.
            val argumentList =
              assistedParameterKeys.map { assistedParameterKey ->
                irGet(functionParams.getValue(assistedParameterKey))
              }

            irExprBodySafe(
              symbol,
              irInvoke(
                dispatchReceiver =
                  irGetField(irGet(dispatchReceiverParameter!!), delegateFactoryField),
                callee = generatedFactory.requireSimpleFunction(LatticeSymbols.StringNames.INVOKE),
                args = argumentList,
              ),
            )
          }
      }
    }

    val companion = implClass.companionObject()!!
    companion.requireSimpleFunction(LatticeSymbols.StringNames.CREATE).owner.apply {
      val factoryParam = valueParameters.single()
      // InstanceFactory.create(Impl(delegateFactory))
      body =
        pluginContext.createIrBuilder(symbol).run {
          irExprBodySafe(
            symbol,
            irInvoke(
                dispatchReceiver = irGetObject(symbols.instanceFactoryCompanionObject),
                callee = symbols.instanceFactoryCreate,
                args = listOf(irInvoke(callee = ctor.symbol, args = listOf(irGet(factoryParam)))),
              )
              .apply { putTypeArgument(0, declaration.typeWith()) },
          )
        }
    }

    implClass.dumpToLatticeLog()

    return implClass
  }

  /** Represents a parsed function in an `@AssistedFactory`-annotated interface. */
  private data class AssistedFactoryFunction(
    val simpleName: String,
    val qualifiedName: String,
    val returnType: IrType,
    val originalFunction: IrSimpleFunction,
    val parameterKeys: List<Parameter.AssistedParameterKey>,
  ) {

    companion object {
      fun IrSimpleFunction.toAssistedFactoryFunction(
        context: LatticeTransformerContext,
        originalDeclaration: IrSimpleFunction,
      ): AssistedFactoryFunction {
        val params = parameters(context)
        return AssistedFactoryFunction(
          simpleName = originalDeclaration.name.asString(),
          qualifiedName = originalDeclaration.kotlinFqName.asString(),
          // TODO FIR validate return type is a graph
          returnType = returnType,
          originalFunction = originalDeclaration,
          parameterKeys =
            originalDeclaration.valueParameters.mapIndexed { index, param ->
              param.toAssistedParameterKey(context.symbols, params.valueParameters[index].typeKey)
            },
        )
      }
    }
  }
}
