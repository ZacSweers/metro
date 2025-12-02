// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler

import dev.zacsweers.metro.compiler.fir.Keys
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin

internal object Origins {
  val Default: IrDeclarationOrigin = IrDeclarationOrigin.GeneratedByPlugin(Keys.Default)
  val InstanceParameter: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(Keys.InstanceParameter)
  val ReceiverParameter: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(Keys.ReceiverParameter)
  val RegularParameter: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(Keys.RegularParameter)
  val ContributionHint: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(Keys.ContributionHint)
  val MetroGraphCreatorsObjectInvokeDeclaration: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(Keys.MetroGraphCreatorsObjectInvokeDeclaration)
  val GraphImplClassDeclaration: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(Keys.GraphImplClassDeclaration)
  val MetroGraphAccessorCallableOverride: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(Keys.MetroGraphAccessorCallableOverride)
  val MetroGraphInjectorCallableOverride: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(Keys.MetroGraphInjectorCallableOverride)
  val MetroGraphFactoryCompanionGetter: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(Keys.MetroGraphFactoryCompanionGetter)
  val MetroContributionClassDeclaration: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(Keys.MetroContributionClassDeclaration)
  val MetroContributionCallableDeclaration: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(Keys.MetroContributionCallableDeclaration)
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
  val ExtendableGraphAccessor: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(Keys.ExtendableGraphAccessor)
  val GeneratedGraphExtension: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(Keys.GeneratedGraphExtension)
  val BindingMirrorClassDeclaration: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(Keys.BindingMirrorClassDeclaration)
  val GeneratedDynamicGraph: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(Keys.GeneratedDynamicGraph)
  val DynamicContainerParam: IrDeclarationOrigin =
    IrDeclarationOrigin.GeneratedByPlugin(Keys.DynamicContainerParam)

  object FirstParty {
    // in 2.3.20, Kotlin changed the types of origins like IrDeclarationOrigin.DEFINED to
    // IrDeclarationOrigin rather than IrDeclarationOriginImpl
    // So, now we do cached reflection lookups of these :(
    private fun getConstant(name: String): Lazy<IrDeclarationOrigin> = lazy {
      IrDeclarationOrigin.Companion::class
        .java
        .getDeclaredMethod("get$name")
        .invoke(IrDeclarationOrigin.Companion) as IrDeclarationOrigin
    }

    val DEFINED: IrDeclarationOrigin by getConstant("DEFINED")
    val PROPERTY_BACKING_FIELD: IrDeclarationOrigin by getConstant("PROPERTY_BACKING_FIELD")
    val LOCAL_FUNCTION_FOR_LAMBDA: IrDeclarationOrigin by getConstant("LOCAL_FUNCTION_FOR_LAMBDA")
    val IR_TEMPORARY_VARIABLE: IrDeclarationOrigin by getConstant("IR_TEMPORARY_VARIABLE")
    val IR_EXTERNAL_JAVA_DECLARATION_STUB: IrDeclarationOrigin by
      getConstant("IR_EXTERNAL_JAVA_DECLARATION_STUB")
  }
}

internal val IrDeclarationOrigin.isSyntheticGeneratedGraph: Boolean
  get() = this == Origins.GeneratedGraphExtension || this == Origins.GeneratedDynamicGraph

internal val IrDeclarationOrigin.isGraphImpl: Boolean
  get() = this == Origins.GraphImplClassDeclaration || isSyntheticGeneratedGraph
