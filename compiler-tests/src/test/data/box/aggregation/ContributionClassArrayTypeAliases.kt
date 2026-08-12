// MODULE: lib
// FILE: Aliases.kt
package aliases

import contributions.OriginalReplacementBindings as OriginalReplacementBindingsTarget
import contributions.ReplacementExclusionBindings as ReplacementExclusionBindingsTarget
import contributions.VariantScope as VariantScopeTarget

typealias VariantScopeAlias = VariantScopeTarget
typealias OriginalReplacementBindingsAlias = OriginalReplacementBindingsTarget
typealias ReplacementExclusionBindingsAlias = ReplacementExclusionBindingsTarget

// FILE: Contributions.kt
package contributions

import aliases.OriginalReplacementBindingsAlias

abstract class VariantScope private constructor()

data class ReplacementValue(val value: String)

data class ExclusionValue(val value: String)

@ContributesTo(VariantScope::class)
@BindingContainer
object VariantScopeBindings {
  @Provides fun provideVariantScopeValue(): Long = 7L
}

@ContributesTo(AppScope::class)
@BindingContainer
object OriginalReplacementBindings {
  @Provides @IntoSet fun provideOriginalValue(): ReplacementValue = ReplacementValue("original")
}

@ContributesTo(AppScope::class, replaces = [OriginalReplacementBindingsAlias::class])
@BindingContainer
object ReplacementBindings {
  @Provides @IntoSet fun provideReplacementValue(): ReplacementValue = ReplacementValue("replacement")
}

@ContributesTo(AppScope::class)
@BindingContainer
object OriginalExclusionBindings {
  @Provides @IntoSet fun provideOriginalValue(): ExclusionValue = ExclusionValue("original")
}

@ContributesTo(AppScope::class, replaces = [OriginalExclusionBindings::class])
@BindingContainer
object ReplacementExclusionBindings {
  @Provides @IntoSet fun provideReplacementValue(): ExclusionValue = ExclusionValue("replacement")
}

@ContributesTo(AppScope::class)
@BindingContainer
object DirectlyExcludedBindings {
  @Provides @IntoSet fun provideDirectValue(): ExclusionValue = ExclusionValue("direct")
}

// MODULE: main(lib)
package app

import aliases.ReplacementExclusionBindingsAlias
import aliases.VariantScopeAlias
import contributions.DirectlyExcludedBindings
import contributions.ExclusionValue
import contributions.ReplacementValue

@DependencyGraph(
  scope = AppScope::class,
  additionalScopes = [VariantScopeAlias::class],
  excludes = [DirectlyExcludedBindings::class, ReplacementExclusionBindingsAlias::class],
)
interface AppGraph {
  val variantScopeValue: Long
  val replacementValues: Set<ReplacementValue>
  val exclusionValues: Set<ExclusionValue>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals(7L, graph.variantScopeValue)
  assertEquals(setOf("replacement"), graph.replacementValues.map { it.value }.toSet())
  assertEquals(setOf("original"), graph.exclusionValues.map { it.value }.toSet())
  return "OK"
}
