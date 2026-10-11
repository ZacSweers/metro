// RENDER_DIAGNOSTICS_FULL_TEXT
@file:Suppress("RUNTIME_ANNOTATION_NOT_SUPPORTED")
import kotlin.reflect.KClass

typealias Strings = Array<String>
typealias BoxedInts = Array<Int>
typealias IntMatrix = Array<IntArray>
typealias StringMatrix = Array<Array<String>>
typealias MoreStrings = Strings

@Qualifier annotation class TypeQualifier(val type: KClass<*>)
@Scope annotation class TypeScope(val type: KClass<*>)
@MapKey annotation class TypeMapKey(val type: KClass<*>)
annotation class NestedType(val type: KClass<*>)
@Qualifier annotation class NestedQualifier(val nested: NestedType)
@MapKey(unwrapValue = false) annotation class TypesMapKey(val types: Array<KClass<*>>)
@Qualifier annotation class PrimitiveValues(val values: IntArray)
annotation class UnrelatedType(val type: KClass<*>)

@TypeQualifier(<!KNOWN_KOTLINC_BUG_WARNING!>Strings::class<!>)
fun strings() = Unit

@TypeQualifier(<!KNOWN_KOTLINC_BUG_WARNING!>BoxedInts::class<!>)
fun boxedInts() = Unit

@TypeQualifier(<!KNOWN_KOTLINC_BUG_WARNING!>MoreStrings::class<!>)
fun aliasChain() = Unit

@TypeScope(<!KNOWN_KOTLINC_BUG_WARNING!>StringMatrix::class<!>)
class ScopedType

@NestedQualifier(NestedType(<!KNOWN_KOTLINC_BUG_WARNING!>IntMatrix::class<!>))
fun nestedAnnotation() = Unit

@BindingContainer
object MapBindings {
  @Provides @IntoMap @TypeMapKey(<!KNOWN_KOTLINC_BUG_WARNING!>IntMatrix::class<!>)
  fun matrix(): Int = 1

  @Provides @IntoMap
  @TypesMapKey([String::class, <!KNOWN_KOTLINC_BUG_WARNING!>Strings::class<!>, IntArray::class])
  fun arrayOfClassLiterals(): Int = 2

  @Provides @IntoMap @TypesMapKey([String::class, IntArray::class])
  fun safeClassLiterals(): Int = 3
}

@SingleIn(<!KNOWN_KOTLINC_BUG_WARNING!>IntMatrix::class<!>)
class ScopedByArray

@DependencyGraph(
  scope = <!KNOWN_KOTLINC_BUG_WARNING!>Strings::class<!>,
  additionalScopes = [<!KNOWN_KOTLINC_BUG_WARNING!>IntMatrix::class<!>],
)
interface ArrayScopeGraph

@GraphExtension(<!KNOWN_KOTLINC_BUG_WARNING!>BoxedInts::class<!>)
interface ArrayScopeExtension

// These values preserve their identity, and unrelated annotations aren't Metro keys.
@TypeQualifier(IntArray::class)
fun primitiveArrayClass() = Unit

@TypeScope(String::class)
class ScalarScope

@PrimitiveValues([1, 2])
fun primitiveArrayValue() = Unit

@NestedQualifier(NestedType(IntArray::class))
fun safeNestedAnnotation() = Unit

@UnrelatedType(Strings::class)
fun unrelatedAnnotation() = Unit
