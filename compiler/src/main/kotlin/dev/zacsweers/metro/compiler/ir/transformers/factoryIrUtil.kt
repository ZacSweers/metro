// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.MetroAnnotations
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.applyIf
import dev.zacsweers.metro.compiler.compat.propertyIfAccessorCompat
import dev.zacsweers.metro.compiler.ir.IrAnnotation
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.addAnnotationCompat
import dev.zacsweers.metro.compiler.ir.addAnnotationsCompat
import dev.zacsweers.metro.compiler.ir.addHiddenFromObjCAnnotation
import dev.zacsweers.metro.compiler.ir.addStaticAnnotations
import dev.zacsweers.metro.compiler.ir.annotationClass
import dev.zacsweers.metro.compiler.ir.annotationsCompat
import dev.zacsweers.metro.compiler.ir.annotationsIn
import dev.zacsweers.metro.compiler.ir.asCanonicalProviderKey
import dev.zacsweers.metro.compiler.ir.buildAnnotation
import dev.zacsweers.metro.compiler.ir.canBeInlined
import dev.zacsweers.metro.compiler.ir.copyParameterDefaultValues
import dev.zacsweers.metro.compiler.ir.createIrBuilder
import dev.zacsweers.metro.compiler.ir.deepRemapperFor
import dev.zacsweers.metro.compiler.ir.extensionReceiverParameterCompat
import dev.zacsweers.metro.compiler.ir.hasMetroDefault
import dev.zacsweers.metro.compiler.ir.irCallConstructorWithSameParameters
import dev.zacsweers.metro.compiler.ir.irExprBodySafe
import dev.zacsweers.metro.compiler.ir.parameters.Parameter
import dev.zacsweers.metro.compiler.ir.parameters.Parameters
import dev.zacsweers.metro.compiler.ir.parameters.dedupeParameters
import dev.zacsweers.metro.compiler.ir.parameters.parameters
import dev.zacsweers.metro.compiler.ir.regularParameters
import dev.zacsweers.metro.compiler.ir.replaceAnnotationsCompat
import dev.zacsweers.metro.compiler.ir.requireStaticIshDeclarationContainer
import dev.zacsweers.metro.compiler.ir.setDispatchReceiver
import dev.zacsweers.metro.compiler.ir.setExtensionReceiver
import dev.zacsweers.metro.compiler.ir.stubExpression
import dev.zacsweers.metro.compiler.ir.thisReceiverOrFail
import dev.zacsweers.metro.compiler.metroAnnotations
import dev.zacsweers.metro.compiler.mirrorIrConstructorCalls
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addFunction
import org.jetbrains.kotlin.ir.builders.declarations.addGetter
import org.jetbrains.kotlin.ir.builders.declarations.addProperty
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeSubstitutor
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.typeWithParameters
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.copyParametersFrom
import org.jetbrains.kotlin.ir.util.copyTo
import org.jetbrains.kotlin.ir.util.copyTypeParametersFrom
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isObject
import org.jetbrains.kotlin.ir.util.nonDispatchParameters
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.platform.jvm.isJvm

/**
 * Implement a static `create()` function for a given [targetConstructor].
 *
 * ```kotlin
 * // Simple
 * fun create(valueProvider: Provider<String>): Example_Factory = Example_Factory(valueProvider)
 *
 * // Generic
 * fun <T> create(valueProvider: Provider<T>): Example_Factory<T> = Example_Factory<T>(valueProvider)
 * ```
 */
