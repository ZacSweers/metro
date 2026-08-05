// MIN_COMPILER_VERSION: 2.4.20-Beta2
// CHECK_BYTECODE_TEXT

// Kotlin 2.4.20-Beta2 encodes duplicate annotations through a repeatable container, even when the annotation is not repeatable.
// 0 ApplicationModule\$AsyncInitializers\$Container
// 0 ApplicationModule\$Initializers\$Container
// 0 ApplicationModule\$NamedInitializer\$Container

class Initializer(val name: String)

class BackgroundAppCoroutineScope

interface ApplicationModule {
  @Qualifier
  @Retention(AnnotationRetention.BINARY)
  annotation class Initializers

  @Qualifier
  @Retention(AnnotationRetention.BINARY)
  annotation class AsyncInitializers

  @Qualifier
  @Retention(AnnotationRetention.BINARY)
  annotation class NamedInitializer(val value: String)
}

class CatchUpApplication {
  var initializerCount = 0
  var asyncInitializerCount = 0

  @Inject
  @ApplicationModule.NamedInitializer("property")
  lateinit var propertyInitializer: Initializer

  @ApplicationModule.NamedInitializer("setter")
  lateinit var setterInitializer: Initializer
    @Inject set

  @Inject
  fun asyncInits(
    scope: BackgroundAppCoroutineScope,
    @ApplicationModule.AsyncInitializers asyncInitializers: Set<Initializer>,
  ) {
    asyncInitializerCount = asyncInitializers.size
  }

  @Inject
  fun inits(@ApplicationModule.Initializers initializers: Set<Initializer>) {
    initializerCount = initializers.size
  }
}

@DependencyGraph
interface AppGraph {
  val injector: MembersInjector<CatchUpApplication>

  @DependencyGraph.Factory
  interface Factory {
    fun create(
      @Provides scope: BackgroundAppCoroutineScope,
      @Provides @ApplicationModule.AsyncInitializers asyncInitializers: Set<Initializer>,
      @Provides @ApplicationModule.Initializers initializers: Set<Initializer>,
      @Provides
      @ApplicationModule.NamedInitializer("property")
      propertyInitializer: Initializer,
      @Provides
      @ApplicationModule.NamedInitializer("setter")
      setterInitializer: Initializer,
    ): AppGraph
  }
}

fun box(): String {
  val graph =
    createGraphFactory<AppGraph.Factory>()
      .create(
        scope = BackgroundAppCoroutineScope(),
        asyncInitializers = setOf(Initializer("async")),
        initializers = setOf(Initializer("sync")),
        propertyInitializer = Initializer("property"),
        setterInitializer = Initializer("setter"),
      )

  val application = CatchUpApplication()
  graph.injector.injectMembers(application)
  assertEquals(1, application.asyncInitializerCount)
  assertEquals(1, application.initializerCount)
  assertEquals("property", application.propertyInitializer.name)
  assertEquals("setter", application.setterInitializer.name)
  return "OK"
}
