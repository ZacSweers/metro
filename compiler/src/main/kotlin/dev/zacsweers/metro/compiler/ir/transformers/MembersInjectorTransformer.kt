// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.NameAllocator
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.capitalizeUS
import dev.zacsweers.metro.compiler.decapitalizeUS
import dev.zacsweers.metro.compiler.exitProcessing
import dev.zacsweers.metro.compiler.fir.MetroDiagnostics
import dev.zacsweers.metro.compiler.generatedClass
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.assignConstructorParamsToFields
import dev.zacsweers.metro.compiler.ir.buildAnnotation
import dev.zacsweers.metro.compiler.ir.createIrBuilder
import dev.zacsweers.metro.compiler.ir.declaredCallableMembers
import dev.zacsweers.metro.compiler.ir.finalizeFakeOverride
import dev.zacsweers.metro.compiler.ir.getAllSuperTypes
import dev.zacsweers.metro.compiler.ir.irExprBodySafe
import dev.zacsweers.metro.compiler.ir.irInvoke
import dev.zacsweers.metro.compiler.ir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.ir.isExternalParent
import dev.zacsweers.metro.compiler.ir.isStaticIsh
import dev.zacsweers.metro.compiler.ir.metroMetadata
import dev.zacsweers.metro.compiler.ir.overriddenSymbolsSequence
import dev.zacsweers.metro.compiler.ir.parameters.Parameter
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.memberInjectParameters
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.parameters.toMemberInjectParameter
import dev.zacsweers.metro.compiler.ir.parameters.wrapInMembersInjector
import dev.zacsweers.metro.compiler.ir.parametersAsProviderArguments
import dev.zacsweers.metro.compiler.ir.qualifierAnnotation
import dev.zacsweers.metro.compiler.ir.rawTypeOrNull
import dev.zacsweers.metro.compiler.ir.regularParameters
import dev.zacsweers.metro.compiler.ir.reportCompat
import dev.zacsweers.metro.compiler.ir.requireSimpleFunction
import dev.zacsweers.metro.compiler.ir.requireStaticIshDeclarationContainer
import dev.zacsweers.metro.compiler.ir.thisReceiverOrFail
import dev.zacsweers.metro.compiler.ir.trackFunctionCall
import dev.zacsweers.metro.compiler.ir.typeRemapperFor
import dev.zacsweers.metro.compiler.memoized
import dev.zacsweers.metro.compiler.newName
import dev.zacsweers.metro.compiler.proto.InjectedClassProto
import dev.zacsweers.metro.compiler.proto.MetroMetadata
import dev.zacsweers.metro.compiler.reportCompilerBug
import org.jetbrains.kotlin.ir.builders.IrBlockBodyBuilder
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irSetField
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeParametersContainer
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.TypeRemapper
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.nestedClasses
import org.jetbrains.kotlin.ir.util.nonDispatchParameters
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

internal class MembersInjectorTransformer(context: IrMetroContext) : IrMetroContext by context {

  data class MemberInjectClass(
    val sourceClass: IrClass,
    val injectorClass: IrClass,
    val typeKey: IrTypeKey,
    val requiredParametersByClass: Map<ClassId, List<Parameters>>,
    val declaredInjectFunctions: Map<IrSimpleFunction, Parameters>,
    val isDagger: Boolean,
  ) {
    context(context: IrMetroContext)
    fun mergedParameters(remapper: TypeRemapper): Parameters {
      // $$MembersInjector -> origin class
      val classTypeParams = sourceClass.typeParameters.associateBy { it.name }
      val allParams =
        declaredInjectFunctions.map { (function, _) ->
          // Need a composite remapper
          // 1. Once to remap function type args -> substituted/matching parent class params
          // 2. The custom remapper we're receiving that uses parent class params
          val substitutionMap =
            function.typeParameters.associate {
              it.symbol to classTypeParams.getValue(it.name).defaultType
            }
          val typeParamRemapper = typeRemapperFor(substitutionMap)
          val compositeRemapper =
            object : TypeRemapper {
              override fun enterScope(irTypeParametersContainer: IrTypeParametersContainer) {}

              override fun leaveScope() {}

              override fun remapType(type: IrType): IrType {
                return remapper.remapType(typeParamRemapper.remapType(type))
              }
            }

          // In metro-generated injectors, we annotate the instance param with `@Assisted`
          // so for dagger interop, we transform the matching function to have the same annotation
          // for logic reuse
          val toUse =
            if (isDagger) {
              function.deepCopyWithSymbols(function.parent).apply {
                regularParameters[0].annotations +=
                  buildAnnotation(symbol, context.metroSymbols.assistedConstructor)
              }
            } else {
              function
            }

          toUse.parameters(compositeRemapper)
        }
      return when (allParams.size) {
        0 -> Parameters.empty()
        1 -> allParams.first()
        else -> allParams.reduce { current, next -> current.mergeValueParametersWith(next) }
      }
    }
  }

