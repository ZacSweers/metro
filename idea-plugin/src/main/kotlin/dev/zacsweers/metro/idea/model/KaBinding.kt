// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.model

import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer
import dev.zacsweers.metro.compiler.graph.MergeContribution
import org.jetbrains.kotlin.name.ClassId

/**
 * A declaration that originates a binding for [key]. The Analysis API analog of the compiler's
 * `IrBinding`, with subtypes named after their IR counterparts. The pointer usually targets a
 * source declaration, but may target a decompiled library declaration.
 */
internal sealed interface KaBinding : MergeContribution {
  val pointer: SmartPsiElementPointer<out PsiElement>
  val key: KaTypeKey

  /** Scope annotation, if present. */
  val scope: KaAnnotationSnapshot?
    get() = null

  /** Short name of the implementation backing this binding when it differs from the key type. */
  val implementationName: String?
    get() = null

  /** The aggregate id this binding contributes to, or null for non-multibinding bindings. */
  val multibindingId: String?
    get() = null

  /** The contributed or injected class a binding originates from. */
  val originClassId: ClassId?
    get() = null

  /** The class whose graph membership gates this binding. */
  val containerId: ClassId?
    get() = null

  override val replaces: Set<ClassId>
    get() = emptySet()

  /** Scopes this binding is contributed to. */
  val contributionScopes: Set<ClassId>
    get() = emptySet()

  /** The keys this binding consumes to construct its value. */
  val dependencies: List<KaContextualTypeKey>
    get() = emptyList()

  /**
   * A stable render of an `@IntoMap` contribution's map key annotation, such as `@StringKey("a")`.
   * Duplicate map key detection groups by this value.
   */
  val mapKeyValue: String?
    get() = null

  /** Human-readable kind label for markers and popups. */
  val label: String

  /** A binding is excluded/replaced by its originating contribution class. */
  override val mergeId: ClassId?
    get() = originClassId

  /** A constructor-injected class providing its own type. */
  class ConstructorInjected(
    override val pointer: SmartPsiElementPointer<out PsiElement>,
    override val key: KaTypeKey,
    override val scope: KaAnnotationSnapshot?,
    override val implementationName: String?,
    override val originClassId: ClassId? = null,
    override val replaces: Set<ClassId> = emptySet(),
    override val contributionScopes: Set<ClassId> = emptySet(),
    override val dependencies: List<KaContextualTypeKey> = emptyList(),
  ) : KaBinding {
    override val label: String
      get() = "injected class"
  }

  /** A `@Provides` callable, or a generated factory contribution modeled as one. */
  class Provided(
    override val pointer: SmartPsiElementPointer<out PsiElement>,
    override val key: KaTypeKey,
    override val scope: KaAnnotationSnapshot? = null,
    override val implementationName: String? = null,
    override val multibindingId: String? = null,
    override val mapKeyValue: String? = null,
    override val originClassId: ClassId? = null,
    override val containerId: ClassId? = null,
    override val replaces: Set<ClassId> = emptySet(),
    override val contributionScopes: Set<ClassId> = emptySet(),
    override val dependencies: List<KaContextualTypeKey> = emptyList(),
  ) : KaBinding {
    override val label: String
      get() = if (multibindingId != null) "multibinding contribution" else "provides"
  }

  /** A `@Binds` callable or contributed binding aliasing [consumedKey]. */
  class Alias(
    override val pointer: SmartPsiElementPointer<out PsiElement>,
    override val key: KaTypeKey,
    /** The source binding this delegates to, when the aliased class originates one. */
    val consumedKey: KaContextualTypeKey?,
    override val scope: KaAnnotationSnapshot? = null,
    override val implementationName: String? = null,
    override val multibindingId: String? = null,
    override val mapKeyValue: String? = null,
    override val originClassId: ClassId? = null,
    override val containerId: ClassId? = null,
    override val replaces: Set<ClassId> = emptySet(),
    override val contributionScopes: Set<ClassId> = emptySet(),
    /** True for `@ContributesBinding`-style class contributions, false for `@Binds` callables. */
    val isClassContribution: Boolean = false,
  ) : KaBinding {
    override val dependencies: List<KaContextualTypeKey>
      get() = listOfNotNull(consumedKey)

    override val label: String
      get() =
        when {
          multibindingId != null -> "multibinding contribution"
          isClassContribution -> "contributed binding"
          else -> "binds"
        }
  }

  /** A `@Multibinds` declaration of a `Set`/`Map` aggregate. */
  class Multibinding(
    override val pointer: SmartPsiElementPointer<out PsiElement>,
    override val key: KaTypeKey,
    override val scope: KaAnnotationSnapshot? = null,
    override val originClassId: ClassId? = null,
    override val containerId: ClassId? = null,
    override val replaces: Set<ClassId> = emptySet(),
    override val contributionScopes: Set<ClassId> = emptySet(),
  ) : KaBinding {
    override val label: String
      get() = "multibinding declaration"
  }

  /** An instance binding from a graph factory `@Provides` parameter. */
  class BoundInstance(
    override val pointer: SmartPsiElementPointer<out PsiElement>,
    override val key: KaTypeKey,
    override val containerId: ClassId?,
  ) : KaBinding {
    override val label: String
      get() = "instance binding"
  }

  /** An `@AssistedFactory` providing its own type. */
  class AssistedFactory(
    override val pointer: SmartPsiElementPointer<out PsiElement>,
    override val key: KaTypeKey,
    override val scope: KaAnnotationSnapshot?,
    override val implementationName: String?,
    override val originClassId: ClassId? = null,
    override val dependencies: List<KaContextualTypeKey> = emptyList(),
  ) : KaBinding {
    override val label: String
      get() = "assisted factory"
  }

  /** An accessor of an `@Includes` graph dependency. */
  class GraphDependency(
    override val pointer: SmartPsiElementPointer<out PsiElement>,
    override val key: KaTypeKey,
    override val containerId: ClassId?,
  ) : KaBinding {
    override val label: String
      get() = "included dependency accessor"
  }

  /** A `@BindsOptionalOf` (Dagger interop) binding exposing `Optional<T>`. */
  class CustomWrapper(
    override val pointer: SmartPsiElementPointer<out PsiElement>,
    override val key: KaTypeKey,
    override val implementationName: String?,
    override val originClassId: ClassId? = null,
    override val containerId: ClassId? = null,
    override val replaces: Set<ClassId> = emptySet(),
    override val contributionScopes: Set<ClassId> = emptySet(),
  ) : KaBinding {
    override val label: String
      get() = "optional binding"
  }
}
