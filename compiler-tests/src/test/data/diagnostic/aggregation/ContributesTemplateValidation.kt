// RENDER_DIAGNOSTICS_FULL_TEXT
import kotlin.reflect.KClass

// Valid template object with @Provides
@ContributesTemplate.Template
object ValidTemplate {
  @Provides fun <T : Any> provide(target: T): Any = target
}

// Valid annotation class referencing a template
@ContributesTemplate(template = ValidTemplate::class)
annotation class ValidAnnotation(val scope: KClass<*>, val replaces: Array<KClass<*>> = [])

// Error: template class not annotated with @Template
object NotATemplate {
  @Provides fun <!METRO_TYPE_PARAMETERS_ERROR!><T : Any><!> <!PROVIDES_ERROR!>provide<!>(target: T): Any = target
}

@ContributesTemplate(template = NotATemplate::class)
annotation class <!AGGREGATION_ERROR!>MissingTemplateAnnotation<!>(val scope: KClass<*>)

// Error: template is a regular class (not object or abstract class)
@ContributesTemplate.Template
class <!AGGREGATION_ERROR!>InvalidTemplateClass<!> {
  @Provides fun <T : Any> provide(target: T): Any = target
}

// Error: template object has no @Provides functions
@ContributesTemplate.Template
object EmptyTemplate {
  fun <T> notProvides(): Int = 42
}

@ContributesTemplate(template = EmptyTemplate::class)
annotation class <!AGGREGATION_ERROR!>NoProvidesFunctions<!>(val scope: KClass<*>)

// Error: @Provides function has no type parameters
@ContributesTemplate.Template
object NoTypeParamTemplate {
  @Provides fun provideInt(): Int = 42
}

@ContributesTemplate(template = NoTypeParamTemplate::class)
annotation class <!AGGREGATION_ERROR!>NoTypeParamOnProvides<!>(val scope: KClass<*>)

// Error: @Provides function has two type parameters
@ContributesTemplate.Template
object TwoTypeParamsTemplate {
  @Provides fun <A, B> provide(): Int = 42
}

@ContributesTemplate(template = TwoTypeParamsTemplate::class)
annotation class <!AGGREGATION_ERROR!>TwoTypeParamsOnProvides<!>(val scope: KClass<*>)

// Error: annotation class missing scope parameter and no scope on @ContributesTemplate
@ContributesTemplate.Template
object MissingScopeTemplate {
  @Provides fun <T> provide(): Int = 42
}

@ContributesTemplate(template = MissingScopeTemplate::class)
annotation class <!AGGREGATION_ERROR!>MissingScopeParam<!>

// Valid: no scope parameter but scope is set on @ContributesTemplate
@ContributesTemplate.Template
object FixedScopeTemplate {
  @Provides fun <T> provide(): Int = 42
}

@ContributesTemplate(template = FixedScopeTemplate::class, scope = AppScope::class)
annotation class ValidWithFixedScope(val replaces: Array<KClass<*>> = [])

// Error: both @ContributesTemplate scope and annotation scope parameter
@ContributesTemplate(template = FixedScopeTemplate::class, scope = AppScope::class)
annotation class <!AGGREGATION_ERROR!>BothScopesDeclared<!>(val scope: KClass<*>)

// --- Target class bound validation ---

interface Marker

@ContributesTemplate.Template
object BoundedTemplate {
  @Provides @IntoSet fun <T : Marker> contribute(target: T): Marker = target
}

@ContributesTemplate(template = BoundedTemplate::class)
annotation class BoundedContainer(val scope: KClass<*>)

// Valid: implements Marker
@BoundedContainer(AppScope::class) @Inject
class ValidTarget : Marker

// Error: does not implement Marker
<!AGGREGATION_ERROR!>@BoundedContainer(AppScope::class)<!> @Inject
class InvalidTarget

// Valid: <T : Any> always satisfied
@ContributesTemplate.Template
object AnyBoundTemplate {
  @Provides @IntoSet fun <T : Any> contribute(target: T): Any = target
}

@ContributesTemplate(template = AnyBoundTemplate::class)
annotation class AnyBoundContainer(val scope: KClass<*>)

@AnyBoundContainer(AppScope::class) @Inject
class AnyTarget
