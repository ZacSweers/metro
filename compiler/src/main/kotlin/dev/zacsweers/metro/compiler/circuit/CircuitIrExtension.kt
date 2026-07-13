// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.circuit

import dev.zacsweers.metro.compiler.ClassIds
import dev.zacsweers.metro.compiler.Origins
import dev.zacsweers.metro.compiler.capitalizeUS
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.compat.IrGeneratedDeclarationsRegistrarCompat
import dev.zacsweers.metro.compiler.expectAsOrNull
import dev.zacsweers.metro.compiler.ir.abstractFunctions
import dev.zacsweers.metro.compiler.ir.annotationsCompat
import dev.zacsweers.metro.compiler.ir.buildAnnotation
import dev.zacsweers.metro.compiler.ir.createIrBuilder
import dev.zacsweers.metro.compiler.ir.finalizeFakeOverride
import dev.zacsweers.metro.compiler.ir.findInjectableConstructor
import dev.zacsweers.metro.compiler.ir.finderFor
import dev.zacsweers.metro.compiler.ir.generateDefaultConstructorBody
import dev.zacsweers.metro.compiler.ir.irInvoke
import dev.zacsweers.metro.compiler.ir.isAnnotatedWithAny
import dev.zacsweers.metro.compiler.ir.kClassReference
import dev.zacsweers.metro.compiler.ir.regularParameters
import dev.zacsweers.metro.compiler.symbols.Symbols
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.wasm.ir2wasm.allSuperInterfaces
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.fir.backend.FirMetadataSource
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irBranch
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irElseBranch
import org.jetbrains.kotlin.ir.builders.irEqeqeq
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irIs
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.builders.irWhen
import org.jetbrains.kotlin.ir.declarations.IrAnnotationContainer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrEnumEntry
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrClassReference
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetEnumValueImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeSystemContextImpl
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.types.starProjectedType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.SYNTHETIC_OFFSET
import org.jetbrains.kotlin.ir.util.addChild
import org.jetbrains.kotlin.ir.util.addFakeOverrides
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.file
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.implicitCastIfNeededTo
import org.jetbrains.kotlin.ir.util.isTopLevelDeclaration
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds

/**
 * IR extension for Circuit-generated factories.
 *
 * In the legacy path, FIR still creates the factory class signatures and this extension only fills
 * in backing fields plus `create()` bodies. When Metro is generating classes directly in IR, this
 * extension first creates the factory class shells as metadata-visible declarations, then runs the
 * same body-fill pass over those new classes.
 *
 * This extension should run after the Compose compiler IR plugin.
 */
public class CircuitIrExtension(
  private val generateClassesInIr: Boolean,
  private val function0Types: Set<ClassId>,
  private val assistedFactoryAnnotations: Set<ClassId>,
  private val injectAnnotations: Set<ClassId>,
  private val qualifierAnnotations: Set<ClassId>,
  private val compatContext: CompatContext,
) : IrGenerationExtension {
  public companion object {
    public fun create(
      generateClassesInIr: Boolean,
      classIds: ClassIds,
      compatContext: CompatContext,
    ): CircuitIrExtension {
      return CircuitIrExtension(
        generateClassesInIr = generateClassesInIr,
        function0Types = classIds.function0Types,
        assistedFactoryAnnotations = classIds.assistedFactoryAnnotations,
        injectAnnotations = classIds.allInjectAnnotations,
        qualifierAnnotations = classIds.qualifierAnnotations,
        compatContext = compatContext,
      )
    }
  }

  override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
    // Circuit runs as a separate IR extension before Metro's main IR pipeline, so it cannot use
    // the DI-provided Symbols instance. Keep this local helper Circuit-focused and pass only the
    // Metro configuration bits this extension actually needs.
    val symbols = CircuitSymbols.Ir(with(compatContext) { pluginContext.finderForBuiltinsCompat() })
    val transformer =
      CircuitIrTransformer(
        pluginContext = pluginContext,
        symbols = symbols,
        generateClassesInIr = generateClassesInIr,
        function0Types = function0Types,
        assistedFactoryAnnotations = assistedFactoryAnnotations,
        injectAnnotations = injectAnnotations,
        qualifierAnnotations = qualifierAnnotations,
        compatContext = compatContext,
      )
    if (generateClassesInIr) {
      // Metro's main IR transformer expects contributed classes to already exist. Create Circuit
      // factories before the normal visitor pass so they look like FIR-generated factories by the
      // time their bodies and Metro contribution classes are generated.
      transformer.generateFactoryShells(moduleFragment)
    }
    moduleFragment.transformChildrenVoid(transformer)
  }
}

