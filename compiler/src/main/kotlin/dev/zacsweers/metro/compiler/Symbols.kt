// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import dev.zacsweers.metro.compiler.Symbols.FqNames.kotlinCollectionsPackageFqn
import dev.zacsweers.metro.compiler.Symbols.FqNames.metroHintsPackage
import dev.zacsweers.metro.compiler.Symbols.StringNames.CALLABLE_METADATA
import dev.zacsweers.metro.compiler.Symbols.StringNames.METRO_RUNTIME_INTERNAL_PACKAGE
import dev.zacsweers.metro.compiler.Symbols.StringNames.METRO_RUNTIME_PACKAGE
import dev.zacsweers.metro.compiler.ir.IrAnnotation
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.getAllSuperTypes
import dev.zacsweers.metro.compiler.ir.irInvoke
import dev.zacsweers.metro.compiler.ir.rawTypeOrNull
import dev.zacsweers.metro.compiler.ir.requireSimpleFunction
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.declarations.IrEnumEntry
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrPackageFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.createEmptyExternalPackageFragment
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.dumpKotlinLike
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.getPropertyGetter
import org.jetbrains.kotlin.ir.util.hasShape
import org.jetbrains.kotlin.ir.util.kotlinPackageFqn
import org.jetbrains.kotlin.ir.util.nestedClasses
import org.jetbrains.kotlin.ir.util.nonDispatchParameters
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.StandardClassIds

