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
package dev.zacsweers.lattice.compiler

import dev.zacsweers.lattice.compiler.LatticeSymbols.FqNames.kotlinCollectionsPackageFqn
import dev.zacsweers.lattice.compiler.LatticeSymbols.StringNames.LATTICE_RUNTIME_INTERNAL_PACKAGE
import dev.zacsweers.lattice.compiler.LatticeSymbols.StringNames.LATTICE_RUNTIME_PACKAGE
import dev.zacsweers.lattice.compiler.ir.requireSimpleFunction
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
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
import org.jetbrains.kotlin.name.Name

internal class LatticeSymbols(
  private val moduleFragment: IrModuleFragment,
  val pluginContext: IrPluginContext,
  val latticeClassIds: LatticeClassIds,
) {

  object StringNames {
    const val CREATE = "create"
    const val FACTORY = "factory"
    const val INJECT_MEMBERS = "injectMembers"
    const val INVOKE = "invoke"
    const val LATTICE_FACTORY = "\$\$LatticeFactory"
    const val LATTICE_HINTS_PACKAGE = "lattice.hints"
    const val LATTICE_IMPL = "\$\$Impl"
    const val LATTICE_RUNTIME_INTERNAL_PACKAGE = "dev.zacsweers.lattice.internal"
    const val LATTICE_RUNTIME_PACKAGE = "dev.zacsweers.lattice"
    const val NEW_INSTANCE = "newInstance"
    const val PROVIDER = "provider"
  }

  object FqNames {
    val kotlinCollectionsPackageFqn = FqName("kotlin.collections")
    val latticeRuntimePackage = FqName(LATTICE_RUNTIME_PACKAGE)
    val latticeRuntimeInternalPackage = FqName(LATTICE_RUNTIME_INTERNAL_PACKAGE)
    val latticeHintsPackage = FqName(StringNames.LATTICE_HINTS_PACKAGE)
  }

  object ClassIds {
    val anyClass = ClassId(kotlinPackageFqn, Name.identifier("Any"))
    val jsExportIgnore = ClassId.fromString("kotlin/js/JsExport.Ignore")
    val latticeFactory = ClassId(FqNames.latticeRuntimeInternalPackage, Names.factoryClassName)
    val latticeProvider = ClassId(FqNames.latticeRuntimePackage, Names.providerClassName)
    val membersInjector = ClassId(FqNames.latticeRuntimePackage, Names.membersInjector)
    val lazy = ClassId(kotlinPackageFqn, Name.identifier("Lazy"))
    val map = ClassId(kotlinCollectionsPackageFqn, Name.identifier("Map"))
    val set = ClassId(kotlinCollectionsPackageFqn, Name.identifier("Set"))
  }

  object Names {
    val create = StringNames.CREATE.asName()
    val delegateFactory = Name.identifier("delegateFactory")
    val factoryClassName = Name.identifier("Factory")
    val factoryFunctionName = StringNames.FACTORY.asName()
    val instance = Name.identifier("instance")
    val injectMembers = Name.identifier(StringNames.INJECT_MEMBERS)
    val invoke = Name.identifier(StringNames.INVOKE)
    val latticeFactory = Name.identifier(StringNames.LATTICE_FACTORY)
    val latticeGraph = Name.identifier("\$\$LatticeGraph")
    val latticeImpl = StringNames.LATTICE_IMPL.asName()
    val latticeMembersInjector = Name.identifier("\$\$LatticeMembersInjector")
    val membersInjector = Name.identifier("MembersInjector")
    val newInstanceFunction = StringNames.NEW_INSTANCE.asName()
    val providerClassName = Name.identifier("Provider")
    val providerFunction = Name.identifier(StringNames.PROVIDER)
    val receiver = Name.identifier("receiver")
    // Used in @Assisted.value
    val value = Name.identifier("value")

    val latticeNames = setOf(latticeFactory, latticeGraph, latticeImpl, latticeMembersInjector)
  }

  // TODO use more constants from StandardNames.FqNames

  private val latticeRuntime: IrPackageFragment by lazy { createPackage(LATTICE_RUNTIME_PACKAGE) }
  private val latticeRuntimeInternal: IrPackageFragment by lazy {
    createPackage(LATTICE_RUNTIME_INTERNAL_PACKAGE)
  }
  private val stdlib: IrPackageFragment by lazy { createPackage(kotlinPackageFqn.asString()) }
  private val stdlibCollections: IrPackageFragment by lazy {
    createPackage(kotlinCollectionsPackageFqn.asString())
  }

  val latticeCreateGraph: IrSimpleFunctionSymbol by lazy {
    pluginContext
      .referenceFunctions(CallableId(latticeRuntime.packageFqName, Name.identifier("createGraph")))
      .single()
  }

  val latticeCreateGraphFactory: IrSimpleFunctionSymbol by lazy {
    pluginContext
      .referenceFunctions(
        CallableId(latticeRuntime.packageFqName, Name.identifier("createGraphFactory"))
      )
      .single()
  }

  private val doubleCheck: IrClassSymbol by lazy {
    pluginContext.referenceClass(
      ClassId(latticeRuntimeInternal.packageFqName, Name.identifier("DoubleCheck"))
    )!!
  }
  val doubleCheckCompanionObject by lazy { doubleCheck.owner.companionObject()!!.symbol }
  val doubleCheckProvider by lazy { doubleCheckCompanionObject.requireSimpleFunction("provider") }
  val doubleCheckLazy by lazy { doubleCheckCompanionObject.requireSimpleFunction("lazy") }

  private val providerOfLazy: IrClassSymbol by lazy {
    pluginContext.referenceClass(
      ClassId(latticeRuntimeInternal.packageFqName, Name.identifier("ProviderOfLazy"))
    )!!
  }
  val providerOfLazyCompanionObject by lazy { providerOfLazy.owner.companionObject()!!.symbol }
  val providerOfLazyCreate: IrFunctionSymbol by lazy {
    providerOfLazyCompanionObject.requireSimpleFunction(StringNames.CREATE)
  }

  private val instanceFactory: IrClassSymbol by lazy {
    pluginContext.referenceClass(
      ClassId(latticeRuntimeInternal.packageFqName, Name.identifier("InstanceFactory"))
    )!!
  }
  val instanceFactoryCompanionObject by lazy { instanceFactory.owner.companionObject()!!.symbol }
  val instanceFactoryCreate: IrFunctionSymbol by lazy {
    instanceFactoryCompanionObject.requireSimpleFunction(StringNames.CREATE)
  }

  val latticeProvider: IrClassSymbol by lazy {
    pluginContext.referenceClass(
      ClassId(latticeRuntime.packageFqName, Name.identifier("Provider"))
    )!!
  }

  val latticeProviderFunction: IrSimpleFunctionSymbol by lazy {
    pluginContext
      .referenceFunctions(CallableId(latticeRuntime.packageFqName, Name.identifier("provider")))
      .single()
  }

  val providerInvoke: IrSimpleFunctionSymbol by lazy {
    latticeProvider.requireSimpleFunction("invoke")
  }

  private val latticeDelegateFactory: IrClassSymbol by lazy {
    pluginContext.referenceClass(
      ClassId(latticeRuntimeInternal.packageFqName, Name.identifier("DelegateFactory"))
    )!!
  }

  val latticeDelegateFactoryConstructor: IrConstructorSymbol by lazy {
    latticeDelegateFactory.constructors.single()
  }

  val latticeDelegateFactoryCompanion: IrClassSymbol by lazy {
    latticeDelegateFactory.owner.companionObject()!!.symbol
  }

  val latticeDelegateFactorySetDelegate: IrFunctionSymbol by lazy {
    latticeDelegateFactoryCompanion.requireSimpleFunction("setDelegate")
  }

  val latticeMembersInjector: IrClassSymbol by lazy {
    pluginContext.referenceClass(
      ClassId(latticeRuntime.packageFqName, Name.identifier("MembersInjector"))
    )!!
  }

  val latticeMembersInjectors: IrClassSymbol by lazy {
    pluginContext.referenceClass(
      ClassId(latticeRuntimeInternal.packageFqName, Name.identifier("MembersInjectors"))
    )!!
  }

  val latticeMembersInjectorsNoOp: IrSimpleFunctionSymbol by lazy {
    latticeMembersInjectors.requireSimpleFunction("noOp")
  }

  val latticeFactory: IrClassSymbol by lazy {
    pluginContext.referenceClass(
      ClassId(latticeRuntimeInternal.packageFqName, Name.identifier("Factory"))
    )!!
  }

  private val setFactory: IrClassSymbol by lazy {
    pluginContext.referenceClass(
      ClassId(latticeRuntimeInternal.packageFqName, Name.identifier("SetFactory"))
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
      ClassId(latticeRuntimeInternal.packageFqName, Name.identifier("MapFactory"))
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
      ClassId(latticeRuntimeInternal.packageFqName, Name.identifier("MapProviderFactory"))
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
    get() = latticeClassIds.dependencyGraphAnnotations

  val dependencyGraphFactoryAnnotations
    get() = latticeClassIds.dependencyGraphFactoryAnnotations

  val injectAnnotations
    get() = latticeClassIds.injectAnnotations

  val qualifierAnnotations
    get() = latticeClassIds.qualifierAnnotations

  val scopeAnnotations
    get() = latticeClassIds.scopeAnnotations

  val mapKeyAnnotations
    get() = latticeClassIds.mapKeyAnnotations

  val bindsInstanceAnnotations
    get() = latticeClassIds.bindsInstanceAnnotations

  val assistedAnnotations
    get() = latticeClassIds.assistedAnnotations

  val assistedFactoryAnnotations
    get() = latticeClassIds.assistedFactoryAnnotations

  val providerTypes
    get() = latticeClassIds.providerTypes

  val lazyTypes
    get() = latticeClassIds.lazyTypes

  private fun createPackage(packageName: String): IrPackageFragment =
    createEmptyExternalPackageFragment(moduleFragment.descriptor, FqName(packageName))
}
