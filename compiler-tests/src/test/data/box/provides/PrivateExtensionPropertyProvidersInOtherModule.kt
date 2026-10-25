// MIN_COMPILER_VERSION: 2.4.20-Beta1
// ENABLE_PRIVATE_PROVIDER_PROPERTIES
// LANGUAGE: +ContextParameters

// MODULE: lib
interface Providers {
  @get:Provides
  @get:Named("mixed")
  context(@Named("prefix") prefix: String)
  private val @receiver:Named("name") String.provideMixed: String
    get() = "$prefix$this"
}

// MODULE: main(lib)
@DependencyGraph
interface AppGraph : Providers {
  @Named("mixed") val mixed: String

  @Provides @Named("prefix") fun prefix(): String = ">"

  @Provides @Named("name") fun name(): String = "name"
}

fun box(): String {
  assertEquals(">name", createGraph<AppGraph>().mixed)
  return "OK"
}
