// MIN_COMPILER_VERSION: 2.5.0-dev-7307

@BindingContainer
interface Providers {
  @Provides fun <!BINDING_CONTAINER_ERROR!>instanceValue<!>(): Int = 1

  companion {
    @Provides fun staticValue(): String = "static"
  }
}
