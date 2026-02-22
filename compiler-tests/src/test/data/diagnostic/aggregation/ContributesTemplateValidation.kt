// RENDER_DIAGNOSTICS_FULL_TEXT
import kotlin.reflect.KClass

// Valid annotation class with companion object and @Provides
@ContributesTemplate
annotation class ValidAnnotation(val scope: KClass<*>, val replaces: Array<KClass<*>> = []) {
  companion object {
    @Provides fun <T : Any> provide(target: T): Any = target
  }
}

// Error: missing companion object
@ContributesTemplate
annotation class <!AGGREGATION_ERROR!>MissingCompanion<!>(val scope: KClass<*>)

// Error: companion has no @Provides functions
@ContributesTemplate
annotation class <!AGGREGATION_ERROR!>NoProvidesFunctions<!>(val scope: KClass<*>) {
  companion object {
    fun <T> notProvides(): Int = 42
  }
}

// Error: @Provides function has no type parameters
@ContributesTemplate
annotation class <!AGGREGATION_ERROR!>NoTypeParamOnProvides<!>(val scope: KClass<*>) {
  companion object {
    @Provides fun provideInt(): Int = 42
  }
}

// Error: @Provides function has two type parameters
@ContributesTemplate
annotation class <!AGGREGATION_ERROR!>TwoTypeParamsOnProvides<!>(val scope: KClass<*>) {
  companion object {
    @Provides fun <A, B> provide(): Int = 42
  }
}

// Error: annotation class missing scope parameter and no scope on @ContributesTemplate
@ContributesTemplate
annotation class <!AGGREGATION_ERROR!>MissingScopeParam<!> {
  companion object {
    @Provides fun <T> provide(): Int = 42
  }
}

// Valid: no scope parameter but scope is set on @ContributesTemplate
@ContributesTemplate(scope = AppScope::class)
annotation class ValidWithFixedScope(val replaces: Array<KClass<*>> = []) {
  companion object {
    @Provides fun <T> provide(): Int = 42
  }
}

// Error: both @ContributesTemplate scope and annotation scope parameter
@ContributesTemplate(scope = AppScope::class)
annotation class <!AGGREGATION_ERROR!>BothScopesDeclared<!>(val scope: KClass<*>) {
  companion object {
    @Provides fun <T> provide(): Int = 42
  }
}

// --- Target class bound validation ---

interface Marker

@ContributesTemplate
annotation class BoundedContainer(val scope: KClass<*>) {
  companion object {
    @Provides @IntoSet fun <T : Marker> contribute(target: T): Marker = target
  }
}

// Valid: implements Marker
@BoundedContainer(AppScope::class) @Inject
class ValidTarget : Marker

// Error: does not implement Marker
<!AGGREGATION_ERROR!>@BoundedContainer(AppScope::class)<!> @Inject
class InvalidTarget

// Valid: <T : Any> always satisfied
@ContributesTemplate
annotation class AnyBoundContainer(val scope: KClass<*>) {
  companion object {
    @Provides @IntoSet fun <T : Any> contribute(target: T): Any = target
  }
}

@AnyBoundContainer(AppScope::class) @Inject
class AnyTarget
