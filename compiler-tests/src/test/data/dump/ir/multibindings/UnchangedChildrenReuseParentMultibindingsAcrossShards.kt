// ENABLE_GRAPH_SHARDING: true
// KEYS_PER_GRAPH_SHARD: 2
// ENABLE_SWITCHING_PROVIDERS: true

// https://github.com/ZacSweers/metro/discussions/2923#discussioncomment-18823579

interface Adapter
@SingleIn(AppScope::class) @Inject class One
@SingleIn(AppScope::class) @Inject class Two
@SingleIn(AppScope::class) @Inject class Three
@Inject class FirstAdapter(val one: One, val two: Two, val three: Three) : Adapter
@Inject class SecondAdapter : Adapter
@Inject class ThirdAdapter : Adapter

interface Handler
@Inject class TrackingHandler(val adapters: Set<Adapter>) : Handler

@DependencyGraph(AppScope::class)
interface AppGraph {
  val adapters: Set<Adapter>
  val activity: ActivityGraph
  val sibling: SiblingGraph

  @Binds fun handler(handler: TrackingHandler): Handler
  @Binds @IntoSet fun first(adapter: FirstAdapter): Adapter
  @Binds @IntoSet fun second(adapter: SecondAdapter): Adapter
}

@GraphExtension
interface ActivityGraph {
  val handler: Handler
  val viewModel: ViewModelGraph
}

@GraphExtension
interface SiblingGraph {
  val handler: Handler
}

@GraphExtension
interface ViewModelGraph {
  val handler: Handler

  @Binds @IntoSet fun third(adapter: ThirdAdapter): Adapter
}
