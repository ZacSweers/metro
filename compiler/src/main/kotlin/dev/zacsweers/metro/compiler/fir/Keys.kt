// Copyright (C) 2025 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.fir

import org.jetbrains.kotlin.GeneratedDeclarationKey

internal object Keys {
  data object Default : GeneratedDeclarationKey() {
    override fun toString() = "Default"
  }

  data object InstanceParameter : GeneratedDeclarationKey() {
    override fun toString() = "InstanceParameter"
  }

  data object ReceiverParameter : GeneratedDeclarationKey() {
    override fun toString() = "ReceiverParameter"
  }

  data object ValueParameter : GeneratedDeclarationKey() {
    override fun toString() = "ValueParameter"
  }

  data object AssistedFactoryImplClassDeclaration : GeneratedDeclarationKey() {
    override fun toString() = "AssistedFactoryImplClassDeclaration"
  }

  data object AssistedFactoryImplCompanionDeclaration : GeneratedDeclarationKey() {
    override fun toString() = "AssistedFactoryImplCompanionDeclaration"
  }

  data object AssistedFactoryImplCreatorFunctionDeclaration : GeneratedDeclarationKey() {
    override fun toString() = "AssistedFactoryImplCreatorFunctionDeclaration"
  }

  data object InjectConstructorFactoryClassDeclaration : GeneratedDeclarationKey() {
    override fun toString() = "InjectConstructorFactoryClassDeclaration"
  }

  data object InjectConstructorFactoryCompanionDeclaration : GeneratedDeclarationKey() {
    override fun toString() = "InjectConstructorFactoryCompanionDeclaration"
  }

  data object MetroGraphDeclaration : GeneratedDeclarationKey() {
    override fun toString() = "MetroGraphDeclaration"
  }

  data object MetroGraphAccessorCallableOverride : GeneratedDeclarationKey() {
    override fun toString() = "MetroGraphAccessorCallableOverride"
  }

  data object MetroGraphInjectorCallableOverride : GeneratedDeclarationKey() {
    override fun toString() = "MetroGraphInjectorCallableOverride"
  }

  data object MetroGraphCreatorsObjectDeclaration : GeneratedDeclarationKey() {
    override fun toString() = "MetroGraphCreatorsObjectDeclaration"
  }

  data object MetroGraphCreatorsObjectInvokeDeclaration : GeneratedDeclarationKey() {
    override fun toString() = "MetroGraphCreatorsObjectInvokeDeclaration"
  }

  data object MetroGraphFactoryCompanionGetter : GeneratedDeclarationKey() {
    override fun toString() = "MetroGraphFactoryCompanionGetter"
  }

  data object MetroGraphFactoryImplDeclaration : GeneratedDeclarationKey() {
    override fun toString() = "MetroGraphFactoryImplDeclaration"
  }

  data object MetroContributionClassDeclaration : GeneratedDeclarationKey() {
    override fun toString() = "MetroContributionClassDeclaration"
  }

  data object MetroContributionCallableDeclaration : GeneratedDeclarationKey() {
    override fun toString() = "MetroContributionCallableDeclaration"
  }

  data object MembersInjectorClassDeclaration : GeneratedDeclarationKey() {
    override fun toString() = "MembersInjectorClassDeclaration"
  }

  data object MembersInjectorCompanionDeclaration : GeneratedDeclarationKey() {
    override fun toString() = "MembersInjectorCompanionDeclaration"
  }

  data object MembersInjectorStaticInjectFunction : GeneratedDeclarationKey() {
    override fun toString() = "MembersInjectorStaticInjectFunction"
  }

  data object ProviderFactoryClassDeclaration : GeneratedDeclarationKey() {
    override fun toString() = "ProviderFactoryClassDeclaration"
  }

  data object ProviderFactoryCompanionDeclaration : GeneratedDeclarationKey() {
    override fun toString() = "ProviderFactoryCompanionDeclaration"
  }

  data object FactoryCreateFunction : GeneratedDeclarationKey() {
    override fun toString() = "FactoryCreateFunction"
  }

  data object FactoryNewInstanceFunction : GeneratedDeclarationKey() {
    override fun toString() = "FactoryNewInstanceFunction"
  }

  data object TopLevelInjectFunctionClass : GeneratedDeclarationKey() {
    override fun toString() = "TopLevelInjectFunctionClass"
  }

  data object TopLevelInjectFunctionClassFunction : GeneratedDeclarationKey() {
    override fun toString() = "TopLevelInjectFunctionClassFunction"
  }

  data object ProviderFieldAccessor : GeneratedDeclarationKey() {
    override fun toString() = "ProviderFieldAccessor"
  }

  data object InstanceFieldAccessor : GeneratedDeclarationKey() {
    override fun toString() = "InstanceFieldAccessor"
  }

  data object ContributedGraph : GeneratedDeclarationKey() {
    override fun toString() = "ContributedGraph"
  }
}
