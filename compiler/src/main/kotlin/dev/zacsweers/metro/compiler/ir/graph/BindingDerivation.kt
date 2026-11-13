// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.graph

import dev.zacsweers.metro.compiler.diagnostics.Note
import dev.zacsweers.metro.compiler.graph.DerivedBindingDiagnosticMessages
import dev.zacsweers.metro.compiler.ir.ProviderFactory
import dev.zacsweers.metro.compiler.ir.render
import dev.zacsweers.metro.compiler.reportCompilerBug
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.kotlinFqName

/**
 * The generic function a derived binding came from. [typeArguments] holds the inferred type for
 * each of [typeParameters], in the same order. Reports use this to say why a binding exists.
 */
internal class BindingDerivation(
  val function: IrSimpleFunction,
  val typeParameters: List<IrTypeParameter>,
  val typeArguments: List<IrType>,
) {
  init {
    if (typeParameters.size != typeArguments.size) {
      reportCompilerBug(
        "${function.name} declares ${typeParameters.size} type parameters but has " +
          "${typeArguments.size} type arguments",
      )
    }
  }

  /** The function's fully qualified name. */
  val providerName: String
    get() = function.kotlinFqName.asString()

  /** Each type parameter's name paired with its rendered type argument, in declaration order. */
  fun renderedTypeArguments(short: Boolean): List<Pair<String, String>> {
    return typeParameters.zip(typeArguments) { typeParameter, argument ->
      typeParameter.name.asString() to argument.render(short = short)
    }
  }

  /** A sentence for binding explanations. */
  fun explanationDetail(): String {
    return DerivedBindingDiagnosticMessages.derivedBindingDetail(
      provider = providerName,
      typeArguments = renderedTypeArguments(short = false),
    )
  }
}

/**
 * Returns where this binding was derived from, or null if it wasn't derived. Only applied views of
 * generic providers and binds functions carry function type arguments.
 */
internal fun IrBinding.derivationOrNull(): BindingDerivation? {
  return when (this) {
    is IrBinding.Provided -> {
      val factory = providerFactory as? ProviderFactory.Metro ?: return null
      val arguments = factory.functionTypeArguments ?: return null
      BindingDerivation(factory.function, factory.functionTypeParameters, arguments)
    }

    is IrBinding.Alias -> {
      val callable = bindsCallable ?: return null
      val arguments = callable.functionTypeArguments ?: return null
      BindingDerivation(callable.function, callable.functionTypeParameters, arguments)
    }

    else -> null
  }
}

/**
 * Diagnostic notes that say which generic provider derived this binding. Traces collect these from
 * their entries, so a reader can tell which link came from a generic provider.
 */
internal fun IrBinding.derivedBindingNotes(): List<Note> {
  val derivation = derivationOrNull() ?: return emptyList()

  val text =
    DerivedBindingDiagnosticMessages.derivedBindingNote(
      key = typeKey.render(short = true),
      provider = derivation.providerName,
      typeArguments = derivation.renderedTypeArguments(short = true),
    )

  return listOf(Note.note(text))
}
