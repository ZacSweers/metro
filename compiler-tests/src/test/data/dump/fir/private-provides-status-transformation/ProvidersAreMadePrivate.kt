interface ExampleProviders {
  @Provides
  fun shouldBePrivate(): String = "hello"

  @Provides
  public fun shouldNotBePrivate(): String = "hello"

  @Provides
  internal fun shouldNotBePrivate(): String = "hello"

  companion object {
    @Provides
    fun shouldBePrivate(): String = "hello"

    @Provides
    public fun shouldNotBePrivate(): String = "hello"

    @Provides
    internal fun shouldNotBePrivate(): String = "hello"
  }
}
