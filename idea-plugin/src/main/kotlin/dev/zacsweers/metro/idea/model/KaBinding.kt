// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.model

import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer
import dev.zacsweers.metro.compiler.graph.BaseBinding
import dev.zacsweers.metro.compiler.graph.LocationDiagnostic
import dev.zacsweers.metro.compiler.graph.MergeContribution
import dev.zacsweers.metro.compiler.graph.WrappedType
import org.jetbrains.kotlin.name.ClassId

/**
 * A declaration that originates a binding for [typeKey]. The Analysis API analog of the compiler's
 * `IrBinding`, with subtypes named after their IR counterparts. The pointer usually targets a
 * source declaration, but may target a decompiled library declaration.
 *
 * Most subtypes are built by the index sweep. [Multibinding] aggregates, re-keyed multibinding
 * elements, and [GraphInstance] nodes are also synthesized during graph sealing.
 */
internal sealed interface KaBinding :
  BaseBinding<KaTypeSnapshot, KaTypeKey, KaContextualTypeKey>, MergeContribution {
  val pointer: SmartPsiElementPointer<out PsiElement>

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

  /** Module restriction inherited from a non-public compiled contribution hint. */
  val hintAvailability: HintAvailability?
    get() = null

  override val dependencies: List<KaContextualTypeKey>
    get() = emptyList()

  /**
   * A stable render of an `@IntoMap` contribution's map key annotation, such as `@StringKey("a")`.
   * Duplicate map key detection groups by this value.
   */
  val mapKeyValue: String?
    get() = null

  /** Human-readable kind label for markers, popups, and diagnostics. */
  val label: String

  /** A binding is excluded/replaced by its originating contribution class. */
  override val mergeId: ClassId?
    get() = originClassId

  /** Session-free display location, such as `Providers.kt:12`, when the pointer resolves. */
  fun location(): String? {
    val element = pointer.element ?: return null
    val file = element.containingFile ?: return null
    val document = file.viewProvider.document
    val line = document?.getLineNumber(element.textOffset)?.plus(1)
    return if (line != null) "${file.name}:$line" else file.name
  }

  override fun renderLocationDiagnostic(
    short: Boolean,
    shortLocation: Boolean,
    underlineTypeKey: Boolean,
  ): LocationDiagnostic {
    return LocationDiagnostic(
      location() ?: typeKey.render(short = true),
      renderDescriptionDiagnostic(short = short, underlineTypeKey = underlineTypeKey),
    )
  }

  override fun renderDescriptionDiagnostic(short: Boolean, underlineTypeKey: Boolean): String {
    return "${typeKey.render(short = short)} ($label)"
  }

  /** A constructor-injected class providing its own type. */
  class ConstructorInjected(
    override val pointer: SmartPsiElementPointer<out PsiElement>,
    typeKey: KaTypeKey,
    override val scope: KaAnnotationSnapshot?,
    override val implementationName: String?,
    override val originClassId: ClassId? = null,
    override val replaces: Set<ClassId> = emptySet(),
    override val contributionScopes: Set<ClassId> = emptySet(),
    override val dependencies: List<KaContextualTypeKey> = emptyList(),
    override val hintAvailability: HintAvailability? = null,
  ) : KaBinding {
    override val contextualTypeKey = typeKey.canonicalContextKey()

    override val label: String
      get() = "injected class"
  }

  /** A `@Provides` callable, or a generated factory contribution modeled as one. */
  class Provided(
    override val pointer: SmartPsiElementPointer<out PsiElement>,
    typeKey: KaTypeKey,
    override val scope: KaAnnotationSnapshot? = null,
    override val implementationName: String? = null,
    override val multibindingId: String? = null,
    override val mapKeyValue: String? = null,
    override val originClassId: ClassId? = null,
    override val containerId: ClassId? = null,
    override val replaces: Set<ClassId> = emptySet(),
    override val contributionScopes: Set<ClassId> = emptySet(),
    override val dependencies: List<KaContextualTypeKey> = emptyList(),
    override val hintAvailability: HintAvailability? = null,
  ) : KaBinding {
    override val contextualTypeKey = typeKey.canonicalContextKey()

    override val label: String
      get() = if (multibindingId != null) "multibinding contribution" else "provides"
  }

  /** A `@Binds` callable or contributed binding aliasing [consumedKey]. */
  class Alias(
    override val pointer: SmartPsiElementPointer<out PsiElement>,
    typeKey: KaTypeKey,
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
    override val hintAvailability: HintAvailability? = null,
  ) : KaBinding {
    override val contextualTypeKey = typeKey.canonicalContextKey()

    override val isAlias: Boolean
      get() = true

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

  /**
   * A `Set`/`Map` aggregate. Index entries anchor `@Multibinds` declarations. Graph sealing
   * synthesizes aggregate nodes whose [dependencies] are the collected element keys.
   */
  class Multibinding(
    override val pointer: SmartPsiElementPointer<out PsiElement>,
    typeKey: KaTypeKey,
    override val scope: KaAnnotationSnapshot? = null,
    override val originClassId: ClassId? = null,
    override val containerId: ClassId? = null,
    override val replaces: Set<ClassId> = emptySet(),
    override val contributionScopes: Set<ClassId> = emptySet(),
    /** Whether the declaration permits an empty aggregate via `@Multibinds(allowEmpty = true)`. */
    val allowEmpty: Boolean = false,
    override val dependencies: List<KaContextualTypeKey> = emptyList(),
    override val hintAvailability: HintAvailability? = null,
  ) : KaBinding {
    override val contextualTypeKey = typeKey.canonicalContextKey()

    override val label: String
      get() = if (dependencies.isEmpty()) "multibinding declaration" else "multibinding"
  }

  /** An instance binding from a graph factory `@Provides` parameter. */
  class BoundInstance(
    override val pointer: SmartPsiElementPointer<out PsiElement>,
    typeKey: KaTypeKey,
    override val containerId: ClassId?,
  ) : KaBinding {
    override val contextualTypeKey = typeKey.canonicalContextKey()

    override val isImplicitlyDeferrable: Boolean
      get() = true

    override val label: String
      get() = "instance binding"
  }

  /** An `@AssistedFactory` providing its own type. */
  class AssistedFactory(
    override val pointer: SmartPsiElementPointer<out PsiElement>,
    typeKey: KaTypeKey,
    override val scope: KaAnnotationSnapshot?,
    override val implementationName: String?,
    override val originClassId: ClassId? = null,
    override val dependencies: List<KaContextualTypeKey> = emptyList(),
  ) : KaBinding {
    override val contextualTypeKey = typeKey.canonicalContextKey()

    override val isImplicitlyDeferrable: Boolean
      get() = true

    override val label: String
      get() = "assisted factory"
  }

  /** An accessor of an `@Includes` graph dependency. */
  class GraphDependency(
    override val pointer: SmartPsiElementPointer<out PsiElement>,
    typeKey: KaTypeKey,
    override val containerId: ClassId?,
  ) : KaBinding {
    override val contextualTypeKey = typeKey.canonicalContextKey()

    override val label: String
      get() = "included dependency accessor"
  }

  /** The graph (or a parent in its extension chain) provided as its own type. Seal-time node. */
  class GraphInstance(
    override val pointer: SmartPsiElementPointer<out PsiElement>,
    typeKey: KaTypeKey,
  ) : KaBinding {
    override val contextualTypeKey = typeKey.canonicalContextKey()

    override val isImplicitlyDeferrable: Boolean
      get() = true

    override val label: String
      get() = "graph instance"
  }

  /** A `@BindsOptionalOf` (Dagger interop) binding exposing `Optional<T>`. */
  class CustomWrapper(
    override val pointer: SmartPsiElementPointer<out PsiElement>,
    typeKey: KaTypeKey,
    override val implementationName: String?,
    override val originClassId: ClassId? = null,
    override val containerId: ClassId? = null,
    override val replaces: Set<ClassId> = emptySet(),
    override val contributionScopes: Set<ClassId> = emptySet(),
    override val hintAvailability: HintAvailability? = null,
  ) : KaBinding {
    override val contextualTypeKey = typeKey.canonicalContextKey()

    override val label: String
      get() = "optional binding"
  }
}

private fun KaTypeKey.canonicalContextKey(): KaContextualTypeKey {
  return KaContextualTypeKey(this, WrappedType.Canonical(type))
}
