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
package dev.zacsweers.lattice.compiler.ir.parameters

import dev.zacsweers.lattice.compiler.LatticeSymbols
import dev.zacsweers.lattice.compiler.ir.ContextualTypeKey
import dev.zacsweers.lattice.compiler.ir.TypeKey
import dev.zacsweers.lattice.compiler.ir.annotationsIn
import dev.zacsweers.lattice.compiler.ir.constArgumentOfTypeAt
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.name.Name

internal sealed interface Parameter : Comparable<Parameter> {
  val kind: Kind
  val name: Name
  val originalName: Name
  // TODO make this just alias to contextualtypekey?
  val type: IrType
  val providerType: IrType
  val contextualTypeKey: ContextualTypeKey
  val lazyType: IrType
  val isWrappedInProvider: Boolean
  val isWrappedInLazy: Boolean
  val isLazyWrappedInProvider: Boolean
  val isAssisted: Boolean
  val assistedIdentifier: String
  val assistedParameterKey: AssistedParameterKey
  val symbols: LatticeSymbols
  val typeKey: TypeKey
  val isGraphInstance: Boolean
  val isBindsInstance: Boolean
  val hasDefault: Boolean
  val location: CompilerMessageSourceLocation?
  val ir: IrValueParameter

  override fun compareTo(other: Parameter): Int = COMPARATOR.compare(this, other)

  // @Assisted parameters are equal, if the type and the identifier match. This subclass makes
  // diffing the parameters easier.
  data class AssistedParameterKey(val typeKey: TypeKey, val assistedIdentifier: String) {
    companion object {
      fun IrValueParameter.toAssistedParameterKey(
        symbols: LatticeSymbols,
        typeKey: TypeKey,
      ): AssistedParameterKey {
        return AssistedParameterKey(
          typeKey,
          annotationsIn(symbols.assistedAnnotations)
            .singleOrNull()
            ?.constArgumentOfTypeAt<String>(0)
            .orEmpty(),
        )
      }
    }
  }

  enum class Kind {
    INSTANCE,
    EXTENSION_RECEIVER,
    VALUE,
    //    CONTEXT_PARAMETER, // Coming soon
  }

  companion object {
    private val COMPARATOR =
      compareBy(Parameter::kind)
        .thenBy(Parameter::name)
        .thenBy(Parameter::originalName)
        .thenBy(Parameter::typeKey)
        .thenBy(Parameter::assistedIdentifier)
  }
}
