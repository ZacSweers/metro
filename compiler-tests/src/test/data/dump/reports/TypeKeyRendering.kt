// ENABLE_FULL_BINDING_GRAPH_VALIDATION
// ENABLE_FUNCTION_PROVIDERS: false
// CHECK_REPORTS: keys-populated/test/ExampleGraph/Impl

package test

import java.util.concurrent.Callable
import kotlin.reflect.KClass

// TODO add context parameters
const val THREE = "3"

@DependencyGraph(AppScope::class)
interface ExampleGraph {
  @Provides val string: String get() = "Hello World!"
  @Provides val int: Int get() = 0
  @Provides val list: List<Int> get() = listOf(1)
  @Provides @Named("star") val listStar: List<*> get() = emptyList<Int>()
  @Provides val callableIn: Callable<in Number> get() = error("not called")
  @Provides val callableOut: Callable<out CharSequence> get() = error("not called")
  @Provides @Named("suppressed") val callableOutSuppressed: @JvmSuppressWildcards Callable<out CharSequence> get() = error("not called")
  @Provides val set: @JvmSuppressWildcards Set<CharSequence> get() = setOf("1")
  @Provides val map: Map<String, @JvmSuppressWildcards CharSequence> get() = mapOf("1" to "2")
  @Provides val functionString: () -> String get() = error("not called")
  @Provides val functionStringWithParams: (Int, String) -> String get() = error("not called")
  @Provides val functionStringWithReceiverParams: String.(Int, String) -> String get() = error("not called")
  @Provides val suspendFunctionStringWithReceiverParams: suspend String.(Int, String) -> String get() = error("not called")
  @Provides val utterlyUnhinged: suspend String?.(Int?, suspend (String) -> (Unit) -> String?) -> String get() = error("not called")
  @Provides @ForScope(AppScope::class) val qualifiedString: String get() = "Hello World!"
  @Provides @Named("qualified") val qualifiedString2: String get() = "Hello World!"
  @Provides @ForScope(AppScope::class) val qualifiedInt: Int get() = 0
  @Provides @ForScope(AppScope::class) val qualifiedList: List<Int> get() = listOf(1)
  @Provides @ForScope(AppScope::class) val qualifiedSet: @JvmSuppressWildcards Set<CharSequence> get() = setOf("1")
  @Provides @ForScope(AppScope::class) val qualifiedMap: Map<String, @JvmSuppressWildcards CharSequence> get() = mapOf("1" to "2")
  @Provides
  @ComplexQualifier<String>(
    boolean = true,
    int = 1,
    string = const,
    klass = Int::class,
    classArray = [Int::class, String::class],
    anotherAnnotation = ForScope(AppScope::class),
    anotherAnnotationArray = [ForScope(AppScope::class), ForScope(Unit::class)],
  )
  val complexQualifier: String get() = "Hello World!"
  @Provides @Named("${THREE}four") val interpolatedAnnotation: String get() = "Hello World!"
}

const val const = "Hello World!"

@Qualifier
annotation class ComplexQualifier<T>(
  val boolean: Boolean,
  val int: Int,
  val string: String,
  val klass: KClass<*>,
  val classArray: Array<KClass<*>>,
  val anotherAnnotation: ForScope,
  val anotherAnnotationArray: Array<ForScope>,
)