@IgnorableReturnValue
context(context: IrMetroContext)
internal fun generateStaticCreateFunction(
  objectClassToGenerateIn: IrClass,
  factoryClass: IrClass,
  sourceTypeParameters: IrClass,
  returnTypeProvider: (List<IrTypeParameter>) -> IrType, // Not called if assisted inject
  targetConstructor: IrConstructorSymbol,
  parameters: Parameters,
  sourceFunction: IrFunction?,
  patchCreationParams: Boolean = true,
  isAssistedInject: Boolean = false,
  wrapInSuspendProvider: Boolean = false,
  stubDefaults: Boolean = true,
): IrSimpleFunction {
  val createFunction =
    objectClassToGenerateIn
      .addFunction(
        name = Symbols.StringNames.CREATE,
        // Placeholder, replaced in body
        returnType = context.irBuiltIns.unitType,
        origin = Origins.FactoryCreateFunction,
      )
      .apply {
        val typeParams = copyTypeParametersFrom(sourceTypeParameters)
        this.returnType =
          if (isAssistedInject) {
            factoryClass.symbol.typeWithParameters(typeParams)
          } else {
            returnTypeProvider(typeParams)
          }
        val typeRemapper =
          sourceTypeParameters.deepRemapperFor(
            sourceTypeParameters.symbol.typeWithParameters(typeParams)
          )
        addParameters(
          parameters.allParameters.filterNot { it.isAssisted },
          wrapInProvider = true,
          copyQualifiers = true,
          stubDefaults = stubDefaults,
          typeRemapper = { type -> typeRemapper.remapType(type) },
          wrapInSuspendProvider = wrapInSuspendProvider,
        )
        addHiddenFromObjCAnnotation(this)
        addStaticAnnotations(this)
        if (factoryClass.shouldRegisterGeneratedFactoryMembersAsMetadataVisible()) {
          context.metadataDeclarationRegistrarCompat.registerFunctionAsMetadataVisible(this)
        }
      }
  transformStaticCreateFunction(
    factoryClass = factoryClass,
    targetConstructor = targetConstructor,
    parameters = parameters,
    providerFunction = sourceFunction,
    patchCreationParams = patchCreationParams,
    copyQualifiers = false, // We've already done it
    createFunction = createFunction,
  )
  return createFunction
}

@IgnorableReturnValue
context(context: IrMetroContext)
internal fun transformStaticCreateFunction(
  objectClassToGenerateIn: IrClass,
  factoryClass: IrClass,
  targetConstructor: IrConstructorSymbol,
  parameters: Parameters,
  providerFunction: IrFunction?,
  patchCreationParams: Boolean = true,
  copyQualifiers: Boolean = false,
): IrSimpleFunction {
  val createFunction =
    objectClassToGenerateIn.functions.first { it.origin == Origins.FactoryCreateFunction }
  transformStaticCreateFunction(
    factoryClass = factoryClass,
    targetConstructor = targetConstructor,
    parameters = parameters,
    providerFunction = providerFunction,
    patchCreationParams = patchCreationParams,
    copyQualifiers = copyQualifiers,
    createFunction = createFunction,
  )
  return createFunction
}

context(context: IrMetroContext)
private fun transformStaticCreateFunction(
  factoryClass: IrClass,
  targetConstructor: IrConstructorSymbol,
  parameters: Parameters,
  providerFunction: IrFunction?,
  patchCreationParams: Boolean, // TODO eventually move this to function creation
  copyQualifiers: Boolean,
  createFunction: IrSimpleFunction,
) {
  createFunction.apply {
    if (patchCreationParams) {
      val instanceParam = regularParameters.find { it.origin == Origins.InstanceParameter }
      val valueParamsToPatch = nonDispatchParameters.filter {
        it.origin == Origins.RegularParameter
      }
      copyParameterDefaultValues(
        providerFunction = providerFunction,
        sourceMetroParameters = parameters,
        sourceParameters =
          parameters.nonDispatchParameters
            .filterNot { it.isAssisted || it.ir?.origin == Origins.InstanceParameter }
            .map { it.asValueParameter },
        targetParameters = valueParamsToPatch,
        containerParameter = instanceParam,
        wrapInProvider = true,
      )
    }

    // Copy qualifier annotations from source parameters to function parameters
    if (copyQualifiers) {
      for ((i, param) in regularParameters.withIndex()) {
        val sourceParam = parameters.regularParameters[i]
        val qualifier = sourceParam.typeKey.qualifier ?: continue
        val qualifierAlreadyPresent =
          param.annotationsCompat().any { existing -> IrAnnotation(existing) == qualifier }
        if (qualifierAlreadyPresent) continue

        with(context) {
          metadataDeclarationRegistrarCompat.addMetadataVisibleAnnotationsToElement(
            param,
            listOf(qualifier.ir.deepCopyWithSymbols()),
          )
        }
      }
    }

    body =
      context.createIrBuilder(symbol).run {
        irExprBodySafe(
          if (factoryClass.isObject) {
            irGetObject(factoryClass.symbol)
          } else {
            irCallConstructorWithSameParameters(createFunction, targetConstructor)
          }
        )
      }
  }
}

