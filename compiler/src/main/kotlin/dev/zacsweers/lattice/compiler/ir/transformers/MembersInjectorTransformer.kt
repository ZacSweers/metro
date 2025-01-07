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
import dev.zacsweers.lattice.compiler.LatticeOrigins
import dev.zacsweers.lattice.compiler.LatticeSymbols
import dev.zacsweers.lattice.compiler.NameAllocator
import dev.zacsweers.lattice.compiler.capitalizeUS
import dev.zacsweers.lattice.compiler.exitProcessing
import dev.zacsweers.lattice.compiler.ir.LatticeTransformerContext
import dev.zacsweers.lattice.compiler.ir.addCompanionObject
import dev.zacsweers.lattice.compiler.ir.addOverride
import dev.zacsweers.lattice.compiler.ir.assignConstructorParamsToFields
import dev.zacsweers.lattice.compiler.ir.createIrBuilder
import dev.zacsweers.lattice.compiler.ir.declaredCallableMembers
import dev.zacsweers.lattice.compiler.ir.getAllSuperTypes
import dev.zacsweers.lattice.compiler.ir.irExprBodySafe
import dev.zacsweers.lattice.compiler.ir.irInvoke
import dev.zacsweers.lattice.compiler.ir.isAnnotatedWithAny
import dev.zacsweers.lattice.compiler.ir.isExternalParent
import dev.zacsweers.lattice.compiler.ir.parameters.MembersInjectParameter
import dev.zacsweers.lattice.compiler.ir.parameters.Parameter
import dev.zacsweers.lattice.compiler.ir.parameters.Parameters
import dev.zacsweers.lattice.compiler.ir.parameters.memberInjectParameters
import dev.zacsweers.lattice.compiler.ir.parametersAsProviderArguments
import dev.zacsweers.lattice.compiler.ir.rawTypeOrNull
import dev.zacsweers.lattice.compiler.ir.requireSimpleFunction
import dev.zacsweers.lattice.compiler.ir.thisReceiverOrFail
import kotlin.collections.component1
import kotlin.collections.component2
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.builders.IrBlockBodyBuilder
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.types.typeWithParameters
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.addSimpleDelegatingConstructor
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.copyTypeParameters
import org.jetbrains.kotlin.ir.util.createImplicitParameterDeclarationWithWrappedDescriptor
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.nestedClasses
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.name.ClassId

