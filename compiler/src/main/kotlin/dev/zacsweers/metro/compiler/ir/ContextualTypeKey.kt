// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.drewhamilton.poko.Poko
import dev.zacsweers.metro.compiler.MetroAnnotations
import dev.zacsweers.metro.compiler.Symbols
import dev.zacsweers.metro.compiler.expectAs
import dev.zacsweers.metro.compiler.ir.parameters.wrapInProvider
import dev.zacsweers.metro.compiler.letIf
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.render

// TODO refactor/merge with FirContextualTypeKey
@Poko
internal class ContextualTypeKey(
  val typeKey: TypeKey,
  val isWrappedInProvider: Boolean = false,
  val isWrappedInLazy: Boolean = false,
  val isLazyWrappedInProvider: Boolean = false,
  val hasDefault: Boolean = false,
  val isDeferrable: Boolean = isWrappedInProvider || isWrappedInLazy || isLazyWrappedInProvider,
  val isIntoMultibinding: Boolean = false,
) {

  val requiresProviderInstance: Boolean =
    isWrappedInProvider || isLazyWrappedInProvider || isWrappedInLazy

  override fun toString(): String = render(short = true)

  fun render(short: Boolean, includeQualifier: Boolean = true): String = buildString {
    val wrapperType =
      when {
        isWrappedInProvider -> "Provider"
        isWrappedInLazy -> "Lazy"
        isLazyWrappedInProvider -> "Provider<Lazy<"
        else -> null
      }
    if (wrapperType != null) {
      append(wrapperType)
      append("<")
    }
    append(typeKey.render(short, includeQualifier))
    if (wrapperType != null) {
      append(">")
      if (isLazyWrappedInProvider) {
        // One more bracket
        append(">")
      }
    }
    if (hasDefault) {
      append(" = ...")
    }
  }

  fun toIrType(metroContext: IrMetroContext): IrType {
    val rawType = typeKey.type
    return when {
      isWrappedInProvider -> rawType.wrapInProvider(metroContext.symbols.metroProvider)
      isWrappedInLazy -> rawType.wrapInProvider(metroContext.symbols.stdlibLazy)
      isLazyWrappedInProvider -> {
        rawType
          .wrapInProvider(metroContext.symbols.stdlibLazy)
          .wrapInProvider(metroContext.symbols.metroProvider)
      }
      else -> rawType
    }
  }

  // TODO cache these in DependencyGraphTransformer or shared transformer data
  companion object {
    fun from(
      context: IrMetroContext,
      function: IrSimpleFunction,
      annotations: MetroAnnotations<IrAnnotation>,
      type: IrType = function.returnType,
    ): ContextualTypeKey =
      type.asContextualTypeKey(
        context,
        with(context) {
          function.correspondingPropertySymbol?.owner?.qualifierAnnotation()
            ?: function.qualifierAnnotation()
        },
        false,
        annotations.isIntoMultibinding,
      )

    fun from(
      context: IrMetroContext,
      parameter: IrValueParameter,
      type: IrType = parameter.type,
    ): ContextualTypeKey =
      type.asContextualTypeKey(
        context = context,
        qualifierAnnotation = with(context) { parameter.qualifierAnnotation() },
        hasDefault = parameter.defaultValue != null,
        isIntoMultibinding = false,
      )
  }
}

internal fun IrType.isMetroProviderType(context: IrMetroContext): Boolean {
  check(this is IrSimpleType) { "Unrecognized IrType '${javaClass}': ${render()}" }

  val declaredType = this
  val rawTypeClass = declaredType.rawTypeOrNull()

  return rawTypeClass!!.implementsAny(context.pluginContext, context.symbols.providerTypes)
}

internal fun IrType.asContextualTypeKey(
  context: IrMetroContext,
  qualifierAnnotation: IrAnnotation?,
  hasDefault: Boolean,
  isIntoMultibinding: Boolean,
): ContextualTypeKey {
  check(this is IrSimpleType) { "Unrecognized IrType '${javaClass}': ${render()}" }

  val declaredType = this
  val rawClass = declaredType.rawTypeOrNull()
  val rawClassId = rawClass?.classId

  val isWrappedInProvider = rawClassId in context.symbols.providerTypes
  val isWrappedInLazy = rawClassId in context.symbols.lazyTypes
  val isLazyWrappedInProvider =
    isWrappedInProvider &&
      declaredType.arguments[0].typeOrFail.rawTypeOrNull()?.classId in context.symbols.lazyTypes

  val type =
    when {
      isLazyWrappedInProvider ->
        declaredType.arguments
          .single()
          .typeOrFail
          .expectAs<IrSimpleType>()
          .arguments
          .single()
          .typeOrFail
      isWrappedInProvider || isWrappedInLazy -> declaredType.arguments.single().typeOrFail
      else -> declaredType
    }.letIf(isMarkedNullable()) { it.makeNullable() }

  val isDeferrable =
    isLazyWrappedInProvider ||
      isWrappedInProvider ||
      isWrappedInLazy ||
      run {
        // Check if this is a Map<Key, Provider<Value>>
        // If it has no type args we can skip
        if (declaredType.arguments.size != 2) return@run false
        val isMap = rawClassId == Symbols.ClassIds.map
        if (!isMap) return@run false
        val valueTypeContextKey =
          declaredType.arguments[1]
            .typeOrFail
            .asContextualTypeKey(
              context,
              // TODO could we actually support these?
              qualifierAnnotation = null,
              hasDefault = false,
              isIntoMultibinding = false,
            )

        valueTypeContextKey.isDeferrable
      }

  val typeKey = TypeKey(type, qualifierAnnotation)
  return ContextualTypeKey(
    typeKey = typeKey,
    isWrappedInProvider = isWrappedInProvider,
    isWrappedInLazy = isWrappedInLazy,
    isLazyWrappedInProvider = isLazyWrappedInProvider,
    hasDefault = hasDefault,
    isDeferrable = isDeferrable,
    isIntoMultibinding = isIntoMultibinding,
  )
}
