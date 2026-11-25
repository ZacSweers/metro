// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.symbols

import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.compat.CompatContext
import dev.zacsweers.metro.compiler.ir.requireSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrPackageFragment
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.nestedClasses
import org.jetbrains.kotlin.ir.util.nonDispatchParameters
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

internal interface FrameworkSymbols {
  val canonicalProviderType: IrClassSymbol
  val doubleCheckCompanionObject: IrClassSymbol
  val doubleCheckProvider: IrSimpleFunctionSymbol
  val doubleCheckLazy: IrSimpleFunctionSymbol
  val providerOfLazyCreate: IrSimpleFunctionSymbol
  val setFactoryBuilder: IrClassSymbol
  val setFactoryBuilderFunction: IrSimpleFunctionSymbol
  val setFactoryEmptyFunction: IrSimpleFunctionSymbol?
  val setFactorySingletonFunction: IrSimpleFunctionSymbol?
  val setFactoryBuilderAddProviderFunction: IrSimpleFunctionSymbol
  val setFactoryBuilderAddCollectionProviderFunction: IrSimpleFunctionSymbol
  val setFactoryBuilderBuildFunction: IrSimpleFunctionSymbol
  val mapFactory: MapFactorySymbols
  val mapProviderFactory: MapFactorySymbols
  val mapLazyFactory: MapFactorySymbols
  val mapProviderLazyFactory: MapFactorySymbols
  val mapSuspendProviderFactory: MapFactorySymbols
}

internal abstract class BaseFrameworkSymbols : FrameworkSymbols {
  protected abstract val doubleCheck: IrClassSymbol
  protected abstract val setFactory: IrClassSymbol

  override val doubleCheckCompanionObject by lazy { doubleCheck.owner.companionObject()!!.symbol }
  override val doubleCheckProvider by lazy {
    doubleCheckCompanionObject.requireSimpleFunction("provider")
  }
  // Note: doubleCheckLazy is not implemented in base class - each subclass must implement it
  // because Metro uses "lazy" while Dagger uses "lazyFromMetroProvider"

  override val setFactoryBuilder: IrClassSymbol by lazy {
    setFactory.owner.nestedClasses.first { it.name.asString() == "Builder" }.symbol
  }

  override val setFactoryBuilderAddProviderFunction: IrSimpleFunctionSymbol by lazy {
    setFactoryBuilder.requireSimpleFunction("addProvider")
  }

  override val setFactoryBuilderAddCollectionProviderFunction: IrSimpleFunctionSymbol by lazy {
    setFactoryBuilder.requireSimpleFunction("addCollectionProvider")
  }

  override val setFactoryBuilderBuildFunction: IrSimpleFunctionSymbol by lazy {
    setFactoryBuilder.requireSimpleFunction("build")
  }
}

