// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.NameAllocator
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
import dev.zacsweers.metro.compiler.ir.metroMetadata
import dev.zacsweers.metro.compiler.ir.parameters.MembersInjectParameter
import dev.zacsweers.metro.compiler.ir.parameters.Parameter
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.memberInjectParameters
import dev.zacsweers.metro.compiler.ir.parametersAsProviderArguments
import dev.zacsweers.metro.compiler.ir.rawTypeOrNull
import dev.zacsweers.metro.compiler.ir.regularParameters
import dev.zacsweers.metro.compiler.ir.requireSimpleFunction
import dev.zacsweers.metro.compiler.ir.thisReceiverOrFail
import dev.zacsweers.metro.compiler.memoized
import dev.zacsweers.metro.compiler.proto.InjectableMember
import dev.zacsweers.metro.compiler.proto.InjectableMembers
import dev.zacsweers.metro.compiler.proto.MemberInjectInfo
import dev.zacsweers.metro.compiler.proto.MetroMetadata
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
import org.jetbrains.kotlin.ir.util.properties
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

    // Use cached member inject parameters if available, otherwise fall back to fresh lookup
    val injectedMembersByClass = declaration.getOrComputeMemberInjectParameters()
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
        it.flatMap(Parameters<MembersInjectParameter>::regularParameters)
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
              it.regularParameters,
              it.contextParameters,
              null,
            )
          },
      providerFunction = null,
      patchCreationParams = false, // TODO when we support absent
    )

    // Implement static inject{name}() for each declared callable in this class
    for ((function, params) in declaredInjectFunctions) {
      function.apply {
        val instanceParam = regularParameters[0]

        body =
          pluginContext.createIrBuilder(symbol).run {
            val bodyExpression: IrExpression =
              if (params.isProperty) {
                val value = regularParameters[1]
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
                  args = regularParameters.drop(1).map { irGet(it) },
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
            instanceReceiver = regularParameters[0],
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

  private fun IrClass.getOrComputeMemberInjectParameters():
    Map<ClassId, List<Parameters<MembersInjectParameter>>> {
    val metadata = metroMetadata
    val memberInjectInfo = metadata?.member_inject_info

    // Compute supertypes once - we'll need them for either cached lookup or fresh computation
    val allTypes =
      getAllSuperTypes(pluginContext, excludeSelf = false, excludeAny = true)
        .mapNotNull { it.rawTypeOrNull() }
        .filterNot { it.isInterface }
        .memoized()

    if (memberInjectInfo != null && memberInjectInfo.injectable_members_by_class.isNotEmpty()) {
      // Metadata is present - check if we have any injectable members at all
      return lookupMemberInjectParameters(memberInjectInfo, allTypes)
    } else if (metadata != null) {
      // There _is_ metadata but no member injections here, so there are also no injectable members
      // here
      return emptyMap()
    }

    // No metadata available, fall back to (somehwat expensive) computation
    val computed = memberInjectParameters(allTypes)

    // Cache the results for future use (including empty results)
    cacheMemberInjectParameters(computed, allTypes)

    return computed
  }

  private fun lookupMemberInjectParameters(
    memberInjectInfo: MemberInjectInfo,
    allTypes: Sequence<IrClass>,
  ): Map<ClassId, List<Parameters<MembersInjectParameter>>> {
    return processTypes(allTypes) { clazz, classId, nameAllocator ->
      // Do we have injectable members in this class?
      val cachedMembers = memberInjectInfo.injectable_members_by_class[classId.toString()]

      if (cachedMembers != null && cachedMembers.members.isNotEmpty()) {
        cachedMembers.members.map { cachedMember ->
          // Find the actual IR member by name
          getIrMemberByName(clazz, cachedMember.name, cachedMember.is_property)
            .memberInjectParameters(nameAllocator, clazz)
        }
      } else {
        // Cache is present but empty, or class not in cache - no injectable members
        emptyList()
      }
    }
  }

  private fun IrClass.cacheMemberInjectParameters(
    membersByClass: Map<ClassId, List<Parameters<MembersInjectParameter>>>,
    allTypes: Sequence<IrClass>,
  ) {
    val injectableMembersByClass = mutableMapOf<String, InjectableMembers>()

    for ((classId, parametersList) in membersByClass) {
      val members =
        parametersList.map { params ->
          InjectableMember(
            name =
              if (params.isProperty) {
                params.irProperty!!.name.asString()
              } else {
                params.callableId.callableName.asString()
              },
            is_property = params.isProperty,
            is_lateinit = if (params.isProperty) params.irProperty!!.isLateinit else false,
          )
        }

      injectableMembersByClass[classId.toString()] = InjectableMembers(members = members)
    }

    // Cache negative results (null for classes with no injectable members)
    for (clazz in allTypes) {
      val classId = clazz.classIdOrFail.toString()
      if (classId !in injectableMembersByClass) {
        injectableMembersByClass[classId] = InjectableMembers(members = emptyList())
      }
    }

    val memberInjectInfo = MemberInjectInfo(injectable_members_by_class = injectableMembersByClass)

    // Store the metadata
    metroMetadata = MetroMetadata(member_inject_info = memberInjectInfo)
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

context(context: IrMetroContext)
internal fun memberInjectParameters(
  types: Sequence<IrClass>
): Map<ClassId, List<Parameters<MembersInjectParameter>>> {
  return processTypes(types) { clazz, _, nameAllocator ->
    clazz
      .declaredCallableMembers(
        functionFilter = { it.isAnnotatedWithAny(context.symbols.injectAnnotations) },
        propertyFilter = {
          (it.isVar || it.isLateinit) &&
            (it.isAnnotatedWithAny(context.symbols.injectAnnotations) ||
              it.setter?.isAnnotatedWithAny(context.symbols.injectAnnotations) == true ||
              it.backingField?.isAnnotatedWithAny(context.symbols.injectAnnotations) == true)
        },
      )
      .map { it.ir.memberInjectParameters(nameAllocator, clazz) }
      .toList()
  }
}

/**
 * Common logic for processing types and collecting injectable member parameters.
 *
 * @param types The precomputed list of types to process
 * @param memberExtractor Function that takes (clazz, classId, nameAllocator) and returns a list of
 *   Parameters for that class
 */
private fun processTypes(
  types: Sequence<IrClass>,
  memberExtractor: (IrClass, ClassId, NameAllocator) -> List<Parameters<MembersInjectParameter>>,
): Map<ClassId, List<Parameters<MembersInjectParameter>>> {
  return buildList {
      val nameAllocator = NameAllocator(mode = NameAllocator.Mode.COUNT)

      for (clazz in types) {
        val classId = clazz.classIdOrFail
        val injectedMembers = memberExtractor(clazz, classId, nameAllocator)

        if (injectedMembers.isNotEmpty()) {
          add(classId to injectedMembers)
        }
      }
    }
    // Reverse it such that the supertypes are first
    .asReversed()
    .associate { it.first to it.second }
}

private fun getIrMemberByName(clazz: IrClass, name: String, isProperty: Boolean): IrSimpleFunction {
  return if (isProperty) {
    clazz.properties.first { it.name.asString() == name }.setter!!
  } else {
    clazz.requireSimpleFunction(name).owner
  }
}
