// ENABLE_SWITCHING_PROVIDERS: true

@DependencyGraph(AppScope::class)
interface AppGraph {
  val childGraph: ChildGraph

  @SingleIn(AppScope::class)
  @Provides
  fun provideString(): String = "Hello"
}

@Inject
@SingleIn(AppScope::class)
class ScopedClass

@GraphExtension
interface ChildGraph {
  val string: String
  val scopedClass: ScopedClass
}
