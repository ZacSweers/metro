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
package dev.zacsweers.lattice.transformers

import kotlin.text.appendLine
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.name.FqName

internal interface BindingStack {
  val component: IrClass
  val entries: List<BindingStackEntry>

  fun push(entry: BindingStackEntry)

  fun pop()

  companion object {
    private val EMPTY =
      object : BindingStack {
        override val component
          get() = throw UnsupportedOperationException()

        override val entries: List<BindingStackEntry>
          get() = emptyList<BindingStackEntry>()

        override fun push(entry: BindingStackEntry) {
          // Do nothing
        }

        override fun pop() {
          // Do nothing
        }
      }

    operator fun invoke(component: IrClass): BindingStack = BindingStackImpl(component)

    fun empty() = EMPTY
  }
}

internal val BindingStack.lastEntryOrComponent
  get() = entries.firstOrNull()?.declaration ?: component

internal fun Appendable.appendBindingStack(stack: BindingStack) {
  val componentName = stack.component.kotlinFqName
  for (entry in stack.entries) {
    entry.render(componentName).prependIndent("    ").lineSequence().forEach { appendLine(it) }
  }
}

internal class BindingStackImpl(override val component: IrClass) : BindingStack {
  private val stack = ArrayDeque<BindingStackEntry>()
  override val entries: List<BindingStackEntry> = stack

  override fun push(entry: BindingStackEntry) {
    stack.addFirst(entry)
  }

  override fun pop() {
    stack.removeFirstOrNull()
  }
}

internal class BindingStackEntry(
  val typeKey: TypeKey,
  val action: String?,
  val context: String?,
  val declaration: IrDeclaration?,
) {
  fun render(component: FqName): String {
    return buildString {
      append(typeKey)
      action?.let {
        append(' ')
        appendLine(it)
      }
      context?.let {
        append("    ")
        append("[${component.asString()}]")
        append(' ')
        append(it)
      }
    }
  }

  companion object {
    /*
    com.slack.circuit.star.Example1 is requested at
           [com.slack.circuit.star.ExampleComponent] com.slack.circuit.star.ExampleComponent.example1()
     */
    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun requestedAt(typeKey: TypeKey, accessor: IrSimpleFunction): BindingStackEntry {
      val targetFqName = accessor.parentAsClass.kotlinFqName
      val declaration: IrDeclarationWithName =
        accessor.correspondingPropertySymbol?.owner ?: accessor
      val accessor =
        if (declaration is IrProperty) {
          declaration.name.asString()
        } else {
          declaration.name.asString() + "()"
        }
      return BindingStackEntry(
        typeKey = typeKey,
        action = "is requested at",
        context = "$targetFqName.$accessor",
        declaration = declaration,
      )
    }

    /*
    com.slack.circuit.star.Example1
     */
    @OptIn(UnsafeDuringIrConstructionAPI::class)
    fun simpleTypeRef(typeKey: TypeKey): BindingStackEntry {
      return BindingStackEntry(typeKey = typeKey, action = null, context = null, declaration = null)
    }

    /*
    java.lang.CharSequence is injected at
          [com.slack.circuit.star.ExampleComponent] com.slack.circuit.star.Example1(…, text2)
    */
    fun injectedAt(
      typeKey: TypeKey,
      function: IrFunction,
      param: IrValueParameter,
    ): BindingStackEntry {
      val targetFqName = function.parent.kotlinFqName
      val middle = if (function is IrConstructor) "" else "."
      return BindingStackEntry(
        typeKey = typeKey,
        action = "is injected at",
        context = "$targetFqName$middle(…, ${param.name.asString()})",
        declaration = param,
      )
    }
  }
}
