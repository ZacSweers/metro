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

import dev.drewhamilton.poko.Poko
import dev.zacsweers.lattice.compiler.LatticeSymbols
import dev.zacsweers.lattice.compiler.ir.BindingStack
import dev.zacsweers.lattice.compiler.ir.ContextualTypeKey
import dev.zacsweers.lattice.compiler.ir.LatticeTransformerContext
import dev.zacsweers.lattice.compiler.ir.TypeKey
import dev.zacsweers.lattice.compiler.ir.annotationsIn
import dev.zacsweers.lattice.compiler.ir.asContextualTypeKey
import dev.zacsweers.lattice.compiler.ir.constArgumentOfTypeAt
import dev.zacsweers.lattice.compiler.ir.locationOrNull
import dev.zacsweers.lattice.compiler.ir.parameters.Parameter.Kind
import dev.zacsweers.lattice.compiler.unsafeLazy
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.name.Name

@Poko
internal class ConstructorParameter(
  override val kind: Kind,
  override val name: Name,
  override val contextualTypeKey: ContextualTypeKey,
  override val isAssisted: Boolean,
  override val isGraphInstance: Boolean,
  override val isBindsInstance: Boolean,
  override val hasDefault: Boolean,
  override val assistedIdentifier: String,
  override val assistedParameterKey: Parameter.AssistedParameterKey =
    Parameter.AssistedParameterKey(contextualTypeKey.typeKey, assistedIdentifier),
  @Poko.Skip override val originalName: Name,
  @Poko.Skip override val providerType: IrType,
  @Poko.Skip override val lazyType: IrType,
  @Poko.Skip override val symbols: LatticeSymbols,
  @Poko.Skip val bindingStackEntry: BindingStack.Entry,
  @Poko.Skip override val location: CompilerMessageSourceLocation?,
) : Parameter {
  override lateinit var ir: IrValueParameter
  override val typeKey: TypeKey = contextualTypeKey.typeKey
  override val type: IrType = contextualTypeKey.typeKey.type
  override val isWrappedInProvider: Boolean = contextualTypeKey.isWrappedInProvider
  override val isWrappedInLazy: Boolean = contextualTypeKey.isWrappedInLazy
  override val isLazyWrappedInProvider: Boolean = contextualTypeKey.isLazyWrappedInProvider

  private val cachedToString by unsafeLazy {
    buildString {
      contextualTypeKey.typeKey.qualifier?.let { append(it) }
      append(name)
      append(':')
      append(' ')
      append(contextualTypeKey.render(short = true, includeQualifier = false))
    }
  }

  override fun toString(): String = cachedToString
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
      false,
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
