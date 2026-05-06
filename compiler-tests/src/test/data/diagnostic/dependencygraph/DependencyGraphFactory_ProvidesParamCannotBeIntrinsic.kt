// RENDER_DIAGNOSTICS_FULL_TEXT
// ENABLE_DAGGER_INTEROP

// `@Provides` on a @DependencyGraph.Factory parameter is Metro's analog to Dagger's
// `@BindsInstance`. Passing a pre-wrapped intrinsic type silently double-wraps the binding,
// so intrinsic parameter types are rejected the same way as intrinsic return types on
// `@Provides`/`@Binds` declarations.

class Target

@DependencyGraph
interface ExampleGraph {
  val string: String
  val target: Target

  @DependencyGraph.Factory
  interface Factory {
    fun create(
      @Provides string: <!INTRINSIC_BINDING_ERROR!>Provider<Double><!>,
      @Provides lazyTarget: <!INTRINSIC_BINDING_ERROR!>Lazy<Int><!>,
      @Provides factory: <!INTRINSIC_BINDING_ERROR!>() -> String<!>,
      @dagger.BindsInstance bindsInstanceFactory: <!INTRINSIC_BINDING_ERROR!>() -> Long<!>,
    ): ExampleGraph
  }
}
