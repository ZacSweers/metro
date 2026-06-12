// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer
import dev.zacsweers.metro.compiler.graph.BaseContextualTypeKey
import dev.zacsweers.metro.compiler.graph.BaseTypeKey
import dev.zacsweers.metro.compiler.graph.WrappedType
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.types.KaType
import org.jetbrains.kotlin.analysis.api.types.KaTypePointer
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtElement

/** A structured, session-free snapshot of a resolved annotation argument value. */
internal sealed interface MetroKaAnnotationValue {
  data class Literal(val value: Any?) : MetroKaAnnotationValue

  data class EnumEntry(val callableId: CallableId?) : MetroKaAnnotationValue

  data class KClassRef(val classId: ClassId?) : MetroKaAnnotationValue

  data class Array(val values: List<MetroKaAnnotationValue>) : MetroKaAnnotationValue

  data class Nested(val annotation: MetroKaAnnotation) : MetroKaAnnotationValue

  data object Unsupported : MetroKaAnnotationValue
}

/**
 * An annotation participating in key/scope identity (a qualifier like `@Named("cdn")` or a scope
 * like `@SingleIn(AppScope::class)`). The Analysis API analog of the compiler's
 * `MetroFirAnnotation`/`IrAnnotation`, with the same canonical-render equality semantics — but
 * built from the structured resolved argument values rather than source text, so spelling
 * differences (named vs positional args, import styles) don't break identity.
 */
internal data class MetroKaAnnotation(
  val classId: ClassId,
  val arguments: List<Pair<Name, MetroKaAnnotationValue>>,
) {
  fun render(short: Boolean): String = buildString {
    append('@')
    append(if (short) classId.shortClassName.asString() else classId.asFqNameString())
    if (arguments.isNotEmpty()) {
      arguments.joinTo(this, separator = ", ", prefix = "(", postfix = ")") { (name, value) ->
        "${name.asString()} = ${renderValue(value, short)}"
      }
    }
  }

  private fun renderValue(value: MetroKaAnnotationValue, short: Boolean): String {
    return when (value) {
      is MetroKaAnnotationValue.Literal ->
        when (val literal = value.value) {
          is String -> "\"$literal\""
          is Char -> "'$literal'"
          else -> literal.toString()
        }
      is MetroKaAnnotationValue.EnumEntry ->
        value.callableId?.let {
          if (short) it.callableName.asString() else it.asSingleFqName().asString()
        } ?: "<error>"
      is MetroKaAnnotationValue.KClassRef ->
        value.classId?.let {
          val name = if (short) it.shortClassName.asString() else it.asFqNameString()
          "$name::class"
        } ?: "<error>"
      is MetroKaAnnotationValue.Array ->
        value.values.joinToString(separator = ", ", prefix = "[", postfix = "]") {
          renderValue(it, short)
        }
      is MetroKaAnnotationValue.Nested -> value.annotation.render(short)
      is MetroKaAnnotationValue.Unsupported -> "..."
    }
  }
}

/**
 * A session-free snapshot of a [KaType].
 *
 * [pointer] can restore the semantic type inside a [KaSession], while [renderedType] and
 * [shortType] give cached keys and UI text to cross-session indexes. Equality is structural by
 * [renderedType]; Analysis API pointers are intentionally excluded because two pointers can point
 * at equivalent type renderings while still being different pointer objects.
 */
internal class KaTypeSnapshot(
  val pointer: KaTypePointer<KaType>,
  val renderedType: String,
  val shortType: String = renderedType,
  val classId: ClassId?,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is KaTypeSnapshot) return false
    return renderedType == other.renderedType
  }

  override fun hashCode(): Int = renderedType.hashCode()

  override fun toString(): String = renderedType
}

/** The Analysis API analog of the compiler's `FirTypeKey`/`IrTypeKey`. */
internal class KaTypeKey(
  override val type: KaTypeSnapshot,
  override val qualifier: MetroKaAnnotation? = null,
) : BaseTypeKey<KaTypeSnapshot, MetroKaAnnotation, KaTypeKey> {
  val renderedType: String
    get() = type.renderedType

  override fun copy(type: KaTypeSnapshot, qualifier: MetroKaAnnotation?): KaTypeKey {
    return KaTypeKey(type, qualifier)
  }

  override fun render(short: Boolean, includeQualifier: Boolean): String = buildString {
    if (includeQualifier) {
      qualifier?.let {
        append(it.render(short))
        append(' ')
      }
    }
    append(if (short) type.shortType else renderedType)
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is KaTypeKey) return false
    return renderedType == other.renderedType && qualifier == other.qualifier
  }

  override fun hashCode(): Int = 31 * renderedType.hashCode() + (qualifier?.hashCode() ?: 0)

  override fun toString(): String = render(short = false)

  override fun compareTo(other: KaTypeKey): Int {
    if (this == other) return 0
    return toString().compareTo(other.toString())
  }
}

