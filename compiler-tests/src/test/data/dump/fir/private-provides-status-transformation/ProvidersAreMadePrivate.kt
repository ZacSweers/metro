// TRANSFORM_PROVIDERS_TO_PRIVATE: true

interface ExampleProviders {
  @Provides
  fun shouldBePrivate(): String = "hello"

  @Provides
  public fun shouldNotBePrivate1(): String = "hello"

  @Provides
  private fun shouldBePrivate2(): String = "hello"

  companion object {
    @Provides
    fun shouldBePrivate(): String = "hello"

    @Provides
    public fun shouldNotBePrivate1(): String = "hello"

    @Provides
    internal fun shouldNotBePrivate2(): String = "hello"
  }
}
