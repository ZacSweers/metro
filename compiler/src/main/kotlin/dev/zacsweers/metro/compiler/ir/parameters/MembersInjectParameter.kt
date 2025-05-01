// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.parameters

import dev.drewhamilton.poko.Poko
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.asName
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.IrTypeKey
import dev.zacsweers.metro.compiler.ir.asContextualTypeKey
import dev.zacsweers.metro.compiler.ir.locationOrNull
import dev.zacsweers.metro.compiler.ir.parameters.Parameter.Kind
import dev.zacsweers.metro.compiler.unsafeLazy
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.name.Name

@Poko
internal class MembersInjectParameter(
  override val kind: Kind,
  override val name: Name,
  override val contextualTypeKey: IrContextualTypeKey,
  override val hasDefault: Boolean,
  @Poko.Skip override val originalName: Name,
  @Poko.Skip override val providerType: IrType,
  @Poko.Skip override val lazyType: IrType,
  @Poko.Skip override val symbols: Symbols,
  @Poko.Skip override val location: CompilerMessageSourceLocation?,
  @Poko.Skip override val ir: IrValueParameter,
) : Parameter {
  override val typeKey: IrTypeKey = contextualTypeKey.typeKey
  override val type: IrType = contextualTypeKey.typeKey.type
  override val isWrappedInProvider: Boolean = contextualTypeKey.isWrappedInProvider
  override val isWrappedInLazy: Boolean = contextualTypeKey.isWrappedInLazy
  override val isLazyWrappedInProvider: Boolean = contextualTypeKey.isLazyWrappedInProvider
  override val isAssisted: Boolean = false
  override val assistedIdentifier: String = ""
  override val assistedParameterKey: Parameter.AssistedParameterKey =
    Parameter.AssistedParameterKey(contextualTypeKey.typeKey, assistedIdentifier)
  override val isBindsInstance: Boolean = false
  override val isGraphInstance: Boolean = false
  override val isIncludes: Boolean = false
  override val isExtends: Boolean = false
  private val cachedToString by unsafeLazy {
    buildString {
      contextualTypeKey.typeKey.qualifier?.let {
        append(it)
        append(' ')
      }
      append(name)
      append(':')
      append(' ')
      append(contextualTypeKey.render(short = true, includeQualifier = false))
    }
  }

  override fun toString(): String = cachedToString
}

internal fun List<IrValueParameter>.mapToMemberInjectParameters(
  context: IrMetroContext,
  nameAllocator: dev.zacsweers.metro.compiler.NameAllocator,
  typeParameterRemapper: ((IrType) -> IrType)? = null,
): List<MembersInjectParameter> {
  return map { valueParameter ->
    valueParameter.toMemberInjectParameter(
      context = context,
      uniqueName = nameAllocator.newName(valueParameter.name.asString()).asName(),
      kind = Kind.VALUE,
      typeParameterRemapper = typeParameterRemapper,
    )
  }
}

internal fun IrProperty.toMemberInjectParameter(
  context: IrMetroContext,
  uniqueName: Name,
  kind: Kind = Kind.VALUE,
  typeParameterRemapper: ((IrType) -> IrType)? = null,
): MembersInjectParameter {
  val propertyType =
    getter?.returnType ?: backingField?.type ?: error("No getter or backing field!")

  val setterParam = setter?.valueParameters?.singleOrNull()

  // Remap type parameters in underlying types to the new target container. This is important for
  // type mangling
  val declaredType = typeParameterRemapper?.invoke(propertyType) ?: propertyType

  // TODO warn if it's anything other than null for now?
  val defaultValue = getter?.body ?: backingField?.initializer
  val contextKey =
    declaredType.asContextualTypeKey(
      context,
      with(context) { qualifierAnnotation() },
      defaultValue != null,
      false,
    )

  return MembersInjectParameter(
    kind = kind,
    name = uniqueName,
    originalName = name,
    contextualTypeKey = contextKey,
    providerType = contextKey.typeKey.type.wrapInProvider(context.symbols.metroProvider),
    lazyType = contextKey.typeKey.type.wrapInLazy(context.symbols),
    symbols = context.symbols,
    hasDefault = defaultValue != null,
    location = locationOrNull(),
    ir = setterParam!!,
  )
}

internal fun IrValueParameter.toMemberInjectParameter(
  context: IrMetroContext,
  uniqueName: Name,
  kind: Kind = Kind.VALUE,
  typeParameterRemapper: ((IrType) -> IrType)? = null,
): MembersInjectParameter {
  // Remap type parameters in underlying types to the new target container. This is important for
  // type mangling
  val declaredType =
    typeParameterRemapper?.invoke(this@toMemberInjectParameter.type)
      ?: this@toMemberInjectParameter.type

  val contextKey =
    declaredType.asContextualTypeKey(
      context,
      with(context) { qualifierAnnotation() },
      defaultValue != null,
      false,
    )

  return MembersInjectParameter(
    kind = kind,
    name = uniqueName,
    originalName = name,
    contextualTypeKey = contextKey,
    providerType = contextKey.typeKey.type.wrapInProvider(context.symbols.metroProvider),
    lazyType = contextKey.typeKey.type.wrapInLazy(context.symbols),
    symbols = context.symbols,
    hasDefault = defaultValue != null,
    location = locationOrNull(),
    ir = this,
  )
}
