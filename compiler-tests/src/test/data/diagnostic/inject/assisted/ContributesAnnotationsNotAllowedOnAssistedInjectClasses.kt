// RENDER_DIAGNOSTICS_FULL_TEXT
// SUPPRESS_WARNINGS: SUGGEST_CLASS_INJECTION

interface Blah

// Invalid: @ContributesBinding on a class with an @AssistedInject constructor
<!AGGREGATION_ERROR!>@ContributesBinding(AppScope::class)<!>
class CtorAssistedBinding @AssistedInject constructor(
  @Assisted private val assistedString: String,
) : Blah {
  @AssistedFactory
  fun interface Factory {
    fun create(assistedString: String): CtorAssistedBinding
  }
}

// Invalid: @ContributesIntoSet on a class with an @AssistedInject constructor
<!AGGREGATION_ERROR!>@ContributesIntoSet(AppScope::class)<!>
class CtorAssistedIntoSet @AssistedInject constructor(
  @Assisted private val assistedString: String,
) : Blah {
  @AssistedFactory
  fun interface Factory {
    fun create(assistedString: String): CtorAssistedIntoSet
  }
}

// Invalid: @ContributesIntoMap on a class with an @AssistedInject constructor
<!AGGREGATION_ERROR!>@ContributesIntoMap(AppScope::class)<!>
@StringKey("key")
class CtorAssistedIntoMap @AssistedInject constructor(
  @Assisted private val assistedString: String,
) : Blah {
  @AssistedFactory
  fun interface Factory {
    fun create(assistedString: String): CtorAssistedIntoMap
  }
}

// Invalid: @ContributesBinding on a class-level @AssistedInject class
<!AGGREGATION_ERROR!>@ContributesBinding(AppScope::class)<!>
@AssistedInject
class ClassLevelAssistedBinding(
  @Assisted private val assistedString: String,
) : Blah {
  @AssistedFactory
  fun interface Factory {
    fun create(assistedString: String): ClassLevelAssistedBinding
  }
}

// Valid: @ContributesBinding on a plain @Inject class (no assisted injection)
@ContributesBinding(AppScope::class)
@Inject
class PlainInjectBinding : Blah
