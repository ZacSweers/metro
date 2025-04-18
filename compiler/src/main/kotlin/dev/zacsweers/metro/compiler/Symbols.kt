// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import dev.zacsweers.metro.compiler.Symbols.FqNames.kotlinCollectionsPackageFqn
import dev.zacsweers.metro.compiler.Symbols.StringNames.METRO_RUNTIME_INTERNAL_PACKAGE
import dev.zacsweers.metro.compiler.Symbols.StringNames.METRO_RUNTIME_PACKAGE
import dev.zacsweers.metro.compiler.ir.ContextualTypeKey
import dev.zacsweers.metro.compiler.ir.irInvoke
import dev.zacsweers.metro.compiler.ir.requireSimpleFunction
import kotlin.collections.contains
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
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
import org.jetbrains.kotlin.ir.util.explicitParametersCount
import org.jetbrains.kotlin.ir.util.getPropertyGetter
import org.jetbrains.kotlin.ir.util.kotlinPackageFqn
import org.jetbrains.kotlin.ir.util.nestedClasses
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.JsStandardClassIds
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.StandardClassIds

internal class Symbols(
  private val moduleFragment: IrModuleFragment,
  val pluginContext: IrPluginContext,
  val classIds: dev.zacsweers.metro.compiler.ClassIds,
  val options: MetroOptions,
) {

  object StringNames {
    const val CREATE = "create"
    const val FACTORY = "factory"
    const val GET = "get"
    const val INJECT_MEMBERS = "injectMembers"
    const val INVOKE = "invoke"
    const val METRO_ACCESSOR = "_metroAccessor"
    const val METRO_FACTORY = "$\$MetroFactory"
    const val METRO_HINTS_PACKAGE = "metro.hints"
    const val METRO_IMPL = "$\$Impl"
    const val METRO_RUNTIME_INTERNAL_PACKAGE = "dev.zacsweers.metro.internal"
    const val METRO_RUNTIME_PACKAGE = "dev.zacsweers.metro"
    const val NEW_INSTANCE = "newInstance"
    const val PROVIDER = "provider"
  }

  object FqNames {
    val kotlinCollectionsPackageFqn = FqName("kotlin.collections")
    val composeRuntime = FqName("androidx.compose.runtime")
    val metroRuntimePackage = FqName(METRO_RUNTIME_PACKAGE)
    val metroRuntimeInternalPackage = FqName(METRO_RUNTIME_INTERNAL_PACKAGE)
    val metroHintsPackage = FqName(StringNames.METRO_HINTS_PACKAGE)
    val providesCallableIdClass = ClassIds.providesCallableIdClass.asSingleFqName()
    val graphFactoryInvokeFunctionMarkerClass =
      ClassIds.graphFactoryInvokeFunctionMarkerClass.asSingleFqName()
  }

  // TODO replace with StandardClassIds
  object ClassIds {
    val anyClass = StandardClassIds.Any
    val jsExportIgnore = JsStandardClassIds.Annotations.JsExportIgnore
    val composable = ClassId(FqNames.composeRuntime, "Composable".asName())
    val stable = ClassId(FqNames.composeRuntime, "Stable".asName())
    val metroBinds = ClassId(FqNames.metroRuntimePackage, Names.bindsClassName)
    val metroContribution =
      ClassId(FqNames.metroRuntimeInternalPackage, "MetroContribution".asName())
    val metroExtends = ClassId(FqNames.metroRuntimePackage, "Extends".asName())
    val metroFactory = ClassId(FqNames.metroRuntimeInternalPackage, Names.factoryClassName)
    val metroIncludes = ClassId(FqNames.metroRuntimePackage, "Includes".asName())
    val metroInject = ClassId(FqNames.metroRuntimePackage, "Inject".asName())
    val metroIntoMap = ClassId(FqNames.metroRuntimePackage, "IntoMap".asName())
    val metroIntoSet = ClassId(FqNames.metroRuntimePackage, "IntoSet".asName())
    val metroProvider = ClassId(FqNames.metroRuntimePackage, Names.providerClassName)
    val metroProvides = ClassId(FqNames.metroRuntimePackage, "Provides".asName())
    val metroSingleIn = ClassId(FqNames.metroRuntimePackage, "SingleIn".asName())
    val metroInjectedFunctionClass =
      ClassId(FqNames.metroRuntimeInternalPackage, "InjectedFunctionClass".asName())
    val providesCallableIdClass =
      ClassId(FqNames.metroRuntimeInternalPackage, "ProvidesCallableId".asName())
    val graphFactoryInvokeFunctionMarkerClass =
      ClassId(FqNames.metroRuntimeInternalPackage, "GraphFactoryInvokeFunctionMarker".asName())
    val membersInjector = ClassId(FqNames.metroRuntimePackage, Names.membersInjector)
    val nonRestartableComposable =
      ClassId(FqNames.composeRuntime, "NonRestartableComposable".asName())
    val lazy = StandardClassIds.byName("Lazy")
    val map = StandardClassIds.Map
    val set = StandardClassIds.Set
  }

  object Names {
    val bindsClassName = Name.identifier("Binds")
    val create = StringNames.CREATE.asName()
    val createFactoryProvider = "createFactoryProvider".asName()
    val delegateFactory = Name.identifier("delegateFactory")
    val factoryClassName = Name.identifier("Factory")
    val factoryFunctionName = StringNames.FACTORY.asName()
    val instance = Name.identifier("instance")
    val injectMembers = Name.identifier(StringNames.INJECT_MEMBERS)
    val invoke = Name.identifier(StringNames.INVOKE)
    val isExtendable = "isExtendable".asName()
    val metroAccessor = StringNames.METRO_ACCESSOR.asName()
    val metroFactory = Name.identifier(StringNames.METRO_FACTORY)
    val metroContribution = Name.identifier("$\$MetroContribution")
    val metroGraph = Name.identifier("$\$MetroGraph")
    val metroImpl = StringNames.METRO_IMPL.asName()
    val metroMembersInjector = Name.identifier("$\$MetroMembersInjector")
    val membersInjector = Name.identifier("MembersInjector")
    val newInstanceFunction = StringNames.NEW_INSTANCE.asName()
    val providerClassName = Name.identifier("Provider")
    val providerFunction = Name.identifier(StringNames.PROVIDER)
    val receiver = Name.identifier("receiver")
    // Used in @Assisted.value
    val value = StandardNames.DEFAULT_VALUE_PARAMETER

    val metroNames = setOf(metroFactory, metroGraph, metroImpl, metroMembersInjector)
  }

  // TODO use more constants from StandardNames.FqNames

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

  fun providerSymbolsFor(key: ContextualTypeKey): ProviderSymbols {
    return key.rawType?.let(::providerSymbolsFor) ?: metroProviderSymbols
  }

  fun providerSymbolsFor(type: IrType?): ProviderSymbols {
    val useDaggerInterop =
      options.enableDaggerRuntimeInterop &&
        type?.classOrNull?.owner?.classId in daggerSymbols.providerPrimitives
    return if (useDaggerInterop) daggerSymbols else metroProviderSymbols
  }

  val metroCreateGraph: IrSimpleFunctionSymbol by lazy {
    pluginContext
      .referenceFunctions(CallableId(metroRuntime.packageFqName, Name.identifier("createGraph")))
      .single()
  }

  val metroCreateGraphFactory: IrSimpleFunctionSymbol by lazy {
    pluginContext
      .referenceFunctions(
        CallableId(metroRuntime.packageFqName, Name.identifier("createGraphFactory"))
      )
      .single()
  }

  private val doubleCheck: IrClassSymbol by lazy {
    pluginContext.referenceClass(
      ClassId(metroRuntimeInternal.packageFqName, Name.identifier("DoubleCheck"))
    )!!
  }
  val doubleCheckCompanionObject by lazy { doubleCheck.owner.companionObject()!!.symbol }
  val doubleCheckProvider by lazy { doubleCheckCompanionObject.requireSimpleFunction("provider") }
  val doubleCheckLazy by lazy { doubleCheckCompanionObject.requireSimpleFunction("lazy") }

  private val providerOfLazy: IrClassSymbol by lazy {
    pluginContext.referenceClass(
      ClassId(metroRuntimeInternal.packageFqName, Name.identifier("ProviderOfLazy"))
    )!!
  }
  val providerOfLazyCompanionObject by lazy { providerOfLazy.owner.companionObject()!!.symbol }
  val providerOfLazyCreate: IrFunctionSymbol by lazy {
    providerOfLazyCompanionObject.requireSimpleFunction(StringNames.CREATE)
  }

  private val instanceFactory: IrClassSymbol by lazy {
    pluginContext.referenceClass(
      ClassId(metroRuntimeInternal.packageFqName, Name.identifier("InstanceFactory"))
    )!!
  }
  val instanceFactoryConstructorSymbol by lazy { instanceFactory.constructors.single() }

  val metroProvider: IrClassSymbol by lazy {
    pluginContext.referenceClass(ClassId(metroRuntime.packageFqName, Name.identifier("Provider")))!!
  }

  val metroProviderFunction: IrSimpleFunctionSymbol by lazy {
    pluginContext
      .referenceFunctions(CallableId(metroRuntime.packageFqName, Name.identifier("provider")))
      .single()
  }

  val providerInvoke: IrSimpleFunctionSymbol by lazy {
    metroProvider.requireSimpleFunction("invoke")
  }

  private val metroDelegateFactory: IrClassSymbol by lazy {
    pluginContext.referenceClass(
      ClassId(metroRuntimeInternal.packageFqName, Name.identifier("DelegateFactory"))
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
    pluginContext.referenceClass(
      ClassId(metroRuntime.packageFqName, Name.identifier("MembersInjector"))
    )!!
  }

  val metroMembersInjectors: IrClassSymbol by lazy {
    pluginContext.referenceClass(
      ClassId(metroRuntimeInternal.packageFqName, Name.identifier("MembersInjectors"))
    )!!
  }

  val metroMembersInjectorsNoOp: IrSimpleFunctionSymbol by lazy {
    metroMembersInjectors.requireSimpleFunction("noOp")
  }

  val metroFactory: IrClassSymbol by lazy {
    pluginContext.referenceClass(
      ClassId(metroRuntimeInternal.packageFqName, Name.identifier("Factory"))
    )!!
  }

  val metroSingleIn: IrClassSymbol by lazy {
    pluginContext.referenceClass(ClassId(metroRuntime.packageFqName, Name.identifier("SingleIn")))!!
  }

  val metroSingleInConstructor: IrConstructorSymbol by lazy { metroSingleIn.constructors.first() }

  val graphFactoryInvokeFunctionMarkerClass: IrClassSymbol by lazy {
    pluginContext.referenceClass(
      ClassId(metroRuntime.packageFqName, Name.identifier("GraphFactoryInvokeFunctionMarker"))
    )!!
  }

  val graphFactoryInvokeFunctionMarkerConstructor: IrConstructorSymbol by lazy {
    graphFactoryInvokeFunctionMarkerClass.constructors.first()
  }

  private val setFactory: IrClassSymbol by lazy {
    pluginContext.referenceClass(
      ClassId(metroRuntimeInternal.packageFqName, Name.identifier("SetFactory"))
    )!!
  }

  val setFactoryBuilder: IrClassSymbol by lazy {
    setFactory.owner.nestedClasses.first { it.name.asString() == "Builder" }.symbol
  }

  val setFactoryCompanionObject: IrClassSymbol by lazy {
    setFactory.owner.companionObject()!!.symbol
  }

  val setFactoryBuilderFunction: IrSimpleFunctionSymbol by lazy {
    setFactoryCompanionObject.requireSimpleFunction("builder")
  }

  val setFactoryBuilderAddProviderFunction: IrSimpleFunctionSymbol by lazy {
    setFactoryBuilder.requireSimpleFunction("addProvider")
  }

  val setFactoryBuilderAddCollectionProviderFunction: IrSimpleFunctionSymbol by lazy {
    setFactoryBuilder.requireSimpleFunction("addCollectionProvider")
  }

  val setFactoryBuilderBuildFunction: IrSimpleFunctionSymbol by lazy {
    setFactoryBuilder.requireSimpleFunction("build")
  }

  private val mapFactory: IrClassSymbol by lazy {
    pluginContext.referenceClass(
      ClassId(metroRuntimeInternal.packageFqName, Name.identifier("MapFactory"))
    )!!
  }

  val mapFactoryBuilder: IrClassSymbol by lazy {
    mapFactory.owner.nestedClasses.first { it.name.asString() == "Builder" }.symbol
  }

  val mapFactoryCompanionObject: IrClassSymbol by lazy {
    mapFactory.owner.companionObject()!!.symbol
  }

  val mapFactoryBuilderFunction: IrSimpleFunctionSymbol by lazy {
    mapFactoryCompanionObject.requireSimpleFunction("builder")
  }

  val mapFactoryEmptyFunction: IrSimpleFunctionSymbol by lazy {
    mapFactoryCompanionObject.requireSimpleFunction("empty")
  }

  val mapFactoryBuilderPutFunction: IrSimpleFunctionSymbol by lazy {
    mapFactoryBuilder.requireSimpleFunction("put")
  }

  val mapFactoryBuilderPutAllFunction: IrSimpleFunctionSymbol by lazy {
    mapFactoryBuilder.requireSimpleFunction("putAll")
  }

  val mapFactoryBuilderBuildFunction: IrSimpleFunctionSymbol by lazy {
    mapFactoryBuilder.requireSimpleFunction("build")
  }

  private val mapProviderFactory: IrClassSymbol by lazy {
    pluginContext.referenceClass(
      ClassId(metroRuntimeInternal.packageFqName, Name.identifier("MapProviderFactory"))
    )!!
  }

  val mapProviderFactoryBuilder: IrClassSymbol by lazy {
    mapProviderFactory.owner.nestedClasses.first { it.name.asString() == "Builder" }.symbol
  }

  val mapProviderFactoryCompanionObject: IrClassSymbol by lazy {
    mapProviderFactory.owner.companionObject()!!.symbol
  }

  val mapProviderFactoryBuilderFunction: IrSimpleFunctionSymbol by lazy {
    mapProviderFactoryCompanionObject.requireSimpleFunction("builder")
  }

  val mapProviderFactoryEmptyFunction: IrSimpleFunctionSymbol by lazy {
    mapProviderFactoryCompanionObject.requireSimpleFunction("empty")
  }

  val mapProviderFactoryBuilderPutFunction: IrSimpleFunctionSymbol by lazy {
    mapProviderFactoryBuilder.requireSimpleFunction("put")
  }

  val mapProviderFactoryBuilderPutAllFunction: IrSimpleFunctionSymbol by lazy {
    mapProviderFactoryBuilder.requireSimpleFunction("putAll")
  }

  val mapProviderFactoryBuilderBuildFunction: IrSimpleFunctionSymbol by lazy {
    mapProviderFactoryBuilder.requireSimpleFunction("build")
  }

  val stdlibLazy: IrClassSymbol by lazy {
    pluginContext.referenceClass(ClassId(stdlib.packageFqName, Name.identifier("Lazy")))!!
  }

  val lazyGetValue: IrFunctionSymbol by lazy { stdlibLazy.getPropertyGetter("get")!! }

  val stdlibErrorFunction: IrFunctionSymbol by lazy {
    pluginContext
      .referenceFunctions(CallableId(stdlib.packageFqName, Name.identifier("error")))
      .first()
  }

  val stdlibCheckNotNull: IrFunctionSymbol by lazy {
    pluginContext
      .referenceFunctions(CallableId(stdlib.packageFqName, Name.identifier("checkNotNull")))
      .single { it.owner.explicitParametersCount == 2 }
  }

  val emptySet by lazy {
    pluginContext
      .referenceFunctions(CallableId(stdlibCollections.packageFqName, Name.identifier("emptySet")))
      .first()
  }

  val setOfSingleton by lazy {
    pluginContext
      .referenceFunctions(CallableId(stdlibCollections.packageFqName, Name.identifier("setOf")))
      .first {
        it.owner.valueParameters.size == 1 && it.owner.valueParameters[0].varargElementType == null
      }
  }

  val buildSetWithCapacity by lazy {
    pluginContext
      .referenceFunctions(CallableId(stdlibCollections.packageFqName, Name.identifier("buildSet")))
      .first { it.owner.valueParameters.size == 2 }
  }

  val mutableSetAdd by lazy {
    pluginContext.irBuiltIns.mutableSetClass.owner.declarations
      .filterIsInstance<IrSimpleFunction>()
      .single { it.name.asString() == "add" }
  }

  val intoMapConstructor by lazy {
    pluginContext
      .referenceClass(ClassId(metroRuntime.packageFqName, "IntoMap".asName()))!!
      .constructors
      .single()
  }

  val intoSetConstructor by lazy {
    pluginContext
      .referenceClass(ClassId(metroRuntime.packageFqName, "IntoSet".asName()))!!
      .constructors
      .single()
  }

  val elementsIntoSetConstructor by lazy {
    pluginContext
      .referenceClass(ClassId(metroRuntime.packageFqName, "ElementsIntoSet".asName()))!!
      .constructors
      .single()
  }

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
    val doubleCheckLazy by lazy { doubleCheckCompanionObject.requireSimpleFunction("lazy") }

    /** Transforms a given [metroProvider] into the [target] type's provider equivalent. */
    abstract fun IrBuilderWithScope.transformMetroProvider(
      metroProvider: IrExpression,
      target: ContextualTypeKey,
    ): IrExpression

    /** Transforms a given [provider] into a Metro provider. */
    abstract fun IrBuilderWithScope.transformToMetroProvider(
      provider: IrExpression,
      type: IrType,
    ): IrExpression
  }

  class MetroProviderSymbols(
    private val metroRuntimeInternal: IrPackageFragment,
    private val pluginContext: IrPluginContext,
  ) : ProviderSymbols() {
    override val doubleCheck by lazy {
      pluginContext.referenceClass(
        ClassId(metroRuntimeInternal.packageFqName, Name.identifier("DoubleCheck"))
      )!!
    }

    override fun IrBuilderWithScope.transformMetroProvider(
      metroProvider: IrExpression,
      target: ContextualTypeKey,
    ): IrExpression {
      // Nothing to do here!
      return metroProvider
    }

    override fun IrBuilderWithScope.transformToMetroProvider(
      provider: IrExpression,
      type: IrType,
    ): IrExpression {
      // Nothing to do here!
      return provider
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

    // TODO double check, its helpers, converters

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
        ClassId(
          daggerInteropRuntimeInternal.packageFqName,
          Name.identifier("DaggerInteropDoubleCheck"),
        )
      )!!
    }

    override fun IrBuilderWithScope.transformMetroProvider(
      metroProvider: IrExpression,
      target: ContextualTypeKey,
    ): IrExpression {
      val targetClassId =
        target.rawType?.classOrNull?.owner?.classId
          ?: error("Unexpected non-jakarta/javax provider type $target")
      val interopFunction =
        when (targetClassId) {
          ClassIds.DAGGER_INTERNAL_PROVIDER_CLASS_ID -> asDaggerInternalProvider
          ClassIds.JAVAX_PROVIDER_CLASS_ID -> asJavaxProvider
          ClassIds.JAKARTA_PROVIDER_CLASS_ID -> asJakartaProvider
          else -> error("Unexpected non-dagger/jakarta/javax provider $targetClassId")
        }
      return irInvoke(extensionReceiver = metroProvider, callee = interopFunction).apply {
        putTypeArgument(0, target.typeKey.type)
      }
    }

    override fun IrBuilderWithScope.transformToMetroProvider(
      provider: IrExpression,
      type: IrType,
    ): IrExpression {
      return irInvoke(extensionReceiver = provider, callee = asMetroProvider).apply {
        putTypeArgument(0, type)
      }
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
            "asDaggerInternalProvider".asName(),
          )
        )
        .single()
    }

    val asJavaxProvider by lazy {
      pluginContext
        .referenceFunctions(
          CallableId(daggerInteropRuntime.packageFqName, "asJavaxProvider".asName())
        )
        .single()
    }

    val asJakartaProvider by lazy {
      pluginContext
        .referenceFunctions(
          CallableId(daggerInteropRuntime.packageFqName, "asJakartaProvider".asName())
        )
        .single()
    }

    val asMetroProvider by lazy {
      pluginContext
        .referenceFunctions(
          CallableId(daggerInteropRuntime.packageFqName, "asMetroProvider".asName())
        )
        .first()
    }

    val asDaggerMembersInjector by lazy {
      pluginContext
        .referenceFunctions(
          CallableId(daggerInteropRuntime.packageFqName, "asDaggerMembersInjector".asName())
        )
        .first()
    }

    val asMetroMembersInjector by lazy {
      pluginContext
        .referenceFunctions(
          CallableId(daggerInteropRuntime.packageFqName, "asMetroMembersInjector".asName())
        )
        .first()
    }

    object ClassIds {
      private val daggerRuntimePackageFqName = FqName("dagger")
      val DAGGER_LAZY_CLASS_ID = ClassId(daggerRuntimePackageFqName, Name.identifier("Lazy"))
      val DAGGER_REUSABLE_CLASS_ID =
        ClassId(daggerRuntimePackageFqName, Name.identifier("Reusable"))
      val DAGGER_INTERNAL_PROVIDER_CLASS_ID =
        ClassId(FqName("dagger.internal"), Name.identifier("Provider"))
      val JAVAX_PROVIDER_CLASS_ID = ClassId(FqName("javax.inject"), Name.identifier("Provider"))
      val JAKARTA_PROVIDER_CLASS_ID = ClassId(FqName("jakarta.inject"), Name.identifier("Provider"))
    }
  }
}

private fun IrModuleFragment.createPackage(packageName: String): IrPackageFragment =
  createEmptyExternalPackageFragment(descriptor, FqName(packageName))
