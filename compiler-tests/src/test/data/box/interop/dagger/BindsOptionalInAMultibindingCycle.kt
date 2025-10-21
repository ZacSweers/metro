// https://github.com/ZacSweers/metro/issues/1201
// ENABLE_DAGGER_INTEROP
// MODULE: lib
import dagger.BindsOptionalOf

interface CycleReference

@BindingContainer
interface OptionalCycleReferenceModule {
  @BindsOptionalOf
  fun optionalCycleReference(): CycleReference
}

// MODULE: main(lib)
import java.util.Optional

abstract class LoggedInScope private constructor()

@SingleIn(LoggedInScope::class) @Inject class Cycle1(cycle2: Cycle2)

@SingleIn(LoggedInScope::class) @Inject class Cycle2(cycle1Provider: Provider<Cycle1>)

@SingleIn(LoggedInScope::class)
@ContributesBinding(LoggedInScope::class)
@Inject
class LoggedInCycleReference(cycle2: Cycle2) : CycleReference

@SingleIn(AppScope::class)
@DependencyGraph(AppScope::class, bindingContainers = [OptionalCycleReferenceModule::class])
interface AppGraph {
  val loggedInGraph: LoggedInGraph
}

interface Multibinding

@ContributesIntoSet(LoggedInScope::class)
@Inject
class Multibinding1(val optional: Optional<CycleReference>) : Multibinding

@ContributesIntoSet(LoggedInScope::class)
@Inject
class Multibinding2(val optional: Optional<CycleReference>) : Multibinding

@SingleIn(LoggedInScope::class)
@Inject
class MultibindingsReference(val multibindings: Set<Multibinding>)

@SingleIn(LoggedInScope::class)
@GraphExtension(LoggedInScope::class)
interface LoggedInGraph {
  val multibindingsReference: MultibindingsReference
}

fun box(): String {
  val graph = createGraph<AppGraph>().loggedInGraph
  assertEquals(2, graph.multibindingsReference.multibindings.size)
  return "OK"
}