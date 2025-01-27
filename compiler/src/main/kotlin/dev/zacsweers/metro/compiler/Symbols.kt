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
package dev.zacsweers.metro.compiler

import dev.zacsweers.metro.compiler.Symbols.FqNames.kotlinCollectionsPackageFqn
import dev.zacsweers.metro.compiler.Symbols.StringNames.METRO_RUNTIME_INTERNAL_PACKAGE
import dev.zacsweers.metro.compiler.Symbols.StringNames.METRO_RUNTIME_PACKAGE
import dev.zacsweers.metro.compiler.ir.requireSimpleFunction
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.builtins.StandardNames
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrPackageFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.createEmptyExternalPackageFragment
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.explicitParametersCount
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
) {

  object StringNames {
    const val CREATE = "create"
    const val FACTORY = "factory"
    const val INJECT_MEMBERS = "injectMembers"
    const val INVOKE = "invoke"
    const val METRO_FACTORY = "\$\$MetroFactory"
    const val METRO_HINTS_PACKAGE = "metro.hints"
    const val METRO_IMPL = "\$\$Impl"
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
  }

  // TODO replace with StandardClassIds
  object ClassIds {
    val anyClass = StandardClassIds.Any
    val jsExportIgnore = JsStandardClassIds.Annotations.JsExportIgnore
    val composable = ClassId(FqNames.composeRuntime, "Composable".asName())
    val stable = ClassId(FqNames.composeRuntime, "Stable".asName())
    val metroBinds = ClassId(FqNames.metroRuntimePackage, Names.bindsClassName)
    val metroInject = ClassId(FqNames.metroRuntimePackage, "Inject".asName())
    val metroIntoSet = ClassId(FqNames.metroRuntimePackage, "IntoSet".asName())
    val metroIntoMap = ClassId(FqNames.metroRuntimePackage, "IntoMap".asName())
    val metroFactory = ClassId(FqNames.metroRuntimeInternalPackage, Names.factoryClassName)
    val metroProvider = ClassId(FqNames.metroRuntimePackage, Names.providerClassName)
    val metroProvides = ClassId(FqNames.metroRuntimePackage, "Provides".asName())
    val metroOrigin = ClassId(FqNames.metroRuntimeInternalPackage, "Origin".asName())
    val metroInjectedFunctionClass =
      ClassId(FqNames.metroRuntimeInternalPackage, "InjectedFunctionClass".asName())
    val membersInjector = ClassId(FqNames.metroRuntimePackage, Names.membersInjector)
    val lazy = StandardClassIds.byName("Lazy")
    val map = StandardClassIds.Map
    val set = StandardClassIds.Set
  }

  object Names {
    val bindsClassName = Name.identifier("Binds")
    val create = StringNames.CREATE.asName()
    val delegateFactory = Name.identifier("delegateFactory")
    val factoryClassName = Name.identifier("Factory")
    val factoryFunctionName = StringNames.FACTORY.asName()
    val instance = Name.identifier("instance")
    val injectMembers = Name.identifier(StringNames.INJECT_MEMBERS)
    val invoke = Name.identifier(StringNames.INVOKE)
    val metroFactory = Name.identifier(StringNames.METRO_FACTORY)
    val metroGraph = Name.identifier("\$\$MetroGraph")
    val metroImpl = StringNames.METRO_IMPL.asName()
    val metroMembersInjector = Name.identifier("\$\$MetroMembersInjector")
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

  private val metroRuntime: IrPackageFragment by lazy { createPackage(METRO_RUNTIME_PACKAGE) }
  private val metroRuntimeInternal: IrPackageFragment by lazy {
    createPackage(METRO_RUNTIME_INTERNAL_PACKAGE)
  }
  private val stdlib: IrPackageFragment by lazy { createPackage(kotlinPackageFqn.asString()) }
  private val stdlibCollections: IrPackageFragment by lazy {
    createPackage(kotlinCollectionsPackageFqn.asString())
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
  val instanceFactoryCompanionObject by lazy { instanceFactory.owner.companionObject()!!.symbol }
  val instanceFactoryCreate: IrFunctionSymbol by lazy {
    instanceFactoryCompanionObject.requireSimpleFunction(StringNames.CREATE)
  }

  val metroProvider: IrClassSymbol by lazy {
    pluginContext.referenceClass(
      ClassId(metroRuntime.packageFqName, Name.identifier("Provider"))
    )!!
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

  val bindsInstanceAnnotations
    get() = classIds.bindsInstanceAnnotations

  val assistedAnnotations
    get() = classIds.assistedAnnotations

  val assistedFactoryAnnotations
    get() = classIds.assistedFactoryAnnotations

  val providerTypes
    get() = classIds.providerTypes

  val lazyTypes
    get() = classIds.lazyTypes

  private fun createPackage(packageName: String): IrPackageFragment =
    createEmptyExternalPackageFragment(moduleFragment.descriptor, FqName(packageName))
}
