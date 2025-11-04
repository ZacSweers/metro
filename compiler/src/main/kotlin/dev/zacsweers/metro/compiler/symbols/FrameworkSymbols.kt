// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.symbols

import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.ir.requireSimpleFunction
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
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

internal sealed class FrameworkSymbols {
  protected abstract val doubleCheck: IrClassSymbol

  val doubleCheckCompanionObject by lazy { doubleCheck.owner.companionObject()!!.symbol }
  val doubleCheckProvider by lazy { doubleCheckCompanionObject.requireSimpleFunction("provider") }

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

  abstract val canonicalProviderType: IrClassSymbol

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

internal class MetroFrameworkSymbols(
  private val metroRuntimeInternal: IrPackageFragment,
  private val pluginContext: IrPluginContext,
) : FrameworkSymbols() {

  override val canonicalProviderType: IrClassSymbol by lazy {
    pluginContext.referenceClass(Symbols.ClassIds.metroProvider)!!
  }

  override val doubleCheck by lazy {
    pluginContext.referenceClass(
      ClassId(metroRuntimeInternal.packageFqName, "DoubleCheck".asName())
    )!!
  }

  val doubleCheckLazy by lazy { doubleCheckCompanionObject.requireSimpleFunction("lazy") }

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
}

internal class DaggerSymbols(
  private val moduleFragment: IrModuleFragment,
  private val pluginContext: IrPluginContext,
) : FrameworkSymbols() {

  private val daggerRuntimeInternal: IrPackageFragment by lazy {
    moduleFragment.createPackage("dagger.internal")
  }

  private val daggerInteropRuntime: IrPackageFragment by lazy {
    moduleFragment.createPackage("dev.zacsweers.metro.interop.dagger")
  }

  private val daggerInteropRuntimeInternal: IrPackageFragment by lazy {
    moduleFragment.createPackage("dev.zacsweers.metro.interop.dagger.internal")
  }

  override val canonicalProviderType: IrClassSymbol by lazy {
    pluginContext.referenceClass(
      ClassId(daggerRuntimeInternal.packageFqName, Symbols.Names.ProviderClass)
    )!!
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

  override val setFactory: IrClassSymbol by lazy {
    pluginContext.referenceClass(
      ClassId(daggerRuntimeInternal.packageFqName, "SetFactory".asName())
    )!!
  }

  override val setFactoryBuilderFunction: IrSimpleFunctionSymbol by lazy {
    // Static function in this case
    setFactory.functions.first {
      it.owner.nonDispatchParameters.size == 1 && it.owner.name == Symbols.Names.builder
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
      it.owner.nonDispatchParameters.size == 1 && it.owner.name == Symbols.Names.builder
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
      it.owner.nonDispatchParameters.size == 1 && it.owner.name == Symbols.Names.builder
    }
  }

  override val mapProviderFactoryEmptyFunction: IrSimpleFunctionSymbol? = null

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
          Symbols.StringNames.AS_DAGGER_INTERNAL_PROVIDER.asName(),
        )
      )
      .single()
  }

  val asJavaxProvider by lazy {
    pluginContext
      .referenceFunctions(
        CallableId(
          daggerInteropRuntime.packageFqName,
          Symbols.StringNames.AS_JAVAX_PROVIDER.asName(),
        )
      )
      .single()
  }

  val asJakartaProvider by lazy {
    pluginContext
      .referenceFunctions(
        CallableId(
          daggerInteropRuntime.packageFqName,
          Symbols.StringNames.AS_JAKARTA_PROVIDER.asName(),
        )
      )
      .single()
  }

  val asMetroProvider by lazy {
    pluginContext
      .referenceFunctions(
        CallableId(
          daggerInteropRuntime.packageFqName,
          Symbols.StringNames.AS_METRO_PROVIDER.asName(),
        )
      )
      .first()
  }

  val asDaggerMembersInjector by lazy {
    pluginContext
      .referenceFunctions(
        CallableId(
          daggerInteropRuntime.packageFqName,
          Symbols.StringNames.AS_DAGGER_MEMBERS_INJECTOR.asName(),
        )
      )
      .first()
  }

  val asMetroMembersInjector by lazy {
    pluginContext
      .referenceFunctions(
        CallableId(
          daggerInteropRuntime.packageFqName,
          Symbols.StringNames.AS_METRO_MEMBERS_INJECTOR.asName(),
        )
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
    val DAGGER_INTERNAL_PROVIDER_CLASS_ID =
      ClassId(daggerInternalPackageFqName, Symbols.Names.ProviderClass)
    val DAGGER_MULTIBINDS = ClassId(daggerMultibindsPackageFqName, "Multibinds".asName())
    val DAGGER_ASSISTED_INJECT = ClassId(daggerAssistedPackageFqName, "AssistedInject".asName())
    val DAGGER_INJECTED_FIELD_SIGNATURE =
      ClassId(daggerInternalPackageFqName, "InjectedFieldSignature".asName())
    val JAVAX_PROVIDER_CLASS_ID = ClassId(FqName("javax.inject"), "Provider".asName())
    val JAKARTA_PROVIDER_CLASS_ID = ClassId(FqName("jakarta.inject"), "Provider".asName())
  }
}
