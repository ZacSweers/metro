// RENDER_DIAGNOSTICS_FULL_TEXT
// TARGET_BACKEND: JVM_IR

import kotlin.reflect.KClass

@Qualifier annotation class ArrayType(val type: KClass<*>)

@ArrayType(<!KNOWN_KOTLINC_BUG_WARNING!>Array<Int>::class<!>)
fun boxedArray() = Unit

@ArrayType(<!KNOWN_KOTLINC_BUG_WARNING!>Array<IntArray>::class<!>)
fun nestedPrimitiveArray() = Unit

@ArrayType(<!KNOWN_KOTLINC_BUG_WARNING!>Array<Array<String>>::class<!>)
fun nestedObjectArray() = Unit

@ArrayType(IntArray::class)
fun primitiveArray() = Unit

@ContributesTo(<!KNOWN_KOTLINC_BUG_WARNING!>Array<String>::class<!>)
interface ArrayScopeContribution

@ArrayType(<!KNOWN_KOTLINC_BUG_WARNING!>Array<Byte>::class<!>)
fun boxedByteArray() = Unit

@ArrayType(<!KNOWN_KOTLINC_BUG_WARNING!>Array<ByteArray>::class<!>)
fun nestedByteArray() = Unit

@ArrayType(ByteArray::class)
fun primitiveByteArray() = Unit

@ArrayType(<!KNOWN_KOTLINC_BUG_WARNING!>Array<Short>::class<!>)
fun boxedShortArray() = Unit

@ArrayType(<!KNOWN_KOTLINC_BUG_WARNING!>Array<ShortArray>::class<!>)
fun nestedShortArray() = Unit

@ArrayType(ShortArray::class)
fun primitiveShortArray() = Unit

@ArrayType(<!KNOWN_KOTLINC_BUG_WARNING!>Array<Long>::class<!>)
fun boxedLongArray() = Unit

@ArrayType(<!KNOWN_KOTLINC_BUG_WARNING!>Array<LongArray>::class<!>)
fun nestedLongArray() = Unit

@ArrayType(LongArray::class)
fun primitiveLongArray() = Unit

@ArrayType(<!KNOWN_KOTLINC_BUG_WARNING!>Array<Float>::class<!>)
fun boxedFloatArray() = Unit

@ArrayType(<!KNOWN_KOTLINC_BUG_WARNING!>Array<FloatArray>::class<!>)
fun nestedFloatArray() = Unit

@ArrayType(FloatArray::class)
fun primitiveFloatArray() = Unit

@ArrayType(<!KNOWN_KOTLINC_BUG_WARNING!>Array<Double>::class<!>)
fun boxedDoubleArray() = Unit

@ArrayType(<!KNOWN_KOTLINC_BUG_WARNING!>Array<DoubleArray>::class<!>)
fun nestedDoubleArray() = Unit

@ArrayType(DoubleArray::class)
fun primitiveDoubleArray() = Unit

@ArrayType(<!KNOWN_KOTLINC_BUG_WARNING!>Array<Char>::class<!>)
fun boxedCharArray() = Unit

@ArrayType(<!KNOWN_KOTLINC_BUG_WARNING!>Array<CharArray>::class<!>)
fun nestedCharArray() = Unit

@ArrayType(CharArray::class)
fun primitiveCharArray() = Unit

@ArrayType(<!KNOWN_KOTLINC_BUG_WARNING!>Array<Boolean>::class<!>)
fun boxedBooleanArray() = Unit

@ArrayType(<!KNOWN_KOTLINC_BUG_WARNING!>Array<BooleanArray>::class<!>)
fun nestedBooleanArray() = Unit

@ArrayType(BooleanArray::class)
fun primitiveBooleanArray() = Unit
