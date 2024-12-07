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
package dev.zacsweers.lattice

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrPackageFragment
import org.jetbrains.kotlin.ir.declarations.createEmptyExternalPackageFragment
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.companionObject
import org.jetbrains.kotlin.ir.util.constructors
import org.jetbrains.kotlin.ir.util.explicitParametersCount
import org.jetbrains.kotlin.ir.util.getSimpleFunction
import org.jetbrains.kotlin.ir.util.kotlinPackageFqn
import org.jetbrains.kotlin.ir.util.nestedClasses
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

@OptIn(UnsafeDuringIrConstructionAPI::class)
internal class LatticeSymbols(
  private val moduleFragment: IrModuleFragment,
  val pluginContext: IrPluginContext,
  val latticeClassIds: LatticeClassIds,
) {

  object ClassIds {
    val AnyClass = ClassId(kotlinPackageFqn, Name.identifier("Any"))
    val PublishedApi = ClassId(kotlinPackageFqn, Name.identifier("PublishedApi"))
  }

  object Names {
    val CompanionObject = Name.identifier("Companion")
    val Factory = Name.identifier("Factory")
    // Used in @Assisted.value
    val Value = Name.identifier("value")
  }

  // TODO use more constants from StandardNames.FqNames

  private val latticeRuntime: IrPackageFragment by lazy { createPackage("dev.zacsweers.lattice") }
  private val latticeRuntimeInternal: IrPackageFragment by lazy {
    createPackage("dev.zacsweers.lattice.internal")
  }
  private val latticeAnnotations: IrPackageFragment by lazy {
    createPackage("dev.zacsweers.lattice.annotations")
  }
  private val stdlib: IrPackageFragment by lazy { createPackage("kotlin") }
  private val stdlibJvm: IrPackageFragment by lazy { createPackage("kotlin.jvm") }

  val anyConstructor by lazy { pluginContext.irBuiltIns.anyClass.owner.constructors.single() }

  val latticeCreateComponent: IrSimpleFunctionSymbol by lazy {
    pluginContext
      .referenceFunctions(
        CallableId(latticeRuntime.packageFqName, Name.identifier("createComponent"))
      )
      .single()
  }

  val latticeCreateComponentFactory: IrSimpleFunctionSymbol by lazy {
    pluginContext
      .referenceFunctions(
        CallableId(latticeRuntime.packageFqName, Name.identifier("createComponentFactory"))
      )
      .single()
  }

  val latticeInject: IrClassSymbol by lazy {
    pluginContext.referenceClass(
      ClassId(latticeAnnotations.packageFqName, Name.identifier("Inject"))
    )!!
  }

  val latticeProvides: IrClassSymbol by lazy {
    pluginContext.referenceClass(
      ClassId(latticeAnnotations.packageFqName, Name.identifier("Provides"))
    )!!
  }

  val latticeQualifier: IrClassSymbol by lazy {
    pluginContext.referenceClass(
      ClassId(latticeAnnotations.packageFqName, Name.identifier("Qualifier"))
    )!!
  }

  val latticeScope: IrClassSymbol by lazy {
    pluginContext.referenceClass(
      ClassId(latticeAnnotations.packageFqName, Name.identifier("Scope"))
    )!!
  }

  val latticeComponent: IrClassSymbol by lazy {
    pluginContext.referenceClass(
      ClassId(latticeAnnotations.packageFqName, Name.identifier("Component"))
    )!!
  }

  val latticeBindsInstance: IrClassSymbol by lazy {
    pluginContext.referenceClass(
      ClassId(latticeAnnotations.packageFqName, Name.identifier("BindsInstance"))
    )!!
  }
  val latticeComponentFactory by lazy {
    latticeComponent.owner.nestedClasses
      .single { klass -> klass.name.asString() == "Factory" }
      .symbol
  }

  val doubleCheck: IrClassSymbol by lazy {
    pluginContext.referenceClass(
      ClassId(latticeRuntimeInternal.packageFqName, Name.identifier("DoubleCheck"))
    )!!
  }
  val doubleCheckCompanionObject by lazy { doubleCheck.owner.companionObject()!!.symbol }
  val doubleCheckProvider by lazy { doubleCheckCompanionObject.getSimpleFunction("provider")!! }
  val doubleCheckLazy by lazy { doubleCheckCompanionObject.getSimpleFunction("lazy")!! }

  val providerOfLazy: IrClassSymbol by lazy {
    pluginContext.referenceClass(
      ClassId(latticeRuntimeInternal.packageFqName, Name.identifier("ProviderOfLazy"))
    )!!
  }
  val providerOfLazyCompanionObject by lazy { providerOfLazy.owner.companionObject()!!.symbol }
  val providerOfLazyCreate: IrFunctionSymbol by lazy {
    providerOfLazyCompanionObject.getSimpleFunction("create")!!
  }

  val instanceFactory: IrClassSymbol by lazy {
    pluginContext.referenceClass(
      ClassId(latticeRuntimeInternal.packageFqName, Name.identifier("InstanceFactory"))
    )!!
  }
  val instanceFactoryCompanionObject by lazy { instanceFactory.owner.companionObject()!!.symbol }
  val instanceFactoryCreate: IrFunctionSymbol by lazy {
    instanceFactoryCompanionObject.getSimpleFunction("create")!!
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
    latticeProvider.getSimpleFunction("invoke")!!
  }

  val latticeFactory: IrClassSymbol by lazy {
    pluginContext.referenceClass(
      ClassId(latticeRuntimeInternal.packageFqName, Name.identifier("Factory"))
    )!!
  }

  val stdlibLazy: IrClassSymbol by lazy {
    pluginContext.referenceClass(ClassId(stdlib.packageFqName, Name.identifier("Lazy")))!!
  }

  val stdlibCheckNotNull: IrFunctionSymbol by lazy {
    pluginContext
      .referenceFunctions(CallableId(stdlib.packageFqName, Name.identifier("checkNotNull")))
      .single { it.owner.explicitParametersCount == 2 }
  }

  val jvmStatic: IrClassSymbol by lazy {
    pluginContext.referenceClass(ClassId(stdlibJvm.packageFqName, Name.identifier("JvmStatic")))!!
  }

  val componentAnnotations
    get() = latticeClassIds.componentAnnotations

  val componentFactoryAnnotations
    get() = latticeClassIds.componentFactoryAnnotations

  val injectAnnotations
    get() = latticeClassIds.injectAnnotations + latticeClassIds.assistedInjectAnnotations

  val qualifierAnnotations
    get() = latticeClassIds.qualifierAnnotations

  val scopeAnnotations
    get() = latticeClassIds.scopeAnnotations

  val providesAnnotations
    get() = latticeClassIds.providesAnnotations

  val bindsInstanceAnnotations
    get() = latticeClassIds.bindsInstanceAnnotations

  val assistedAnnotations
    get() = latticeClassIds.assistedAnnotations

  val assistedInjectAnnotations
    get() = latticeClassIds.assistedInjectAnnotations

  val assistedFactoryAnnotations
    get() = latticeClassIds.assistedFactoryAnnotations

  val providerTypes
    get() = latticeClassIds.providerTypes

  val lazyTypes
    get() = latticeClassIds.lazyTypes

  protected fun createPackage(packageName: String): IrPackageFragment =
    createEmptyExternalPackageFragment(moduleFragment.descriptor, FqName(packageName))
}
