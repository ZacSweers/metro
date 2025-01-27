/*
 * Copyright (C) 2025 Zac Sweers
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
}
