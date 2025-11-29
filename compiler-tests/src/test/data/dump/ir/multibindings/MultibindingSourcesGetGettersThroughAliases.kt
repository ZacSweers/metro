// https://github.com/ZacSweers/metro/issues/1397

abstract class Base

@Inject class Foo : Base()
@Inject class Bar : Base()

@DependencyGraph
interface AppGraph {
  val values: Set<Any>

  @Binds @IntoSet val Foo.bind: Any
  @Binds val Bar.bind2: Base
  @Binds @IntoSet val Base.bind3: Any
}