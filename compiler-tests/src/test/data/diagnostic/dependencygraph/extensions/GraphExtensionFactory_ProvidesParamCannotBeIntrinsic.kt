// RENDER_DIAGNOSTICS_FULL_TEXT
// ENABLE_DAGGER_INTEROP

// Same rule applies to @GraphExtension.Factory `@Provides` parameters as to
// @DependencyGraph.Factory parameters — intrinsic parameter types silently double-wrap
// and are rejected.

class Target

@DependencyGraph
interface ParentGraph : ChildGraph.Factory

@GraphExtension
interface ChildGraph {
  val string: String
  val target: Target

  @GraphExtension.Factory
  interface Factory {
    fun create(
      @Provides string: <!INTRINSIC_BINDING_ERROR!>Provider<Double><!>,
      @Provides lazyTarget: <!INTRINSIC_BINDING_ERROR!>Lazy<Int><!>,
      @Provides factory: <!INTRINSIC_BINDING_ERROR!>() -> String<!>,
      @dagger.BindsInstance bindsInstanceFactory: <!INTRINSIC_BINDING_ERROR!>() -> Long<!>,
    ): ChildGraph
  }
}
