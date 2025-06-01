// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.parameters

import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.ir.IrBindingStack
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.annotationsIn
import dev.zacsweers.metro.compiler.ir.constArgumentOfTypeAt
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.name.Name

internal sealed interface Parameter : Comparable<Parameter> {
  val kind: IrParameterKind
  val name: Name
  val originalName: Name
  val contextualTypeKey: IrContextualTypeKey
  val isAssisted: Boolean
  val assistedIdentifier: String
  val assistedParameterKey: AssistedParameterKey
  val isGraphInstance: Boolean
  val isBindsInstance: Boolean
  val isIncludes: Boolean
  val isExtends: Boolean
  val ir: IrValueParameter

  val typeKey: IrTypeKey get() = contextualTypeKey.typeKey
  val type: IrType get() = contextualTypeKey.typeKey.type
  val isWrappedInProvider: Boolean get() = contextualTypeKey.isWrappedInProvider
  val isWrappedInLazy: Boolean get() = contextualTypeKey.isWrappedInLazy
  val isLazyWrappedInProvider: Boolean get() = contextualTypeKey.isLazyWrappedInProvider
  val hasDefault: Boolean get() = contextualTypeKey.hasDefault

  override fun compareTo(other: Parameter): Int = COMPARATOR.compare(this, other)

  // @Assisted parameters are equal if the type and the identifier match. This subclass makes
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

  companion object {
    private val COMPARATOR =
      compareBy<Parameter> { it.kind }
        .thenBy { it.name }
        .thenBy { it.originalName }
        .thenBy { it.typeKey }
        .thenBy { it.assistedIdentifier }
  }
}
