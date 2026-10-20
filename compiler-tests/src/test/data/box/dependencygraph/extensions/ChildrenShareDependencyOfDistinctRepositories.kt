// https://github.com/ZacSweers/metro/discussions/2923#discussioncomment-19052566
@SingleIn(AppScope::class)
class Logger @Inject constructor()

interface TrackingAdapter
class DefaultTrackingAdapter @Inject constructor(val logger: Logger) : TrackingAdapter
interface TrackingDataSource
class DefaultTrackingDataSource @Inject constructor(val logger: Logger) : TrackingDataSource
interface TrackingHandler
class DefaultTrackingHandler @Inject constructor(
  val adapter: TrackingAdapter,
  val dataSource: TrackingDataSource,
  val logger: Logger,
) : TrackingHandler

@BindingContainer
interface TrackingModule {
  @Binds fun handler(instance: DefaultTrackingHandler): TrackingHandler
  @Binds fun adapter(instance: DefaultTrackingAdapter): TrackingAdapter
  @Binds fun dataSource(instance: DefaultTrackingDataSource): TrackingDataSource
}

@DependencyGraph(AppScope::class, bindingContainers = [TrackingModule::class])
interface AppComponent {
  val child1: ActivitySubComponent1
  val branch: RepositoryBranch
  val child3: ActivitySubComponent3
  val overridingChild: OverridingChild
}

class ActivityRepository1 @Inject constructor(val trackingHandler: TrackingHandler)
@GraphExtension
interface ActivitySubComponent1 { val repository: ActivityRepository1 }

class ActivityRepository2 @Inject constructor(val trackingHandler: TrackingHandler)
@GraphExtension
interface ActivitySubComponent2 { val repository: ActivityRepository2 }

class ActivityRepository3 @Inject constructor(val trackingHandler: TrackingHandler)
@GraphExtension
interface ActivitySubComponent3 { val repository: ActivityRepository3 }

class ChildTrackingAdapter(val logger: Logger) : TrackingAdapter

@GraphExtension
interface OverridingChild {
  val repository: ActivityRepository1

  @Provides fun adapter(logger: Logger): TrackingAdapter = ChildTrackingAdapter(logger)
}

fun box(): String {
  val graph = createGraph<AppComponent>()
  val child1 = graph.child1
  val first = assertIs<DefaultTrackingHandler>(child1.repository.trackingHandler)
  val repeated = assertIs<DefaultTrackingHandler>(child1.repository.trackingHandler)
  val second = assertIs<DefaultTrackingHandler>(graph.branch.child2.repository.trackingHandler)
  val third = assertIs<DefaultTrackingHandler>(graph.child3.repository.trackingHandler)
  for (handler in listOf(repeated, second, third)) {
    assertNotSame(first, handler)
    assertNotSame(first.adapter, handler.adapter)
    assertNotSame(first.dataSource, handler.dataSource)
    assertSame(first.logger, handler.logger)
    assertSame(first.logger, assertIs<DefaultTrackingAdapter>(handler.adapter).logger)
    assertSame(first.logger, assertIs<DefaultTrackingDataSource>(handler.dataSource).logger)
  }
  val overridden = assertIs<DefaultTrackingHandler>(graph.overridingChild.repository.trackingHandler)
  assertIs<ChildTrackingAdapter>(overridden.adapter)
  assertSame(first.logger, overridden.logger)
  assertSame(first.logger, assertIs<ChildTrackingAdapter>(overridden.adapter).logger)
  return "OK"
}

@GraphExtension
interface RepositoryBranch {
  val child2: ActivitySubComponent2
}
