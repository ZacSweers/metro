// ENABLE_SWITCHING_PROVIDERS: true

@DependencyGraph(AppScope::class)
interface AppGraph {
  val string: String
  val scopedClass: ScopedClass

  val int1: Provider<Int>
  val int2: Provider<Int>

  @SingleIn(AppScope::class)
  @Provides
  fun provideString(): String = "Hello"

  @Provides
  fun provideReusedInt(): Int = 3
}

@Inject
@SingleIn(AppScope::class)
class ScopedClass
