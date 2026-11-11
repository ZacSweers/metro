// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.graph

import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer
import dev.zacsweers.metro.compiler.graph.BaseBindingStack
import dev.zacsweers.metro.compiler.graph.LifecycleDiagnosticMessages
import dev.zacsweers.metro.idea.model.ConsumerEntry
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaContextualTypeKey
import dev.zacsweers.metro.idea.model.KaGraphDeclaration
import dev.zacsweers.metro.idea.model.KaTypeKey
import dev.zacsweers.metro.idea.model.KaTypeSnapshot
import dev.zacsweers.metro.idea.model.LifecycleRequest
import org.jetbrains.kotlin.name.FqName

/** The Analysis API analog of the compiler's `IrBindingStack`. */
internal class KaBindingStack(override val graph: KaGraphDeclaration) :
  BaseBindingStack<
    KaGraphDeclaration,
    KaTypeSnapshot,
    KaTypeKey,
    KaBindingStack.Entry,
    KaBindingStack,
  > {
  override val entries = ArrayDeque<Entry>()
  override val graphFqName: FqName =
    graph.classId?.asSingleFqName() ?: graph.name?.let(::FqName) ?: FqName.ROOT

  override fun push(entry: Entry) {
    entries.addFirst(entry)
  }

  override fun pop() {
    entries.removeFirstOrNull() ?: error("Binding stack is empty!")
  }

  override fun copy(): KaBindingStack {
    return KaBindingStack(graph).also { it.entries.addAll(entries) }
  }

  override fun entryFor(key: KaTypeKey): Entry? {
    return entries.firstOrNull { entry -> entry.typeKey == key }
  }

  override fun toString(): String {
    return entries.joinToString(" -> ") { it.toString() }
  }

  class Entry(
    override val contextKey: KaContextualTypeKey,
    override val usage: String? = null,
    override val graphContext: String? = null,
    /** Navigation target for tool-window stack rendering. */
    val pointer: SmartPsiElementPointer<out PsiElement>? = null,
    override val displayTypeKey: KaTypeKey = contextKey.typeKey,
    override val isSynthetic: Boolean = false,
    override val trailingComment: String? = null,
    override val chainRoot: String? = null,
  ) : BaseBindingStack.BaseEntry<KaTypeSnapshot, KaTypeKey, KaContextualTypeKey> {
    override fun toString() = contextKey.toString()

    companion object {
      /** An entry for a graph accessor, injector, or callback parameter requesting [contextKey]. */
      fun requestedAt(
        contextKey: KaContextualTypeKey,
        consumer: ConsumerEntry,
        graph: String,
        name: String?,
      ): Entry {
        val request = consumer.lifecycleRequest
        val signature = request?.let { lifecycleSignature(it, name) }
        if (request != null && signature != null) {
          // Matches the compiler's trace for a callback parameter.
          val label = LifecycleDiagnosticMessages.annotationLabel(request.phase)
          return Entry(
            contextKey = contextKey,
            usage = "is injected at",
            graphContext = "$label $signature",
            pointer = consumer.pointer,
            chainRoot = signature,
          )
        }
        return Entry(
          contextKey = contextKey,
          usage = "is requested at",
          graphContext = name?.let { "$graph.$it" },
          pointer = consumer.pointer,
          isSynthetic = true,
        )
      }

      /** Shows [parameter] in its callback's signature, or null when a name isn't available. */
      private fun lifecycleSignature(request: LifecycleRequest, parameter: String?): String? {
        val callableId = request.declaration.signature.callableId ?: return null
        val owner = callableId.classId ?: return null
        if (parameter == null) {
          return null
        }
        return LifecycleDiagnosticMessages.parameterSignature(
          owner = owner.asSingleFqName().asString(),
          callback = callableId.callableName.asString(),
          parameter = parameter,
          index = request.parameterIndex,
          parameterCount = request.declaration.signature.parameterTypes.size,
        )
      }

      /** An entry for [contextKey] injected as a dependency of [binding]. */
      fun injectedAt(
        contextKey: KaContextualTypeKey,
        binding: KaBinding,
        location: String?,
      ): Entry {
        return Entry(
          contextKey = contextKey,
          usage = "is injected at",
          graphContext = location,
          pointer = binding.pointer,
        )
      }

      /** An entry for a binding provided directly by its declaration. */
      fun providedAt(binding: KaBinding, location: String?): Entry {
        return Entry(
          contextKey = binding.contextualTypeKey,
          usage = "is provided at",
          graphContext = location,
          pointer = binding.pointer,
        )
      }
    }

    fun withTrailingComment(comment: String): Entry {
      return Entry(
        contextKey = contextKey,
        usage = usage,
        graphContext = graphContext,
        pointer = pointer,
        displayTypeKey = displayTypeKey,
        isSynthetic = isSynthetic,
        trailingComment = comment,
        chainRoot = chainRoot,
      )
    }

    /** Keeps the diagnostic route while navigating to its precise injection-site declaration. */
    fun withPointer(pointer: SmartPsiElementPointer<out PsiElement>): Entry {
      return Entry(
        contextKey = contextKey,
        usage = usage,
        graphContext = graphContext,
        pointer = pointer,
        displayTypeKey = displayTypeKey,
        isSynthetic = isSynthetic,
        trailingComment = trailingComment,
        chainRoot = chainRoot,
      )
    }
  }
}
