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
package dev.zacsweers.lattice.ir

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
  val graph: IrClass
  val entries: List<Entry>

  fun push(entry: Entry)

  fun pop()

  fun entryFor(key: TypeKey): Entry?

  class Entry(
    val typeKey: TypeKey,
    val usage: String?,
    val graphContext: String?,
    val declaration: IrDeclaration?,
    val displayTypeKey: TypeKey = typeKey,
  ) {
    fun render(graph: FqName): String {
      return buildString {
        append(displayTypeKey)
        usage?.let {
          append(' ')
          append(it)
        }
        graphContext?.let {
          appendLine()
          append("    ")
          append("[${graph.asString()}]")
          append(' ')
          append(it)
        }
      }
    }

    companion object {
      /*
      com.slack.circuit.star.Example1 is requested at
             [com.slack.circuit.star.ExampleGraph] com.slack.circuit.star.ExampleGraph.example1()
       */
      @OptIn(UnsafeDuringIrConstructionAPI::class)
      fun requestedAt(typeKey: TypeKey, accessor: IrSimpleFunction): Entry {
        val targetFqName = accessor.parentAsClass.kotlinFqName
        val declaration: IrDeclarationWithName =
          accessor.correspondingPropertySymbol?.owner ?: accessor
        val accessorString =
          if (declaration is IrProperty) {
            declaration.name.asString()
          } else {
            declaration.name.asString() + "()"
          }
        return Entry(
          typeKey = typeKey,
          usage = "is requested at",
          graphContext = "$targetFqName.$accessorString",
          declaration = declaration,
        )
      }

      /*
      com.slack.circuit.star.Example1
       */
      fun simpleTypeRef(typeKey: TypeKey, usage: String? = null): Entry =
        Entry(typeKey = typeKey, usage = usage, graphContext = null, declaration = null)

      /*
      java.lang.CharSequence is injected at
            [com.slack.circuit.star.ExampleGraph] com.slack.circuit.star.Example1(…, text2)
      */
      fun injectedAt(
        typeKey: TypeKey,
        function: IrFunction,
        param: IrValueParameter? = null,
        declaration: IrDeclaration? = param,
        displayTypeKey: TypeKey = typeKey,
      ): Entry {
        val targetFqName = function.parent.kotlinFqName
        val middle = if (function is IrConstructor) "" else ".${function.name.asString()}"
        val end = if (param == null) "()" else "(…, ${param.name.asString()})"
        val context = "$targetFqName$middle$end"
        return Entry(
          typeKey = typeKey,
          displayTypeKey = displayTypeKey,
          usage = "is injected at",
          graphContext = context,
          declaration = declaration,
        )
      }
    }
  }

  companion object {
    private val EMPTY =
      object : BindingStack {
        override val graph
          get() = throw UnsupportedOperationException()

        override val entries: List<Entry>
          get() = emptyList<Entry>()

        override fun push(entry: Entry) {
          // Do nothing
        }

        override fun pop() {
          // Do nothing
        }

        override fun entryFor(key: TypeKey): Entry? {
          return null
        }
      }

    operator fun invoke(graph: IrClass): BindingStack = BindingStackImpl(graph)

    fun empty() = EMPTY
  }
}

internal inline fun <T> BindingStack.withEntry(entry: BindingStack.Entry, block: () -> T): T {
  push(entry)
  val result = block()
  pop()
  return result
}

internal val BindingStack.lastEntryOrGraph
  get() = entries.firstOrNull()?.declaration ?: graph

internal fun Appendable.appendBindingStack(
  stack: BindingStack,
  indent: String = "    ",
  ellipse: Boolean = false,
) {
  val graphName = stack.graph.kotlinFqName
  for (entry in stack.entries) {
    entry.render(graphName).prependIndent(indent).lineSequence().forEach { appendLine(it) }
  }
  if (ellipse) {
    append(indent)
    appendLine("...")
  }
}

internal class BindingStackImpl(override val graph: IrClass) : BindingStack {
  // TODO can we use one structure?
  private val entrySet = mutableSetOf<TypeKey>()
  private val stack = ArrayDeque<BindingStack.Entry>()
  override val entries: List<BindingStack.Entry> = stack

  override fun push(entry: BindingStack.Entry) {
    stack.addFirst(entry)
    entrySet.add(entry.typeKey)
  }

  override fun pop() {
    val removed = stack.removeFirstOrNull() ?: error("Binding stack is empty!")
    entrySet.remove(removed.typeKey)
  }

  override fun entryFor(key: TypeKey): BindingStack.Entry? {
    return if (key in entrySet) {
      stack.first { entry -> entry.typeKey == key }
    } else {
      null
    }
  }
}