  private val generatedInjectors = mutableMapOf<ClassId, MemberInjectClass?>()
  private val injectorParamsByClass = mutableMapOf<ClassId, List<Parameters>>()

  fun visitClass(declaration: IrClass) {
    getOrGenerateInjector(declaration)
  }

  private fun requireInjector(declaration: IrClass): MemberInjectClass {
    return getOrGenerateInjector(declaration)
      ?: reportCompilerBug("No members injector found for ${declaration.kotlinFqName}.")
  }

  fun getOrGenerateAllInjectorsFor(declaration: IrClass): List<MemberInjectClass> {
    return declaration
      .getAllSuperTypes(excludeSelf = false, excludeAny = true)
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

    val typeKey =
      IrTypeKey(declaration.defaultType.wrapInMembersInjector(), declaration.qualifierAnnotation())

    fun computeMemberInjectClass(injectorClass: IrClass, isDagger: Boolean): MemberInjectClass {
      // Use cached member inject parameters if available, otherwise fall back to fresh lookup
      val injectedMembersByClass = declaration.getOrComputeMemberInjectParameters(isDagger)
      val parameterGroupsForClass = injectedMembersByClass.getValue(injectedClassId)

      val declaredInjectFunctions =
        parameterGroupsForClass.associateBy { params ->
          val name =
            if (params.isProperty) {
              params.irProperty!!.name
            } else {
              params.callableId.callableName
            }
          val creatorsClass = injectorClass.requireStaticIshDeclarationContainer()
          creatorsClass.requireSimpleFunction("inject${name.capitalizeUS().asString()}").owner
        }

      return MemberInjectClass(
        declaration,
        injectorClass,
        typeKey,
        injectedMembersByClass,
        declaredInjectFunctions,
        isDagger,
      )
    }

    // For external classes with no Metro metadata, the only option is Dagger (if enabled)
    if (isExternal && declaration.metroMetadata?.injected_class == null) {
      if (options.enableDaggerRuntimeInterop) {
        val daggerInjector =
          pluginContext.referenceClass(declaration.classIdOrFail.generatedClass("_MembersInjector"))
        if (daggerInjector != null) {
          return computeMemberInjectClass(daggerInjector.owner, isDagger = true).also {
            generatedInjectors[injectedClassId] = it
          }
        }
      }
      // No Metro metadata and no Dagger injector found - assume no members to inject
      generatedInjectors[injectedClassId] = null
      return null
    }

    // Look for Metro-generated injector (either in-compilation or external with metadata)
    val injectorClass =
      declaration.nestedClasses.singleOrNull {
        val isMetroImpl = it.name == Symbols.Names.MetroMembersInjector
        // If not external, double check its origin
        if (isMetroImpl && !isExternal) {
          if (it.origin != Origins.MembersInjectorClassDeclaration) {
            reportCompat(
              declaration,
              MetroDiagnostics.METRO_ERROR,
              "Found a Metro members injector declaration in ${declaration.kotlinFqName} but with an unexpected origin ${it.origin}",
            )
            exitProcessing()
          }
        }
        isMetroImpl
      }

    if (injectorClass == null) {
      // If we're external with Metro metadata but no nested class, that's an error
      if (isExternal) {
        reportCompat(
          declaration,
          MetroDiagnostics.METRO_ERROR,
          "Found Metro metadata for members injector on ${declaration.kotlinFqName} but could not find the nested MetroMembersInjector class",
        )
      }
      // For in-compilation classes, assume no members to inject
      generatedInjectors[injectedClassId] = null
      return null
    }

    val companionObject = injectorClass.companionObject()!!

    val memberInjectClass = computeMemberInjectClass(injectorClass, isDagger = false)
    if (isExternal) {
      return memberInjectClass.also { generatedInjectors[injectedClassId] = it }
    }

    val ctor = injectorClass.primaryConstructor!!

    val injectedMembersByClass = memberInjectClass.requiredParametersByClass
    val allParameters =
      injectedMembersByClass.values.flatMap { it.flatMap(Parameters::regularParameters) }

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
      parentClass = companionObject,
      targetClass = injectorClass,
      targetConstructor = ctor.symbol,
      parameters =
        injectedMembersByClass.values
          .flatten()
          .reduce { current, next -> current.mergeValueParametersWith(next) }
          .let {
            Parameters(
              Parameters.empty().callableId,
              null,
              null,
              it.regularParameters,
              it.contextParameters,
            )
          },
      providerFunction = null,
      patchCreationParams = false, // TODO when we support absent
    )

