// WITH_ANVIL

import com.squareup.anvil.annotations.ContributesBinding

interface Service

typealias ServiceAlias = Service

@ContributesBinding(AppScope::class, boundType = ServiceAlias::class)
@Inject
class ServiceImpl : Service

@DependencyGraph(AppScope::class)
interface AppGraph {
  val service: Service
}

fun box(): String {
  assertIs<ServiceImpl>(createGraph<AppGraph>().service)
  return "OK"
}
