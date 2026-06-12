// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer
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
 * The Analysis API analog of the compiler's `FirTypeKey`/`IrTypeKey`, mirroring the
 * `dev.zacsweers.metro.compiler.graph.BaseTypeKey` contract (`type` + `qualifier` + `render(short,
 * includeQualifier)`).
 *
 * Unlike the compiler's keys, [type] is the fully-expanded, fully-qualified *rendered* type,
 * compared structurally rather than semantically — Analysis API types cannot escape their analysis
 * session, so the index stores renderings. [shortType] is the short-name rendering of the same
 * type, kept for display only and excluded from equality.
 */
internal class KaTypeKey(
  val type: String,
  val qualifier: MetroKaAnnotation? = null,
  private val shortType: String = type,
) : Comparable<KaTypeKey> {
  fun render(short: Boolean, includeQualifier: Boolean = true): String = buildString {
    if (includeQualifier) {
      qualifier?.let {
        append(it.render(short))
        append(' ')
      }
    }
    append(if (short) shortType else type)
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is KaTypeKey) return false
    return type == other.type && qualifier == other.qualifier
  }

  override fun hashCode(): Int = 31 * type.hashCode() + (qualifier?.hashCode() ?: 0)

  override fun toString(): String = render(short = false)

  override fun compareTo(other: KaTypeKey): Int {
    if (this == other) return 0
    return toString().compareTo(other.toString())
  }
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
