// RENDER_DIAGNOSTICS_FULL_TEXT
// Originally from for https://github.com/ZacSweers/metro/issues/1193

@DependencyGraph
interface AComponent {
  val bar: Bar

  @Provides
  fun text(): String = "Hola"
}

@Inject
class Bar(text: String)

@DependencyGraph
interface BComponent {
  val bar: Bar

  @Provides
  fun aComponent(): AComponent = createGraph<AComponent>()

  @Provides
  fun <!REDUNDANT_PROVIDES!>bar<!>(aComponent: AComponent): Bar = aComponent.bar
}
