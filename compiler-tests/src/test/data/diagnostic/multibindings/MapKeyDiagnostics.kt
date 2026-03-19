// RENDER_DIAGNOSTICS_FULL_TEXT
import kotlin.reflect.KClass

@MapKey annotation class GenericAnnotation<!MAP_KEY_TYPE_PARAM_ERROR!><T><!>(val int: Int)

@MapKey annotation class <!MAP_KEY_ERROR!>MissingCtor<!>

@MapKey
annotation class MissingCtor2<!MAP_KEY_ERROR!>()<!> // Empty but technically present

@MapKey
annotation class NotOneArgButUnwrapping<!MAP_KEY_ERROR!>(val arg1: Int, val arg2: Int)<!>

@MapKey
annotation class ArrayArg(val arg1: <!MAP_KEY_ERROR!>IntArray<!>)

// ok
@MapKey(unwrapValue = false)
annotation class UnwrapFalseWithMultipleParamsIsOk(val arg1: Int, val arg2: Int)

@MapKey
annotation class UnwrappingWithSingleParamIsOk(val arg: Int)

///// implicitClassKey validation

// multiple params
@MapKey(implicitClassKey = true)
annotation class ImplicitMultipleParams<!MAP_KEY_ERROR, MAP_KEY_ERROR!>(val value: KClass<*> = Nothing::class, val extra: String)<!>

// param is not KClass
@MapKey(implicitClassKey = true)
annotation class ImplicitNonKClass(val value: <!MAP_KEY_ERROR!>String<!> = <!MAP_KEY_ERROR!>""<!>)

// no default value
@MapKey(implicitClassKey = true)
annotation class ImplicitNoDefault(val <!MAP_KEY_ERROR!>value<!>: KClass<*>)

// wrong default value
@MapKey(implicitClassKey = true)
annotation class ImplicitWrongDefault(val value: KClass<*> = <!MAP_KEY_ERROR!>String::class<!>)

// ok - implicitClassKey with KClass param and default value
@MapKey(implicitClassKey = true)
annotation class ImplicitClassKeyOk(val value: KClass<*> = Nothing::class)

// FUNCTION target validation (applies to all map keys)
@MapKey
<!MAP_KEY_ERROR!>@Target(AnnotationTarget.CLASS)<!>
annotation class MissingFunctionTarget(val value: Int)