/**
 * Generates a static `newInstance()` function into a given [parentClass].
 *
 * ```
 * // Simple
 * fun newInstance(value: T): Example = Example(value)
 *
 * // Generic
 * fun <T> newInstance(value: T): Example<T> = Example<T>(value)
 *
 * // Provider
 * fun newInstance(value: Provider<String>): Example = Example(value)
 * ```
 */
context(context: IrMetroContext)
internal fun generateStaticNewInstanceFunction(
  parentClass: IrClass,
  factoryClass: IrClass = parentClass,
  sourceTypeParameters: IrClass,
  returnTypeProvider: (List<IrTypeParameter>) -> IrType,
  sourceMetroParameters: Parameters,
  sourceParameters: List<IrValueParameter>,
  signatureAnnotations: MetroAnnotations<IrAnnotation>? = null,
  functionName: String = Symbols.StringNames.NEW_INSTANCE,
  targetFunction: IrFunction? = null,
  isSuspend: Boolean = false,
  buildBody: IrBuilderWithScope.(IrSimpleFunction) -> IrExpression,
): IrSimpleFunction {
  val newInstanceFunction =
    parentClass
      .addFunction(
        name = functionName,
        // Placeholder, replaced in body
        returnType = context.irBuiltIns.unitType,
        origin = Origins.FactoryNewInstanceFunction,
        isSuspend = isSuspend,
        // inline can only work if the target is visible
        isInline = targetFunction?.canBeInlined() == true,
      )
      .apply {
        val typeParams = copyTypeParametersFrom(sourceTypeParameters)
        this.returnType = returnTypeProvider(typeParams)
        val typeRemapper =
          sourceTypeParameters.deepRemapperFor(
            sourceTypeParameters.symbol.typeWithParameters(typeParams)
          )
        signatureAnnotations?.let {
          copySignatureAnnotations(factoryClass, targetFunction, it)
        }
        addParameters(
          sourceMetroParameters.allParameters,
          wrapInProvider = false,
          copyQualifiers = true,
          copyAssisted = signatureAnnotations != null,
          copySourceOffsets = signatureAnnotations != null,
          typeRemapper = { type -> typeRemapper.remapType(type) },
        )
        addHiddenFromObjCAnnotation(this)
        addStaticAnnotations(this)
        if (factoryClass.shouldRegisterGeneratedFactoryMembersAsMetadataVisible()) {
          context.metadataDeclarationRegistrarCompat.registerFunctionAsMetadataVisible(this)
        }
      }
  transformStaticNewInstanceFunction(
    sourceMetroParameters = sourceMetroParameters,
    sourceParameters = sourceParameters,
    targetFunction = targetFunction,
    newInstanceFunction = newInstanceFunction,
    buildBody = buildBody,
  )
  return newInstanceFunction
}

context(context: IrMetroContext)
private fun transformStaticNewInstanceFunction(
  sourceMetroParameters: Parameters,
  sourceParameters: List<IrValueParameter>,
  targetFunction: IrFunction?,
  newInstanceFunction: IrSimpleFunction,
  buildBody: IrBuilderWithScope.(IrSimpleFunction) -> IrExpression,
) {
  newInstanceFunction.apply {
    val instanceParam = regularParameters.find { it.origin == Origins.InstanceParameter }
    val valueParametersToMap = nonDispatchParameters.filter {
      it.origin == Origins.RegularParameter
    }
    // TODO move to function creation
    copyParameterDefaultValues(
      providerFunction = targetFunction,
      sourceMetroParameters = sourceMetroParameters,
      sourceParameters = sourceParameters,
      targetParameters = valueParametersToMap,
      containerParameter = instanceParam,
    )

    body = context.createIrBuilder(symbol).run { irExprBodySafe(buildBody(this@apply)) }
  }
}

