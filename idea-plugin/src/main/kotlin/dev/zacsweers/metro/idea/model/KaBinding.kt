// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.model

import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer
import dev.zacsweers.metro.compiler.MetroClassIds
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
 * Most subtypes are built by the index sweep. [Multibinding] nodes, re-keyed multibinding elements,
 * and [GraphInstance] nodes are also synthesized during graph sealing.
 */
internal sealed interface KaBinding :
  BaseBinding<KaTypeSnapshot, KaTypeKey, KaContextualTypeKey>, MergeContribution {
  val pointer: SmartPsiElementPointer<out PsiElement>

  /** Scope annotation, if present. */
  val scope: KaAnnotationSnapshot?
    get() = null

  /** Whether this binding is provided directly by a suspend declaration. */
  val isSuspend: Boolean
    get() = false

  /** Short name of the implementation backing this binding when it differs from the key type. */
  val implementationName: String?
    get() = null

  /** The multibinding id this binding contributes to, or null for regular bindings. */
  val multibindingId: String?
    get() = null

  /** The contributed or injected class a binding originates from. */
  val originClassId: ClassId?
    get() = null

  /** The class whose graph membership gates this binding. */
  val containerId: ClassId?
    get() = null

  /** Concrete binding-container factory input whose membership gates this binding. */
  val includedContainerKey: KaTypeKey?
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
    /** The subset of [dependencies] required by member injection after construction. */
    val memberDependencies: List<KaContextualTypeKey> = emptyList(),
    override val hintAvailability: HintAvailability? = null,
  ) : KaBinding {
    override val contextualTypeKey = typeKey.canonicalContextKey()

    override val label: String
      get() = "injected class"

    val hasInjectedMembers: Boolean
      get() = memberDependencies.isNotEmpty()
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
    override val includedContainerKey: KaTypeKey? = null,
    override val replaces: Set<ClassId> = emptySet(),
    override val contributionScopes: Set<ClassId> = emptySet(),
    override val dependencies: List<KaContextualTypeKey> = emptyList(),
    override val isSuspend: Boolean = false,
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
    override val includedContainerKey: KaTypeKey? = null,
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
   * A `Set`/`Map` multibinding. Index entries anchor `@Multibinds` declarations. Graph sealing
   * synthesizes nodes whose [sourceBindings] are the collected element keys.
   */
  class Multibinding(
    override val pointer: SmartPsiElementPointer<out PsiElement>,
    typeKey: KaTypeKey,
    override val contextualTypeKey: KaContextualTypeKey = typeKey.canonicalContextKey(),
    override val scope: KaAnnotationSnapshot? = null,
    override val originClassId: ClassId? = null,
    override val containerId: ClassId? = null,
    override val includedContainerKey: KaTypeKey? = null,
    override val replaces: Set<ClassId> = emptySet(),
    override val contributionScopes: Set<ClassId> = emptySet(),
    /** Whether the declaration permits an empty multibinding. */
    val allowEmpty: Boolean = false,
    val sourceBindings: List<KaTypeKey> = emptyList(),
    override val hintAvailability: HintAvailability? = null,
  ) : KaBinding {
    override val dependencies: List<KaContextualTypeKey> = sourceBindings.map {
      it.canonicalContextKey()
    }

    override val label: String
      get() = if (sourceBindings.isEmpty()) "multibinding declaration" else "multibinding"
  }

  /** An instance supplied by a graph factory through `@Provides` or graph-like `@Includes`. */
  class BoundInstance(
    override val pointer: SmartPsiElementPointer<out PsiElement>,
    typeKey: KaTypeKey,
    override val containerId: ClassId?,
    /** True for a graph dependency supplied through a factory `@Includes` parameter. */
    val isGraphInput: Boolean = false,
    /** True for a concrete binding container supplied through a factory `@Includes` parameter. */
    val isBindingContainerInput: Boolean = false,
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
    val targetConstructorDependencies: List<KaContextualTypeKey> = emptyList(),
    val targetMemberDependencies: List<KaContextualTypeKey> = emptyList(),
    val factoryFunctionName: String? = null,
    val factoryFunctionIsSuspend: Boolean = false,
  ) : KaBinding {
    override val contextualTypeKey = typeKey.canonicalContextKey()

    // Mirrors IR: target dependencies participate in graph population and cycle detection through
    // deferred Provider edges. Suspend validation inspects the unwrapped target lists above.
    override val dependencies: List<KaContextualTypeKey> =
      (targetConstructorDependencies + targetMemberDependencies).map { it.wrapInProvider() }

    override val isImplicitlyDeferrable: Boolean
      get() = true

    override val label: String
      get() = "assisted factory"
  }

  /** An accessor of an `@Includes` graph dependency. */
  class GraphDependency(
    override val pointer: SmartPsiElementPointer<out PsiElement>,
    override val contextualTypeKey: KaContextualTypeKey,
    /** The included graph object on which this accessor is invoked. */
    val ownerKey: KaTypeKey,
    /** Whether the included graph accessor itself is suspend. */
    val accessorIsSuspend: Boolean,
  ) : KaBinding {
    override val dependencies: List<KaContextualTypeKey> = listOf(ownerKey.canonicalContextKey())

    override val label: String
      get() = "included dependency accessor"

    override val isSuspend: Boolean
      get() = accessorIsSuspend || contextualTypeKey.wrappedType.requiresSuspendToUnwrap()

    /** Whether the accessor can return this exact wrapper value without unwrapping it. */
    fun canPassThrough(request: KaContextualTypeKey): Boolean {
      return !accessorIsSuspend && contextualTypeKey == request
    }
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

  /** A child graph created by an accessor on [ownerKey]. Seal-time node. */
  class GraphExtension(
    override val pointer: SmartPsiElementPointer<out PsiElement>,
    typeKey: KaTypeKey,
    val ownerKey: KaTypeKey,
  ) : KaBinding {
    override val contextualTypeKey = typeKey.canonicalContextKey()

    override val dependencies: List<KaContextualTypeKey> = listOf(ownerKey.canonicalContextKey())

    override val label: String
      get() = "graph extension"
  }

  /** A `@BindsOptionalOf` (Dagger interop) binding exposing `Optional<T>`. */
  class CustomWrapper(
    override val pointer: SmartPsiElementPointer<out PsiElement>,
    typeKey: KaTypeKey,
    /** The wrapped `T`, marked as defaulted so the wrapper remains valid when `T` is absent. */
    val wrappedContextKey: KaContextualTypeKey,
    override val implementationName: String?,
    override val originClassId: ClassId? = null,
    override val containerId: ClassId? = null,
    override val includedContainerKey: KaTypeKey? = null,
    override val replaces: Set<ClassId> = emptySet(),
    override val contributionScopes: Set<ClassId> = emptySet(),
    override val hintAvailability: HintAvailability? = null,
  ) : KaBinding {
    override val contextualTypeKey = typeKey.canonicalContextKey()

    override val dependencies: List<KaContextualTypeKey> = listOf(wrappedContextKey)

    override val label: String
      get() = "optional binding"
  }
}

internal fun KaTypeKey.canonicalContextKey(): KaContextualTypeKey {
  return KaContextualTypeKey(this, WrappedType.Canonical(type))
}

private fun KaContextualTypeKey.wrapInProvider(): KaContextualTypeKey {
  return KaContextualTypeKey(
    typeKey = typeKey,
    wrappedType = WrappedType.Provider(wrappedType, MetroClassIds.provider),
    hasDefault = hasDefault,
  )
}
