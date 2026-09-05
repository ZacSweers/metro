// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index.snapshot

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer
import dev.zacsweers.metro.idea.checkCanceledEvery
import dev.zacsweers.metro.idea.index.DeclarationAnchorSignature
import dev.zacsweers.metro.idea.model.BindingIndex
import dev.zacsweers.metro.idea.model.BindingIndexBuilder
import dev.zacsweers.metro.idea.model.DeclarationDisplay
import dev.zacsweers.metro.idea.model.IndexGenerationToken
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.sourcePointerIdentity
import java.util.Collections
import java.util.IdentityHashMap
import org.jetbrains.kotlin.psi.KtElement
import org.jetbrains.kotlin.psi.KtNamedDeclaration

/**
 * Captures source ranges and anchors for a new index inside the coordinator's read action.
 *
 * Declaration pointers are captured again for each builder. Module visibility can be reused while
 * the captured files and project inputs stay unchanged.
 */
internal class ResolutionInputCapture(
  project: Project,
  private val onDeclarationSignatures:
    (
      IndexGenerationToken,
      Map<BindingIndex.SourcePointerIdentity, DeclarationAnchorSignature>,
    ) -> Unit,
) {
  private val visibility = ModuleVisibilityCapture(project)

  /** Copies live pointer data while PSI access is valid. Calls are coordinator-owned. */
  fun capture(builder: BindingIndexBuilder, declarationSignatureFiles: Set<VirtualFile>) {
    with(builder) {
      val representatives = linkedMapOf<VirtualFile, PsiElement>()
      val pointerSourceIdentities =
        IdentityHashMap<SmartPsiElementPointer<*>, BindingIndex.SourcePointerIdentity>()
      val declarationDisplays = IdentityHashMap<SmartPsiElementPointer<*>, DeclarationDisplay>()
      val capturedDeclarationSignatures =
        linkedMapOf<BindingIndex.SourcePointerIdentity, DeclarationAnchorSignature>()
      val ambiguousDeclarationSignatures = mutableSetOf<BindingIndex.SourcePointerIdentity>()
      val capturedBindings = Collections.newSetFromMap(IdentityHashMap<KaBinding, Boolean>())
      var pointerCaptureWorkIndex = 0

      fun capture(
        pointer: SmartPsiElementPointer<*>,
        captureAnchorSignature: Boolean = false,
      ) {
        checkCanceledEvery(pointerCaptureWorkIndex++)
        val identity = sourcePointerIdentity(pointer)
        if (identity != null) pointerSourceIdentities[pointer] = identity
        val file = pointer.virtualFile ?: return
        val needsAnchorSignature = captureAnchorSignature && file in declarationSignatureFiles
        val needsDisplay = pointer !in declarationDisplays
        if (!needsAnchorSignature && !needsDisplay && file in representatives) return
        val element = pointer.element ?: return
        representatives.putIfAbsent(file, element)
        if (needsDisplay) {
          val containingFile = element.containingFile
          val document = containingFile?.viewProvider?.document
          val line = document?.getLineNumber(element.textOffset)?.plus(1)
          val location = if (line == null) containingFile?.name else "${containingFile.name}:$line"
          declarationDisplays[pointer] =
            DeclarationDisplay((element as? KtNamedDeclaration)?.name, location)
        }
        if (!needsAnchorSignature || identity == null) return
        val ktElement = element as? KtElement ?: return
        val currentIdentity =
          BindingIndex.SourcePointerIdentity(
            file,
            ktElement.textRange.startOffset,
            ktElement.textRange.endOffset,
          )
        if (currentIdentity != identity) return
        val signature = DeclarationAnchorSignature.capture(ktElement)
        val existing = capturedDeclarationSignatures.putIfAbsent(identity, signature)
        if (existing != null && existing != signature) {
          ambiguousDeclarationSignatures += identity
        }
      }

      fun captureBinding(binding: KaBinding) {
        capturedBindings += binding
        capture(binding.pointer, captureAnchorSignature = true)
      }

      for (binding in bindings) captureBinding(binding)
      for (consumer in consumers) {
        capture(consumer.pointer, captureAnchorSignature = true)
        consumer.injectedMemberPointer?.let { pointer -> capture(pointer) }
      }
      for (graph in graphs) {
        capture(graph.pointer, captureAnchorSignature = true)
        for (factory in graph.extensionFactories) capture(factory.pointer)
        for (implementation in graph.defaultImplementations) {
          capture(implementation.declaration.pointer)
          for (overridden in implementation.overriddenDeclarations) capture(overridden.pointer)
        }
        for (contribution in graph.contributedInterfaces) {
          capture(contribution.contribution.pointer)
          for (binding in contribution.bindings) captureBinding(binding)
          for (consumer in contribution.consumers) {
            capture(consumer.pointer, captureAnchorSignature = true)
          }
          for (factory in contribution.extensionFactories) capture(factory.pointer)
          for (implementation in contribution.defaultImplementations) {
            capture(implementation.declaration.pointer)
            for (overridden in implementation.overriddenDeclarations) capture(overridden.pointer)
          }
        }
      }
      for (contribution in contributions) capture(contribution.pointer)
      for (site in assistedSites) {
        capture(site.pointer, captureAnchorSignature = true)
      }
      for (container in bindingContainers) capture(container.pointer)
      for (dynamicGraph in dynamicGraphs) {
        capture(dynamicGraph.pointer)
        for (input in dynamicGraph.containerInputs) captureBinding(input)
      }
      capturedBindingSourceIdentities =
        IdentityHashMap<KaBinding, BindingIndex.SourcePointerIdentity>().apply {
          for (binding in capturedBindings) {
            checkCanceledEvery(pointerCaptureWorkIndex++)
            pointerSourceIdentities[binding.pointer]?.let { identity -> put(binding, identity) }
          }
        }
      if (declarationSignatureFiles.isNotEmpty()) {
        ambiguousDeclarationSignatures.forEach(capturedDeclarationSignatures::remove)
        onDeclarationSignatures(generationToken, capturedDeclarationSignatures)
      }

      resolutionInputs =
        visibility.capture(representatives, pointerSourceIdentities, declarationDisplays)
    }
  }

  /** Releases cached module views when the coordinator stops. */
  fun clear() {
    visibility.clear()
  }
}