/**
 * Generates a metadata-visible declaration in the factory class that matches the target callable.
 * This declaration is used in downstream compilations to read the callable's signature and also
 * dirty IC.
 */
context(context: IrMetroContext)
internal fun generateMetadataVisibleDeclarationMirror(
  factoryClass: IrClass,
  target: IrFunction?,
  backingField: IrField?,
  annotations: MetroAnnotations<IrAnnotation>,
  registerAsMetadataVisible: Boolean = true,
): IrSimpleFunction {
  val returnType =
    target?.returnType
      ?: backingField?.type
      ?: error("Either target or backingField must be non-null")

  val sourceProperty =
    target?.propertyIfAccessorCompat as? IrProperty
      ?: backingField?.correspondingPropertySymbol?.owner
  val shouldGeneratePropertyMirror =
    context.options.enablePrivateProviderProperties &&
      sourceProperty?.visibility == DescriptorVisibilities.PRIVATE
  val property =
    if (shouldGeneratePropertyMirror) {
      factoryClass.addProperty {
        name = Symbols.Names.declarationMirror
        visibility = DescriptorVisibilities.PUBLIC
        modality = Modality.FINAL
        origin = Origins.Default
      }
    } else {
      null
    }

  val function =
    if (property != null) {
      property.addGetter {
        this.returnType = returnType
        visibility = DescriptorVisibilities.PUBLIC
        modality = Modality.FINAL
        origin = Origins.Default
        isInline = target?.canBeInlined() == true
        isSuspend = target is IrSimpleFunction && target.isSuspend
      }
    } else {
      factoryClass.addFunction {
        name = Symbols.Names.declarationMirror
        this.returnType = returnType
        this.isInline = target?.canBeInlined() == true
        this.isSuspend = target is IrSimpleFunction && target.isSuspend
      }
    }
  function.apply {
    val typeSubstitution =
      if (target is IrConstructor) {
        val sourceClass = factoryClass.parentAsClass
        val copiedTypeParameters = copyTypeParametersFrom(sourceClass)
        sourceClass.typeParameters.zip(copiedTypeParameters).associate { (source, copied) ->
          source.symbol to copied.defaultType
        }
      } else {
        // Copy type parameters from the factory class (e.g., generic binding containers)
        val copiedTypeParameters = copyTypeParametersFrom(factoryClass)
        buildMap {
          factoryClass.typeParameters.zip(copiedTypeParameters).forEach { (source, copied) ->
            put(source.symbol, copied.defaultType)
          }
          factoryClass.parentAsClass.typeParameters.zip(copiedTypeParameters).forEach {
            (source, copied) ->
            put(source.symbol, copied.defaultType)
          }
        }
      }
    copySignatureAnnotations(factoryClass, target, annotations)
    if (target != null) {
      if (typeSubstitution.isNotEmpty()) {
        copyParametersFrom(target, typeSubstitution)
      } else {
        copyParametersFrom(target)
      }
      target.extensionReceiverParameterCompat?.let { receiver ->
        val receiverType = IrTypeSubstitutor(typeSubstitution).substitute(receiver.type)
        setExtensionReceiver(receiver.copyTo(this, type = receiverType))
      }
    }
    setDispatchReceiver(factoryClass.thisReceiverOrFail.copyTo(this))
    typeSubstitution
      .takeIf { it.isNotEmpty() }
      ?.let {
        this.returnType = IrTypeSubstitutor(it).substitute(returnType)
      }

    regularParameters.forEach {
      // If it has a default value expression, just replace it with a stub. We don't need it to
      // be functional, we just need it to be indicated
      if (it.hasMetroDefault()) {
        it.defaultValue = context.createIrBuilder(symbol).run { irExprBody(stubExpression()) }
      } else {
        it.defaultValue = null
      }
    }
    // The declaration's signature already matches the target callable's signature.
    body = context.createIrBuilder(symbol).run { irExprBodySafe(stubExpression()) }

    // On JVM, mark as @ComptimeOnly so R8 can strip the declaration mirror from runtime jars
    if (context.pluginContext.platform.isJvm()) {
      addAnnotationCompat(
        buildAnnotation(symbol, context.metroSymbols.comptimeOnlyAnnotationConstructor)
      )
    }
  }
  addHiddenFromObjCAnnotation(function)
  if (registerAsMetadataVisible) {
    if (property != null) {
      context.metadataDeclarationRegistrarCompat.registerPropertyAsMetadataVisible(property)
    } else {
      context.metadataDeclarationRegistrarCompat.registerFunctionAsMetadataVisible(function)
    }
  }
  return function
}

