// WITH_ANVIL
// MODULE: lib

interface Service
interface OtherService

@Named("ignored")
@com.squareup.anvil.annotations.ContributesBinding(
  scope = AppScope::class,
  boundType = Service::class,
  ignoreQualifier = true,
  rank = 100,
)
object ProducerImpl : Service, OtherService

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
