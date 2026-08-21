// WITH_ANVIL
// MODULE: lib
// ENABLE_ANVIL_INTEROP: false
// FILE: lib.kt

interface Service

@com.squareup.anvil.annotations.ContributesBinding(AppScope::class, rank = 100)
object ProducerImpl : Service

// MODULE: main(lib)

@ContributesBinding(AppScope::class, priority = 10)
object ConsumerImpl : Service

@DependencyGraph(AppScope::class)
interface AppGraph {
  val service: Service
}

fun box(): String {
  assertSame(ProducerImpl, createGraph<AppGraph>().service)
  return "OK"
}
