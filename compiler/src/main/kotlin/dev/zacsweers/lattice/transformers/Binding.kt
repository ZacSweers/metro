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
package dev.zacsweers.lattice.transformers

import dev.zacsweers.lattice.ir.IrAnnotation
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction

internal sealed interface Binding {
  val scope: IrAnnotation?
  val dependencies: Map<TypeKey, Parameter>
  // Track the list of parameters, which may not have unique type keys
  val parameters: Parameters
  val nameHint: String

  data class ConstructorInjected(
    val type: IrClass,
    val typeKey: TypeKey,
    override val parameters: Parameters,
    override val scope: IrAnnotation? = null,
    override val dependencies: Map<TypeKey, Parameter> =
      parameters.nonInstanceParameters.associateBy { it.typeKey },
  ) : Binding {
    override val nameHint: String = type.name.asString()
  }

  data class Provided(
    val providerFunction: IrSimpleFunction,
    val typeKey: TypeKey,
    override val parameters: Parameters,
    override val scope: IrAnnotation? = null,
    override val dependencies: Map<TypeKey, Parameter> =
      parameters.nonInstanceParameters.associateBy { it.typeKey },
  ) : Binding {
    override val nameHint: String = providerFunction.name.asString()
  }

  data class ComponentDependency(val component: IrClass, val getter: IrFunction) : Binding {
    override val scope: IrAnnotation? = null
    // TODO what if the getter is a property getter, then it's a special name
    override val nameHint: String = component.name.asString() + getter.name.asString()
    override val dependencies: Map<TypeKey, Parameter> = emptyMap()
    override val parameters: Parameters = Parameters.EMPTY
  }
}
