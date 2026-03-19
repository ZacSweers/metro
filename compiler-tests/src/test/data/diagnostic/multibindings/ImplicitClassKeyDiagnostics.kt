// RENDER_DIAGNOSTICS_FULL_TEXT
import kotlin.reflect.KClass

interface Base

// Provides/Binds functions

@BindingContainer
object Bindings {
  // Error - cannot use explicit Nothing::class
  @ClassKey(<!MAP_KEY_IMPLICIT_CLASS_KEY_ERROR!>Nothing::class<!>)
  @Provides
  @IntoMap
  fun provideNothing(): String = "hello"

  // ok - explicit value and is required
  @ClassKey(String::class)
  @Provides
  @IntoMap
  fun provideString(): String = "hello"
}

///// Contribution class cases

// Nothing::class on a contribution class
@ClassKey(<!MAP_KEY_IMPLICIT_CLASS_KEY_ERROR!>Nothing::class<!>)
@ContributesIntoMap(AppScope::class)
@Inject
class NothingKeyImpl : Base

// Warning - value matches the annotated class (redundant)
@ClassKey(<!MAP_KEY_REDUNDANT_IMPLICIT_CLASS_KEY!>RedundantKeyImpl::class<!>)
@ContributesIntoMap(AppScope::class)
@Inject
class RedundantKeyImpl : Base

// ok - explicit class key on a contribution class (different from annotated class)
@ClassKey(Base::class)
@ContributesIntoMap(AppScope::class)
@Inject
class ExplicitKeyImpl : Base

// ok - no value (implicit, uses default sentinel handled at IR time)
@ClassKey
@ContributesIntoMap(AppScope::class)
@Inject
class ImplicitKeyImpl : Base

///// Contribution class with binding<...>() cases

@ContributesIntoMap(AppScope::class, binding = binding<@ClassKey(<!MAP_KEY_IMPLICIT_CLASS_KEY_ERROR!>Nothing::class<!>) Base>())
@Inject
class BindingNothingKeyImpl : Base

@ContributesIntoMap(AppScope::class, binding = binding<@ClassKey(<!MAP_KEY_REDUNDANT_IMPLICIT_CLASS_KEY!>BindingRedundantKeyImpl::class<!>) Base>())
@Inject
class BindingRedundantKeyImpl : Base

@ContributesIntoMap(AppScope::class, binding = binding<@ClassKey(Base::class) Base>())
@Inject
class BindingExplicitKeyImpl : Base

// Custom implicit class key annotation

@MapKey(implicitClassKey = true)
annotation class CustomClassKey(val value: KClass<*> = Nothing::class)

@CustomClassKey(<!MAP_KEY_IMPLICIT_CLASS_KEY_ERROR!>Nothing::class<!>)
@ContributesIntoMap(AppScope::class)
@Inject
class CustomNothingKeyImpl : Base

// Non-implicit class key map keys should not trigger

@MapKey
annotation class StringKey(val value: String)

@BindingContainer
object NonImplicitBindings {
  @StringKey("foo")
  @Provides
  @IntoMap
  fun provideString(): String = "hello"
}
