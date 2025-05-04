// Copyright (C) 2024 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import com.jakewharton.picnic.TextAlignment
import com.jakewharton.picnic.renderText
import com.jakewharton.picnic.table
import dev.zacsweers.metro.compiler.MetroLogger
import dev.zacsweers.metro.compiler.graph.BaseBindingStack
import dev.zacsweers.metro.compiler.graph.BaseTypeKey
import dev.zacsweers.metro.compiler.ir.IrBindingStack.Entry
import dev.zacsweers.metro.compiler.unsafeLazy
import dev.zacsweers.metro.compiler.withoutLineBreaks
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.isPropertyAccessor
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.kotlinFqName
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.propertyIfAccessor
import org.jetbrains.kotlin.name.FqName

internal interface IrBindingStack : BaseBindingStack<IrClass, IrType, IrTypeKey, Entry> {
  class Entry(
    override val contextKey: IrContextualTypeKey,
    override val usage: String?,
    override val graphContext: String?,
    val declaration: IrDeclarationWithName?,
    override val displayTypeKey: IrTypeKey = contextKey.typeKey,
    /**
     * Indicates this entry is informational only and not an actual functional binding that should
     * participate in validation.
     */
    override val isSynthetic: Boolean = false,
  ) : BaseBindingStack.BaseEntry<IrType, IrTypeKey, IrContextualTypeKey> {

    override fun toString(): String = render(FqName("..."), short = true)

    companion object {
      /*
      com.slack.circuit.star.Example1 is requested at
             [com.slack.circuit.star.ExampleGraph] com.slack.circuit.star.ExampleGraph.example1()
       */
      fun requestedAt(contextKey: IrContextualTypeKey, accessor: IrFunction): Entry {
        val declaration =
          if (accessor is IrSimpleFunction) {
            val rawDeclaration = accessor.correspondingPropertySymbol?.owner ?: accessor
            if (rawDeclaration.isFakeOverride) {
              rawDeclaration.resolveOverriddenTypeIfAny()
            } else {
              rawDeclaration
            }
          } else {
            accessor
          }
        val targetFqName = declaration.parentAsClass.kotlinFqName
        val accessorString =
          when (declaration) {
            is IrProperty -> declaration.name.asString()
            is IrConstructor -> targetFqName.shortName().asString() + "()"
            else -> declaration.name.asString() + "()"
          }
        return Entry(
          contextKey = contextKey,
          usage = "is requested at",
          graphContext = "$targetFqName#$accessorString",
          declaration = declaration,
          isSynthetic = true,
        )
      }

      /*
      com.slack.circuit.star.Example1
       */
      fun contributedToMultibinding(
        contextKey: IrContextualTypeKey,
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
      fun simpleTypeRef(contextKey: IrContextualTypeKey, usage: String? = null): Entry =
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
        contextKey: IrContextualTypeKey,
        function: IrFunction?,
        param: IrValueParameter? = null,
        declaration: IrDeclarationWithName? = param,
        displayTypeKey: IrTypeKey = contextKey.typeKey,
        isSynthetic: Boolean = false,
      ): Entry {
        // TODO make some of this lazily evaluated
        val functionToUse =
          if (function is IrSimpleFunction && function.isFakeOverride) {
            function.resolveOverriddenTypeIfAny()
          } else {
            function
          }
        val context =
          if (functionToUse == null) {
            "<intrinsic>"
          } else {
            val targetFqName = functionToUse.parent.kotlinFqName
            val middle =
              when {
                functionToUse is IrConstructor -> ""
                functionToUse.isPropertyAccessor ->
                  "#${(functionToUse.propertyIfAccessor as IrProperty).name.asString()}"
                else -> "#${functionToUse.name.asString()}"
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
        contextualTypeKey: IrContextualTypeKey,
        function: IrFunction,
        displayTypeKey: IrTypeKey = contextualTypeKey.typeKey,
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
      object : IrBindingStack {
        override val graph
          get() = throw UnsupportedOperationException()

        override val graphFqName: FqName
          get() = throw UnsupportedOperationException()

        override val entries: List<Entry>
          get() = emptyList()

        override fun push(entry: Entry) {
          // Do nothing
        }

        override fun pop() {
          // Do nothing
        }

        override fun entryFor(key: IrTypeKey): Entry? {
          return null
        }

        override fun entriesSince(key: IrTypeKey): List<Entry> {
          return emptyList()
        }
      }

    operator fun invoke(graph: IrClass, logger: MetroLogger): IrBindingStack =
      IrBindingStackImpl(graph, logger)

    fun empty() = EMPTY
  }
}

internal inline fun <
  T,
  Type : Any,
  TypeKey : BaseTypeKey<Type, *, *>,
  Entry : BaseBindingStack.BaseEntry<Type, TypeKey, *>,
> BaseBindingStack<*, Type, TypeKey, Entry>.withEntry(entry: Entry?, block: () -> T): T {
  if (entry == null) return block()
  push(entry)
  val result = block()
  pop()
  return result
}

internal val IrBindingStack.lastEntryOrGraph
  get() = entries.firstOrNull()?.declaration ?: graph

internal fun Appendable.appendBindingStack(
  stack: BaseBindingStack<*, *, *, *>,
  indent: String = "    ",
  ellipse: Boolean = false,
  short: Boolean = true,
) = appendBindingStackEntries(stack.graphFqName, stack.entries, indent, ellipse, short)

internal fun Appendable.appendBindingStackEntries(
  graphName: FqName,
  entries: Collection<BaseBindingStack.BaseEntry<*, *, *>>,
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

internal class IrBindingStackImpl(override val graph: IrClass, private val logger: MetroLogger) :
  IrBindingStack {
  override val graphFqName: FqName by unsafeLazy { graph.kotlinFqName }

  // TODO can we use one structure?
  // TODO can we use scattermap's IntIntMap? Store the typekey hash to its index
  private val entrySet = mutableSetOf<IrTypeKey>()
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

  override fun entryFor(key: IrTypeKey): Entry? {
    return if (key in entrySet) {
      stack.first { entry -> entry.typeKey == key }
    } else {
      null
    }
  }

  // TODO optimize this by looking in the entrySet first?
  override fun entriesSince(key: IrTypeKey): List<Entry> {
    // Top entry is always the key currently being processed, so exclude it from analysis with
    // dropLast(1)
    val inFocus = stack.asReversed().dropLast(1)
    if (inFocus.isEmpty()) return emptyList()

    val first =
      inFocus.indexOfFirst {
        !it.contextKey.isIntoMultibinding && !it.isSynthetic && it.typeKey == key
      }
    if (first == -1) return emptyList()

    // path from the earlier duplicate up to the key just below the current one
    return inFocus.subList(first, inFocus.size)
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
  contextKey: IrContextualTypeKey,
  targetKey: IrTypeKey,
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
      Entry.contributedToMultibinding(binding.contextualTypeKey, binding.declaration)
    }
    is Binding.ObjectClass -> TODO()
    is Binding.BoundInstance -> TODO()
    is Binding.GraphDependency -> TODO()
    is Binding.Absent -> error("Should never happen")
  }
}
