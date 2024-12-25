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

import com.jakewharton.picnic.TextAlignment
import com.jakewharton.picnic.renderText
import com.jakewharton.picnic.table
import dev.zacsweers.lattice.ir.BindingStack.Entry
import kotlin.text.appendLine
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.isPropertyAccessor
import org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.propertyIfAccessor
import org.jetbrains.kotlin.name.FqName

internal interface BindingStack {
  val graph: IrClass
  val entries: List<Entry>

  fun push(entry: Entry)

  fun pop()

  fun entryFor(key: TypeKey): Entry?

  fun entriesSince(key: TypeKey): List<Entry>

  class Entry(
    val contextKey: ContextualTypeKey,
    val usage: String?,
    val graphContext: String?,
    val declaration: IrDeclaration?,
    val displayTypeKey: TypeKey = contextKey.typeKey,
    /**
     * Indicates this entry is informational only and not an actual functional binding that should
     * participate in validation.
     */
    val isSynthetic: Boolean = false,
  ) {
    val typeKey: TypeKey
      get() = contextKey.typeKey

    fun render(graph: FqName, short: Boolean): String {
      return buildString {
        append(displayTypeKey.render(short))
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

    override fun toString(): String = render(FqName("..."), short = true)

    companion object {
      /*
      com.slack.circuit.star.Example1 is requested at
             [com.slack.circuit.star.ExampleGraph] com.slack.circuit.star.ExampleGraph.example1()
       */
      @OptIn(UnsafeDuringIrConstructionAPI::class)
      fun requestedAt(contextKey: ContextualTypeKey, accessor: IrSimpleFunction): Entry {
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
          contextKey = contextKey,
          usage = "is requested at",
          graphContext = "$targetFqName.$accessorString",
          declaration = declaration,
          isSynthetic = true,
        )
      }

      /*
      com.slack.circuit.star.Example1
       */
      fun simpleTypeRef(contextKey: ContextualTypeKey, usage: String? = null): Entry =
        Entry(
          contextKey = contextKey,
          usage = usage,
          graphContext = null,
          declaration = null,
          isSynthetic = true,
        )

      /*
      java.lang.CharSequence is injected at
            [com.slack.circuit.star.ExampleGraph] com.slack.circuit.star.Example1(…, text2)
      */
      fun injectedAt(
        contextKey: ContextualTypeKey,
        function: IrFunction,
        param: IrValueParameter? = null,
        declaration: IrDeclaration? = param,
        displayTypeKey: TypeKey = contextKey.typeKey,
      ): Entry {
        val targetFqName = function.parent.kotlinFqName
        val middle = when {
          function is IrConstructor -> ""
          function.isPropertyAccessor -> ".${(function.propertyIfAccessor as IrProperty).name.asString()}"
          else -> ".${function.name.asString()}"
        }
        val end = if (param == null) "()" else "(…, ${param.name.asString()})"
        val context = "$targetFqName$middle$end"
        return Entry(
          contextKey = contextKey,
          displayTypeKey = displayTypeKey,
          usage = "is injected at",
          graphContext = context,
          declaration = declaration,
        )
      }

      /*
      kotlin.Int is provided at
            [com.slack.circuit.star.ExampleGraph] provideInt(...): kotlin.Int
      */
      fun providedAt(
        contextualTypeKey: ContextualTypeKey,
        function: IrFunction,
        displayTypeKey: TypeKey = contextualTypeKey.typeKey,
      ): Entry {
        val targetFqName = function.parent.kotlinFqName
        val middle = if (function is IrConstructor) "" else ".${function.name.asString()}"
        val context = "$targetFqName$middle(…)"
        return Entry(
          contextKey = contextualTypeKey,
          displayTypeKey = displayTypeKey,
          usage = "is provided at",
          graphContext = context,
          declaration = function,
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

        override fun entriesSince(key: TypeKey): List<Entry> {
          return emptyList()
        }
      }

    operator fun invoke(graph: IrClass): BindingStack = BindingStackImpl(graph)

    fun empty() = EMPTY
  }
}

internal inline fun <T> BindingStack.withEntry(entry: Entry?, block: () -> T): T {
  if (entry == null) return block()
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
  short: Boolean = true,
) = appendBindingStackEntries(stack.graph.kotlinFqName, stack.entries, indent, ellipse, short)

internal fun Appendable.appendBindingStackEntries(
  graphName: FqName,
  entries: Collection<Entry>,
  indent: String = "    ",
  ellipse: Boolean = false,
  short: Boolean = true,
) {
  for (entry in entries) {
    entry.render(graphName, short).prependIndent(indent).lineSequence().forEach { appendLine(it) }
  }
  if (ellipse) {
    append(indent)
    appendLine("...")
  }
}

internal class BindingStackImpl(override val graph: IrClass) : BindingStack {
  // TODO can we use one structure?
  // TODO can we use scattermap's IntIntMap? Store the typekey hash to its index
  private val entrySet = mutableSetOf<TypeKey>()
  private val stack = ArrayDeque<Entry>()
  override val entries: List<Entry> = stack

  override fun push(entry: Entry) {
    stack.addFirst(entry)
    entrySet.add(entry.typeKey)
  }

  override fun pop() {
    val removed = stack.removeFirstOrNull() ?: error("Binding stack is empty!")
    entrySet.remove(removed.typeKey)
  }

  override fun entryFor(key: TypeKey): Entry? {
    return if (key in entrySet) {
      stack.first { entry -> entry.typeKey == key }
    } else {
      null
    }
  }

  // TODO optimize this by looking in the entrySet first
  override fun entriesSince(key: TypeKey): List<Entry> {
    val reversed = stack.asReversed()
    val index = reversed.indexOfFirst { it.typeKey == key }
    if (index == -1) return emptyList()
    return reversed.slice(index until reversed.size).filterNot { it.isSynthetic }
  }

  override fun toString() = renderTable()

  fun renderTable(): String {
    return table {
        cellStyle {
          border = true
          paddingLeft = 1
          paddingRight = 1
        }

        header {
          cellStyle { alignment = TextAlignment.MiddleCenter }
          row {
            cell("Index")
            cell("Display Key")
            cell("Usage")
            cell("Key")
            cell("Context")
            cell("Deferrable?")
          }
        }

        for ((i, entry) in stack.withIndex()) {
          body {
            row {
              cellStyle { alignment = TextAlignment.MiddleCenter }
              cell("${stack.lastIndex - i}")
              cell(entry.displayTypeKey.render(short = true))
              cell("${entry.usage}...")
              val key = entry.typeKey.render(short = true)
              cell(key)
              val contextKey = entry.contextKey.render(short = true)
              cell(if (contextKey == key) "--" else contextKey)
              cell("${entry.contextKey.isDeferrable}")
            }
          }
        }

        footer {
          cellStyle {
            paddingTop = 1
            paddingBottom = 1
            alignment = TextAlignment.MiddleCenter
          }
          row { cell("[${graph.kotlinFqName.pathSegments().last()}]") { columnSpan = 6 } }
        }
      }
      .renderText()
      .prependIndent("  ")
  }
}

internal fun bindingStackEntryForDependency(
  binding: Binding,
  contextKey: ContextualTypeKey,
  targetKey: TypeKey,
): Entry {
  return when (binding) {
    is Binding.ConstructorInjected -> {
      Entry.injectedAt(
        contextKey,
        binding.injectedConstructor,
        binding.parameterFor(targetKey),
        displayTypeKey = targetKey,
      )
    }
    is Binding.Provided -> {
      Entry.injectedAt(
        contextKey,
        binding.providerFunction,
        binding.parameterFor(targetKey),
        displayTypeKey = targetKey,
      )
    }
    is Binding.Assisted -> {
      Entry.injectedAt(contextKey, binding.function, displayTypeKey = targetKey)
    }
    is Binding.Multibinding -> {
      TODO()
    }
    is Binding.BoundInstance -> TODO()
    is Binding.GraphDependency -> TODO()
    is Binding.Absent -> error("Should never happen")
  }
}