internal class MembersInjectorTransformer(context: LatticeTransformerContext) :
  LatticeTransformerContext by context {

  data class MemberInjectClass(
    val ir: IrClass,
    val parameters: Map<ClassId, List<Parameters<MembersInjectParameter>>>,
    val injectFunctions: Map<IrSimpleFunction, Parameters<MembersInjectParameter>>,
  )

  private val generatedInjectors = mutableMapOf<ClassId, MemberInjectClass?>()

  fun visitClass(declaration: IrClass) {
    getOrGenerateInjector(declaration)
  }

  fun requireInjector(declaration: IrClass): MemberInjectClass {
    return getOrGenerateInjector(declaration)
      ?: error("No members injector found for ${declaration.kotlinFqName}.")
  }

  @OptIn(UnsafeDuringIrConstructionAPI::class)
  fun getOrGenerateAllInjectorsFor(declaration: IrClass): List<MemberInjectClass> {
    return declaration
      .getAllSuperTypes(pluginContext, excludeSelf = false, excludeAny = true)
      .mapNotNull { it.classOrNull?.owner }
      .filterNot { it.isInterface }
      .mapNotNull { getOrGenerateInjector(it) }
      .toList()
      .asReversed() // Base types go first
  }

  @OptIn(UnsafeDuringIrConstructionAPI::class)
  fun getOrGenerateInjector(declaration: IrClass): MemberInjectClass? {
    // TODO if declaration is external to this compilation, look
    //  up its factory or warn if it doesn't exist
    val injectedClassId: ClassId = declaration.classIdOrFail
    generatedInjectors[injectedClassId]?.let {
      return it
    }

    val isExternal = declaration.isExternalParent

    /*
    Generates an implementation of a MembersInjector for the given target type. This includes
    - Dependencies as provider params
    - A static create() to instantiate it
    - An implementation of MembersInjector.injectMembers()
    - Static inject* functions for each member of the target class's _declared_ members.
    */
    val injectorClass = declaration.nestedClasses.singleOrNull {
      val isLatticeImpl = it.name == LatticeSymbols.Names.latticeMembersInjector
      // If not external, double check its origin
      if (isLatticeImpl && !isExternal) {
        if (it.origin != LatticeOrigins.MembersInjectorClassDeclaration) {
          declaration.reportError(
            "Found a Lattice members injector declaration in ${declaration.kotlinFqName} but with an unexpected origin ${it.origin}"
          )
          exitProcessing()
        }
      }
      isLatticeImpl
    }

    if (injectorClass == null) {
      // For now, assume there's no members to inject. Would be nice if we could better check this in the future
      generatedInjectors[injectedClassId] = null
      return null
    }

    if (declaration.isExternalParent) {
      //        val params = TODO()
      //
      //        val allParams =
      //          injectedMembers.flatMap { it.valueParameters }.associateBy { it.name.asString()
      // }
      //        val functions =
      //          declaration
      //            .companionObject()!!
      //            .simpleFunctions()
      //            .mapNotNull { function ->
      //              val name = function.name.asString()
      //              // TODO generate a better marker annotation? Or use attributes?
      //              if (!name.startsWith("inject")) return@mapNotNull null
      //              val paramName = name.removePrefix("inject").decapitalizeUS()
      //              val param =
      //                allParams[paramName]
      //                  ?: error("Could not find param with name $paramName for
      // $injectedClassId")
      //              param to function
      //            }
      //            .associate { it.first to it.second }
      return MemberInjectClass(injectorClass, TODO(), TODO()).also {
        generatedInjectors[injectedClassId] = it
      }
    }

    // Loop through _declared_ member inject params. Collect and use to create unique names

    val injectedMembersByClass = declaration.memberInjectParameters(this)

    if (injectedMembersByClass.isEmpty() || injectedMembersByClass.values.all { it.isEmpty() }) {
      return null
    }

    val typeParameters = injectorClass.typeParameters
    val injectorClassParameterized = injectorClass.symbol.typeWithParameters(typeParameters)

    val allParameters = injectedMembersByClass.values.flatMap { it.flatMap { it.valueParameters } }
    val ctor = injectorClass.primaryConstructor!!

    val constructorParametersToFields = assignConstructorParamsToFields(ctor, injectorClass)

    // TODO This is ugly. Can we just source all the params directly from the FIR class now?
    val sourceParametersToFields: Map<Parameter, IrField> =
      constructorParametersToFields.entries.withIndex().associate { (index, pair) ->
        val (_, field) = pair
        val sourceParam = allParameters[index]
        sourceParam to field
      }

    val companionObject = injectorClass.companionObject()!!

    // Static create()
    generateStaticCreateFunction(
      context = latticeContext,
      parentClass = companionObject,
      targetClass = injectorClass,
      targetClassParameterized = injectorClassParameterized,
      targetConstructor = ctor.symbol,
      parameters =
        injectedMembersByClass.values
          .flatten()
          .reduce { current, next -> current.mergeValueParametersWith(next) }
          .let {
            Parameters(
              Parameters.empty<MembersInjectParameter>().callableId,
              null,
              null,
              it.valueParameters,
              null,
            )
          },
      providerFunction = null,
      patchCreationParams = false, // TODO when we support absent
    )

    // Implement static inject{name}() for each declared callable in this class
    val parameterGroupsForClass = injectedMembersByClass.getValue(injectedClassId)
    val declaredInjectFunctions: Map<IrSimpleFunction, Parameters<MembersInjectParameter>> =
      parameterGroupsForClass.associate { params ->
        val name =
          if (params.isProperty) {
            params.irProperty!!.name
          } else {
            params.callableId.callableName
          }
        val function =
          companionObject.requireSimpleFunction("inject${name.capitalizeUS().asString()}")
            .owner
            .apply {
              // Params
              // Add instance
              val instanceParam = valueParameters[0]

              body =
                pluginContext.createIrBuilder(symbol).run {
                  val bodyExpression: IrExpression =
                    if (params.isProperty) {
                      val value = valueParameters[1]
                      val irField = params.irProperty!!.backingField
                      if (irField == null) {
                        irInvoke(
                          irGet(instanceParam),
                          callee = params.ir!!.symbol,
                          args = listOf(irGet(value)),
                        )
                      } else {
                        irSetField(irGet(instanceParam), irField, irGet(value))
                      }
                    } else {
                      irInvoke(
                        irGet(instanceParam),
                        callee = params.ir!!.symbol,
                        args = valueParameters.drop(1).map { irGet(it) },
                      )
                    }
                  irExprBodySafe(symbol, bodyExpression)
                }
            }
        function to params
      }

    val inheritedInjectFunctions: Map<IrSimpleFunction, Parameters<MembersInjectParameter>> =
      buildMap {
        // Locate function refs for supertypes
        for ((classId, injectedMembers) in injectedMembersByClass) {
          if (classId == injectedClassId) continue
          if (injectedMembers.isEmpty()) continue

          // This is what generates supertypes lazily as needed
          val functions =
            requireInjector(pluginContext.referenceClass(classId)!!.owner)!!.injectFunctions

          putAll(functions)
        }
      }

    val injectFunctions = inheritedInjectFunctions + declaredInjectFunctions

    // Override injectMembers()
    injectorClass.requireSimpleFunction(LatticeSymbols.StringNames.injectMembers)
      .owner
      .apply {
        val functionReceiver = dispatchReceiverParameter!!
        val instanceParam = valueParameters[0]
        body =
          pluginContext.createIrBuilder(symbol).irBlockBody {
            addMemberInjection(
              context = latticeContext,
              instanceReceiver = instanceParam,
              injectorReceiver = functionReceiver,
              injectFunctions = injectFunctions,
              parametersToFields = sourceParametersToFields,
            )
          }
      }

    injectorClass.dumpToLatticeLog()

    return MemberInjectClass(injectorClass, injectedMembersByClass, declaredInjectFunctions).also {
      generatedInjectors[injectedClassId] = it
    }
  }
}

