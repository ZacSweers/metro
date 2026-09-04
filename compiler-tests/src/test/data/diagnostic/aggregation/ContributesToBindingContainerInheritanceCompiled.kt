// RUN_PIPELINE_TILL: BACKEND
// RENDER_DIAGNOSTICS_FULL_TEXT

// MODULE: lib
// FILE: Parents.kt

interface CompiledMarkerParent

interface CompiledPrivateHelperParent {
  private fun helper(): String = "private"
}

interface CompiledAccessorParent {
  val value: String
}

interface CompiledBindingParent {
  @Binds val String.bind: CharSequence
}

interface CompiledPrivateBindingParent {
  @Binds private val String.bind: CharSequence get() = this
}

interface CompiledCompanionProviderParent {
  companion object {
    @Provides fun provideString(): String = "parent companion"
  }
}

// MODULE: main(lib)
// FILE: Children.kt

// The warning must make the same decisions for declarations loaded from a compiled dependency.
@ContributesTo(AppScope::class)
interface <!CONTRIBUTES_TO_COULD_BE_BINDING_CONTAINER!>CompiledMarkerChild<!> : CompiledMarkerParent {
  @Binds val String.bind: CharSequence
}

@ContributesTo(AppScope::class)
interface <!CONTRIBUTES_TO_COULD_BE_BINDING_CONTAINER!>CompiledPrivateHelperChild<!> : CompiledPrivateHelperParent {
  @Binds val String.bind: CharSequence
}

@ContributesTo(AppScope::class)
interface CompiledAccessorChild : CompiledAccessorParent {
  @Binds val String.bind: CharSequence
}

@ContributesTo(AppScope::class)
interface CompiledBindingChild : CompiledBindingParent {
  @Multibinds val values: Set<String>
}

@ContributesTo(AppScope::class)
interface CompiledPrivateBindingChild : CompiledPrivateBindingParent {
  @Multibinds val values: Set<String>
}

@ContributesTo(AppScope::class)
interface CompiledCompanionProviderChild : CompiledCompanionProviderParent {
  @Binds val String.bind: CharSequence
}
