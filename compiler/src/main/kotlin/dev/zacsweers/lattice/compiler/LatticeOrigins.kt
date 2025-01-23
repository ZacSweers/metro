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

import dev.zacsweers.lattice.compiler.fir.LatticeKeys
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin

internal object LatticeOrigins {
  val Default: IrDeclarationOrigin = IrDeclarationOrigin.GeneratedByPlugin(LatticeKeys.Default)
  val InstanceParameter: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(LatticeKeys.InstanceParameter)
  val ReceiverParameter: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(LatticeKeys.ReceiverParameter)
  val ValueParameter: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(LatticeKeys.ValueParameter)
  val AssistedFactoryImplClassDeclaration: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(LatticeKeys.AssistedFactoryImplClassDeclaration)
  val AssistedFactoryImplCreatorFunctionDeclaration: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(LatticeKeys.AssistedFactoryImplCreatorFunctionDeclaration)
  val LatticeGraphCreatorsObjectInvokeDeclaration: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(LatticeKeys.LatticeGraphCreatorsObjectInvokeDeclaration)
  val LatticeGraphAccessorCallableOverride: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(LatticeKeys.LatticeGraphAccessorCallableOverride)
  val LatticeGraphInjectorCallableOverride: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(LatticeKeys.LatticeGraphInjectorCallableOverride)
  val LatticeGraphFactoryCompanionGetter: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(LatticeKeys.LatticeGraphFactoryCompanionGetter)
  val InjectConstructorFactoryClassDeclaration: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(LatticeKeys.InjectConstructorFactoryClassDeclaration)
  val MembersInjectorClassDeclaration: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(LatticeKeys.MembersInjectorClassDeclaration)
  val FactoryCreateFunction: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(LatticeKeys.FactoryCreateFunction)
  val FactoryNewInstanceFunction: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(LatticeKeys.FactoryNewInstanceFunction)
  val ProviderFactoryClassDeclaration: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(LatticeKeys.ProviderFactoryClassDeclaration)
  val TopLevelInjectFunctionClassFunction: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(LatticeKeys.TopLevelInjectFunctionClassFunction)
}