private class CircuitIrTransformer(
  private val pluginContext: IrPluginContext,
  private val symbols: CircuitSymbols.Ir,
  private val generateClassesInIr: Boolean,
  private val function0Types: Set<ClassId>,
  private val assistedFactoryAnnotations: Set<ClassId>,
  private val injectAnnotations: Set<ClassId>,
  private val qualifierAnnotations: Set<ClassId>,
  private val compatContext: CompatContext,
) : IrElementTransformerVoid(), CompatContext by compatContext {
  private val builtinsFinder by lazy {
    with(compatContext) { pluginContext.finderForBuiltinsCompat() }
  }

  private val metadataDeclarationRegistrarCompat: IrGeneratedDeclarationsRegistrarCompat by lazy {
    compatContext.createIrGeneratedDeclarationsRegistrar(pluginContext)
  }

  private val irTypeSystemContext by lazy { IrTypeSystemContextImpl(pluginContext.irBuiltIns) }

  // Target data for factories created in this IR pass. Legacy factories read equivalent data from
  // their FIR metadata source; see circuitFactoryTargetData().
  private val generatedTargets = mutableMapOf<IrClass, CircuitIrFactoryTarget>()

  private val injectAnnotationCtor by lazy {
    builtinsFinder.findClass(Symbols.ClassIds.metroInject)!!.constructors.first()
  }

  private val contributesIntoSetAnnotationCtor by lazy {
    builtinsFinder.findClass(CONTRIBUTES_INTO_SET_CLASS_ID)!!.constructors.first()
  }

  private val composableAnnotationCtor by lazy {
    builtinsFinder.findClass(Symbols.ClassIds.Composable)!!.constructors.first()
  }

  private val originAnnotationCtor by lazy {
    builtinsFinder.findClass(Symbols.ClassIds.metroOrigin)!!.constructors.first()
  }

  private val deprecatedAnnotationCtor by lazy {
    builtinsFinder.findClass(StandardClassIds.Annotations.Deprecated)!!.constructors.first {
      it.owner.isPrimary
    }
  }

  private val deprecationLevel by lazy {
    builtinsFinder.findClass(StandardClassIds.DeprecationLevel)!!
  }

  private val hiddenDeprecationLevel by lazy {
    deprecationLevel.owner.declarations
      .filterIsInstance<IrEnumEntry>()
      .single { it.name.asString() == "HIDDEN" }
      .symbol
  }

  /** Cached invoke() symbol for metro's Provider type. */
  private val providerInvokeFunction: IrSimpleFunctionSymbol by lazy {
    builtinsFinder.findClass(Symbols.ClassIds.metroProvider)!!.functions.first {
      it.owner.name.asString() == "invoke"
    }
  }

  fun generateFactoryShells(moduleFragment: IrModuleFragment) {
    // Walk source declarations only. Newly generated factories are added to these files/classes but
    // are not themselves scanned as @CircuitInject targets.
    for (file in moduleFragment.files) {
      for (declaration in file.declarations.toList()) {
        when (declaration) {
          is IrClass -> generateNestedFactoryShells(declaration)
          is IrSimpleFunction -> generateTopLevelFunctionFactoryShell(declaration)
        }
      }
    }
  }

  override fun visitClass(declaration: IrClass): IrStatement {
    if (
      declaration.origin.expectAsOrNull<IrDeclarationOrigin.GeneratedByPlugin>()?.pluginKey
        is CircuitOrigins.FactoryClass
    ) {
      // Find the target info from the factory class annotations
      val circuitTargetInfo = declaration.circuitFactoryTargetData()
      val screenClass =
        with(compatContext) {
          pluginContext.finderFor(declaration).findClass(circuitTargetInfo.screenType)
        }!!

      if (!generateClassesInIr) {
        // Legacy FIR-generated Circuit factories could not safely receive @Origin in FIR, so keep
        // adding it here. IR-generated factories already get it as part of shell creation.
        circuitTargetInfo.originClassId?.let { originClassId ->
          metadataDeclarationRegistrarCompat.addMetadataVisibleAnnotationsToElement(
            declaration,
            context(pluginContext) {
              buildAnnotation(declaration.symbol, originAnnotationCtor) {
                it.arguments[0] =
                  kClassReference(
                    with(compatContext) {
                      pluginContext.finderFor(declaration).findClass(originClassId)
                    }!!
                  )
              }
            },
          )
        }
      }

      val fieldsByName = addBackingFieldsForConstructorParams(declaration)

      val createFunction = declaration.abstractFunctions().first { it.name.asString() == "create" }

      // Properly finalize the fake override with a dispatch receiver scoped to this function
      createFunction.finalizeFakeOverride(declaration.thisReceiver!!)
      createFunction.modality = Modality.FINAL
      createFunction.body = generateCreateFunctionBody(createFunction, screenClass, fieldsByName)
    }
    return super.visitClass(declaration)
  }

  private fun generateNestedFactoryShells(targetClass: IrClass) {
    // Recurse through every class because assisted factories are often nested inside an
    // unannotated Presenter/Ui class. Only annotated classes get a generated CircuitFactory.
    if (targetClass.shouldGenerateNestedFactory()) {
      val target = targetClass.toCircuitFactoryTarget()
      if (target != null) {
        val factoryClass =
          createFactoryClass(
            parent = targetClass,
            name = CircuitNames.Factory,
            target = target,
            origin =
              IrDeclarationOrigin.GeneratedByPlugin(
                CircuitOrigins.FactoryClass(target.factoryType)
              ),
          )
        targetClass.addChild(factoryClass)
        generatedTargets[factoryClass] = target
      }
    }

    for (nestedClass in targetClass.declarations.filterIsInstance<IrClass>().toList()) {
      generateNestedFactoryShells(nestedClass)
    }
  }

  private fun IrClass.shouldGenerateNestedFactory(): Boolean {
    return !isExpect &&
      annotationsCompat().any(::isCircuitInjectAnnotation) &&
      !hasFactoryClass(CircuitNames.Factory)
  }

  private fun IrClass.hasFactoryClass(name: Name): Boolean {
    return declarations.filterIsInstance<IrClass>().any { it.name == name }
  }

  private fun generateTopLevelFunctionFactoryShell(function: IrSimpleFunction) {
    if (!function.isTopLevelDeclaration || function.isExpect) return
    if (function.annotationsCompat().none(::isCircuitInjectAnnotation)) return

    val target = function.toCircuitFactoryTarget() ?: return
    val file = function.file
    if (file.hasTopLevelFactoryClass(target.factoryClassId)) return

    val factoryClass =
      createFactoryClass(
        parent = file,
        name = target.factoryClassId.shortClassName,
        target = target,
        origin =
          IrDeclarationOrigin.GeneratedByPlugin(CircuitOrigins.FactoryClass(target.factoryType)),
      )
    file.addChild(factoryClass)
    generatedTargets[factoryClass] = target
  }

  private fun IrFile.hasTopLevelFactoryClass(classId: ClassId): Boolean {
    return declarations.filterIsInstance<IrClass>().any { it.classIdOrFail == classId }
  }

  private fun createFactoryClass(
    parent: IrDeclarationParent,
    name: Name,
    target: CircuitIrFactoryTarget,
    origin: IrDeclarationOrigin,
  ): IrClass {
    /*
     * Keep this shell aligned with CircuitFirExtension.generateFactoryClass():
     * - @Inject lets Metro generate the factory's own provider
     * - @ContributesIntoSet makes the factory a graph contribution
     * - Deprecated(HIDDEN) keeps the class out of normal source use
     * - metadata-visible registration lets downstream modules see the generated class
     */
    return pluginContext.irFactory
      .buildClass {
        this.name = name
        this.origin = origin
        kind = ClassKind.CLASS
        visibility = DescriptorVisibilities.PUBLIC
        modality = Modality.FINAL
      }
      .apply {
        this.parent = parent
        createThisReceiverParameter()
        superTypes += target.factoryType!!.factoryClassId.type()
        addFactoryAnnotations(target)
        markAsDeprecatedHidden()
        addConstructor {
          this.origin = CircuitOrigins.IrFactoryConstructor
          isPrimary = true
          visibility = DescriptorVisibilities.PUBLIC
        }
          .apply constructor@{
            for (param in target.constructorParams) {
              addValueParameter(param.name, param.type).apply {
                param.qualifier?.let { addAnnotationCompat(it) }
              }
            }
            body = context(pluginContext) { this@constructor.generateDefaultConstructorBody() }
            metadataDeclarationRegistrarCompat.registerConstructorAsMetadataVisible(this)
          }
        addFakeOverrides(irTypeSystemContext)
        metadataDeclarationRegistrarCompat.registerClassAsMetadataVisible(this)
      }
  }

  /**
   * Creates the annotations Metro and downstream modules depend on to treat the Circuit factory as
   * an ordinary contributed binding. These are added directly to the generated class so both
   * metadata and the later Metro IR contribution pass see the same information.
   */
  private fun IrClass.addFactoryAnnotations(target: CircuitIrFactoryTarget) {
    addAnnotationCompat(context(pluginContext) { buildAnnotation(symbol, injectAnnotationCtor) })
    addAnnotationCompat(
      context(pluginContext) {
        buildAnnotation(symbol, contributesIntoSetAnnotationCtor) { annotation ->
          annotation.arguments[0] = kClassReference(target.scopeClass!!)
        }
      }
    )
    target.qualifier?.let { addAnnotationCompat(it) }
    target.originClassId?.let { originClassId ->
      addAnnotationCompat(
        context(pluginContext) {
          buildAnnotation(symbol, originAnnotationCtor) { annotation ->
            annotation.arguments[0] =
              kClassReference(
                with(compatContext) {
                  pluginContext.finderFor(this@addFactoryAnnotations).findClass(originClassId)
                } ?: error("Could not find Circuit origin class $originClassId")
              )
          }
        }
      )
    }
  }

  /**
   * Converts an annotated Presenter/Ui class, or an assisted factory nested under one, into the
   * information needed to generate `Target.CircuitFactory`.
   *
   * The constructor parameter list is the DI-facing constructor for the generated factory, not the
   * target class constructor. Circuit-supplied parameters are excluded because `create()` receives
   * them at runtime.
   */
  private fun IrClass.toCircuitFactoryTarget(): CircuitIrFactoryTarget? {
    val annotation = annotationsCompat().firstOrNull(::isCircuitInjectAnnotation) ?: return null
    val (screenType, scopeType) = annotation.extractCircuitInjectArgs() ?: return null
    val factoryClassId = classIdOrFail.createNestedClassId(CircuitNames.Factory)
    val factoryType = determineFactoryTypeForTarget(this) ?: return null
    val constructorParams =
      if (isAnnotatedWithAny(assistedFactoryAnnotations)) {
        // For assisted factories, Metro injects the assisted factory itself. The create() body will
        // delegate to that field instead of directly constructing the target Presenter/Ui.
        listOf(CircuitIrConstructorParam(CircuitNames.factoryField, defaultType))
      } else {
        val constructor =
          findInjectableConstructor(
            onlyUsePrimaryConstructor = true,
            injectAnnotations = injectAnnotations,
          )
        constructor
          ?.regularParameters
          .orEmpty()
          // Match legacy behavior: classes with no injectable constructor still get a factory
          // shell, but it has no DI parameters and the checker owns reporting invalid uses.
          .filterNot { it.isCircuitProvidedParam(factoryType) }
          .map { param ->
            CircuitIrConstructorParam(
              name = param.name,
              type = injectableParamType(param.type),
              qualifier = param.qualifierAnnotation(),
            )
          }
      }
    return CircuitIrFactoryTarget(
      originClassId = classIdOrFail,
      factoryClassId = factoryClassId,
      screenType = screenType.owner.classIdOrFail,
      scopeClassId = scopeType.owner.classIdOrFail,
      scopeClass = scopeType,
      factoryType = factoryType,
      instantiationType = InstantiationType.CLASS,
      constructorParams = constructorParams,
      qualifier = qualifierAnnotation(),
    )
  }

  /**
   * Converts a top-level `@CircuitInject` function into its generated `<FunctionName>Factory`.
   *
   * Unlike class targets, function targets have no stable origin class. We keep the original IR
   * function symbol so the body generator can call it directly after the shell is created.
   */
  private fun IrSimpleFunction.toCircuitFactoryTarget(): CircuitIrFactoryTarget? {
    val annotation = annotationsCompat().firstOrNull(::isCircuitInjectAnnotation) ?: return null
    val (screenType, scopeType) = annotation.extractCircuitInjectArgs() ?: return null
    val factoryType =
      if (returnType == pluginContext.irBuiltIns.unitType) FactoryType.UI else FactoryType.PRESENTER
    val factoryClassId =
      ClassId(file.packageFqName, Name.identifier("${name.asString().capitalizeUS()}Factory"))
    val constructorParams =
      regularParameters
        .filterNot { it.isCircuitProvidedParam(factoryType) }
        .map { param ->
          CircuitIrConstructorParam(
            name = param.name,
            type = injectableParamType(param.type),
            qualifier = param.qualifierAnnotation(),
          )
        }
    return CircuitIrFactoryTarget(
      originClassId = null,
      factoryClassId = factoryClassId,
      screenType = screenType.owner.classIdOrFail,
      scopeClassId = scopeType.owner.classIdOrFail,
      scopeClass = scopeType,
      factoryType = factoryType,
      instantiationType = InstantiationType.FUNCTION,
      originalFunctionSymbol = symbol,
      constructorParams = constructorParams,
      qualifier = qualifierAnnotation(),
    )
  }

  /**
   * Finds whether a class target should produce a Presenter.Factory or Ui.Factory. Assisted factory
   * annotations live on the nested factory interface, while the Presenter/Ui supertype usually
   * lives on the containing class, so this checks both places.
   */
  private fun determineFactoryTypeForTarget(targetClass: IrClass): FactoryType? {
    bfsForFactoryType(targetClass)?.let {
      return it
    }
    return (targetClass.parent as? IrClass)?.let(::bfsForFactoryType)
  }

  private fun bfsForFactoryType(root: IrClass): FactoryType? {
    val queue = ArrayDeque<IrClass>()
    val seen = mutableSetOf<ClassId>()
    queue.add(root)
    while (queue.isNotEmpty()) {
      val clazz = queue.removeFirst()
      val classId = clazz.classId ?: continue
      if (!seen.add(classId)) continue
      for (supertype in clazz.superTypes) {
        val superClass = supertype.classOrNull?.owner ?: continue
        when (superClass.classId) {
          FactoryType.PRESENTER.classId -> return FactoryType.PRESENTER
          FactoryType.UI.classId -> return FactoryType.UI
          else -> queue.add(superClass)
        }
      }
    }
    return null
  }

  /** Reads the positional or named `screen`/`scope` class references from `@CircuitInject`. */
  private fun IrConstructorCall.extractCircuitInjectArgs(): Pair<IrClassSymbol, IrClassSymbol>? {
    val screenArg =
      arguments[0] as? IrClassReference
        ?: argument(CircuitNames.screen) as? IrClassReference
        ?: return null
    val scopeArg =
      arguments[1] as? IrClassReference
        ?: argument(Symbols.Names.scope) as? IrClassReference
        ?: return null
    val screenSymbol = screenArg.symbol as? IrClassSymbol ?: return null
    val scopeSymbol = scopeArg.symbol as? IrClassSymbol ?: return null
    return screenSymbol to scopeSymbol
  }

  private fun IrConstructorCall.argument(name: Name): IrExpression? {
    val parameter = symbol.owner.parameters.firstOrNull { it.name == name } ?: return null
    return arguments[parameter.indexInParameters]
  }

  private fun IrValueParameter.isCircuitProvidedParam(factoryType: FactoryType): Boolean {
    val paramClass = type.classOrNull?.owner ?: return false
    val circuitType = classifyCircuitType(paramClass) ?: return false
    return when (circuitType) {
      CircuitProvidedType.SCREEN -> true
      CircuitProvidedType.NAVIGATOR -> factoryType == FactoryType.PRESENTER
      CircuitProvidedType.MODIFIER -> factoryType == FactoryType.UI
      CircuitProvidedType.UI_STATE -> factoryType == FactoryType.UI
    }
  }

  /**
   * Mirrors the FIR constructor signature rule: DI parameters are stored as Provider<T> fields
   * unless the target already expects a provider-like wrapper.
   */
  private fun injectableParamType(paramType: IrType): IrType {
    val paramClassId = paramType.classOrNull?.owner?.classId
    val isAlreadyWrapped =
      paramClassId != null &&
        (paramClassId in Symbols.ClassIds.commonMetroProviders ||
          paramClassId == Symbols.ClassIds.Lazy ||
          paramClassId == Symbols.ClassIds.function0)
    return if (isAlreadyWrapped) {
      paramType
    } else {
      builtinsFinder.findClass(Symbols.ClassIds.metroProvider)!!.typeWith(paramType)
    }
  }

  private fun IrAnnotationContainer.qualifierAnnotation(): IrConstructorCall? {
    return annotationsCompat().firstOrNull { annotation ->
      annotation.symbol.owner.parentAsClass.isAnnotatedWithAny(qualifierAnnotations)
    }
  }

  private fun isCircuitInjectAnnotation(annotation: IrConstructorCall): Boolean {
    return annotation.symbol.owner.parentAsClass.classId == CircuitClassIds.CircuitInject
  }

  private fun ClassId.type(): IrType {
    return builtinsFinder.findClass(this)?.defaultType ?: error("Could not find $this")
  }

  /** Adds the same `Deprecated(HIDDEN)` marker used by the FIR-generated hidden classes. */
  private fun IrClass.markAsDeprecatedHidden() {
    addAnnotationCompat(
      context(pluginContext) {
        buildAnnotation(symbol, deprecatedAnnotationCtor) { annotation ->
          annotation.arguments[0] =
            irString("This synthesized declaration should not be used directly")
          annotation.arguments[2] =
            IrGetEnumValueImpl(
              SYNTHETIC_OFFSET,
              SYNTHETIC_OFFSET,
              deprecationLevel.defaultType,
              hiddenDeprecationLevel,
            )
        }
      }
    )
  }

  /**
   * Produces the unified target model for the body generator. New IR-generated factories read from
   * [generatedTargets]; legacy FIR-generated factories bridge their FIR declaration data here.
   */
  private fun IrClass.circuitFactoryTargetData(): CircuitIrFactoryTarget {
    generatedTargets[this]?.let {
      return it
    }
    // Non-IR-only mode still gets factory signatures from FIR. Bridge that FIR declaration data
    // into the same IR target model used by generatedTargets so the body generation stays shared.
    return circuitFactoryTargetData?.toCircuitIrFactoryTarget()
      ?: error("Circuit factory class ${classId} is missing target data.")
  }

  /** Adapts the legacy FIR target object to the smaller IR-only data needed after FIR2IR. */
  private fun CircuitFactoryTarget.toCircuitIrFactoryTarget(): CircuitIrFactoryTarget {
    return CircuitIrFactoryTarget(
      originClassId = originClassId,
      factoryClassId = factoryClassId,
      screenType = screenType,
      scopeClassId = scopeClassId,
      scopeClass = null,
      factoryType = factoryType,
      instantiationType = instantiationType,
      originalFunctionFirSymbol = originalFunctionSymbol,
      constructorParams = emptyList(),
      qualifier = null,
    )
  }

  /**
   * Resolves the function a top-level generated factory should invoke. IR-generated factories keep
   * the symbol directly; legacy factories keep a FIR symbol in declaration data, so this maps that
   * symbol back to the corresponding IR function.
   */
  private fun originalFunctionSymbol(
    createFunction: IrSimpleFunction,
    @Suppress("UNUSED_PARAMETER") factoryClass: IrClass,
    circuitTargetInfo: CircuitIrFactoryTarget,
  ): IrSimpleFunctionSymbol {
    circuitTargetInfo.originalFunctionSymbol?.let {
      return it
    }
    val firFunctionSymbol =
      circuitTargetInfo.originalFunctionFirSymbol
        ?: error("Function-based factory missing original function symbol")
    return with(compatContext) {
        pluginContext.finderFor(createFunction).findFunctions(firFunctionSymbol.callableId)
      }
      .first { irSymbol ->
        (irSymbol.owner.metadata as? FirMetadataSource.Function)?.fir?.symbol == firFunctionSymbol
      }
  }

  private fun generateCreateFunctionBody(
    function: IrSimpleFunction,
    screenClass: IrClassSymbol,
    fieldsByName: Map<Name, IrField>,
  ): IrBody {
    val factoryClass = function.parentAsClass
    val factoryType = determineFactoryType(factoryClass)
    val screenParam = function.regularParameters.first { it.name == CircuitNames.screen }
    val targetInfo = TargetInfo(screenClass)

    val returnType =
      when (factoryType) {
        FactoryType.UI -> symbols.ui.typeWith(pluginContext.irBuiltIns.anyNType).makeNullable()
        FactoryType.PRESENTER ->
          symbols.presenter.typeWith(pluginContext.irBuiltIns.anyNType).makeNullable()
      }

    return pluginContext.createIrBuilder(function.symbol).irBlockBody {
      +irReturn(
        irWhen(
          returnType,
          branches =
            listOf(
              irBranch(
                generateScreenMatchCondition(screenParam, targetInfo),
                generateInstantiationExpression(function, factoryClass, targetInfo, fieldsByName),
              ),
              irElseBranch(irNull()),
            ),
        )
      )
    }
  }

  private fun IrBuilderWithScope.generateScreenMatchCondition(
    screenParam: IrValueParameter,
    targetInfo: TargetInfo,
  ): IrExpression {
    val screenClassSymbol = targetInfo.screenClassSymbol

    return if (screenClassSymbol.owner.kind == ClassKind.OBJECT) {
      // For object screens, use equality check: screen == ScreenObject
      irEqeqeq(irGet(screenParam), irGetObject(screenClassSymbol))
    } else {
      // For class screens, use is check: screen is ScreenClass
      irIs(irGet(screenParam), screenClassSymbol.starProjectedType)
    }
  }

  private fun IrBuilderWithScope.generateInstantiationExpression(
    function: IrSimpleFunction,
    factoryClass: IrClass,
    targetInfo: TargetInfo,
    fieldsByName: Map<Name, IrField>,
  ): IrExpression {
    val factoryField = fieldsByName[CircuitNames.factoryField]

    val circuitTargetInfo = factoryClass.circuitFactoryTargetData()

    return when {
      factoryField != null -> {
        // factory.create(...) - call the assisted factory
        generateAssistedFactoryCall(function, factoryField, targetInfo)
      }
      circuitTargetInfo.instantiationType == InstantiationType.FUNCTION -> {
        // Function-based factory: call presenterOf{}/ui{}
        val factoryType = determineFactoryType(factoryClass)
        generateFunctionFactoryCall(
          function,
          factoryType,
          originalFunctionSymbol(function, factoryClass, circuitTargetInfo),
          fieldsByName,
        )
      }
      else -> {
        // Class-based factory: instantiate the target class directly, wiring circuit-provided
        // params from create() and injectable params from factory fields (Provider.invoke()).
        val factoryType = determineFactoryType(factoryClass)
        generateClassInstantiation(function, factoryClass, factoryType, fieldsByName)
      }
    }
  }

  /**
   * Generates instantiation for class-based factories. Constructor params are wired from two
   * sources:
   * - Circuit-provided params (Screen, Navigator, etc.) are matched by type from `create()` params
   * - Injectable params come from factory backing fields (`Provider<T>.invoke()`)
   */
  private fun IrBuilderWithScope.generateClassInstantiation(
    createFunction: IrSimpleFunction,
    factoryClass: IrClass,
    factoryType: FactoryType,
    fieldsByName: Map<Name, IrField>,
  ): IrExpression {
    val circuitTargetInfo = factoryClass.circuitFactoryTargetData()
    val targetClassId =
      circuitTargetInfo.originClassId ?: error("Class-based factory missing origin class ID")
    val targetClass =
      with(compatContext) { pluginContext.finderFor(factoryClass).findClass(targetClassId) }
        ?: error("Could not find target class: $targetClassId")
    val constructor = targetClass.constructors.first()
    val ctorParams = constructor.owner.regularParameters
    if (ctorParams.isEmpty()) {
      return irCall(constructor)
    }

    val thisReceiver = createFunction.dispatchReceiverParameter!!
    val createParamsByName = createFunction.regularParameters.associateBy { it.name }
    return irCall(constructor).apply {
      for (ctorParam in ctorParams) {
        val matchingCreateParam =
          findMatchingCircuitParam(ctorParam, createParamsByName, factoryType)
        if (matchingCreateParam != null) {
          // Circuit-provided: pass from create() param, casting if needed
          arguments[ctorParam.indexInParameters] =
            irGet(matchingCreateParam).implicitCastIfNeededTo(ctorParam.type)
        } else {
          // Injectable: read from factory field and invoke Provider
          val field = fieldsByName[ctorParam.name] ?: continue
          val fieldGet = irGetField(irGet(thisReceiver), field)
          val paramClassId = ctorParam.type.classOrNull?.owner?.classId
          val isAlreadyWrapped =
            paramClassId != null &&
              (paramClassId in Symbols.ClassIds.commonMetroProviders ||
                paramClassId == Symbols.ClassIds.Lazy ||
                paramClassId == Symbols.ClassIds.metroSuspendProvider ||
                paramClassId in function0Types)
          arguments[ctorParam.indexInParameters] =
            if (isAlreadyWrapped) {
              fieldGet
            } else {
              // Wasm requires the call's IR type match the substituted Provider<T>.invoke()
              // return type. https://github.com/ZacSweers/metro/issues/2227
              irInvoke(
                dispatchReceiver = fieldGet,
                callee = providerInvokeFunction,
                typeHint = ctorParam.type,
              )
            }
        }
      }
    }
  }

  /**
   * Finds the `create()` parameter that matches a constructor parameter by type. Returns the
   * matching create() param if the constructor param type is a circuit-provided type (Screen
   * subtype, Navigator, Modifier, CircuitUiState subtype), or null if it's an injectable param.
   */
  private fun classifyCircuitType(paramClass: IrClass): CircuitProvidedType? {
    return when (paramClass.classId) {
      CircuitClassIds.Screen -> CircuitProvidedType.SCREEN
      CircuitClassIds.Navigator -> CircuitProvidedType.NAVIGATOR
      CircuitClassIds.Modifier -> CircuitProvidedType.MODIFIER
      CircuitClassIds.CircuitUiState -> CircuitProvidedType.UI_STATE
      else -> {
        // Screen and CircuitUiState are always subtyped — single walk checking both
        for (superInterface in paramClass.allSuperInterfaces()) {
          when (superInterface.classId) {
            CircuitClassIds.Screen -> return CircuitProvidedType.SCREEN
            CircuitClassIds.CircuitUiState -> return CircuitProvidedType.UI_STATE
          }
        }
        null
      }
    }
  }

  private fun findMatchingCircuitParam(
    ctorParam: IrValueParameter,
    createParamsByName: Map<Name, IrValueParameter>,
    factoryType: FactoryType,
  ): IrValueParameter? {
    val paramClass = ctorParam.type.classOrNull?.owner ?: return null
    val type = classifyCircuitType(paramClass) ?: return null

    val name =
      when (type) {
        CircuitProvidedType.SCREEN -> CircuitNames.screen
        CircuitProvidedType.NAVIGATOR ->
          if (factoryType == FactoryType.PRESENTER) {
            CircuitNames.navigator
          } else {
            return null
          }
        CircuitProvidedType.MODIFIER ->
          if (factoryType == FactoryType.UI) {
            CircuitNames.modifier
          } else {
            return null
          }
        CircuitProvidedType.UI_STATE ->
          if (factoryType == FactoryType.UI) {
            CircuitNames.state
          } else {
            return null
          }
      }
    return createParamsByName[name]
  }

  private fun IrBuilderWithScope.generateAssistedFactoryCall(
    function: IrSimpleFunction,
    factoryField: IrField,
    @Suppress("UNUSED_PARAMETER") targetInfo: TargetInfo,
  ): IrExpression {
    val thisReceiver = function.dispatchReceiverParameter ?: return irNull()

    // Get the factory field
    val factoryGet = irGetField(irGet(thisReceiver), factoryField)

    // Find the create function on the factory
    val factoryClass = factoryField.type.classOrNull?.owner ?: return irNull()
    val createFunction =
      factoryClass.functions.find {
        it.name.asString() == "create" || it.name.asString() == "invoke"
      } ?: return irNull()

    // Build the call with assisted parameters
    return irCall(createFunction).apply {
      dispatchReceiver = factoryGet

      // Pass through screen, navigator, context as needed, using indexInParameters
      // to correctly skip the dispatch receiver slot
      for (param in createFunction.regularParameters) {
        val matchingParam = function.regularParameters.find { it.name == param.name }
        if (matchingParam != null) {
          // Wasm requires precise reference types; cast when the outer create() supplies a wider
          // type (e.g. Screen) than the assisted factory expects (e.g. CounterScreen).
          // https://github.com/ZacSweers/metro/issues/2227
          arguments[param.indexInParameters] =
            irGet(matchingParam).implicitCastIfNeededTo(param.type)
        }
      }
    }
  }

  /**
   * For function-based factories, the FIR extension generates constructor params (Provider<T>) for
   * injected dependencies but doesn't create backing fields. We add them here so the lambda body in
   * `create()` can read the values via `irGetField`.
   */
  private fun addBackingFieldsForConstructorParams(factoryClass: IrClass): Map<Name, IrField> {
    val constructor = factoryClass.constructors.firstOrNull() ?: return emptyMap()
    val constructorParams = constructor.regularParameters
    if (constructorParams.isEmpty()) return emptyMap()

    val result = mutableMapOf<Name, IrField>()
    for (param in constructorParams) {
      val field = factoryClass.addField(param.name, param.type)
      field.initializer =
        pluginContext.createIrBuilder(field.symbol).run { irExprBody(irGet(param)) }
      result[param.name] = field
    }
    return result
  }

  /**
   * Generates the instantiation expression for function-based factories by calling `presenterOf {
   * originalFunction(...) }` or `ui<State> { state, modifier -> originalFunction(...) }`.
   *
   * The lambda is annotated with `@Composable` so the Compose compiler transforms it.
   */
  private fun IrBuilderWithScope.generateFunctionFactoryCall(
    createFunction: IrSimpleFunction,
    factoryType: FactoryType,
    originalFunctionSymbol: IrSimpleFunctionSymbol,
    fieldsByName: Map<Name, IrField>,
  ): IrExpression {
    val originalFunction = originalFunctionSymbol.owner
    // Build parameter mapping from create() params
    val availableValueParams = buildMap {
      for (param in createFunction.regularParameters) {
        put(param.name, param)
      }
    }

    // Resolve injected dependencies from factory fields. For plain Provider<T> fields,
    // extract the value via .invoke() outside the composable lambda to avoid recomputation
    // on every recomposition. For types already wrapped in Provider/Lazy/Function (which the
    // original function expects as-is), pass the field value directly to the lambda.
    return irBlock {
      val resolvedLocals = mutableMapOf<Name, IrValueDeclaration>()
      for (param in originalFunction.regularParameters) {
        if (param.name in availableValueParams) continue
        val field = fieldsByName[param.name] ?: continue
        val fieldGet = irGetField(irGet(createFunction.dispatchReceiverParameter!!), field)

        // Check if the original function param type is already Provider/Lazy/Function.
        // If so, the field type matches and we pass it through without invoking.
        val paramType = param.type
        val paramClassId = paramType.classOrNull?.owner?.classId
        val isAlreadyWrapped =
          paramClassId != null &&
            (paramClassId in Symbols.ClassIds.commonMetroProviders ||
              paramClassId == Symbols.ClassIds.Lazy ||
              paramClassId == Symbols.ClassIds.metroSuspendProvider ||
              paramClassId in function0Types)

        val localVar =
          if (isAlreadyWrapped) {
            // Pass through as-is (the field type already matches the param type)
            irTemporary(fieldGet, nameHint = param.name.asString())
          } else {
            // Provider<T> field, extract via .invoke() once. Wasm requires the call's IR type
            // match the substituted return type. https://github.com/ZacSweers/metro/issues/2227
            val invokedValue =
              irInvoke(
                dispatchReceiver = fieldGet,
                callee = providerInvokeFunction,
                typeHint = paramType,
              )
            irTemporary(invokedValue, nameHint = param.name.asString())
          }
        resolvedLocals[param.name] = localVar
      }

      // Merge all available params: create() params + resolved locals
      val allAvailableParams = buildMap {
        putAll(availableValueParams)
        putAll(resolvedLocals)
      }

      val factoryCall =
        when (factoryType) {
          FactoryType.PRESENTER -> {
            val stateType = originalFunction.returnType
            irInvoke(
              callee = symbols.presenterOfFun,
              typeArgs = listOf(stateType),
              args =
                listOf(
                  buildComposableLambda(
                    createFunction = createFunction,
                    originalFunction = originalFunction,
                    originalFunctionSymbol = originalFunctionSymbol,
                    returnType = stateType,
                    lambdaParamTypes = emptyList(),
                    capturedParams = allAvailableParams,
                  )
                ),
            )
          }
          FactoryType.UI -> {
            val stateType =
              originalFunction.regularParameters
                .firstOrNull { param ->
                  param.type.classOrNull?.owner?.superTypes?.any {
                    it.classOrNull?.owner?.name?.asString() == "CircuitUiState"
                  } == true
                }
                ?.type ?: pluginContext.irBuiltIns.anyNType

            irInvoke(
              callee = symbols.uiFun,
              typeArgs = listOf(stateType),
              args =
                listOf(
                  buildComposableLambda(
                    createFunction = createFunction,
                    originalFunction = originalFunction,
                    originalFunctionSymbol = originalFunctionSymbol,
                    returnType = pluginContext.irBuiltIns.unitType,
                    lambdaParamTypes =
                      listOf(
                        CircuitNames.state to stateType,
                        CircuitNames.modifier to symbols.modifier.defaultType,
                      ),
                    capturedParams = allAvailableParams,
                  )
                ),
            )
          }
        }
      +factoryCall
    }
  }

  /**
   * Builds a `@Composable` lambda that calls [originalFunction] with params matched by name from:
   * 1. The lambda's own params (e.g., state, modifier for UI)
   * 2. [capturedParams] — pre-resolved values from the `create()` scope (create() params + provider
   *    locals extracted before the lambda)
   */
  private fun buildComposableLambda(
    createFunction: IrSimpleFunction,
    originalFunction: IrSimpleFunction,
    originalFunctionSymbol: IrSimpleFunctionSymbol,
    returnType: IrType,
    lambdaParamTypes: List<Pair<Name, IrType>>,
    capturedParams: Map<Name, IrValueDeclaration>,
  ): IrFunctionExpression {
    // TODO irLambda
    val lambda =
      pluginContext.irFactory
        .buildFun {
          startOffset = SYNTHETIC_OFFSET
          endOffset = SYNTHETIC_OFFSET
          origin = Origins.FirstParty.LOCAL_FUNCTION_FOR_LAMBDA
          name = Name.special("<anonymous>")
          visibility = DescriptorVisibilities.LOCAL
          this.returnType = returnType
        }
        .apply {
          parent = createFunction

          // @Composable annotation so Compose compiler transforms this lambda
          addAnnotationCompat(
            pluginContext.createIrBuilder(symbol).run {
              irAnnotationCompat(composableAnnotationCtor, typeArguments = emptyList())
            }
          )

          for ((paramName, paramType) in lambdaParamTypes) {
            addValueParameter(paramName.asString(), paramType)
          }

          // Merge all available params: captured from create() scope + lambda's own params
          val allParams = buildMap {
            putAll(capturedParams)
            for (param in regularParameters) {
              put(param.name, param)
            }
          }

          body =
            pluginContext.createIrBuilder(symbol).irBlockBody {
              val call =
                irCall(originalFunctionSymbol).apply {
                  var argIndex = 0
                  for (param in originalFunction.regularParameters) {
                    // Wasm requires precise reference types; cast when the captured value
                    // (e.g. Screen) is wider than the original function param
                    // (e.g. CounterScreen). https://github.com/ZacSweers/metro/issues/2227
                    arguments[argIndex++] =
                      allParams[param.name]?.let { irGet(it).implicitCastIfNeededTo(param.type) }
                  }
                }
              if (returnType == pluginContext.irBuiltIns.unitType) {
                +call
              } else {
                +irReturn(call)
              }
            }
        }

    return IrFunctionExpressionImpl(
      startOffset = SYNTHETIC_OFFSET,
      endOffset = SYNTHETIC_OFFSET,
      type =
        pluginContext.irBuiltIns
          .functionN(lambdaParamTypes.size)
          .typeWith(*(lambdaParamTypes.map { it.second } + returnType).toTypedArray()),
      origin = IrStatementOrigin.LAMBDA,
      function = lambda,
    )
  }

  private fun determineFactoryType(factoryClass: IrClass): FactoryType {
    for (supertype in factoryClass.allSuperInterfaces()) {
      return when (supertype.classId) {
        FactoryType.UI.factoryClassId -> FactoryType.UI
        FactoryType.PRESENTER.factoryClassId -> FactoryType.PRESENTER
        else -> continue
      }
    }
    error("Could not determine factory type for ${factoryClass.classId}")
  }

  @JvmInline
  private value class TargetInfo(val screenClassSymbol: IrClassSymbol) {
    val screenIsObject: Boolean
      get() = screenClassSymbol.owner.kind == ClassKind.OBJECT
  }
}

private data class CircuitIrConstructorParam(
  val name: Name,
  val type: IrType,
  val qualifier: IrConstructorCall? = null,
)

private data class CircuitIrFactoryTarget(
  val originClassId: ClassId?,
  val factoryClassId: ClassId,
  val screenType: ClassId,
  val scopeClassId: ClassId,
  val scopeClass: IrClassSymbol?,
  val factoryType: FactoryType?,
  val instantiationType: InstantiationType,
  val originalFunctionSymbol: IrSimpleFunctionSymbol? = null,
  val originalFunctionFirSymbol: FirFunctionSymbol<*>? = null,
  val constructorParams: List<CircuitIrConstructorParam>,
  val qualifier: IrConstructorCall?,
)

private val CONTRIBUTES_INTO_SET_CLASS_ID =
  ClassId(Symbols.FqNames.metroRuntimePackage, Name.identifier("ContributesIntoSet"))
