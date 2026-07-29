// MIN_COMPILER_VERSION: 2.4.20-Beta2
// IGNORE_BACKEND: JS_IR
// GENERATE_CLASSES_IN_IR: false
// CHECK_BYTECODE_TEXT

// Kotlin 2.4.20-Beta2 encodes duplicate annotations through a repeatable container, even when the annotation is not repeatable.
// 0 ApplicationModule\$AsyncInitializers\$Container
// 0 ApplicationModule\$Initializers\$Container

class Initializer

class BackgroundAppCoroutineScope

interface ApplicationModule {
  @Qualifier
  @Retention(AnnotationRetention.BINARY)
  annotation class Initializers

  @Qualifier
  @Retention(AnnotationRetention.BINARY)
  annotation class AsyncInitializers
}

class CatchUpApplication {
  var initializerCount = 0
  var asyncInitializerCount = 0

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

fun box(): String {
  @Suppress("DEPRECATION_ERROR")
  val injector =
    CatchUpApplication.MetroMembersInjector.Companion.create(
      providerOf(BackgroundAppCoroutineScope()),
      providerOf(setOf(Initializer())),
      providerOf(setOf(Initializer())),
    )

  val application = CatchUpApplication()
  injector.injectMembers(application)
  assertEquals(1, application.asyncInitializerCount)
  assertEquals(1, application.initializerCount)
  return "OK"
}