internal class Symbols(
  private val moduleFragment: IrModuleFragment,
  val pluginContext: IrPluginContext,
  val classIds: dev.zacsweers.metro.compiler.ClassIds,
  val options: MetroOptions,
) {

  object StringNames {
    const val ADDITIONAL_SCOPES = "additionalScopes"
    const val ASSISTED = "Assisted"
    const val AS_DAGGER_INTERNAL_PROVIDER = "asDaggerInternalProvider"
    const val AS_DAGGER_MEMBERS_INJECTOR = "asDaggerMembersInjector"
    const val AS_JAKARTA_PROVIDER = "asJakartaProvider"
    const val AS_JAVAX_PROVIDER = "asJavaxProvider"
    const val AS_METRO_MEMBERS_INJECTOR = "asMetroMembersInjector"
    const val AS_METRO_PROVIDER = "asMetroProvider"
    const val BINDING = "binding"
    const val BOUND_TYPE = "boundType"
    const val COMPOSABLE = "Composable"
    const val CONTRIBUTED = "contributed"
    const val CREATE = "create"
    const val CREATE_FACTORY_PROVIDER = "createFactoryProvider"
    const val CREATE_GRAPH = "createGraph"
    const val CREATE_GRAPH_FACTORY = "createGraphFactory"
    const val CREATE_DYNAMIC_GRAPH = "createDynamicGraph"
    const val CREATE_DYNAMIC_GRAPH_FACTORY = "createDynamicGraphFactory"
    const val ELEMENTS_INTO_SET = "ElementsIntoSet"
    const val ERROR = "error"
    const val EXCLUDES = "excludes"
    const val EXTENDS = "Extends"
    const val FACTORY = "factory"
    const val GET = "get"
    const val IGNORE_QUALIFIER = "ignoreQualifier"
    const val INCLUDES = "Includes"
    const val INJECT = "Inject"
    const val INJECTED_FUNCTION_CLASS = "InjectedFunctionClass"
    const val INJECT_MEMBERS = "injectMembers"
    const val INTO_MAP = "IntoMap"
    const val INTO_SET = "IntoSet"
    const val IMPL = "Impl"
    const val INVOKE = "invoke"
    const val METRO_CONTRIBUTION = "MetroContribution"
    const val METRO_CONTRIBUTION_NAME_PREFIX = "MetroContribution"
    const val METRO_FACTORY = "MetroFactory"
    const val METRO_HINTS_PACKAGE = "metro.hints"
    const val METRO_RUNTIME_INTERNAL_PACKAGE = "dev.zacsweers.metro.internal"
    const val METRO_RUNTIME_PACKAGE = "dev.zacsweers.metro"
    const val MIRROR_FUNCTION = "mirrorFunction"
    const val NEW_INSTANCE = "newInstance"
    const val NON_RESTARTABLE_COMPOSABLE = "NonRestartableComposable"
    const val PROVIDER = "provider"
    const val PROVIDES = "Provides"
    const val CALLABLE_METADATA = "CallableMetadata"
    const val RANK = "rank"
    const val REPLACES = "replaces"
    const val SCOPE = "scope"
    const val SINGLE_IN = "SingleIn"
    const val STABLE = "Stable"
  }

  object FqNames {
    val composeRuntime = FqName("androidx.compose.runtime")
    val javaUtil = FqName("java.util")
    val kotlinCollectionsPackageFqn = StandardClassIds.BASE_COLLECTIONS_PACKAGE
    val metroHintsPackage = FqName(StringNames.METRO_HINTS_PACKAGE)
    val metroRuntimeInternalPackage = FqName(METRO_RUNTIME_INTERNAL_PACKAGE)
    val metroRuntimePackage = FqName(METRO_RUNTIME_PACKAGE)
    val GraphFactoryInvokeFunctionMarkerClass =
      metroRuntimeInternalPackage.child("GraphFactoryInvokeFunctionMarker".asName())
    val CallableMetadataClass = metroRuntimeInternalPackage.child(CALLABLE_METADATA.asName())

    fun scopeHint(scopeClassId: ClassId): FqName {
      return CallableIds.scopeHint(scopeClassId).asSingleFqName()
    }
  }

  object CallableIds {
    fun scopeHint(scopeClassId: ClassId): CallableId {
      return CallableId(metroHintsPackage, scopeClassId.joinSimpleNames().shortClassName)
    }

    fun scopedInjectClassHint(scopeAnnotation: IrAnnotation): CallableId {
      return CallableId(
        metroHintsPackage,
        ("scopedInjectClassHintFor" + scopeAnnotation.hashCode()).asName(),
      )
    }
  }

  object ClassIds {
    val Composable = ClassId(FqNames.composeRuntime, StringNames.COMPOSABLE.asName())
    val GraphFactoryInvokeFunctionMarkerClass =
      ClassId(FqNames.metroRuntimeInternalPackage, "GraphFactoryInvokeFunctionMarker".asName())
    val JavaOptional = ClassId(FqNames.javaUtil, Names.Optional)
    val Lazy = StandardClassIds.byName("Lazy")
    val MembersInjector = ClassId(FqNames.metroRuntimePackage, Names.membersInjector)
    val MultibindingElement =
      ClassId(FqNames.metroRuntimeInternalPackage, "MultibindingElement".asName())
    val NonRestartableComposable =
      ClassId(FqNames.composeRuntime, StringNames.NON_RESTARTABLE_COMPOSABLE.asName())
    val CallableMetadata = ClassId(FqNames.metroRuntimeInternalPackage, CALLABLE_METADATA.asName())
    val Stable = ClassId(FqNames.composeRuntime, StringNames.STABLE.asName())
    val graphExtension = ClassId(FqNames.metroRuntimePackage, "GraphExtension".asName())
    val graphExtensionFactory = graphExtension.createNestedClassId(Names.FactoryClass)
    val metroAssisted = ClassId(FqNames.metroRuntimePackage, StringNames.ASSISTED.asName())
    val metroAssistedMarker =
      ClassId(FqNames.metroRuntimeInternalPackage, "AssistedMarker".asName())
    val metroBinds = ClassId(FqNames.metroRuntimePackage, Names.Binds)
    val metroContribution =
      ClassId(FqNames.metroRuntimeInternalPackage, StringNames.METRO_CONTRIBUTION.asName())
    val metroFactory = ClassId(FqNames.metroRuntimeInternalPackage, Names.FactoryClass)
    val metroIncludes = ClassId(FqNames.metroRuntimePackage, StringNames.INCLUDES.asName())
    val metroInject = ClassId(FqNames.metroRuntimePackage, StringNames.INJECT.asName())
    val metroInjectedFunctionClass =
      ClassId(FqNames.metroRuntimeInternalPackage, StringNames.INJECTED_FUNCTION_CLASS.asName())
    val metroIntoMap = ClassId(FqNames.metroRuntimePackage, StringNames.INTO_MAP.asName())
    val metroIntoSet = ClassId(FqNames.metroRuntimePackage, StringNames.INTO_SET.asName())
    val metroImplMarker = ClassId(FqNames.metroRuntimeInternalPackage, "MetroImplMarker".asName())
    val metroOrigin = ClassId(FqNames.metroRuntimePackage, "Origin".asName())
    val metroProvider = ClassId(FqNames.metroRuntimePackage, Names.ProviderClass)
    val metroProvides = ClassId(FqNames.metroRuntimePackage, StringNames.PROVIDES.asName())
    val metroSingleIn = ClassId(FqNames.metroRuntimePackage, StringNames.SINGLE_IN.asName())
    val metroInstanceFactory =
      ClassId(FqNames.metroRuntimeInternalPackage, "InstanceFactory".asName())

    val commonMetroProviders by lazy { setOf(metroProvider, metroFactory, metroInstanceFactory) }
  }

  object Names {
    val Binds = "Binds".asName()
    val BindsMirrorClass = "BindsMirror".asName()
    val Container = "Container".asName()
    val FactoryClass = "Factory".asName()
    val MetroContributionNamePrefix = StringNames.METRO_CONTRIBUTION_NAME_PREFIX.asName()
    val MetroFactory = StringNames.METRO_FACTORY.asName()
    val Impl = StringNames.IMPL.asName()
    val MetroMembersInjector = "MetroMembersInjector".asName()
    val Optional = "Optional".asName()
    val ProviderClass = "Provider".asName()
    val Provides = StringNames.PROVIDES.asName()
    val additionalScopes = StringNames.ADDITIONAL_SCOPES.asName()
    val asContribution = "asContribution".asName()
    val binding = StringNames.BINDING.asName()
    val bindingContainers = "bindingContainers".asName()
    val builder = "builder".asName()
    val boundType = StringNames.BOUND_TYPE.asName()
    val contributed = StringNames.CONTRIBUTED.asName()
    val create = StringNames.CREATE.asName()
    val createFactoryProvider = StringNames.CREATE_FACTORY_PROVIDER.asName()
    val createGraph = StringNames.CREATE_GRAPH.asName()
    val createGraphFactory = StringNames.CREATE_GRAPH_FACTORY.asName()
    val createDynamicGraph = StringNames.CREATE_DYNAMIC_GRAPH.asName()
    val createDynamicGraphFactory = StringNames.CREATE_DYNAMIC_GRAPH_FACTORY.asName()
    val delegateFactory = "delegateFactory".asName()
    val error = StringNames.ERROR.asName()
    val excludes = StringNames.EXCLUDES.asName()
    val factory = StringNames.FACTORY.asName()
    val ignoreQualifier = StringNames.IGNORE_QUALIFIER.asName()
    val includes = "includes".asName()
    val injectMembers = StringNames.INJECT_MEMBERS.asName()
    val instance = "instance".asName()
    val invoke = StringNames.INVOKE.asName()
    val membersInjector = "MembersInjector".asName()
    val mirrorFunction = StringNames.MIRROR_FUNCTION.asName()
    val modules = "modules".asName()
    val newInstance = StringNames.NEW_INSTANCE.asName()
    val provider = StringNames.PROVIDER.asName()
    val rank = StringNames.RANK.asName()
    val receiver = "receiver".asName()
    val replaces = StringNames.REPLACES.asName()
    val subcomponents = "subcomponents".asName()
    val scope = StringNames.SCOPE.asName()
    val unwrapValue = "unwrapValue".asName()
  }

  private val metroRuntime: IrPackageFragment by lazy {
    moduleFragment.createPackage(METRO_RUNTIME_PACKAGE)
  }
  private val metroRuntimeInternal: IrPackageFragment by lazy {
    moduleFragment.createPackage(METRO_RUNTIME_INTERNAL_PACKAGE)
  }
  private val stdlib: IrPackageFragment by lazy {
    moduleFragment.createPackage(kotlinPackageFqn.asString())
  }
  private val stdlibCollections: IrPackageFragment by lazy {
    moduleFragment.createPackage(kotlinCollectionsPackageFqn.asString())
  }

  val metroProviderSymbols = MetroProviderSymbols(metroRuntimeInternal, pluginContext)
  val daggerSymbols by lazy {
    check(options.enableDaggerRuntimeInterop)
    DaggerSymbols(moduleFragment, pluginContext)
  }

  fun providerSymbolsFor(key: IrContextualTypeKey): ProviderSymbols {
    return key.rawType?.let(::providerSymbolsFor) ?: metroProviderSymbols
  }

  fun providerSymbolsFor(type: IrType?): ProviderSymbols {
    val useDaggerInterop =
      options.enableDaggerRuntimeInterop &&
        run {
          val classId = type?.classOrNull?.owner?.classId
          classId in daggerSymbols.providerPrimitives ||
            classId == DaggerSymbols.ClassIds.DAGGER_LAZY_CLASS_ID
        }
    return if (useDaggerInterop) daggerSymbols else metroProviderSymbols
  }

  val asContribution: IrSimpleFunctionSymbol by lazy {
    pluginContext
      .referenceFunctions(CallableId(metroRuntime.packageFqName, Names.asContribution))
      .single()
  }

  val metroCreateGraph: IrSimpleFunctionSymbol by lazy {
    pluginContext
      .referenceFunctions(CallableId(metroRuntime.packageFqName, "createGraph".asName()))
      .first()
  }

  val metroCreateGraphFactory: IrSimpleFunctionSymbol by lazy {
    pluginContext
      .referenceFunctions(CallableId(metroRuntime.packageFqName, "createGraphFactory".asName()))
      .first()
  }

  val metroCreateDynamicGraph: IrSimpleFunctionSymbol by lazy {
    pluginContext
      .referenceFunctions(CallableId(metroRuntime.packageFqName, "createDynamicGraph".asName()))
      .first()
  }

  val metroCreateDynamicGraphFactory: IrSimpleFunctionSymbol by lazy {
    pluginContext
      .referenceFunctions(
        CallableId(metroRuntime.packageFqName, "createDynamicGraphFactory".asName())
      )
      .first()
  }

  private val doubleCheck: IrClassSymbol by lazy {
    pluginContext.referenceClass(
      ClassId(metroRuntimeInternal.packageFqName, "DoubleCheck".asName())
    )!!
  }
  val doubleCheckCompanionObject by lazy { doubleCheck.owner.companionObject()!!.symbol }
  val doubleCheckProvider by lazy { doubleCheckCompanionObject.requireSimpleFunction("provider") }

  private val providerOfLazy: IrClassSymbol by lazy {
    pluginContext.referenceClass(
      ClassId(metroRuntimeInternal.packageFqName, "ProviderOfLazy".asName())
    )!!
  }
  val providerOfLazyCompanionObject by lazy { providerOfLazy.owner.companionObject()!!.symbol }
  val providerOfLazyCreate: IrFunctionSymbol by lazy {
    providerOfLazyCompanionObject.requireSimpleFunction(StringNames.CREATE)
  }

  private val instanceFactory: IrClassSymbol by lazy {
    pluginContext.referenceClass(ClassIds.metroInstanceFactory)!!
  }
  val instanceFactoryCompanionObject by lazy { instanceFactory.owner.companionObject()!!.symbol }
  val instanceFactoryInvoke: IrFunctionSymbol by lazy {
    instanceFactoryCompanionObject.requireSimpleFunction(StringNames.INVOKE)
  }

  val multibindingElement: IrConstructorSymbol by lazy {
    pluginContext.referenceClass(ClassIds.MultibindingElement)!!.constructors.first()
  }

  val metroDependencyGraphAnnotationConstructor: IrConstructorSymbol by lazy {
    pluginContext.referenceClass(classIds.dependencyGraphAnnotation)!!.constructors.first()
  }

  val callableMetadataAnnotationConstructor: IrConstructorSymbol by lazy {
    pluginContext.referenceClass(ClassIds.CallableMetadata)!!.constructors.first()
  }

  val metroProvider: IrClassSymbol by lazy {
    pluginContext.referenceClass(ClassId(metroRuntime.packageFqName, "Provider".asName()))!!
  }

  val metroProviderFunction: IrSimpleFunctionSymbol by lazy {
    pluginContext
      .referenceFunctions(CallableId(metroRuntime.packageFqName, "provider".asName()))
      .single()
  }

  val providerInvoke: IrSimpleFunctionSymbol by lazy {
    metroProvider.requireSimpleFunction("invoke")
  }

  private val metroDelegateFactory: IrClassSymbol by lazy {
    pluginContext.referenceClass(
      ClassId(metroRuntimeInternal.packageFqName, "DelegateFactory".asName())
    )!!
  }

  val metroDelegateFactoryConstructor: IrConstructorSymbol by lazy {
    metroDelegateFactory.constructors.single()
  }

  val metroDelegateFactoryCompanion: IrClassSymbol by lazy {
    metroDelegateFactory.owner.companionObject()!!.symbol
  }

  val metroDelegateFactorySetDelegate: IrFunctionSymbol by lazy {
    metroDelegateFactoryCompanion.requireSimpleFunction("setDelegate")
  }

  val metroMembersInjector: IrClassSymbol by lazy {
    pluginContext.referenceClass(ClassId(metroRuntime.packageFqName, "MembersInjector".asName()))!!
  }

  val metroMembersInjectors: IrClassSymbol by lazy {
    pluginContext.referenceClass(
      ClassId(metroRuntimeInternal.packageFqName, "MembersInjectors".asName())
    )!!
  }

  val metroMembersInjectorsNoOp: IrSimpleFunctionSymbol by lazy {
    metroMembersInjectors.requireSimpleFunction("noOp")
  }

  val metroFactory: IrClassSymbol by lazy {
    pluginContext.referenceClass(ClassId(metroRuntimeInternal.packageFqName, "Factory".asName()))!!
  }

  val metroSingleIn: IrClassSymbol by lazy {
    pluginContext.referenceClass(ClassId(metroRuntime.packageFqName, "SingleIn".asName()))!!
  }

  val metroSingleInConstructor: IrConstructorSymbol by lazy { metroSingleIn.constructors.first() }

  val graphFactoryInvokeFunctionMarkerClass: IrClassSymbol by lazy {
    pluginContext.referenceClass(
      ClassId(metroRuntime.packageFqName, "GraphFactoryInvokeFunctionMarker".asName())
    )!!
  }

  val graphFactoryInvokeFunctionMarkerConstructor: IrConstructorSymbol by lazy {
    graphFactoryInvokeFunctionMarkerClass.constructors.first()
  }

  val stdlibLazy: IrClassSymbol by lazy {
    pluginContext.referenceClass(ClassId(stdlib.packageFqName, "Lazy".asName()))!!
  }

  val lazyGetValue: IrFunctionSymbol by lazy { stdlibLazy.getPropertyGetter("get")!! }

  val stdlibErrorFunction: IrFunctionSymbol by lazy {
    pluginContext.referenceFunctions(CallableId(stdlib.packageFqName, "error".asName())).first()
  }

  val stdlibCheckNotNull: IrFunctionSymbol by lazy {
    pluginContext
      .referenceFunctions(CallableId(stdlib.packageFqName, "checkNotNull".asName()))
      .single { it.owner.parameters.size == 2 }
  }

  val emptySet by lazy {
    pluginContext
      .referenceFunctions(CallableId(stdlibCollections.packageFqName, "emptySet".asName()))
      .first()
  }

  val emptyMap by lazy {
    pluginContext
      .referenceFunctions(CallableId(stdlibCollections.packageFqName, "emptyMap".asName()))
      .first()
  }

  val setOfSingleton by lazy {
    pluginContext
      .referenceFunctions(CallableId(stdlibCollections.packageFqName, "setOf".asName()))
      .first {
        it.owner.hasShape(regularParameters = 1) && it.owner.parameters[0].varargElementType == null
      }
  }

  val buildSetWithCapacity by lazy {
    pluginContext
      .referenceFunctions(CallableId(stdlibCollections.packageFqName, "buildSet".asName()))
      .first { it.owner.hasShape(regularParameters = 2) }
  }

  val mutableSetAdd by lazy {
    pluginContext.irBuiltIns.mutableSetClass.owner.declarations
      .filterIsInstance<IrSimpleFunction>()
      .single { it.name.asString() == "add" }
  }

  val buildMapWithCapacity by lazy {
    pluginContext
      .referenceFunctions(CallableId(stdlibCollections.packageFqName, "buildMap".asName()))
      .first { it.owner.hasShape(regularParameters = 2) }
  }

  val mutableMapPut by lazy {
    pluginContext.irBuiltIns.mutableMapClass.owner.declarations
      .filterIsInstance<IrSimpleFunction>()
      .single { it.name.asString() == "put" }
  }

  val intoMapConstructor by lazy {
    pluginContext
      .referenceClass(ClassId(metroRuntime.packageFqName, StringNames.INTO_MAP.asName()))!!
      .constructors
      .single()
  }

  val intoSetConstructor by lazy {
    pluginContext
      .referenceClass(ClassId(metroRuntime.packageFqName, StringNames.INTO_SET.asName()))!!
      .constructors
      .single()
  }

  val elementsIntoSetConstructor by lazy {
    pluginContext
      .referenceClass(ClassId(metroRuntime.packageFqName, StringNames.ELEMENTS_INTO_SET.asName()))!!
      .constructors
      .single()
  }

  val bindsConstructor by lazy {
    pluginContext
      .referenceClass(ClassId(metroRuntime.packageFqName, Names.Binds))!!
      .constructors
      .single()
  }

  val providesConstructor by lazy {
    pluginContext
      .referenceClass(ClassId(metroRuntime.packageFqName, Names.Provides))!!
      .constructors
      .single()
  }

  val assistedConstructor by lazy {
    pluginContext
      .referenceClass(ClassId(metroRuntime.packageFqName, StringNames.ASSISTED.asName()))!!
      .constructors
      .single()
  }

  val bindsOptionalConstructor by lazy {
    pluginContext
      .referenceClass(DaggerSymbols.ClassIds.DAGGER_BINDS_OPTIONAL_OF)!!
      .constructors
      .single()
  }

  val deprecatedAnnotationConstructor: IrConstructorSymbol by lazy {
    pluginContext.referenceClass(StandardClassIds.Annotations.Deprecated)!!.constructors.first {
      it.owner.isPrimary
    }
  }

  val deprecated: IrClassSymbol by lazy {
    pluginContext.referenceClass(StandardClassIds.Annotations.Deprecated)!!
  }

  val deprecationLevel: IrClassSymbol by lazy {
    pluginContext.referenceClass(StandardClassIds.DeprecationLevel)!!
  }

  val hiddenDeprecationLevel by lazy {
    deprecationLevel.owner.declarations
      .filterIsInstance<IrEnumEntry>()
      .single { it.name.toString() == "HIDDEN" }
      .symbol
  }

  val javaOptional: IrClassSymbol by lazy { pluginContext.referenceClass(ClassIds.JavaOptional)!! }

  val javaOptionalEmpty: IrFunctionSymbol by lazy { javaOptional.requireSimpleFunction("empty") }

  val javaOptionalOf: IrFunctionSymbol by lazy { javaOptional.requireSimpleFunction("of") }

  val dependencyGraphAnnotations
    get() = classIds.dependencyGraphAnnotations

  val dependencyGraphFactoryAnnotations
    get() = classIds.dependencyGraphFactoryAnnotations

  val injectAnnotations
    get() = classIds.injectAnnotations

  val qualifierAnnotations
    get() = classIds.qualifierAnnotations

  val scopeAnnotations
    get() = classIds.scopeAnnotations

  val mapKeyAnnotations
    get() = classIds.mapKeyAnnotations

  val assistedAnnotations
    get() = classIds.assistedAnnotations

  val assistedFactoryAnnotations
    get() = classIds.assistedFactoryAnnotations

  val providerTypes
    get() = classIds.providerTypes

  val lazyTypes
    get() = classIds.lazyTypes

  sealed class ProviderSymbols {
    protected abstract val doubleCheck: IrClassSymbol

    val doubleCheckCompanionObject by lazy { doubleCheck.owner.companionObject()!!.symbol }
    val doubleCheckProvider by lazy { doubleCheckCompanionObject.requireSimpleFunction("provider") }

    context(context: IrMetroContext)
    protected abstract fun lazyFor(providerType: IrType): IrSimpleFunctionSymbol

    context(context: IrMetroContext)
    fun IrBuilderWithScope.invokeDoubleCheckLazy(
      contextKey: IrContextualTypeKey,
      arg: IrExpression,
    ): IrExpression {
      val lazySymbol = lazyFor(arg.type)
      return irInvoke(
        dispatchReceiver = irGetObject(doubleCheckCompanionObject),
        callee = lazySymbol,
        args = listOf(arg),
        typeHint = contextKey.toIrType(),
        typeArgs = listOf(arg.type, contextKey.typeKey.type),
      )
    }

    /** Transforms a given [metroProvider] into the [target] type's provider equivalent. */
    context(context: IrMetroContext)
    abstract fun IrBuilderWithScope.transformMetroProvider(
      metroProvider: IrExpression,
      target: IrContextualTypeKey,
    ): IrExpression

    /** Transforms a given [provider] into a Metro provider. */
    context(context: IrMetroContext)
    abstract fun IrBuilderWithScope.transformToMetroProvider(
      provider: IrExpression,
      type: IrType,
    ): IrExpression

    protected abstract val setFactory: IrClassSymbol

    val setFactoryBuilder: IrClassSymbol by lazy {
      setFactory.owner.nestedClasses.first { it.name.asString() == "Builder" }.symbol
    }

    abstract val setFactoryBuilderFunction: IrSimpleFunctionSymbol

    val setFactoryBuilderAddProviderFunction: IrSimpleFunctionSymbol by lazy {
      setFactoryBuilder.requireSimpleFunction("addProvider")
    }

    val setFactoryBuilderAddCollectionProviderFunction: IrSimpleFunctionSymbol by lazy {
      setFactoryBuilder.requireSimpleFunction("addCollectionProvider")
    }

    val setFactoryBuilderBuildFunction: IrSimpleFunctionSymbol by lazy {
      setFactoryBuilder.requireSimpleFunction("build")
    }
    protected abstract val mapFactory: IrClassSymbol

    val mapFactoryBuilder: IrClassSymbol by lazy {
      mapFactory.owner.nestedClasses.first { it.name.asString() == "Builder" }.symbol
    }

    abstract val mapFactoryBuilderFunction: IrSimpleFunctionSymbol
    abstract val mapFactoryEmptyFunction: IrSimpleFunctionSymbol

    val mapFactoryBuilderPutFunction: IrSimpleFunctionSymbol by lazy {
      mapFactoryBuilder.requireSimpleFunction("put")
    }

    val mapFactoryBuilderPutAllFunction: IrSimpleFunctionSymbol by lazy {
      mapFactoryBuilder.requireSimpleFunction("putAll")
    }

    val mapFactoryBuilderBuildFunction: IrSimpleFunctionSymbol by lazy {
      mapFactoryBuilder.requireSimpleFunction("build")
    }

    protected abstract val mapProviderFactory: IrClassSymbol

    val mapProviderFactoryBuilder: IrClassSymbol by lazy {
      mapProviderFactory.owner.nestedClasses.first { it.name.asString() == "Builder" }.symbol
    }

    abstract val mapProviderFactoryBuilderFunction: IrSimpleFunctionSymbol
    abstract val mapProviderFactoryEmptyFunction: IrSimpleFunctionSymbol?

    val mapProviderFactoryBuilderPutFunction: IrSimpleFunctionSymbol by lazy {
      mapProviderFactoryBuilder.requireSimpleFunction("put")
    }

    val mapProviderFactoryBuilderPutAllFunction: IrSimpleFunctionSymbol by lazy {
      mapProviderFactoryBuilder.requireSimpleFunction("putAll")
    }

    val mapProviderFactoryBuilderBuildFunction: IrSimpleFunctionSymbol by lazy {
      mapProviderFactoryBuilder.requireSimpleFunction("build")
    }
  }

  class MetroProviderSymbols(
    private val metroRuntimeInternal: IrPackageFragment,
    private val pluginContext: IrPluginContext,
  ) : ProviderSymbols() {
    override val doubleCheck by lazy {
      pluginContext.referenceClass(
        ClassId(metroRuntimeInternal.packageFqName, "DoubleCheck".asName())
      )!!
    }

    private val doubleCheckLazy by lazy { doubleCheckCompanionObject.requireSimpleFunction("lazy") }

    override val setFactory: IrClassSymbol by lazy {
      pluginContext.referenceClass(
        ClassId(metroRuntimeInternal.packageFqName, "SetFactory".asName())
      )!!
    }

    val setFactoryCompanionObject: IrClassSymbol by lazy {
      setFactory.owner.companionObject()!!.symbol
    }

    override val setFactoryBuilderFunction: IrSimpleFunctionSymbol by lazy {
      setFactoryCompanionObject.requireSimpleFunction("builder")
    }

    override val mapFactory: IrClassSymbol by lazy {
      pluginContext.referenceClass(
        ClassId(metroRuntimeInternal.packageFqName, "MapFactory".asName())
      )!!
    }

    private val mapFactoryCompanionObject: IrClassSymbol by lazy {
      mapFactory.owner.companionObject()!!.symbol
    }

    override val mapFactoryBuilderFunction: IrSimpleFunctionSymbol by lazy {
      mapFactoryCompanionObject.requireSimpleFunction("builder")
    }

    override val mapFactoryEmptyFunction: IrSimpleFunctionSymbol by lazy {
      mapFactoryCompanionObject.requireSimpleFunction("empty")
    }

    override val mapProviderFactory: IrClassSymbol by lazy {
      pluginContext.referenceClass(
        ClassId(metroRuntimeInternal.packageFqName, "MapProviderFactory".asName())
      )!!
    }

    private val mapProviderFactoryCompanionObject: IrClassSymbol by lazy {
      mapProviderFactory.owner.companionObject()!!.symbol
    }

    override val mapProviderFactoryBuilderFunction: IrSimpleFunctionSymbol by lazy {
      mapProviderFactoryCompanionObject.requireSimpleFunction("builder")
    }

    override val mapProviderFactoryEmptyFunction: IrSimpleFunctionSymbol by lazy {
      mapProviderFactoryCompanionObject.requireSimpleFunction("empty")
    }

    context(context: IrMetroContext)
    override fun IrBuilderWithScope.transformMetroProvider(
      metroProvider: IrExpression,
      target: IrContextualTypeKey,
    ): IrExpression {
      // Nothing to do here!
      return metroProvider
    }

    context(context: IrMetroContext)
    override fun IrBuilderWithScope.transformToMetroProvider(
      provider: IrExpression,
      type: IrType,
    ): IrExpression {
      // Nothing to do here!
      return provider
    }

    context(context: IrMetroContext)
    override fun lazyFor(providerType: IrType): IrSimpleFunctionSymbol {
      // Nothing to do here!
      return doubleCheckLazy
    }
  }

  class DaggerSymbols(
    private val moduleFragment: IrModuleFragment,
    private val pluginContext: IrPluginContext,
  ) : ProviderSymbols() {

    private val daggerRuntime: IrPackageFragment by lazy { moduleFragment.createPackage("dagger") }

    private val daggerRuntimeInternal: IrPackageFragment by lazy {
      moduleFragment.createPackage("dagger.internal")
    }

    private val daggerInteropRuntime: IrPackageFragment by lazy {
      moduleFragment.createPackage("dev.zacsweers.metro.interop.dagger")
    }

    private val daggerInteropRuntimeInternal: IrPackageFragment by lazy {
      moduleFragment.createPackage("dev.zacsweers.metro.interop.dagger.internal")
    }

    val primitives =
      setOf(
        ClassIds.DAGGER_LAZY_CLASS_ID,
        ClassIds.DAGGER_INTERNAL_PROVIDER_CLASS_ID,
        ClassIds.JAVAX_PROVIDER_CLASS_ID,
        ClassIds.JAKARTA_PROVIDER_CLASS_ID,
      )

    val providerPrimitives =
      setOf(
        ClassIds.DAGGER_INTERNAL_PROVIDER_CLASS_ID,
        ClassIds.JAVAX_PROVIDER_CLASS_ID,
        ClassIds.JAKARTA_PROVIDER_CLASS_ID,
      )

    override val doubleCheck by lazy {
      pluginContext.referenceClass(
        ClassId(daggerInteropRuntimeInternal.packageFqName, "DaggerInteropDoubleCheck".asName())
      )!!
    }

    private val lazyFromDaggerProvider by lazy {
      doubleCheckCompanionObject.requireSimpleFunction("lazyFromDaggerProvider")
    }
    private val lazyFromJavaxProvider by lazy {
      doubleCheckCompanionObject.requireSimpleFunction("lazyFromJavaxProvider")
    }
    private val lazyFromJakartaProvider by lazy {
      doubleCheckCompanionObject.requireSimpleFunction("lazyFromJakartaProvider")
    }
    private val lazyFromMetroProvider by lazy {
      doubleCheckCompanionObject.requireSimpleFunction("lazyFromMetroProvider")
    }

    override val setFactory: IrClassSymbol by lazy {
      pluginContext.referenceClass(
        ClassId(daggerRuntimeInternal.packageFqName, "SetFactory".asName())
      )!!
    }

    override val setFactoryBuilderFunction: IrSimpleFunctionSymbol by lazy {
      // Static function in this case
      setFactory.functions.first {
        it.owner.nonDispatchParameters.size == 1 && it.owner.name == Names.builder
      }
    }

    override val mapFactory: IrClassSymbol by lazy {
      pluginContext.referenceClass(
        ClassId(daggerRuntimeInternal.packageFqName, "MapFactory".asName())
      )!!
    }

    override val mapFactoryBuilderFunction: IrSimpleFunctionSymbol by lazy {
      // Static function in this case
      mapFactory.functions.first {
        it.owner.nonDispatchParameters.size == 1 && it.owner.name == Names.builder
      }
    }

    override val mapFactoryEmptyFunction: IrSimpleFunctionSymbol by lazy {
      // Static function in this case
      mapFactory.requireSimpleFunction("empty")
    }

    override val mapProviderFactory: IrClassSymbol by lazy {
      pluginContext.referenceClass(
        ClassId(daggerRuntimeInternal.packageFqName, "MapProviderFactory".asName())
      )!!
    }

    override val mapProviderFactoryBuilderFunction: IrSimpleFunctionSymbol by lazy {
      // Static function in this case
      mapProviderFactory.functions.first {
        it.owner.nonDispatchParameters.size == 1 && it.owner.name == Names.builder
      }
    }

    override val mapProviderFactoryEmptyFunction: IrSimpleFunctionSymbol? = null

    context(context: IrMetroContext)
    override fun IrBuilderWithScope.transformMetroProvider(
      metroProvider: IrExpression,
      target: IrContextualTypeKey,
    ): IrExpression {
      val targetClass = target.rawType?.classOrNull?.owner
      val targetClassId =
        targetClass?.classId
          ?: reportCompilerBug("Unexpected non-jakarta/javax provider type $target")
      val interopFunction =
        when (targetClassId) {
          ClassIds.DAGGER_INTERNAL_PROVIDER_CLASS_ID -> asDaggerInternalProvider
          ClassIds.JAVAX_PROVIDER_CLASS_ID -> asJavaxProvider
          ClassIds.JAKARTA_PROVIDER_CLASS_ID -> asJakartaProvider
          ClassIds.DAGGER_LAZY_CLASS_ID -> {
            return invokeDoubleCheckLazy(target, metroProvider)
          }

          else -> reportCompilerBug("Unexpected non-dagger/jakarta/javax provider $targetClassId")
        }
      return irInvoke(
        extensionReceiver = metroProvider,
        callee = interopFunction,
        typeArgs = listOf(target.typeKey.type),
      )
    }

    context(context: IrMetroContext)
    override fun IrBuilderWithScope.transformToMetroProvider(
      provider: IrExpression,
      type: IrType,
    ): IrExpression {
      return irInvoke(
        extensionReceiver = provider,
        callee = asMetroProvider,
        typeArgs = listOf(type),
      )
    }

    val daggerLazy: IrClassSymbol by lazy {
      pluginContext.referenceClass(ClassIds.DAGGER_LAZY_CLASS_ID)!!
    }

    val javaxProvider: IrClassSymbol by lazy {
      pluginContext.referenceClass(ClassIds.JAVAX_PROVIDER_CLASS_ID)!!
    }

    val jakartaProvider: IrClassSymbol by lazy {
      pluginContext.referenceClass(ClassIds.JAKARTA_PROVIDER_CLASS_ID)!!
    }

    val asDaggerInternalProvider by lazy {
      pluginContext
        .referenceFunctions(
          CallableId(
            daggerInteropRuntimeInternal.packageFqName,
            StringNames.AS_DAGGER_INTERNAL_PROVIDER.asName(),
          )
        )
        .single()
    }

    val asJavaxProvider by lazy {
      pluginContext
        .referenceFunctions(
          CallableId(daggerInteropRuntime.packageFqName, StringNames.AS_JAVAX_PROVIDER.asName())
        )
        .single()
    }

    val asJakartaProvider by lazy {
      pluginContext
        .referenceFunctions(
          CallableId(daggerInteropRuntime.packageFqName, StringNames.AS_JAKARTA_PROVIDER.asName())
        )
        .single()
    }

    val asMetroProvider by lazy {
      pluginContext
        .referenceFunctions(
          CallableId(daggerInteropRuntime.packageFqName, StringNames.AS_METRO_PROVIDER.asName())
        )
        .first()
    }

    val asDaggerMembersInjector by lazy {
      pluginContext
        .referenceFunctions(
          CallableId(
            daggerInteropRuntime.packageFqName,
            StringNames.AS_DAGGER_MEMBERS_INJECTOR.asName(),
          )
        )
        .first()
    }

    val asMetroMembersInjector by lazy {
      pluginContext
        .referenceFunctions(
          CallableId(
            daggerInteropRuntime.packageFqName,
            StringNames.AS_METRO_MEMBERS_INJECTOR.asName(),
          )
        )
        .first()
    }

    context(context: IrMetroContext)
    override fun lazyFor(providerType: IrType): IrSimpleFunctionSymbol {
      providerType.rawTypeOrNull()?.let { initialRawType ->
        for (type in initialRawType.getAllSuperTypes(excludeSelf = false, excludeAny = true)) {
          val classId = type.classOrNull?.owner?.classId ?: continue
          val symbol =
            when (classId) {
              ClassIds.DAGGER_INTERNAL_PROVIDER_CLASS_ID -> lazyFromDaggerProvider
              ClassIds.JAVAX_PROVIDER_CLASS_ID -> lazyFromJavaxProvider
              ClassIds.JAKARTA_PROVIDER_CLASS_ID -> lazyFromJakartaProvider
              Symbols.ClassIds.metroProvider -> lazyFromMetroProvider
              else -> continue
            }
          return symbol
        }
      }
      reportCompilerBug("Unexpected provider type: ${providerType.dumpKotlinLike()}")
    }

    object ClassIds {
      private val daggerRuntimePackageFqName = FqName("dagger")
      private val daggerInternalPackageFqName = FqName("dagger.internal")
      private val daggerMultibindsPackageFqName = FqName("dagger.multibindings")
      private val daggerAssistedPackageFqName = FqName("dagger.assisted")
      val DAGGER_LAZY_CLASS_ID = ClassId(daggerRuntimePackageFqName, "Lazy".asName())
      val DAGGER_MODULE = ClassId(daggerRuntimePackageFqName, "Module".asName())
      val DAGGER_PROVIDES = ClassId(daggerRuntimePackageFqName, "Provides".asName())
      val DAGGER_BINDS = ClassId(daggerRuntimePackageFqName, "Binds".asName())
      val DAGGER_REUSABLE_CLASS_ID = ClassId(daggerRuntimePackageFqName, "Reusable".asName())
      val DAGGER_BINDS_OPTIONAL_OF = ClassId(daggerRuntimePackageFqName, "BindsOptionalOf".asName())
      val DAGGER_INTERNAL_PROVIDER_CLASS_ID =
        ClassId(daggerInternalPackageFqName, Names.ProviderClass)
      val DAGGER_MULTIBINDS = ClassId(daggerMultibindsPackageFqName, "Multibinds".asName())
      val DAGGER_ASSISTED_INJECT = ClassId(daggerAssistedPackageFqName, "AssistedInject".asName())
      val DAGGER_INJECTED_FIELD_SIGNATURE =
        ClassId(daggerInternalPackageFqName, "InjectedFieldSignature".asName())
      val JAVAX_PROVIDER_CLASS_ID = ClassId(FqName("javax.inject"), "Provider".asName())
      val JAKARTA_PROVIDER_CLASS_ID = ClassId(FqName("jakarta.inject"), "Provider".asName())
    }
  }
}

private fun IrModuleFragment.createPackage(packageName: String): IrPackageFragment =
  createEmptyExternalPackageFragment(descriptor, FqName(packageName))
