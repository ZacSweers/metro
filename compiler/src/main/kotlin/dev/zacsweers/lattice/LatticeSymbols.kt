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
import org.jetbrains.kotlin.ir.util.getSimpleFunction
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

@OptIn(UnsafeDuringIrConstructionAPI::class)
internal class LatticeSymbols(
  private val moduleFragment: IrModuleFragment,
  pluginContext: IrPluginContext,
) {

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

  val doubleCheck: IrClassSymbol by lazy {
    pluginContext.referenceClass(
      ClassId(latticeRuntimeInternal.packageFqName, Name.identifier("DoubleCheck"))
    )!!
  }
  val doubleCheckCompanionObject by lazy { doubleCheck.owner.companionObject()!!.symbol }
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

  val jvmStatic: IrClassSymbol by lazy {
    pluginContext.referenceClass(ClassId(stdlibJvm.packageFqName, Name.identifier("JvmStatic")))!!
  }

  val componentAnnotations = setOf(latticeComponent.owner.classIdOrFail)
  val injectAnnotations = setOf(latticeInject.owner.classIdOrFail)
  val qualifierAnnotations = setOf(latticeQualifier.owner.classIdOrFail)
  val scopeAnnotations = setOf(latticeScope.owner.classIdOrFail)
  val providesAnnotations = setOf(latticeProvides.owner.classIdOrFail)
  // TODO
  val assistedAnnotations = setOf<ClassId>()
  val providerTypes = setOf(latticeProvider.owner.classIdOrFail)
  val lazyTypes = setOf(stdlibLazy.owner.classIdOrFail)

  protected fun createPackage(packageName: String): IrPackageFragment =
    createEmptyExternalPackageFragment(moduleFragment.descriptor, FqName(packageName))
}
