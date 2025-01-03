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
package dev.zacsweers.lattice.compiler.ir

import dev.drewhamilton.poko.Poko
import dev.zacsweers.lattice.compiler.LatticeAnnotations
import dev.zacsweers.lattice.compiler.expectAs
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.typeOrFail
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.render

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

  // TODO cache these in DependencyGraphTransformer or shared transformer data
  companion object {
    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun from(
      context: LatticeTransformerContext,
      function: IrSimpleFunction,
      annotations: LatticeAnnotations<IrAnnotation>,
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
      context: LatticeTransformerContext,
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

@OptIn(UnsafeDuringIrConstructionAPI::class)
internal fun IrType.isLatticeProviderType(context: LatticeTransformerContext): Boolean {
  check(this is IrSimpleType) { "Unrecognized IrType '${javaClass}': ${render()}" }

  val declaredType = this
  val rawTypeClass = declaredType.rawTypeOrNull()

  return rawTypeClass!!.implementsAny(context.pluginContext, context.symbols.providerTypes)
}

@OptIn(UnsafeDuringIrConstructionAPI::class)
internal fun IrType.asContextualTypeKey(
  context: LatticeTransformerContext,
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
    }

  val isDeferrable =
    isLazyWrappedInProvider ||
      isWrappedInProvider ||
      isWrappedInLazy ||
      run {
        // Check if this is a Map<Key, Provider<Value>>
        // If it has no type args we can skip
        if (declaredType.arguments.size != 2) return@run false
        val isMap =
          rawClass?.implements(
            context.pluginContext,
            context.pluginContext.irBuiltIns.mapClass.owner.classId!!,
          ) == true
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
