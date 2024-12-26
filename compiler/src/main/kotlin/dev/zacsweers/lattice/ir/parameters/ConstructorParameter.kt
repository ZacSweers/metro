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
package dev.zacsweers.lattice.ir.parameters

import dev.zacsweers.lattice.LatticeSymbols
import dev.zacsweers.lattice.ir.BindingStack
import dev.zacsweers.lattice.ir.ContextualTypeKey
import dev.zacsweers.lattice.ir.LatticeTransformerContext
import dev.zacsweers.lattice.ir.TypeKey
import dev.zacsweers.lattice.ir.annotationsIn
import dev.zacsweers.lattice.ir.asContextualTypeKey
import dev.zacsweers.lattice.ir.constArgumentOfTypeAt
import dev.zacsweers.lattice.ir.locationOrNull
import dev.zacsweers.lattice.ir.parameters.Parameter.Kind
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.name.Name

internal data class ConstructorParameter(
  override val kind: Kind,
  override val name: Name,
  override val contextualTypeKey: ContextualTypeKey,
  override val originalName: Name,
  override val providerType: IrType,
  override val lazyType: IrType,
  override val isAssisted: Boolean,
  override val assistedIdentifier: String,
  override val assistedParameterKey: Parameter.AssistedParameterKey =
    Parameter.AssistedParameterKey(contextualTypeKey.typeKey, assistedIdentifier),
  override val symbols: LatticeSymbols,
  override val isGraphInstance: Boolean,
  val bindingStackEntry: BindingStack.Entry,
  override val isBindsInstance: Boolean,
  override val hasDefault: Boolean,
  override val location: CompilerMessageSourceLocation?,
) : Parameter {
  override lateinit var ir: IrValueParameter
  override val typeKey: TypeKey = contextualTypeKey.typeKey
  override val type: IrType = contextualTypeKey.typeKey.type
  override val isWrappedInProvider: Boolean = contextualTypeKey.isWrappedInProvider
  override val isWrappedInLazy: Boolean = contextualTypeKey.isWrappedInLazy
  override val isLazyWrappedInProvider: Boolean = contextualTypeKey.isLazyWrappedInProvider
}

internal fun List<IrValueParameter>.mapToConstructorParameters(
  context: LatticeTransformerContext,
  typeParameterRemapper: ((IrType) -> IrType)? = null,
): List<ConstructorParameter> {
  return map { valueParameter ->
    valueParameter.toConstructorParameter(
      context,
      Kind.VALUE,
      valueParameter.name,
      typeParameterRemapper,
    )
  }
}

internal fun IrValueParameter.toConstructorParameter(
  context: LatticeTransformerContext,
  kind: Kind = Kind.VALUE,
  uniqueName: Name = this.name,
  typeParameterRemapper: ((IrType) -> IrType)? = null,
): ConstructorParameter {
  // Remap type parameters in underlying types to the new target container. This is important for
  // type mangling
  val declaredType =
    typeParameterRemapper?.invoke(this@toConstructorParameter.type)
      ?: this@toConstructorParameter.type

  val contextKey =
    declaredType.asContextualTypeKey(
      context,
      with(context) { qualifierAnnotation() },
      defaultValue != null,
    )

  val assistedAnnotation = annotationsIn(context.symbols.assistedAnnotations).singleOrNull()

  val isBindsInstance =
    annotationsIn(context.symbols.bindsInstanceAnnotations).singleOrNull() != null

  val assistedIdentifier = assistedAnnotation?.constArgumentOfTypeAt<String>(0).orEmpty()

  val ownerFunction = this.parent as IrFunction // TODO is this safe

  return ConstructorParameter(
      kind = kind,
      name = uniqueName,
      originalName = name,
      contextualTypeKey = contextKey,
      providerType = contextKey.typeKey.type.wrapInProvider(context.symbols.latticeProvider),
      lazyType = contextKey.typeKey.type.wrapInLazy(context.symbols),
      isAssisted = assistedAnnotation != null,
      assistedIdentifier = assistedIdentifier,
      symbols = context.symbols,
      isGraphInstance = false,
      bindingStackEntry = BindingStack.Entry.injectedAt(contextKey, ownerFunction, this),
      isBindsInstance = isBindsInstance,
      hasDefault = defaultValue != null,
      location = locationOrNull(),
    )
    .apply { this.ir = this@toConstructorParameter }
}
