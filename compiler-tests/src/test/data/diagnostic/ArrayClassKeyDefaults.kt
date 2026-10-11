// RENDER_DIAGNOSTICS_FULL_TEXT
@file:Suppress("RUNTIME_ANNOTATION_NOT_SUPPORTED")
import kotlin.reflect.KClass

typealias Strings = Array<String>
typealias IntMatrix = Array<IntArray>

@Qualifier
annotation class DefaultQualifier(
  val type: KClass<*> = <!KNOWN_KOTLINC_BUG_WARNING!>Strings::class<!>
)

@Scope
annotation class UnusedDefaultScope(
  val type: KClass<*> = <!KNOWN_KOTLINC_BUG_WARNING!>IntMatrix::class<!>
)

@MapKey(unwrapValue = false)
annotation class DefaultMapKey(
  val types: Array<KClass<*>> = [IntArray::class, <!KNOWN_KOTLINC_BUG_WARNING!>Strings::class<!>]
)

// The declaration owns this warning even when several uses omit the argument.
@DefaultQualifier
fun firstDefault() = Unit

@DefaultQualifier
fun secondDefault() = Unit

@DefaultQualifier(IntArray::class)
fun overridesDefault() = Unit

annotation class NestedDefault(val type: KClass<*> = Strings::class)
@Qualifier annotation class NestedQualifier(val nested: NestedDefault)

<!KNOWN_KOTLINC_BUG_WARNING!>@NestedQualifier(NestedDefault())<!>
fun nestedDefault() = Unit

@NestedQualifier(NestedDefault(IntArray::class))
fun overridesNestedDefault() = Unit

@Qualifier
annotation class DefaultNestedQualifier(
  val nested: NestedDefault = <!KNOWN_KOTLINC_BUG_WARNING!>NestedDefault()<!>
)

@DefaultNestedQualifier
fun inheritedNestedDefault() = Unit

// An unused ordinary annotation's default doesn't participate in Metro key matching.
annotation class UnrelatedDefault(val type: KClass<*> = Strings::class)

// Several paths reach the same default. The enclosing use owns one warning.
annotation class DefaultPair(
  val first: NestedDefault = NestedDefault(),
  val second: NestedDefault = NestedDefault(),
)
annotation class DefaultPairs(
  val first: DefaultPair = DefaultPair(),
  val second: DefaultPair = DefaultPair(),
)
@Qualifier annotation class SharedDefaultsQualifier(val value: DefaultPairs)

<!KNOWN_KOTLINC_BUG_WARNING!>@SharedDefaultsQualifier(DefaultPairs())<!>
fun sharedNestedDefaults() = Unit

@SharedDefaultsQualifier(
  DefaultPairs(
    DefaultPair(NestedDefault(IntArray::class), NestedDefault(String::class)),
    DefaultPair(NestedDefault(String::class), NestedDefault(IntArray::class)),
  )
)
fun overridesAllSharedDefaults() = Unit
