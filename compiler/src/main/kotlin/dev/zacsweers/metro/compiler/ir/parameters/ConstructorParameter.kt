// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.parameters

import dev.drewhamilton.poko.Poko
import dev.zacsweers.metro.compiler.ir.IrContextualTypeKey
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.annotationsIn
import dev.zacsweers.metro.compiler.ir.asContextualTypeKey
import dev.zacsweers.metro.compiler.ir.constArgumentOfTypeAt
import dev.zacsweers.metro.compiler.unsafeLazy
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.name.Name

@Poko
internal class ConstructorParameter(
  override val kind: IrParameterKind,
  override val name: Name,
  override val contextualTypeKey: IrContextualTypeKey,
  override val isAssisted: Boolean,
  override val isGraphInstance: Boolean,
  override val isBindsInstance: Boolean,
  override val isIncludes: Boolean,
  override val isExtends: Boolean,
  override val assistedIdentifier: String,
  override val assistedParameterKey: Parameter.AssistedParameterKey =
    Parameter.AssistedParameterKey(contextualTypeKey.typeKey, assistedIdentifier),
) : Parameter {
  override val originalName: Name = name

  override lateinit var ir: IrValueParameter

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

internal fun List<IrValueParameter>.mapToConstructorParameters(
  context: IrMetroContext,
  typeParameterRemapper: ((IrType) -> IrType)? = null,
): List<ConstructorParameter> {
  return map { valueParameter ->
    valueParameter.toConstructorParameter(
      context,
      IrParameterKind.Regular,
      typeParameterRemapper,
    )
  }
}

internal fun IrValueParameter.toConstructorParameter(
  context: IrMetroContext,
  kind: IrParameterKind = IrParameterKind.Regular,
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

  var isProvides = false
  var isIncludes = false
  var isExtends = false
  for (annotation in annotations) {
    val classId = annotation.symbol.owner.parentAsClass.classId
    when (classId) {
      in context.symbols.classIds.providesAnnotations -> {
        isProvides = true
      }
      in context.symbols.classIds.includes -> {
        isIncludes = true
      }
      in context.symbols.classIds.extends -> {
        isExtends = true
      }
      else -> continue
    }
  }

  val assistedIdentifier = assistedAnnotation?.constArgumentOfTypeAt<String>(0).orEmpty()

  return ConstructorParameter(
      kind = kind,
      name = name,
      contextualTypeKey = contextKey,
      isAssisted = assistedAnnotation != null,
      assistedIdentifier = assistedIdentifier,
      isGraphInstance = false,
      isBindsInstance = isProvides,
      isExtends = isExtends,
      isIncludes = isIncludes,
    )
    .apply { this.ir = this@toConstructorParameter }
}
