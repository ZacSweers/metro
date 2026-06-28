// RENDER_DIAGNOSTICS_FULL_TEXT

interface ExampleGraph {
  @Binds fun valid(): InjectedType

  @Binds fun missingInject(): <!BINDS_ERROR!>MissingInject<!>

  @Binds fun genericClass(): <!BINDS_ERROR!>GenericType<*><!>

  @Binds fun genericReturn(): <!BINDS_ERROR!>GenericType<String><!>

  @Binds fun interfaceType(): <!BINDS_ERROR!>Service<!>

  @Named("name")
  @Binds
  fun <!BINDS_ERROR!>qualified<!>(): InjectedType

  @Binds
  <!BINDS_ERROR!>@IntoSet<!>
  fun intoSet(): InjectedType

  @Binds
  <!BINDS_ERROR!>@IntoMap<!>
  @StringKey("key")
  fun intoMap(): InjectedType

  <!BINDS_ERROR!>@SingleIn(Unit::class)<!>
  @Binds
  fun scoped(): InjectedType
}

@Inject class InjectedType

class MissingInject

@Inject class GenericType<T>

interface Service
