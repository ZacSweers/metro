// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.parameters

import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.annotationsIn
import dev.zacsweers.metro.compiler.ir.constArgumentOfTypeAt
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
  val contextualTypeKey: IrContextualTypeKey
  val lazyType: IrType
  val isWrappedInProvider: Boolean
  val isWrappedInLazy: Boolean
  val isLazyWrappedInProvider: Boolean
  val isAssisted: Boolean
  val assistedIdentifier: String
  val assistedParameterKey: AssistedParameterKey
  val symbols: Symbols
  val typeKey: IrTypeKey
  val isGraphInstance: Boolean
  val isBindsInstance: Boolean
  val isIncludes: Boolean
  val isExtends: Boolean
  val hasDefault: Boolean
  val location: CompilerMessageSourceLocation?
  val ir: IrValueParameter

  override fun compareTo(other: Parameter): Int = COMPARATOR.compare(this, other)

  // @Assisted parameters are equal, if the type and the identifier match. This subclass makes
  // diffing the parameters easier.
  data class AssistedParameterKey(val typeKey: IrTypeKey, val assistedIdentifier: String) {
    companion object {
      fun IrValueParameter.toAssistedParameterKey(
        symbols: Symbols,
        typeKey: IrTypeKey,
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
      compareBy<Parameter> { it.kind }
        .thenBy { it.name }
        .thenBy { it.originalName }
        .thenBy { it.typeKey }
        .thenBy { it.assistedIdentifier }
  }
}