context(context: IrMetroContext)
private fun IrSimpleFunction.copySignatureAnnotations(
  factoryClass: IrClass,
  target: IrFunction?,
  annotations: MetroAnnotations<IrAnnotation>,
) {
  if (target is IrConstructor) {
    val sourceClass = factoryClass.parentAsClass
    val classMetroAnnotations = sourceClass.metroAnnotations(context.metroSymbols.classIds)
    val scopeAndQualifierAnnotations = buildList {
      classMetroAnnotations.scope?.ir?.let(::add)
      classMetroAnnotations.qualifier?.ir?.let(::add)
    }
    if (scopeAndQualifierAnnotations.isNotEmpty()) {
      addAnnotationsCompat(scopeAndQualifierAnnotations)
    }
    return
  }

  replaceAnnotationsCompat(
    annotations
      .mirrorIrConstructorCalls(symbol)
      .filterNot {
        // Exclude @Provides to avoid reentrant factory generation.
        it.annotationClass.classId in context.metroSymbols.classIds.providesAnnotations
      }
      .map { it.deepCopyWithSymbols() }
  )
}

context(context: IrMetroContext)
internal fun shouldUseCreatorSignatureCarrier(): Boolean {
  val supportsIrGeneratedClasses = context.icCapabilities.irGeneratedClasses
  val annotationsAreReadable = context.icCapabilities.readableAnnotationMetadata
  val annotationChangesInvalidateLookups = context.icCapabilities.annotationArgumentInvalidation
  return supportsIrGeneratedClasses && annotationsAreReadable && annotationChangesInvalidateLookups
}

context(context: IrMetroContext)
private fun IrClass.shouldRegisterGeneratedFactoryMembersAsMetadataVisible(): Boolean {
  return context.options.generateClassesInIr ||
    !parentAsClass.hasAnnotation(Symbols.ClassIds.irOnlyFactories)
}

/**
 * Adds stub `create()` and named creator functions to a factory class for cross-module invisible
 * factory stubs. These are phantom functions that the consuming module can reference, at runtime
 * the real factory class from the producing module provides the actual implementation.
 *
 * For object factories, the functions are added directly to the object. For class factories, the
 * functions are added to the companion object.
 */