    // Implement static inject{name}() for each declared callable in this class
    for ((function, params) in memberInjectClass.declaredInjectFunctions) {
      function.apply {
        val instanceParam = regularParameters[0]

        // Copy any qualifier annotations over to propagate them
        for ((i, param) in regularParameters.drop(1).withIndex()) {
          val injectedParam = params.regularParameters[i]
          injectedParam.typeKey.qualifier?.let { qualifier ->
            pluginContext.metadataDeclarationRegistrar.addMetadataVisibleAnnotationsToElement(
              param,
              qualifier.ir.deepCopyWithSymbols(),
            )
          }
        }

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
            irExprBodySafe(bodyExpression)
          }
      }
    }

    val inheritedInjectFunctions: Map<IrSimpleFunction, Parameters> = buildMap {
      // Locate function refs for supertypes
      for ((classId, injectedMembers) in injectedMembersByClass) {
        if (classId == injectedClassId) continue
        if (injectedMembers.isEmpty()) continue

        // This is what generates supertypes lazily as needed
        val functions =
          requireInjector(pluginContext.referenceClass(classId)!!.owner).declaredInjectFunctions

        putAll(functions)
      }
    }

    val injectFunctions = inheritedInjectFunctions + memberInjectClass.declaredInjectFunctions

    // Override injectMembers()
    injectorClass.requireSimpleFunction(Symbols.StringNames.INJECT_MEMBERS).owner.apply {
      finalizeFakeOverride(injectorClass.thisReceiverOrFail)
      val typeArgs = declaration.typeParameters.map { it.defaultType }
      body =
        pluginContext.createIrBuilder(symbol).irBlockBody {
          addMemberInjection(
            typeArgs = typeArgs,
            callingFunction = this@apply,
            instanceReceiver = regularParameters[0],
            injectorReceiver = dispatchReceiverParameter!!,
            injectFunctions = injectFunctions,
            parametersToFields = sourceParametersToFields,
          )
        }
    }

    injectorClass.dumpToMetroLog()

    return memberInjectClass.also { generatedInjectors[injectedClassId] = it }
  }

  private fun IrClass.getOrComputeMemberInjectParameters(
    isDagger: Boolean
  ): Map<ClassId, List<Parameters>> {
    // Compute supertypes once - we'll need them for either cached lookup or fresh computation
    val allTypes =
      getAllSuperTypes(excludeSelf = false, excludeAny = true)
        .mapNotNull { it.rawTypeOrNull() }
        .filterNot { it.isInterface }
        .memoized()

    val result =
      processTypes(allTypes) { clazz, classId, nameAllocator ->
        injectorParamsByClass.getOrPut(classId) {
          if (clazz.isExternalParent) {
            // For external classes, check for Dagger first if we're in Dagger mode or interop is
            // enabled
            if (isDagger || options.enableDaggerRuntimeInterop) {
              val injectorClass =
                pluginContext
                  .referenceClass(clazz.classIdOrFail.generatedClass("_MembersInjector"))
                  ?.owner

              if (injectorClass != null) {
                return@getOrPut deriveParametersFromStaticInjectFunctions(
                  clazz,
                  injectorClass.requireStaticIshDeclarationContainer(),
                  nameAllocator,
                )
              }
            }

            // No Dagger injector found - check Metro metadata
            val metadata = clazz.metroMetadata?.injected_class
            val injectFunctionNames = metadata?.member_inject_functions ?: emptyList()

            if (injectFunctionNames.isNotEmpty()) {
              // Derive from existing injector class using cached function names
              deriveParametersFromInjectFunctionNames(clazz, injectFunctionNames, nameAllocator)
            } else {
              emptyList()
            }
          } else {
            // In-round class - could be from Dagger (via KSP/KAPT) or Metro
            // Check for Dagger injector first, even if not external
            if (isDagger || options.enableDaggerRuntimeInterop) {
              val injectorClass =
                pluginContext
                  .referenceClass(clazz.classIdOrFail.generatedClass("_MembersInjector"))
                  ?.owner

              if (injectorClass != null) {
                // Found Dagger injector, derive from it
                return@getOrPut deriveParametersFromStaticInjectFunctions(
                  clazz,
                  injectorClass.requireStaticIshDeclarationContainer(),
                  nameAllocator,
                )
              }
            }

            // No Dagger injector found - compute from source and cache
            val computed =
              clazz
                .declaredCallableMembers(
                  functionFilter = { it.isAnnotatedWithAny(metroSymbols.injectAnnotations) },
                  propertyFilter = {
                    (it.isVar || it.isLateinit) &&
                      (it.isAnnotatedWithAny(metroSymbols.injectAnnotations) ||
                        it.setter?.isAnnotatedWithAny(metroSymbols.injectAnnotations) == true ||
                        it.backingField?.isAnnotatedWithAny(metroSymbols.injectAnnotations) == true)
                  },
                )
                .map { it.ir.memberInjectParameters(nameAllocator, clazz) }
                // Stable sort properties first
                // TODO this implicit ordering requirement is brittle
                .sortedBy { !it.isProperty }
                .toList()

            // Cache function names derived from computed parameters
            val functionNames =
              computed.map { params ->
                val memberName =
                  if (params.isProperty) {
                    params.irProperty!!.name.asString()
                  } else {
                    params.callableId.callableName.asString()
                  }
                "inject${memberName.capitalizeUS()}"
              }
            clazz.cacheMemberInjectFunctionNamesInMetadata(functionNames)

            computed
          }
        }
      }

    return result
  }

  private fun IrClass.cacheMemberInjectFunctionNamesInMetadata(functionNames: List<String>) {
    if (isExternalParent) return
    val injectedClass = InjectedClassProto(member_inject_functions = functionNames)

    // Store the metadata for this class only
    metroMetadata = MetroMetadata(injected_class = injectedClass)
  }

  private fun deriveParametersFromInjectFunctionNames(
    clazz: IrClass,
    injectFunctionNames: List<String>,
    nameAllocator: NameAllocator,
  ): List<Parameters> {
    val injectorClass =
      clazz.nestedClasses.singleOrNull { it.name == Symbols.Names.MetroMembersInjector }
        ?: return emptyList()

    val companionObject = injectorClass.companionObject() ?: return emptyList()

    return injectFunctionNames.mapNotNull { functionName ->
      // Find the inject function by name
      val injectFunction =
        companionObject.declarations.filterIsInstance<IrSimpleFunction>().find {
          it.name.asString() == functionName
        }

      injectFunction?.let { function ->
        extractParametersFromInjectFunction(clazz, nameAllocator, function)
      }
    }
  }

  private fun extractParametersFromInjectFunction(
    clazz: IrClass,
    nameAllocator: NameAllocator,
    function: IrSimpleFunction,
  ): Parameters {
    // Derive Parameters directly from inject function signature
    // Drop the first as that's always the instance param, which we'll handle separately
    val dependencyParams = function.nonDispatchParameters.drop(1)
    val memberName = function.name.asString().removePrefix("inject").decapitalizeUS()

    // Create a synthetic Parameters object from the inject function
    val callableId = CallableId(clazz.classIdOrFail, Name.identifier(memberName))
    val regularParams =
      dependencyParams.map { param ->
        // Convert IrValueParameter to Parameter - derive from inject function param
        param.toMemberInjectParameter(uniqueName = nameAllocator.newName(param.name))
      }

    return Parameters(
      callableId = callableId,
      dispatchReceiverParameter = null,
      extensionReceiverParameter = null,
      regularParameters = regularParams,
      contextParameters = emptyList(),
      ir = function,
    )
  }

  /**
   * Derives parameters from Dagger's static inject functions. Matches all static functions starting
   * with "inject" that return Unit. Note: Dagger only uses `@InjectedFieldSignature` for field
   * injection, not setter injection.
   */
  private fun deriveParametersFromStaticInjectFunctions(
    clazz: IrClass,
    injectorClass: IrClass,
    nameAllocator: NameAllocator,
  ): List<Parameters> {
    // Dagger functions are static in the class itself
    return injectorClass.functions
      .filter { function ->
        // Match all static functions starting with "inject" that return Unit
        function.isStaticIsh &&
          function.name.asString().startsWith("inject") &&
          function.returnType.isUnit() &&
          // Shorthand to filter out overrides of "injectMembers", which may pass through here
          // IFF they're generated kotlin injector sources, for example from Anvil
          function.overriddenSymbolsSequence().none()
      }
      .map { function -> extractParametersFromInjectFunction(clazz, nameAllocator, function) }
      .toList()
  }

  /**
   * Common logic for processing types and collecting injectable member parameters.
   *
   * @param types The precomputed sequence of types to process
   * @param membersExtractor Function that takes (clazz, classId, nameAllocator) and returns a list
   *   of Parameters for that class
   */
  private fun processTypes(
    types: Sequence<IrClass>,
    membersExtractor: (IrClass, ClassId, NameAllocator) -> List<Parameters>,
  ): Map<ClassId, List<Parameters>> {
    return buildList {
        val nameAllocator = NameAllocator(mode = NameAllocator.Mode.COUNT)

        for (clazz in types) {
          val classId = clazz.classIdOrFail
          val injectedMembers = membersExtractor(clazz, classId, nameAllocator)

          if (injectedMembers.isNotEmpty()) {
            add(classId to injectedMembers)
          }
        }
      }
      // Reverse it such that the supertypes are first
      .asReversed()
      .associate { it.first to it.second }
  }
}

context(context: IrMetroContext)
internal fun IrBlockBodyBuilder.addMemberInjection(
  typeArgs: List<IrType>?,
  callingFunction: IrSimpleFunction,
  injectFunctions: Map<IrSimpleFunction, Parameters>,
  parametersToFields: Map<Parameter, IrField>,
  instanceReceiver: IrValueParameter,
  injectorReceiver: IrValueParameter,
) {
  for ((function, parameters) in injectFunctions) {
    trackFunctionCall(callingFunction, function)
    +irInvoke(
      dispatchReceiver = irGetObject(function.parentAsClass.symbol),
      callee = function.symbol,
      typeArgs = typeArgs,
      args =
        buildList {
          add(irGet(instanceReceiver))
          addAll(parametersAsProviderArguments(parameters, injectorReceiver, parametersToFields))
        },
    )
  }
}