internal class MetroFrameworkSymbols(
  private val metroRuntimeInternal: IrPackageFragment,
  private val builtinsFinder: CompatContext.DeclarationFinderCompat,
) : BaseFrameworkSymbols() {
  override val canonicalProviderType: IrClassSymbol by lazy {
    builtinsFinder.findClass(Symbols.ClassIds.metroProvider)!!
  }

  override val doubleCheck by lazy {
    builtinsFinder.findClass(ClassId(metroRuntimeInternal.packageFqName, "DoubleCheck".asName()))!!
  }

  override val doubleCheckLazy by lazy { doubleCheckCompanionObject.requireSimpleFunction("lazy") }

  private val providerOfLazy: IrClassSymbol by lazy {
    builtinsFinder.findClass(
      ClassId(metroRuntimeInternal.packageFqName, "ProviderOfLazy".asName()),
    )!!
  }

  private val providerOfLazyCompanionObject by lazy {
    providerOfLazy.owner.companionObject()!!.symbol
  }

  override val providerOfLazyCreate: IrSimpleFunctionSymbol by lazy {
    providerOfLazyCompanionObject.requireSimpleFunction(Symbols.StringNames.CREATE)
  }

  override val setFactory: IrClassSymbol by lazy {
    builtinsFinder.findClass(ClassId(metroRuntimeInternal.packageFqName, "SetFactory".asName()))!!
  }

  val setFactoryCompanionObject: IrClassSymbol by lazy {
    setFactory.owner.companionObject()!!.symbol
  }

  override val setFactoryBuilderFunction: IrSimpleFunctionSymbol by lazy {
    setFactoryCompanionObject.requireSimpleFunction("builder")
  }

  override val setFactoryEmptyFunction: IrSimpleFunctionSymbol by lazy {
    setFactoryCompanionObject.requireSimpleFunction("empty")
  }

  override val setFactorySingletonFunction: IrSimpleFunctionSymbol by lazy {
    setFactoryCompanionObject.requireSimpleFunction("singleton")
  }

  override val mapFactory: MapFactorySymbols =
    object : MapFactorySymbols() {
      override val factoryClass: IrClassSymbol by lazy {
        builtinsFinder.findClass(
          ClassId(metroRuntimeInternal.packageFqName, "MapFactory".asName()),
        )!!
      }

      private val factoryCompanionObject: IrClassSymbol by lazy {
        factoryClass.owner.companionObject()!!.symbol
      }

      override val builderFunction: IrSimpleFunctionSymbol by lazy {
        factoryCompanionObject.requireSimpleFunction("builder")
      }

      override val emptyFunction: IrSimpleFunctionSymbol by lazy {
        factoryCompanionObject.requireSimpleFunction("empty")
      }

      override val singletonFunction: IrSimpleFunctionSymbol by lazy {
        factoryCompanionObject.requireSimpleFunction("singleton")
      }
    }

  override val mapProviderFactory: MapFactorySymbols =
    object : MapFactorySymbols() {
      override val factoryClass: IrClassSymbol by lazy {
        builtinsFinder.findClass(
          ClassId(metroRuntimeInternal.packageFqName, "MapProviderFactory".asName()),
        )!!
      }

      private val factoryCompanionObject: IrClassSymbol by lazy {
        factoryClass.owner.companionObject()!!.symbol
      }

      override val builderFunction: IrSimpleFunctionSymbol by lazy {
        factoryCompanionObject.requireSimpleFunction("builder")
      }

      override val emptyFunction: IrSimpleFunctionSymbol by lazy {
        factoryCompanionObject.requireSimpleFunction("empty")
      }

      override val singletonFunction: IrSimpleFunctionSymbol by lazy {
        factoryCompanionObject.requireSimpleFunction("singleton")
      }
    }

  override val mapLazyFactory: MapFactorySymbols =
    object : MapFactorySymbols() {
      override val factoryClass: IrClassSymbol by lazy {
        builtinsFinder.findClass(
          ClassId(metroRuntimeInternal.packageFqName, "MapLazyFactory".asName()),
        )!!
      }

      private val factoryCompanionObject: IrClassSymbol by lazy {
        factoryClass.owner.companionObject()!!.symbol
      }

      override val builderFunction: IrSimpleFunctionSymbol by lazy {
        factoryCompanionObject.requireSimpleFunction("builder")
      }

      override val emptyFunction: IrSimpleFunctionSymbol by lazy {
        factoryCompanionObject.requireSimpleFunction("empty")
      }

      override val singletonFunction: IrSimpleFunctionSymbol by lazy {
        factoryCompanionObject.requireSimpleFunction("singleton")
      }
    }

  override val mapProviderLazyFactory: MapFactorySymbols =
    object : MapFactorySymbols() {
      override val factoryClass: IrClassSymbol by lazy {
        builtinsFinder.findClass(
          ClassId(metroRuntimeInternal.packageFqName, "MapProviderLazyFactory".asName()),
        )!!
      }

      private val factoryCompanionObject: IrClassSymbol by lazy {
        factoryClass.owner.companionObject()!!.symbol
      }

      override val builderFunction: IrSimpleFunctionSymbol by lazy {
        factoryCompanionObject.requireSimpleFunction("builder")
      }

      override val emptyFunction: IrSimpleFunctionSymbol by lazy {
        factoryCompanionObject.requireSimpleFunction("empty")
      }

      override val singletonFunction: IrSimpleFunctionSymbol by lazy {
        factoryCompanionObject.requireSimpleFunction("singleton")
      }
    }

  override val mapSuspendProviderFactory: MapFactorySymbols =
    object : MapFactorySymbols() {
      override val factoryClass: IrClassSymbol by lazy {
        builtinsFinder.findClass(
          ClassId(metroRuntimeInternal.packageFqName, "MapSuspendProviderFactory".asName()),
        )!!
      }

      private val factoryCompanionObject: IrClassSymbol by lazy {
        factoryClass.owner.companionObject()!!.symbol
      }

      override val builderFunction: IrSimpleFunctionSymbol by lazy {
        factoryCompanionObject.requireSimpleFunction("builder")
      }

      override val emptyFunction: IrSimpleFunctionSymbol by lazy {
        factoryCompanionObject.requireSimpleFunction("empty")
      }

      override val singletonFunction: IrSimpleFunctionSymbol by lazy {
        factoryCompanionObject.requireSimpleFunction("singleton")
      }
    }

  // MapFunctionFactory is JS-only — see runtime/src/jsMain/.../MapFunctionFactory.kt.
  // Only access these symbols from code paths gated on `platform.isJs()`. Non-empty function maps
  // use the provider map factories and providerMapAsFunctions instead.
  val mapFunctionFactory: IrClassSymbol by lazy {
    builtinsFinder.findClass(
      ClassId(metroRuntimeInternal.packageFqName, "MapFunctionFactory".asName()),
    )!!
  }

  private val mapFunctionFactoryCompanionObject: IrClassSymbol by lazy {
    mapFunctionFactory.owner.companionObject()!!.symbol
  }

  val mapFunctionFactoryBuilder: IrClassSymbol by lazy {
    mapFunctionFactory.owner.nestedClasses.first { it.name.asString() == "Builder" }.symbol
  }

  val mapFunctionFactoryBuilderFunction: IrSimpleFunctionSymbol by lazy {
    mapFunctionFactoryCompanionObject.requireSimpleFunction("builder")
  }

  val mapFunctionFactoryEmptyFunction: IrSimpleFunctionSymbol by lazy {
    mapFunctionFactoryCompanionObject.requireSimpleFunction("empty")
  }

  val mapFunctionFactoryBuilderBuildFunction: IrSimpleFunctionSymbol by lazy {
    mapFunctionFactoryBuilder.requireSimpleFunction("build")
  }
}