/** The Analysis API analog of the compiler's contextual type key. */
internal class KaContextualTypeKey(
  override val typeKey: KaTypeKey,
  override val wrappedType: WrappedType<KaTypeSnapshot>,
  override val hasDefault: Boolean = false,
  override val rawType: KaTypeSnapshot? = null,
) : BaseContextualTypeKey<KaTypeSnapshot, KaTypeKey, KaContextualTypeKey> {
  override fun render(short: Boolean, includeQualifier: Boolean): String {
    return wrappedType.render { snapshot ->
      if (snapshot == typeKey.type) {
        typeKey.render(short, includeQualifier)
      } else if (short) {
        snapshot.shortType
      } else {
        snapshot.renderedType
      }
    }
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is KaContextualTypeKey) return false
    return typeKey == other.typeKey && wrappedType == other.wrappedType
  }

  override fun hashCode(): Int = 31 * typeKey.hashCode() + wrappedType.hashCode()

  override fun toString(): String = render(short = false)
}

internal enum class MetroProviderKind(val label: String) {
  /** A `@Provides` callable. */
  PROVIDES("provides"),
  /** A `@Binds` callable. */
  BINDS("binds"),
  /** An injected class providing its own type. */
  INJECT("injected class"),
  /** A `@ContributesBinding`-style class bound to a supertype. */
  CONTRIBUTED("contributed binding"),
  /** An `@IntoSet`/`@ContributesIntoSet`-style multibinding contribution. */
  MULTIBINDING_CONTRIBUTION("multibinding contribution"),
  /** A `@Multibinds` declaration. */
  MULTIBINDING_DECLARATION("multibinding declaration"),
  /** An instance binding from a graph factory `@Provides` parameter. */
  INSTANCE("instance binding"),
}

/**
 * A declaration that originates a binding for [key]. The pointer usually targets a source
 * [KtElement], but may target a decompiled library declaration for externally-resolved inject
 * classes.
 */
internal class MetroProviderEntry(
  val pointer: SmartPsiElementPointer<out PsiElement>,
  val key: KaTypeKey,
  val kind: MetroProviderKind,
  /** Scope annotation, e.g. `@SingleIn(AppScope::class)`, if present. */
  val scope: MetroKaAnnotation?,
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
)

/** A site that consumes a binding for [key]: an injected parameter/property or graph accessor. */
internal class MetroConsumerEntry(
  val pointer: SmartPsiElementPointer<out KtElement>,
  val key: KaTypeKey,
  /** Whether the declared type is an interface or abstract class (drives implementation inlays). */
  val isAbstractType: Boolean = false,
  /** For `Set`/`Map` aggregate sites, the multibinding id collecting contributed elements. */
  val multibindingId: String? = null,
  /** The consumed type's class, when it is a class type. Used to resolve library inject classes. */
  val typeClassId: ClassId? = null,
)

/**
 * A parameter supplied at runtime rather than injected from the graph: `@Assisted` parameters and
 * Circuit-provided types (`Screen`, `Navigator`, etc.) on `@CircuitInject` declarations.
 */
internal class MetroAssistedSite(
  val pointer: SmartPsiElementPointer<out KtElement>,
  /** Short description of what supplies the value, e.g. `@Assisted` or `Circuit`. */
  val supplier: String,
  /**
   * True when nothing in the source marks the parameter as assisted (e.g. Circuit-provided types),
   * as opposed to an explicit `@Assisted` annotation. Implicit sites get an `assisted` inlay;
   * explicit ones don't need a second marker.
   */
  val isImplicit: Boolean,
)

/** A `@DependencyGraph`-annotated class and its aggregation scope classes. */
internal class MetroGraphEntry(
  val pointer: SmartPsiElementPointer<KtClassOrObject>,
  val scopeKeys: Set<ClassId>,
)

/**
 * A declaration contributing to aggregation scopes: a `@Contributes*`-annotated class or a
 * `@CircuitInject`-annotated declaration whose generated factory is contributed.
 */
internal class MetroContributionEntry(
  val pointer: SmartPsiElementPointer<out KtElement>,
  val scopeKeys: Set<ClassId>,
)

