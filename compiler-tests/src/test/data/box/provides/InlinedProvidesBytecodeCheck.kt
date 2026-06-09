// IGNORE_BACKEND: JS_IR

import kotlin.reflect.KClass

const val CONST_STRING = "const-prop-read"

object ObjectValue

enum class EnumValue {
  Entry
}

class ClassLiteralValue

@BindingContainer
object Bindings {
  @Provides fun provideByte(): Byte = 7

  @Provides fun provideShort(): Short = 300

  @Provides fun provideInt(): Int = 123456

  @Provides fun provideLong(): Long = 12345678901L

  @Provides fun provideBoolean(): Boolean = true

  @Provides fun provideChar(): Char = 'Z'

  @Provides fun provideFloat(): Float = 12.5F

  @Provides fun provideDouble(): Double = 12.25

  @Provides @Named("literal") fun provideString(): String = "inline-string"

  @Provides @Named("const") fun provideConstString(): String = CONST_STRING

  @Provides fun provideNull(): String? = null

  @Provides fun provideObject(): ObjectValue = ObjectValue

  @Provides fun provideEnum(): EnumValue = EnumValue.Entry

  @Provides fun provideClassLiteral(): KClass<*> = ClassLiteralValue::class
}

@DependencyGraph(bindingContainers = [Bindings::class])
interface AppGraph {
  val byte: Byte
  val short: Short
  val int: Int
  val long: Long
  val boolean: Boolean
  val char: Char
  val float: Float
  val double: Double
  @get:Named("literal") val string: String
  @get:Named("const") val constString: String
  val nullableString: String?
  val objectValue: ObjectValue
  val enumValue: EnumValue
  val classLiteral: KClass<*>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  graph.byte
  graph.short
  graph.int
  graph.long
  graph.boolean
  graph.char
  graph.float
  graph.double
  graph.string
  graph.constString
  graph.nullableString
  graph.objectValue
  graph.enumValue
  graph.classLiteral
  return "OK"
}

/*
Most inlineable values appear twice:
1. Provides declaration
2. Accessor

The extra boolean/null/enum constants come from Kotlin's generated enum and graph companion code.
*/

// <count> <instruction>
// CHECK_BYTECODE_TEXT
// 2 BIPUSH 7
// 2 SIPUSH 300
// 4 LDC 123456
// 2 LDC 12345678901
// 3 ICONST_1
// 2 BIPUSH 90
// 2 LDC 12.5
// 2 LDC 12.25
// 2 LDC "inline-string"
// 2 LDC "const-prop-read"
// 3 ACONST_NULL
// 2 GETSTATIC ObjectValue.INSTANCE : LObjectValue;
// 3 GETSTATIC EnumValue.Entry : LEnumValue;
// 2 LDC LClassLiteralValue;.class
// 2 INVOKESTATIC kotlin/jvm/internal/Reflection.getOrCreateKotlinClass
