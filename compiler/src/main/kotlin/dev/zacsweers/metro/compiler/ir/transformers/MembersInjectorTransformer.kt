// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.capitalizeUS
import dev.zacsweers.metro.compiler.exitProcessing
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.assignConstructorParamsToFields
import dev.zacsweers.metro.compiler.ir.createIrBuilder
import dev.zacsweers.metro.compiler.ir.declaredCallableMembers
import dev.zacsweers.metro.compiler.ir.finalizeFakeOverride
import dev.zacsweers.metro.compiler.ir.getAllSuperTypes
import dev.zacsweers.metro.compiler.ir.irExprBodySafe
import dev.zacsweers.metro.compiler.ir.irInvoke
import dev.zacsweers.metro.compiler.ir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.ir.isExternalParent
import dev.zacsweers.metro.compiler.ir.parameters.MembersInjectParameter
import dev.zacsweers.metro.compiler.ir.parameters.Parameter
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.memberInjectParameters
import dev.zacsweers.metro.compiler.ir.parametersAsProviderArguments
import dev.zacsweers.metro.compiler.ir.rawTypeOrNull
import dev.zacsweers.metro.compiler.ir.requireSimpleFunction
import dev.zacsweers.metro.compiler.ir.thisReceiverOrFail
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.set
import org.jetbrains.kotlin.ir.builders.IrBlockBodyBuilder
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.nestedClasses
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.name.ClassId

internal class MembersInjectorTransformer(context: IrMetroContext) : IrMetroContext by context {

  data class MemberInjectClass(
    val ir: IrClass,
    val parameters: Map<ClassId, List<Parameters<MembersInjectParameter>>>,
    val injectFunctions: Map<IrSimpleFunction, Parameters<MembersInjectParameter>>,
  )

  private val generatedInjectors = mutableMapOf<ClassId, MemberInjectClass?>()

  fun visitClass(declaration: IrClass) {
    getOrGenerateInjector(declaration)
  }

  private fun requireInjector(declaration: IrClass): MemberInjectClass {
    return getOrGenerateInjector(declaration)
      ?: error("No members injector found for ${declaration.kotlinFqName}.")
  }

  fun getOrGenerateAllInjectorsFor(declaration: IrClass): List<MemberInjectClass> {
    return declaration
      .getAllSuperTypes(pluginContext, excludeSelf = false, excludeAny = true)
      .mapNotNull { it.classOrNull?.owner }
      .filterNot { it.isInterface }
      .mapNotNull { getOrGenerateInjector(it) }
      .toList()
      .asReversed() // Base types go first
  }

  fun getOrGenerateInjector(declaration: IrClass): MemberInjectClass? {
    val injectedClassId: ClassId = declaration.classId ?: return null
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
    val injectorClass =
      declaration.nestedClasses.singleOrNull {
        val isMetroImpl = it.name == Symbols.Names.MetroMembersInjector
        // If not external, double check its origin
        if (isMetroImpl && !isExternal) {
          if (it.origin != Origins.MembersInjectorClassDeclaration) {
            declaration.reportError(
              "Found a Metro members injector declaration in ${declaration.kotlinFqName} but with an unexpected origin ${it.origin}"
            )
            exitProcessing()
          }
        }
        isMetroImpl
      }

    if (injectorClass == null) {
      if (options.enableDaggerRuntimeInterop) {
        // TODO Look up where dagger would generate one
        //  requires memberInjectParameters to support fields
      }
      // For now, assume there's no members to inject. Would be nice if we could better check this
      // in the future
      generatedInjectors[injectedClassId] = null
      return null
    }

    val companionObject = injectorClass.companionObject()!!

    // TODO this is expensive can we store it somewhere from FIR via metadata?
    // Loop through _declared_ member inject params. Collect and use to create unique names
    val injectedMembersByClass = declaration.memberInjectParameters(this)
    val parameterGroupsForClass = injectedMembersByClass.getValue(injectedClassId)
    val declaredInjectFunctions =
      parameterGroupsForClass.associateBy { params ->
        val name =
          if (params.isProperty) {
            params.irProperty!!.name
          } else {
            params.callableId.callableName
          }
        companionObject.requireSimpleFunction("inject${name.capitalizeUS().asString()}").owner
      }

    if (declaration.isExternalParent) {
      return MemberInjectClass(injectorClass, injectedMembersByClass, declaredInjectFunctions)
        .also { generatedInjectors[injectedClassId] = it }
    }

    val ctor = injectorClass.primaryConstructor!!

    val allParameters =
      injectedMembersByClass.values.flatMap {
        it.flatMap(Parameters<MembersInjectParameter>::valueParameters)
      }

    val constructorParametersToFields = assignConstructorParamsToFields(ctor, injectorClass)

    // TODO This is ugly. Can we just source all the params directly from the FIR class now?
    val sourceParametersToFields: Map<Parameter, IrField> =
      constructorParametersToFields.entries.withIndex().associate { (index, pair) ->
        val (_, field) = pair
        val sourceParam = allParameters[index]
        sourceParam to field
      }

    // Static create()
    generateStaticCreateFunction(
      context = metroContext,
      parentClass = companionObject,
      targetClass = injectorClass,
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
    for ((function, params) in declaredInjectFunctions) {
      function.apply {
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
    }

    val inheritedInjectFunctions: Map<IrSimpleFunction, Parameters<MembersInjectParameter>> =
      buildMap {
        // Locate function refs for supertypes
        for ((classId, injectedMembers) in injectedMembersByClass) {
          if (classId == injectedClassId) continue
          if (injectedMembers.isEmpty()) continue

          // This is what generates supertypes lazily as needed
          val functions =
            requireInjector(pluginContext.referenceClass(classId)!!.owner).injectFunctions

          putAll(functions)
        }
      }

    val injectFunctions = inheritedInjectFunctions + declaredInjectFunctions

    // Override injectMembers()
    injectorClass.requireSimpleFunction(Symbols.StringNames.INJECT_MEMBERS).owner.apply {
      finalizeFakeOverride(injectorClass.thisReceiverOrFail)
      body =
        pluginContext.createIrBuilder(symbol).irBlockBody {
          addMemberInjection(
            context = metroContext,
            instanceReceiver = valueParameters[0],
            injectorReceiver = dispatchReceiverParameter!!,
            injectFunctions = injectFunctions,
            parametersToFields = sourceParametersToFields,
          )
        }
    }

    injectorClass.dumpToMetroLog()

    return MemberInjectClass(injectorClass, injectedMembersByClass, declaredInjectFunctions).also {
      generatedInjectors[injectedClassId] = it
    }
  }
}

internal fun IrBlockBodyBuilder.addMemberInjection(
  context: IrMetroContext,
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
  context: IrMetroContext
): Map<ClassId, List<Parameters<MembersInjectParameter>>> {
  return buildList {
      val nameAllocator =
        dev.zacsweers.metro.compiler.NameAllocator(
          mode = dev.zacsweers.metro.compiler.NameAllocator.Mode.COUNT
        )
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
