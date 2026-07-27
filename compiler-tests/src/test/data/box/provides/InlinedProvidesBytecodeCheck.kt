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
Each inlineable value appears in both the provides declaration and the graph accessor.
*/

// <count> <instruction>
// CHECK_BYTECODE_TEXT
// @Bindings.class:
// 1 BIPUSH 7
// 1 SIPUSH 300
// 2 LDC 123456
// 1 LDC 12345678901
// 1 ICONST_1
// 1 BIPUSH 90
// 1 LDC 12.5
// 1 LDC 12.25
// 1 LDC "inline-string"
// 1 LDC "const-prop-read"
// 1 ACONST_NULL
// 1 GETSTATIC ObjectValue.INSTANCE : LObjectValue;
// 1 GETSTATIC EnumValue.Entry : LEnumValue;
// 1 LDC LClassLiteralValue;.class
// 1 INVOKESTATIC kotlin/jvm/internal/Reflection.getOrCreateKotlinClass
// @AppGraph$Impl.class:
// 1 BIPUSH 7
// 1 SIPUSH 300
// 2 LDC 123456
// 1 LDC 12345678901
// 1 ICONST_1
// 1 BIPUSH 90
// 1 LDC 12.5
// 1 LDC 12.25
// 1 LDC "inline-string"
// 1 LDC "const-prop-read"
// 1 ACONST_NULL
// 1 GETSTATIC ObjectValue.INSTANCE : LObjectValue;
// 1 GETSTATIC EnumValue.Entry : LEnumValue;
// 1 LDC LClassLiteralValue;.class
// 1 INVOKESTATIC kotlin/jvm/internal/Reflection.getOrCreateKotlinClass
