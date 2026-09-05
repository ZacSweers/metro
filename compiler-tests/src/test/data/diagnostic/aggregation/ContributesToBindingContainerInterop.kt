// RENDER_DIAGNOSTICS_FULL_TEXT
// ENABLE_DAGGER_INTEROP
// WITH_ANVIL

import com.squareup.anvil.annotations.ContributesTo as AnvilContributesTo

// Configured contribution and binding annotations follow the same classification as Metro's own.
@AnvilContributesTo(scope = AppScope::class)
interface <!CONTRIBUTES_TO_COULD_BE_BINDING_CONTAINER!>Bindings<!> {
  @dagger.Binds fun bindString(value: String): CharSequence
  @dagger.multibindings.Multibinds fun strings(): Set<String>
  @dagger.BindsOptionalOf fun optionalInt(): Int
  @dagger.Provides fun provideString(): String = "hello"
}