/**
 * Project-wide snapshot of Metro declarations, built from stub indexes + the Analysis API.
 *
 * Resolution is key-based across the whole project; per-graph membership (replacements, exclusions,
 * includes) is not modeled.
 */
internal class MetroBindingIndex(
  val providers: List<MetroProviderEntry>,
  val consumers: List<MetroConsumerEntry>,
  val graphs: List<MetroGraphEntry>,
  val contributions: List<MetroContributionEntry>,
  val assistedSites: List<MetroAssistedSite> = emptyList(),
) {
  // Contributions are keyed solely by multibindingId, mirroring the compiler's
  // @MultibindingElement qualifier swap — their element key must not satisfy plain consumers.
  private val providersByKey: Map<KaTypeKey, List<MetroProviderEntry>> by lazy {
    providers.filter { it.multibindingId == null }.groupBy { it.key }
  }
  private val consumersByKey: Map<KaTypeKey, List<MetroConsumerEntry>> by lazy {
    consumers.groupBy { it.key }
  }
  private val contributionsByMultibindingId: Map<String, List<MetroProviderEntry>> by lazy {
    providers.filter { it.multibindingId != null }.groupBy { it.multibindingId!! }
  }
  private val consumersByMultibindingId: Map<String, List<MetroConsumerEntry>> by lazy {
    consumers.filter { it.multibindingId != null }.groupBy { it.multibindingId!! }
  }

  // PSI-identity lookups for editor features classifying the element under the caret/pass.
  // Pointers deref to stable PSI within one index lifetime (the index invalidates on any
  // out-of-block change). Must be accessed in a read action.
  private val providersByElement: Map<PsiElement, List<MetroProviderEntry>> by lazy {
    providers
      .mapNotNull { entry -> entry.pointer.element?.let { it to entry } }
      .groupBy({ it.first }, { it.second })
  }
  private val consumersByElement: Map<KtElement, MetroConsumerEntry> by lazy {
    consumers.mapNotNull { entry -> entry.pointer.element?.let { it to entry } }.toMap()
  }
  private val graphsByElement: Map<KtClassOrObject, MetroGraphEntry> by lazy {
    graphs.mapNotNull { entry -> entry.pointer.element?.let { it to entry } }.toMap()
  }
  private val assistedSitesByElement: Map<KtElement, MetroAssistedSite> by lazy {
    assistedSites.mapNotNull { site -> site.pointer.element?.let { it to site } }.toMap()
  }

  /**
   * Bindings satisfying [consumer]: direct key matches plus, for `Set`/`Map` aggregate sites, the
   * multibinding contributions collected into them.
   */
  fun providersFor(consumer: MetroConsumerEntry): List<MetroProviderEntry> {
    val direct = providersByKey[consumer.key].orEmpty()
    val contributions = consumer.multibindingId?.let { contributionsByMultibindingId[it] }.orEmpty()
    return direct + contributions
  }

  /** Sites consuming any of [providerEntries], joining multibinding contributions by id. */
  fun consumersFor(providerEntries: Collection<MetroProviderEntry>): List<MetroConsumerEntry> {
    val result = LinkedHashSet<MetroConsumerEntry>()
    for (entry in providerEntries) {
      if (entry.multibindingId != null) {
        result += consumersByMultibindingId[entry.multibindingId].orEmpty()
      } else {
        result += consumersByKey[entry.key].orEmpty()
      }
    }
    return result.toList()
  }

  fun providerEntriesAt(element: KtElement): List<MetroProviderEntry> {
    return providersByElement[element].orEmpty()
  }

  fun consumerEntryAt(element: KtElement): MetroConsumerEntry? {
    return consumersByElement[element]
  }

  fun graphEntryAt(element: KtElement): MetroGraphEntry? {
    return graphsByElement[element]
  }

  fun assistedSiteAt(element: KtElement): MetroAssistedSite? {
    return assistedSitesByElement[element]
  }

  fun contributionsForScopes(scopeKeys: Set<ClassId>): List<MetroContributionEntry> {
    if (scopeKeys.isEmpty()) return emptyList()
    return contributions.filter { contribution -> contribution.scopeKeys.any(scopeKeys::contains) }
  }

  fun graphsForScopes(scopeKeys: Set<ClassId>): List<MetroGraphEntry> {
    if (scopeKeys.isEmpty()) return emptyList()
    return graphs.filter { graph -> graph.scopeKeys.any(scopeKeys::contains) }
  }

  companion object {
    val EMPTY = MetroBindingIndex(emptyList(), emptyList(), emptyList(), emptyList())
  }
}