internal class JavaxSymbols(
  private val moduleFragment: IrModuleFragment,
  private val compatContext: CompatContext,
  private val builtinsFinder: CompatContext.DeclarationFinderCompat,
  delegate: FrameworkSymbols,
) : FrameworkSymbols by delegate {
  private val javaxInteropRuntime: IrPackageFragment by lazy {
    moduleFragment.createPackage(
      "${Symbols.StringNames.METRO_RUNTIME_PACKAGE}.interop.javax",
      compatContext,
    )
  }

  val javaxProvider: IrClassSymbol by lazy {
    builtinsFinder.findClass(ClassIds.JAVAX_PROVIDER_CLASS_ID)!!
  }

  val primitives = setOf(ClassIds.JAVAX_PROVIDER_CLASS_ID)

  override val canonicalProviderType: IrClassSymbol by lazy { javaxProvider }

  val asJavaxProvider by lazy {
    builtinsFinder
      .findFunctions(
        CallableId(
          javaxInteropRuntime.packageFqName,
          Symbols.StringNames.AS_JAVAX_PROVIDER.asName(),
        ),
      )
      .single()
  }

  val asMetroProvider by lazy {
    builtinsFinder
      .findFunctions(
        CallableId(
          javaxInteropRuntime.packageFqName,
          Symbols.StringNames.AS_METRO_PROVIDER.asName(),
        ),
      )
      .first()
  }

  object ClassIds {
    val JAVAX_PROVIDER_CLASS_ID = ClassId(FqName("javax.inject"), "Provider".asName())
  }
}

internal class JakartaSymbols(
  private val moduleFragment: IrModuleFragment,
  private val compatContext: CompatContext,
  private val builtinsFinder: CompatContext.DeclarationFinderCompat,
  delegate: FrameworkSymbols,
) : FrameworkSymbols by delegate {
  private val jakartaInteropRuntime: IrPackageFragment by lazy {
    moduleFragment.createPackage(
      "${Symbols.StringNames.METRO_RUNTIME_PACKAGE}.interop.jakarta",
      compatContext,
    )
  }

  val jakartaProvider: IrClassSymbol by lazy {
    builtinsFinder.findClass(ClassIds.JAKARTA_PROVIDER_CLASS_ID)!!
  }

  val primitives = setOf(ClassIds.JAKARTA_PROVIDER_CLASS_ID)

  override val canonicalProviderType: IrClassSymbol by lazy { jakartaProvider }

  val asJakartaProvider by lazy {
    builtinsFinder
      .findFunctions(
        CallableId(
          jakartaInteropRuntime.packageFqName,
          Symbols.StringNames.AS_JAKARTA_PROVIDER.asName(),
        ),
      )
      .single()
  }

  val asMetroProvider by lazy {
    builtinsFinder
      .findFunctions(
        CallableId(
          jakartaInteropRuntime.packageFqName,
          Symbols.StringNames.AS_METRO_PROVIDER.asName(),
        ),
      )
      .first()
  }

  object ClassIds {
    val JAKARTA_PROVIDER_CLASS_ID = ClassId(FqName("jakarta.inject"), "Provider".asName())
  }
}

