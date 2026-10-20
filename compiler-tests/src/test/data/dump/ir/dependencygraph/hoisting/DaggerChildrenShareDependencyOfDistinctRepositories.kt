// ENABLE_DAGGER_INTEROP
// https://github.com/ZacSweers/metro/discussions/2923#discussioncomment-19052566
@javax.inject.Singleton
class Logger @javax.inject.Inject constructor()

interface TrackingAdapter
class DefaultTrackingAdapter @javax.inject.Inject constructor(val logger: Logger) : TrackingAdapter
interface TrackingDataSource
class DefaultTrackingDataSource @javax.inject.Inject constructor(val logger: Logger) : TrackingDataSource
interface TrackingHandler
class DefaultTrackingHandler @javax.inject.Inject constructor(
  val adapter: TrackingAdapter,
  val dataSource: TrackingDataSource,
  val logger: Logger,
) : TrackingHandler

@dagger.Module
interface TrackingModule {
  @dagger.Binds fun handler(instance: DefaultTrackingHandler): TrackingHandler
  @dagger.Binds fun adapter(instance: DefaultTrackingAdapter): TrackingAdapter
  @dagger.Binds fun dataSource(instance: DefaultTrackingDataSource): TrackingDataSource
}

@javax.inject.Singleton
@dagger.Component(modules = [TrackingModule::class])
interface AppComponent {
  val child1: ActivitySubComponent1
  val child2: ActivitySubComponent2
  val child3: ActivitySubComponent3
}

class ActivityRepository1 @javax.inject.Inject constructor(val trackingHandler: TrackingHandler)
@dagger.Subcomponent
interface ActivitySubComponent1 { val repository: ActivityRepository1 }

class ActivityRepository2 @javax.inject.Inject constructor(val trackingHandler: TrackingHandler)
@dagger.Subcomponent
interface ActivitySubComponent2 { val repository: ActivityRepository2 }

class ActivityRepository3 @javax.inject.Inject constructor(val trackingHandler: TrackingHandler)
@dagger.Subcomponent
interface ActivitySubComponent3 { val repository: ActivityRepository3 }
