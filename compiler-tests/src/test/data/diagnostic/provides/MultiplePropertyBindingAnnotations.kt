@Qualifier annotation class Red
@Qualifier annotation class Blue

@Scope annotation class First
@Scope annotation class Second

@DependencyGraph
interface Graph {
  <!BINDING_ERROR!>@Red<!>
  <!BINDING_ERROR!>@Blue<!>
  @Provides
  val qualified: String get() = "value"

  <!BINDING_ERROR!>@First<!>
  <!BINDING_ERROR!>@get:Second<!>
  @Provides
  val scoped: Int get() = 1

  <!MULTIBINDS_ERROR!>@StringKey("key")<!>
  <!MULTIBINDS_ERROR!>@IntKey(1)<!>
  @Provides
  @IntoMap
  val entry: String get() = "value"
}
