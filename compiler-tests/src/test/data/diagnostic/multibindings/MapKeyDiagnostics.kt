// RENDER_DIAGNOSTICS_FULL_TEXT

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