internal class GuiceSymbols(
  private val moduleFragment: IrModuleFragment,
  private val compatContext: CompatContext,
  private val builtinsFinder: CompatContext.DeclarationFinderCompat,
  metroFrameworkSymbols: MetroFrameworkSymbols,
) : FrameworkSymbols by metroFrameworkSymbols {
  private val guiceInteropRuntime: IrPackageFragment by lazy {
    moduleFragment.createPackage(
      "${Symbols.StringNames.METRO_RUNTIME_PACKAGE}.interop.guice",
      compatContext,
    )
  }

  private val guiceInteropRuntimeInternal: IrPackageFragment by lazy {
    moduleFragment.createPackage(
      "${Symbols.StringNames.METRO_RUNTIME_PACKAGE}.interop.guice.internal",
      compatContext,
    )
  }

  val guiceDoubleCheckCompanionObject: IrClassSymbol by lazy {
    builtinsFinder
      .findClass(
        ClassId(guiceInteropRuntimeInternal.packageFqName, "GuiceInteropDoubleCheck".asName()),
      )!!
      .owner
      .companionObject()!!
      .symbol
  }

  val providerPrimitives =
    setOf(ClassIds.provider, JakartaSymbols.ClassIds.JAKARTA_PROVIDER_CLASS_ID)

  val primitives = providerPrimitives

  override val canonicalProviderType: IrClassSymbol by lazy {
    builtinsFinder.findClass(ClassIds.provider)!!
  }

  val asGuiceProvider by lazy {
    builtinsFinder
      .findFunctions(
        CallableId(
          guiceInteropRuntime.packageFqName,
          Symbols.StringNames.AS_GUICE_PROVIDER.asName(),
        ),
      )
      .single()
  }

  val asMetroProvider by lazy {
    builtinsFinder
      .findFunctions(
        CallableId(
          guiceInteropRuntime.packageFqName,
          Symbols.StringNames.AS_METRO_PROVIDER.asName(),
        ),
      )
      .first()
  }

  val asGuiceMembersInjector by lazy {
    builtinsFinder
      .findFunctions(
        CallableId(
          guiceInteropRuntime.packageFqName,
          Symbols.StringNames.AS_GUICE_MEMBERS_INJECTOR.asName(),
        ),
      )
      .first()
  }

  val asMetroMembersInjector by lazy {
    builtinsFinder
      .findFunctions(
        CallableId(
          guiceInteropRuntime.packageFqName,
          Symbols.StringNames.AS_METRO_MEMBERS_INJECTOR.asName(),
        ),
      )
      .first()
  }

  object FqNames {
    val guiceRuntimePackage = FqName("com.google.inject")
  }

  object ClassIds {
    val assisted =
      ClassId(FqNames.guiceRuntimePackage.child("assistedinject".asName()), Symbols.Names.Assisted)
    val provider = ClassId(FqNames.guiceRuntimePackage, Symbols.Names.ProviderClass)
    val module = ClassId(FqNames.guiceRuntimePackage, "Module".asName())
  }
}

