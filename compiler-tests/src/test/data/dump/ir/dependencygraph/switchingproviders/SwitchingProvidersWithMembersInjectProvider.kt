// ENABLE_SWITCHING_PROVIDERS: true

@DependencyGraph(AppScope::class)
interface AppGraph {
  val scopedClass: ScopedClass

  @SingleIn(AppScope::class)
  @Provides
  fun provideString(): String = "Hello"
}

@Inject
@SingleIn(AppScope::class)
class ScopedClass(
  val injectable: MembersInjector<Injectable>,
  val injectable2: MembersInjector<Injectable>,
)

class Injectable {
  @Inject lateinit var string: String
}
