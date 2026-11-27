// RUN_PIPELINE_TILL: BACKEND
// PARENT_BINDING_OVERRIDE_SEVERITY: ERROR

// No ancestor keeps an instance of these classes, so a child binding for them isn't an override.
// Every graph builds its own Unscoped. ChildScoped belongs to the child's own scope.

abstract class ChildScope private constructor()

@Inject open class Unscoped

@Inject class ChildUnscoped : Unscoped()

@SingleIn(ChildScope::class) @Inject open class ChildScoped

@Inject class OtherChildScoped : ChildScoped()

@DependencyGraph(AppScope::class)
interface ParentGraph {
  val unscoped: Unscoped

  fun childGraphFactory(): ChildGraph.Factory
}

@GraphExtension(ChildScope::class)
interface ChildGraph {
  val unscoped: Unscoped
  val childScoped: ChildScoped

  @Binds val ChildUnscoped.bindUnscoped: Unscoped

  @Binds val OtherChildScoped.bindChildScoped: ChildScoped

  @GraphExtension.Factory
  interface Factory {
    fun create(): ChildGraph
  }
}