context(context: IrMetroContext)
internal fun generateStubCreatorFunctions(
  factoryClass: IrClass,
  callableName: String,
  returnType: IrType,
  sourceFunction: IrSimpleFunction,
) {
  val creatorClass = factoryClass.requireStaticIshDeclarationContainer()

  val sourceParameters = sourceFunction.parameters()
  val createParameters =
    sourceParameters.copy(
      regularParameters =
        sourceParameters.regularParameters.dedupeParameters(
          defaultUsesSuspendProvider = sourceFunction.isSuspend
        )
    )

  // create() function, parameters are Provider-wrapped
  creatorClass.addFunction(Symbols.StringNames.CREATE, factoryClass.defaultType).apply {
    setDispatchReceiver(creatorClass.thisReceiverOrFail.copyTo(this))
    addParameters(
      createParameters.nonDispatchParameters,
      wrapInProvider = true,
      copyQualifiers = true,
      wrapInSuspendProvider = sourceFunction.isSuspend,
    )
    addStaticAnnotations(this)
    body = context.createIrBuilder(symbol).run { irExprBodySafe(stubExpression()) }
  }

  // Named function (e.g., "provideImplAsBase")
  creatorClass.addFunction(callableName, returnType).apply {
    isSuspend = sourceFunction.isSuspend
    setDispatchReceiver(creatorClass.thisReceiverOrFail.copyTo(this))
    addParameters(
      sourceParameters.nonDispatchParameters,
      wrapInProvider = false,
      copyQualifiers = true,
    )
    addStaticAnnotations(this)
    body = context.createIrBuilder(symbol).run { irExprBodySafe(stubExpression()) }
  }
}

context(context: IrMetroContext)
internal fun IrFunction.addParameters(
  params: List<Parameter>,
  wrapInProvider: Boolean,
  copyQualifiers: Boolean = false,
  copyAssisted: Boolean = false,
  copySourceOffsets: Boolean = false,
  typeRemapper: ((IrType) -> IrType)? = null,
  stubDefaults: Boolean = true,
  /**
   * Wrap non-dispatch-receiver params in `SuspendProvider<…>` instead of `Provider<…>`. Used when
   * generating constructors / `create()` / etc. for a factory that backs a suspend `@Provides`, so
   * the field type can be invoked from the suspend `invoke()` body and so the graph can pass a
   * `SuspendProvider<…>` directly when the dep is itself suspend.
   */
  wrapInSuspendProvider: Boolean = false,
  onParam: (Parameter, IrValueParameter) -> Unit = { _, _ -> },
) {
  for (param in params) {
    val isInstanceParam = param.asValueParameter.kind == IrParameterKind.DispatchReceiver
    val baseType =
      if (wrapInProvider && !isInstanceParam) {
        val ctxKey = param.contextualTypeKey
        val usesSuspendProvider = ctxKey.wrappedType.usesSuspendProvider(wrapInSuspendProvider)
        ctxKey.asCanonicalProviderKey(usesSuspendProvider).toIrType()
      } else {
        param.contextualTypeKey.toIrType()
      }
    addValueParameter(
        name =
          if (isInstanceParam) {
            Symbols.Names.instance
          } else {
            param.name
          },
        type = typeRemapper?.invoke(baseType) ?: baseType,
        origin =
          if (isInstanceParam) {
            Origins.InstanceParameter
          } else {
            Origins.RegularParameter
          },
      )
      .applyIf(stubDefaults) {
        // Set a stub default value so that metadata registration (which may happen before
        // copyParameterDefaultValues runs) records hasDefaultValue = true for this parameter.
        // The real default expression is set later by copyParameterDefaultValues.
        if (param.hasDefault) {
          defaultValue = context.createIrBuilder(symbol).run { irExprBody(stubExpression()) }
        }
      }
      .apply {
        if (copySourceOffsets) {
          startOffset = param.asValueParameter.startOffset
          endOffset = param.asValueParameter.endOffset
        }
        // Propagate @OptionalBinding if present
        param.asValueParameter
          .annotationsIn(context.metroSymbols.classIds.optionalBindingAnnotations)
          .firstOrNull()
          ?.let { addAnnotationCompat(it.deepCopyWithSymbols()) }
        if (copyAssisted) {
          param.asValueParameter
            .annotationsIn(context.metroSymbols.assistedAnnotations)
            .singleOrNull()
            ?.let { addAnnotationCompat(it.deepCopyWithSymbols()) }
        }
        if (copyQualifiers) {
          param.typeKey.qualifier?.let { addAnnotationCompat(it.ir.deepCopyWithSymbols()) }
        }
      }
      .also { onParam(param, it) }
  }
}
