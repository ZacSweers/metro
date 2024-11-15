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
import org.jetbrains.kotlin.ir.util.classIdOrFail
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
) {

  object ClassIds {
    val AnyClass = ClassId(kotlinPackageFqn, Name.identifier("Any"))
    val PublishedApi = ClassId(kotlinPackageFqn, Name.identifier("PublishedApi"))
  }

  object Names {
    val CompanionObject = Name.identifier("Companion")
    val Factory = Name.identifier("Factory")
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

  val componentAnnotations by lazy { setOf(latticeComponent.owner.classIdOrFail) }
  val componentFactoryAnnotations by lazy { setOf(latticeComponentFactory.owner.classIdOrFail) }
  val injectAnnotations by lazy { setOf(latticeInject.owner.classIdOrFail) }
  val qualifierAnnotations by lazy { setOf(latticeQualifier.owner.classIdOrFail) }
  val scopeAnnotations by lazy { setOf(latticeScope.owner.classIdOrFail) }
  val providesAnnotations by lazy { setOf(latticeProvides.owner.classIdOrFail) }
  // TODO
  val assistedAnnotations by lazy { setOf<ClassId>() }
  val providerTypes by lazy { setOf(latticeProvider.owner.classIdOrFail) }
  val lazyTypes by lazy { setOf(stdlibLazy.owner.classIdOrFail) }

  protected fun createPackage(packageName: String): IrPackageFragment =
    createEmptyExternalPackageFragment(moduleFragment.descriptor, FqName(packageName))
}
