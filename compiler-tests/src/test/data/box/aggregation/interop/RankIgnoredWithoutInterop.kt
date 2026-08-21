// WITH_ANVIL
// MODULE: lib

interface Service

@com.squareup.anvil.annotations.ContributesBinding(AppScope::class, rank = 100)
object ProducerImpl : Service

// MODULE: main(lib)
// ENABLE_ANVIL_INTEROP: false

@ContributesBinding(AppScope::class, priority = 10)
object ConsumerImpl : Service

@DependencyGraph(AppScope::class)
interface AppGraph {
  val service: Service
}

fun box(): String {
  assertSame(ConsumerImpl, createGraph<AppGraph>().service)
  return "OK"
}
