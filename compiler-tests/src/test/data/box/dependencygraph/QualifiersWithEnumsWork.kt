// https://github.com/ZacSweers/metro/issues/690
enum class AuthScope { UNAUTHENTICATED, ACCOUNT, PROFILE }

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ForAuthScope(val scope: AuthScope)

@DependencyGraph
interface AppGraph {
  @ForAuthScope(AuthScope.UNAUTHENTICATED) val id: Int

  @Provides @ForAuthScope(AuthScope.UNAUTHENTICATED) fun provideId(): Int = 3
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertEquals(3, graph.id)
  return "OK"
}