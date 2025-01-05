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
package dev.zacsweers.lattice.compiler.fir

import org.jetbrains.kotlin.GeneratedDeclarationKey

internal object LatticeKeys {
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

  data object InjectConstructorFactoryClassDeclaration : GeneratedDeclarationKey() {
    override fun toString() = "InjectConstructorFactoryClassDeclaration"
  }

  data object ProviderFactoryClassDeclaration : GeneratedDeclarationKey() {
    override fun toString() = "ProviderFactoryClassDeclaration"
  }

  data object FactoryNewInstanceFunction : GeneratedDeclarationKey() {
    override fun toString() = "FactoryNewInstanceFunction"
  }
}
