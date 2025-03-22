// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import com.jakewharton.picnic.TextAlignment
import com.jakewharton.picnic.renderText
import com.jakewharton.picnic.table
import dev.zacsweers.metro.compiler.MetroLogger
import dev.zacsweers.metro.compiler.ir.BindingStack.Entry
import dev.zacsweers.metro.compiler.withoutLineBreaks
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrOverridableDeclaration
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.isPropertyAccessor
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
    val declaration: IrDeclarationWithName?,
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
      fun requestedAt(contextKey: ContextualTypeKey, accessor: IrSimpleFunction): Entry {
        val rawDeclaration: IrOverridableDeclaration<*> =
          accessor.correspondingPropertySymbol?.owner ?: accessor
        val declaration =
          if (rawDeclaration.isFakeOverride) {
            rawDeclaration.resolveOverriddenTypeIfAny()
          } else {
            rawDeclaration
          }
        val targetFqName = declaration.parentAsClass.kotlinFqName
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
      fun contributedToMultibinding(
        contextKey: ContextualTypeKey,
        declaration: IrDeclarationWithName?,
      ): Entry =
        Entry(
          contextKey = contextKey,
          usage = "is a defined multibinding",
          graphContext = null,
          declaration = declaration,
          isSynthetic = false,
        )

      /*
      com.slack.circuit.star.Example1
       */
      fun simpleTypeRef(contextKey: ContextualTypeKey, usage: String? = null): Entry =
        Entry(
          contextKey = contextKey,
          usage = usage,
          graphContext = null,
          declaration = null,
          isSynthetic = false,
        )

      /*
      java.lang.CharSequence is injected at
            [com.slack.circuit.star.ExampleGraph] com.slack.circuit.star.Example1(…, text2)
      */
      fun injectedAt(
        contextKey: ContextualTypeKey,
        function: IrFunction?,
        param: IrValueParameter? = null,
        declaration: IrDeclarationWithName? = param,
        displayTypeKey: TypeKey = contextKey.typeKey,
        isSynthetic: Boolean = false,
      ): Entry {
        val context =
          if (function == null) {
            "<intrinsic>"
          } else {
            val targetFqName = function.parent.kotlinFqName
            val middle =
              when {
                function is IrConstructor -> ""
                function.isPropertyAccessor ->
                  ".${(function.propertyIfAccessor as IrProperty).name.asString()}"
                else -> ".${function.name.asString()}"
              }
            val end = if (param == null) "()" else "(…, ${param.name.asString()})"
            "$targetFqName$middle$end"
          }
        return Entry(
          contextKey = contextKey,
          displayTypeKey = displayTypeKey,
          usage = "is injected at",
          graphContext = context,
          declaration = declaration,
          isSynthetic = isSynthetic,
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
          get() = emptyList()

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

    operator fun invoke(graph: IrClass, logger: MetroLogger): BindingStack =
      BindingStackImpl(graph, logger)

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

internal class BindingStackImpl(override val graph: IrClass, private val logger: MetroLogger) :
  BindingStack {
  // TODO can we use one structure?
  // TODO can we use scattermap's IntIntMap? Store the typekey hash to its index
  private val entrySet = mutableSetOf<TypeKey>()
  private val stack = ArrayDeque<Entry>()
  override val entries: List<Entry> = stack

  init {
    logger.log("New stack: ${logger.type}")
  }

  override fun push(entry: Entry) {
    val logPrefix =
      if (stack.isEmpty()) {
        "\uD83C\uDF32"
      } else {
        "└─"
      }
    val contextHint =
      if (entry.typeKey != entry.displayTypeKey) "(${entry.typeKey.render(short = true)}) " else ""
    logger.log("$logPrefix $contextHint${entry.toString().withoutLineBreaks}")
    stack.addFirst(entry)
    entrySet.add(entry.typeKey)
    logger.indent()
  }

  override fun pop() {
    logger.unindent()
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
    val index = reversed.indexOfFirst { !it.contextKey.isIntoMultibinding && it.typeKey == key }
    if (index == -1) return emptyList()
    return reversed.slice(index until reversed.size).filterNot { it.isSynthetic }
  }

  override fun toString() = renderTable()

  private fun renderTable(): String {
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
    is Binding.Alias -> {
      Entry.injectedAt(
        contextKey,
        binding.ir,
        binding.parameters.extensionOrFirstParameter?.ir,
        displayTypeKey = targetKey,
      )
    }
    is Binding.Provided -> {
      Entry.injectedAt(
        contextKey,
        binding.providerFactory.providesFunction,
        binding.parameterFor(targetKey),
        displayTypeKey = targetKey,
      )
    }
    is Binding.Assisted -> {
      Entry.injectedAt(contextKey, binding.function, displayTypeKey = targetKey)
    }
    is Binding.MembersInjected -> {
      Entry.injectedAt(contextKey, binding.function, displayTypeKey = targetKey)
    }
    is Binding.Multibinding -> {
      Entry.contributedToMultibinding(contextKey, binding.declaration)
    }
    is Binding.ObjectClass -> TODO()
    is Binding.BoundInstance -> TODO()
    is Binding.GraphDependency -> TODO()
    is Binding.Absent -> error("Should never happen")
  }
}
