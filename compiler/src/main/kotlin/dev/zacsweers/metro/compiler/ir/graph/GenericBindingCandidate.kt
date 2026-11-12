// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.ir.BindsCallable
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.ProviderFactory
import dev.zacsweers.metro.compiler.ir.rawTypeOrNull
import dev.zacsweers.metro.compiler.reportCompilerBug
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.util.classIdOrFail
import org.jetbrains.kotlin.name.ClassId

/**
 * A generic `@Provides` or `@Binds` function that a graph collected. Its [patternKey] mentions the
 * function's own type parameters, so it isn't a real binding key. [BindingLookup] keeps these in a
 * separate index keyed by [rawClassId] and the pattern key's qualifier.
 */
internal sealed class GenericBindingCandidate {
  /** The key this candidate provides, with the function's type parameters left in place. */
  abstract val patternKey: IrTypeKey

  /** True if the graph declares this candidate itself. Inherited candidates are false. */
  abstract val isLocallyDeclared: Boolean

  /** The generic source function, used for diagnostics. */
  abstract val function: IrSimpleFunction

  /** The raw class of [patternKey]. FIR rejects bare type parameter return types. */
  val rawClassId: ClassId
    get() {
      val rawClass =
        patternKey.type.rawTypeOrNull()
          ?: reportCompilerBug("Generic binding $patternKey has no raw class")
      return rawClass.classIdOrFail
    }

  /** The function's own type parameters, which [patternKey] mentions. */
  abstract val functionTypeParameters: List<IrTypeParameter>

  /**
   * Checks that [patternKey] mentions every one of [functionTypeParameters]. FIR requires each to
   * appear in the return type. Signature carriers declare their own copies, so a missed remap shows
   * up here.
   */
  fun checkPatternKey() {
    val mentioned = mutableSetOf<IrTypeParameterSymbol>()
    patternKey.type.collectTypeParameters(mentioned)
    val missing = functionTypeParameters.filterNot { it.symbol in mentioned }
    if (missing.isNotEmpty()) {
      reportCompilerBug(
        "Generic binding ${function.name} has key $patternKey, which doesn't mention its type " +
          "parameters ${missing.joinToString { it.name.asString() }}",
      )
    }
  }

  private fun IrType.collectTypeParameters(into: MutableSet<IrTypeParameterSymbol>) {
    if (this !is IrSimpleType) {
      return
    }
    val classifier = classifier
    if (classifier is IrTypeParameterSymbol) {
      into += classifier
    }
    for (argument in arguments) {
      if (argument is IrTypeProjection) {
        argument.type.collectTypeParameters(into)
      }
    }
  }

  class Provider(
    val providerFactory: ProviderFactory.Metro,
    override val isLocallyDeclared: Boolean,
  ) : GenericBindingCandidate() {
    override val patternKey: IrTypeKey
      get() = providerFactory.rawTypeKey

    override val function: IrSimpleFunction
      get() = providerFactory.function

    override val functionTypeParameters: List<IrTypeParameter>
      get() = providerFactory.functionTypeParameters
  }

  class Binds(val bindsCallable: BindsCallable, override val isLocallyDeclared: Boolean) :
    GenericBindingCandidate() {
    override val patternKey: IrTypeKey
      get() = bindsCallable.rawTarget

    override val function: IrSimpleFunction
      get() = bindsCallable.function

    override val functionTypeParameters: List<IrTypeParameter>
      get() = bindsCallable.functionTypeParameters
  }
}
