// Regression test: Metro crashes on named annotation arguments with class references.
// FirNamedArgumentExpression is not handled in renderAnnotationArgument().
// See: https://github.com/ZacSweers/metro/issues/XXXX
// ENABLE_DAGGER_INTEROP
// WITH_ANVIL

import com.squareup.anvil.annotations.ContributesBinding
import com.squareup.anvil.annotations.MergeComponent
import com.squareup.anvil.annotations.optional.SingleIn
import javax.inject.Inject
import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class MyQualifier(val value: kotlin.reflect.KClass<*>)

interface Service

@ContributesBinding(AppScope::class)
class ServiceImpl @Inject constructor() : Service

// This annotation uses a named argument (value = ...) which triggers
// FirNamedArgumentExpression in the FIR tree.
@MyQualifier(value = Service::class)
@ContributesBinding(AppScope::class)
class QualifiedService @Inject constructor() : Service

@SingleIn(AppScope::class)
@MergeComponent(AppScope::class)
interface AppGraph {
  val service: Service
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  assertNotNull(graph.service)
  return "OK"
}
