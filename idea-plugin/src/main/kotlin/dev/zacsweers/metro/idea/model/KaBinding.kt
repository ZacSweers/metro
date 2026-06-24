// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.model

import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer
import org.jetbrains.kotlin.name.ClassId

/**
 * A declaration that originates a binding for [key]. The pointer usually targets a source
 * [org.jetbrains.kotlin.psi.KtElement], but may target a decompiled library declaration for
 * externally-resolved inject classes.
 */
internal class KaBinding(
  val pointer: SmartPsiElementPointer<out PsiElement>,
  val key: KaTypeKey,
  val kind: BindingKind,
  /** Scope annotation, e.g. `@SingleIn(AppScope::class)`, if present. */
  val scope: KaAnnotationSnapshot?,
  /**
   * Short name of the concrete implementation backing this binding when it differs from the key
   * type (e.g. the bound impl class of a `@Binds` or `@ContributesBinding`).
   */
  val implementationName: String?,
  /**
   * For multibinding contributions, the aggregate binding id this element belongs to, mirroring the
   * compiler's `@MultibindingElement(bindingId, ...)` qualifier: the rendered element key for sets,
   * prefixed with the map key type for maps. [key] stays the element key as declared.
   */
  val multibindingId: String? = null,
  /** The contributed/injected class a binding originates from, for excludes/replaces matching. */
  val originClassId: ClassId? = null,
  /**
   * The class whose graph membership gates this binding: the containing binding container for
   * `@Provides`/`@Binds` callables, the owning graph for instance bindings, or the dependency type
   * for included accessors. Null for membership-free bindings (injected classes).
   */
  val containerId: ClassId? = null,
  /** Contribution classes this binding replaces in graphs where both are aggregated. */
  val replaces: Set<ClassId> = emptySet(),
  /** Scopes this binding is contributed to; empty for non-contributed bindings. */
  val contributionScopes: Set<ClassId> = emptySet(),
)
