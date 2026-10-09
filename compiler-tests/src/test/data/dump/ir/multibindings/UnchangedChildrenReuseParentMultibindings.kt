// https://github.com/ZacSweers/metro/discussions/2923#discussioncomment-18823579

@Qualifier annotation class LocalOnly

interface Adapter
@Inject class FirstAdapter : Adapter
@Inject class SecondAdapter : Adapter
@Inject class ThirdAdapter : Adapter

interface Handler
@Inject class TrackingHandler(val adapters: Set<Adapter>) : Handler

@DependencyGraph
interface AppGraph {
  val adapters: Set<Adapter>
  val activity: ActivityGraph
  val sibling: SiblingGraph
  val localOnly: LocalOnlyGraph

  @Provides @IntoSet @LocalOnly fun localName(): String = "local"

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

@GraphExtension
interface LocalOnlyGraph {
  @LocalOnly val names: Set<String>
}