internal class DaggerSymbols(
  private val moduleFragment: IrModuleFragment,
  private val compatContext: CompatContext,
  private val builtinsFinder: CompatContext.DeclarationFinderCompat,
) : BaseFrameworkSymbols() {
  lateinit var jakartaSymbols: JakartaSymbols

  private val daggerRuntimeInternal: IrPackageFragment by lazy {
    moduleFragment.createPackage("dagger.internal", compatContext)
  }

  private val daggerInteropRuntime: IrPackageFragment by lazy {
    moduleFragment.createPackage(
      "${Symbols.StringNames.METRO_RUNTIME_PACKAGE}.interop.dagger",
      compatContext,
    )
  }

  private val daggerInteropRuntimeInternal: IrPackageFragment by lazy {
    moduleFragment.createPackage(
      "${Symbols.StringNames.METRO_RUNTIME_PACKAGE}.interop.dagger.internal",
      compatContext,
    )
  }

  override val canonicalProviderType: IrClassSymbol by lazy {
    builtinsFinder.findClass(
      ClassId(daggerRuntimeInternal.packageFqName, Symbols.Names.ProviderClass),
    )!!
  }

  val providerPrimitives =
    setOf(
      ClassIds.DAGGER_INTERNAL_PROVIDER_CLASS_ID,
      JavaxSymbols.ClassIds.JAVAX_PROVIDER_CLASS_ID,
      JakartaSymbols.ClassIds.JAKARTA_PROVIDER_CLASS_ID,
    )

  val primitives = buildSet {
    addAll(providerPrimitives)
    add(ClassIds.DAGGER_LAZY_CLASS_ID)
    add(ClassIds.DAGGER_INTERNAL_SET_FACTORY_CLASS_ID)
    add(ClassIds.DAGGER_INTERNAL_MAP_PROVIDER_FACTORY_CLASS_ID)
    add(ClassIds.DAGGER_INTERNAL_MAP_FACTORY_CLASS_ID)
  }

  override val doubleCheck by lazy {
    builtinsFinder.findClass(
      ClassId(daggerInteropRuntimeInternal.packageFqName, "DaggerInteropDoubleCheck".asName()),
    )!!
  }

  override val doubleCheckLazy by lazy {
    // Use lazyFromDaggerProvider since the canonical provider type in Dagger mode is
    // dagger.internal.Provider
    doubleCheckCompanionObject.requireSimpleFunction("lazyFromDaggerProvider")
  }

  private val providerOfLazy: IrClassSymbol by lazy {
    builtinsFinder.findClass(
      ClassId(daggerRuntimeInternal.packageFqName, "ProviderOfLazy".asName()),
    )!!
  }

  override val providerOfLazyCreate: IrSimpleFunctionSymbol by lazy {
    providerOfLazy.requireSimpleFunction(Symbols.StringNames.CREATE)
  }

  override val setFactory: IrClassSymbol by lazy {
    builtinsFinder.findClass(ClassId(daggerRuntimeInternal.packageFqName, "SetFactory".asName()))!!
  }

  override val setFactoryBuilderFunction: IrSimpleFunctionSymbol by lazy {
    // Static function in this case
    setFactory.functions.first {
      it.owner.nonDispatchParameters.size == 1 && it.owner.name == Symbols.Names.builder
    }
  }

  override val setFactoryEmptyFunction: IrSimpleFunctionSymbol by lazy {
    setFactory.requireSimpleFunction("empty")
  }

  // Dagger's SetFactory has no singleton() — fall back to the builder path
  override val setFactorySingletonFunction: IrSimpleFunctionSymbol? = null

  override val mapFactory: MapFactorySymbols =
    object : MapFactorySymbols() {
      override val factoryClass: IrClassSymbol by lazy {
        builtinsFinder.findClass(
          ClassId(daggerRuntimeInternal.packageFqName, "MapFactory".asName()),
        )!!
      }

      override val builderFunction: IrSimpleFunctionSymbol by lazy {
        // Static function in this case
        factoryClass.functions.first {
          it.owner.nonDispatchParameters.size == 1 && it.owner.name == Symbols.Names.builder
        }
      }

      override val emptyFunction: IrSimpleFunctionSymbol by lazy {
        // Static function in this case
        factoryClass.requireSimpleFunction("empty")
      }

      override val singletonFunction: IrSimpleFunctionSymbol? = null
    }

  override val mapProviderFactory: MapFactorySymbols =
    object : MapFactorySymbols() {
      override val factoryClass: IrClassSymbol by lazy {
        builtinsFinder.findClass(
          ClassId(daggerRuntimeInternal.packageFqName, "MapProviderFactory".asName()),
        )!!
      }

      override val builderFunction: IrSimpleFunctionSymbol by lazy {
        // Static function in this case
        factoryClass.functions.first {
          it.owner.nonDispatchParameters.size == 1 && it.owner.name == Symbols.Names.builder
        }
      }

      override val emptyFunction: IrSimpleFunctionSymbol? = null

      override val singletonFunction: IrSimpleFunctionSymbol? = null
    }

  override val mapLazyFactory: MapFactorySymbols =
    object : MapFactorySymbols() {
      override val factoryClass: IrClassSymbol by lazy {
        builtinsFinder.findClass(
          ClassId(daggerRuntimeInternal.packageFqName, "MapLazyFactory".asName()),
        )!!
      }

      override val builderFunction: IrSimpleFunctionSymbol by lazy {
        factoryClass.requireSimpleFunction("builder")
      }

      override val emptyFunction: IrSimpleFunctionSymbol by lazy {
        factoryClass.requireSimpleFunction("empty")
      }

      override val singletonFunction: IrSimpleFunctionSymbol? = null
    }

  override val mapProviderLazyFactory: MapFactorySymbols =
    object : MapFactorySymbols() {
      override val factoryClass: IrClassSymbol by lazy {
        builtinsFinder.findClass(
          ClassId(daggerRuntimeInternal.packageFqName, "MapProviderLazyFactory".asName()),
        )!!
      }

      override val builderFunction: IrSimpleFunctionSymbol by lazy {
        factoryClass.requireSimpleFunction("builder")
      }

      override val emptyFunction: IrSimpleFunctionSymbol by lazy {
        factoryClass.requireSimpleFunction("empty")
      }

      override val singletonFunction: IrSimpleFunctionSymbol? = null
    }

  // Dagger has no SuspendProvider concept, use Metro's runtime version
  override val mapSuspendProviderFactory: MapFactorySymbols =
    object : MapFactorySymbols() {
      override val factoryClass: IrClassSymbol by lazy {
        builtinsFinder.findClass(
          ClassId(FqName("dev.zacsweers.metro.internal"), "MapSuspendProviderFactory".asName()),
        )!!
      }

      override val builderFunction: IrSimpleFunctionSymbol by lazy {
        factoryClass.owner.companionObject()!!.symbol.requireSimpleFunction("builder")
      }

      override val emptyFunction: IrSimpleFunctionSymbol by lazy {
        factoryClass.owner.companionObject()!!.symbol.requireSimpleFunction("empty")
      }

      override val singletonFunction: IrSimpleFunctionSymbol by lazy {
        factoryClass.owner.companionObject()!!.symbol.requireSimpleFunction("singleton")
      }
    }

  val daggerLazy: IrClassSymbol by lazy {
    builtinsFinder.findClass(ClassIds.DAGGER_LAZY_CLASS_ID)!!
  }

  val asDaggerInternalProvider by lazy {
    builtinsFinder
      .findFunctions(
        CallableId(
          daggerInteropRuntimeInternal.packageFqName,
          Symbols.StringNames.AS_DAGGER_INTERNAL_PROVIDER.asName(),
        ),
      )
      .single()
  }

  val asDaggerMembersInjector by lazy {
    builtinsFinder
      .findFunctions(
        CallableId(
          daggerInteropRuntime.packageFqName,
          Symbols.StringNames.AS_DAGGER_MEMBERS_INJECTOR.asName(),
        ),
      )
      .first()
  }

  val asMetroMembersInjector by lazy {
    builtinsFinder
      .findFunctions(
        CallableId(
          daggerInteropRuntime.packageFqName,
          Symbols.StringNames.AS_METRO_MEMBERS_INJECTOR.asName(),
        ),
      )
      .first()
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
    val DAGGER_MEMBERS_INJECTOR = ClassId(daggerRuntimePackageFqName, "MembersInjector".asName())
    val DAGGER_INTERNAL_PROVIDER_CLASS_ID =
      ClassId(daggerInternalPackageFqName, Symbols.Names.ProviderClass)
    val DAGGER_INTERNAL_SET_FACTORY_CLASS_ID =
      ClassId(daggerInternalPackageFqName, "SetFactory".asName())
    val DAGGER_INTERNAL_MAP_PROVIDER_FACTORY_CLASS_ID =
      ClassId(daggerInternalPackageFqName, "MapProviderFactory".asName())
    val DAGGER_INTERNAL_MAP_FACTORY_CLASS_ID =
      ClassId(daggerInternalPackageFqName, "MapFactory".asName())
    val DAGGER_MULTIBINDS = ClassId(daggerMultibindsPackageFqName, "Multibinds".asName())
    val DAGGER_ASSISTED_INJECT = ClassId(daggerAssistedPackageFqName, "AssistedInject".asName())
    val DAGGER_LAZY_CLASS_KEY = ClassId(daggerMultibindsPackageFqName, "LazyClassKey".asName())
    val DAGGER_INJECTED_FIELD_SIGNATURE =
      ClassId(daggerInternalPackageFqName, "InjectedFieldSignature".asName())
  }
}
