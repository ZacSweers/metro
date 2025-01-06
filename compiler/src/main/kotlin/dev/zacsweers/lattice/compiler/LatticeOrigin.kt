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

// TODO migrate
// @Deprecated("", ReplaceWith("LatticeOrigins.Default", imports =
// ["dev.zacsweers.lattice.compiler.LatticeOrigins"]))
internal val LatticeOrigin: IrDeclarationOrigin = LatticeOrigins.Default

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
  val InjectConstructorFactoryClassDeclaration: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(LatticeKeys.InjectConstructorFactoryClassDeclaration)
  val MembersInjectorClassDeclaration: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(LatticeKeys.MembersInjectorClassDeclaration)
  val FactoryNewInstanceFunction: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(LatticeKeys.FactoryNewInstanceFunction)
  val ProviderFactoryClassDeclaration: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(LatticeKeys.ProviderFactoryClassDeclaration)
}