internal fun IrBlockBodyBuilder.addMemberInjection(
  context: LatticeTransformerContext,
  injectFunctions: Map<IrSimpleFunction, Parameters<MembersInjectParameter>>,
  parametersToFields: Map<Parameter, IrField>,
  instanceReceiver: IrValueParameter,
  injectorReceiver: IrValueParameter,
) {
  for ((function, parameters) in injectFunctions) {
    +irInvoke(
      dispatchReceiver = irGetObject(function.parentAsClass.symbol),
      callee = function.symbol,
      args =
        buildList {
          add(irGet(instanceReceiver))
          addAll(
            parametersAsProviderArguments(context, parameters, injectorReceiver, parametersToFields)
          )
        },
    )
  }
}

internal fun IrClass.memberInjectParameters(
  context: LatticeTransformerContext
): Map<ClassId, List<Parameters<MembersInjectParameter>>> {
  return buildList {
      val nameAllocator = NameAllocator(mode = NameAllocator.Mode.COUNT)
      for (type in
        getAllSuperTypes(context.pluginContext, excludeSelf = false, excludeAny = true)) {
        val clazz = type.rawTypeOrNull() ?: continue
        // TODO revisit - can we support this now? Interfaces can declare mutable vars that may not
        // be implemented in
        //  the consuming class if using class delegation
        if (clazz.isInterface) continue

        val injectedMembers =
          clazz
            .declaredCallableMembers(
              context = context,
              functionFilter = { it.isAnnotatedWithAny(context.symbols.injectAnnotations) },
              propertyFilter = {
                (it.isVar || it.isLateinit) &&
                  (it.isAnnotatedWithAny(context.symbols.injectAnnotations) ||
                    it.setter?.isAnnotatedWithAny(context.symbols.injectAnnotations) == true ||
                    it.backingField?.isAnnotatedWithAny(context.symbols.injectAnnotations) == true)
              },
            )
            .map { it.ir.memberInjectParameters(context, nameAllocator, clazz) }
            // TODO extension receivers not supported. What about overrides?
            .toList()

        if (injectedMembers.isNotEmpty()) {
          add(clazz.classIdOrFail to injectedMembers)
        }
      }
    }
    // Reverse it such that the supertypes are first
    .asReversed()
    .associate { it.first to it.second }
}
