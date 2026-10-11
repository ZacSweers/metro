// RENDER_DIAGNOSTICS_FULL_TEXT
// WITH_DAGGER

// MODULE: lib
// FILE: ExternalArrayQualifier.kt
import kotlin.reflect.KClass

@javax.inject.Qualifier
@Target(AnnotationTarget.FUNCTION)
annotation class ExternalJavaxQualifier(val value: KClass<*>)

// MODULE: main(lib)
// FILE: ArrayClassKeyArgumentsInterop.kt
import kotlin.reflect.KClass

// The outer Array can lose dimensions across modules; IntArray itself remains distinct.
typealias NestedPrimitiveArrays = Array<IntArray>

@jakarta.inject.Qualifier
@Target(AnnotationTarget.FUNCTION)
annotation class JakartaQualifier(val value: KClass<*>)

@javax.inject.Scope
@Target(AnnotationTarget.FUNCTION)
annotation class JavaxScope(val value: KClass<*>)

@jakarta.inject.Scope
@Target(AnnotationTarget.FUNCTION)
annotation class JakartaScope(val value: KClass<*>)

@dagger.MapKey
@Target(AnnotationTarget.FUNCTION)
annotation class DaggerClassKey(val value: KClass<*>)

// A source argument remains checkable when its annotation class comes from a dependency.
@ExternalJavaxQualifier(<!KNOWN_KOTLINC_BUG_WARNING!>NestedPrimitiveArrays::class<!>)
fun externalJavaxArrayQualifier() = Unit

@JakartaQualifier(<!KNOWN_KOTLINC_BUG_WARNING!>NestedPrimitiveArrays::class<!>)
fun jakartaArrayQualifier() = Unit

@JavaxScope(<!KNOWN_KOTLINC_BUG_WARNING!>NestedPrimitiveArrays::class<!>)
fun javaxArrayScope() = Unit

@JakartaScope(<!KNOWN_KOTLINC_BUG_WARNING!>NestedPrimitiveArrays::class<!>)
fun jakartaArrayScope() = Unit

// Primitive-array and ordinary class literals retain their identity in every role.
@ExternalJavaxQualifier(IntArray::class)
fun externalJavaxPrimitiveQualifier() = Unit

@ExternalJavaxQualifier(String::class)
fun externalJavaxStringQualifier() = Unit

@JakartaQualifier(IntArray::class)
fun jakartaPrimitiveQualifier() = Unit

@JakartaQualifier(String::class)
fun jakartaStringQualifier() = Unit

@JavaxScope(IntArray::class)
fun javaxPrimitiveScope() = Unit

@JavaxScope(String::class)
fun javaxStringScope() = Unit

@JakartaScope(IntArray::class)
fun jakartaPrimitiveScope() = Unit

@JakartaScope(String::class)
fun jakartaStringScope() = Unit

@BindingContainer
object MapBindings {
  @Provides @IntoMap
  @DaggerClassKey(<!KNOWN_KOTLINC_BUG_WARNING!>NestedPrimitiveArrays::class<!>)
  fun daggerArrayMapKey(): Int = 1

  @Provides @IntoMap
  @DaggerClassKey(IntArray::class)
  fun daggerPrimitiveMapKey(): Int = 2

  @Provides @IntoMap
  @DaggerClassKey(String::class)
  fun daggerStringMapKey(): Int = 3
}
