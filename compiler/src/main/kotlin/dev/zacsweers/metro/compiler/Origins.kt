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

import dev.zacsweers.metro.compiler.fir.Keys
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin

internal object Origins {
  val Default: IrDeclarationOrigin = IrDeclarationOrigin.GeneratedByPlugin(Keys.Default)
  val InstanceParameter: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(Keys.InstanceParameter)
  val ReceiverParameter: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(Keys.ReceiverParameter)
  val ValueParameter: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(Keys.ValueParameter)
  val AssistedFactoryImplClassDeclaration: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(Keys.AssistedFactoryImplClassDeclaration)
  val AssistedFactoryImplCreatorFunctionDeclaration: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(Keys.AssistedFactoryImplCreatorFunctionDeclaration)
  val MetroGraphCreatorsObjectInvokeDeclaration: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(Keys.MetroGraphCreatorsObjectInvokeDeclaration)
  val MetroGraphAccessorCallableOverride: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(Keys.MetroGraphAccessorCallableOverride)
  val MetroGraphInjectorCallableOverride: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(Keys.MetroGraphInjectorCallableOverride)
  val MetroGraphFactoryCompanionGetter: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(Keys.MetroGraphFactoryCompanionGetter)
  val InjectConstructorFactoryClassDeclaration: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(Keys.InjectConstructorFactoryClassDeclaration)
  val MembersInjectorClassDeclaration: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(Keys.MembersInjectorClassDeclaration)
  val FactoryCreateFunction: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(Keys.FactoryCreateFunction)
  val FactoryNewInstanceFunction: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(Keys.FactoryNewInstanceFunction)
  val ProviderFactoryClassDeclaration: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(Keys.ProviderFactoryClassDeclaration)
  val TopLevelInjectFunctionClassFunction: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(Keys.TopLevelInjectFunctionClassFunction)
}
