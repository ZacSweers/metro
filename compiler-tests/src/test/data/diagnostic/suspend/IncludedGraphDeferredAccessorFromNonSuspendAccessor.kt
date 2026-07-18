// ENABLE_SUSPEND_PROVIDERS

// RUN_PIPELINE_TILL: FIR2IR
// RENDER_IR_DIAGNOSTICS_FULL_TEXT
@file:Suppress("OPT_IN_USAGE")

@DependencyGraph
interface ProviderGraph {
  val value: suspend () -> Int

  @Provides suspend fun provideInt(): Int = 1
}

@DependencyGraph
interface ProviderConsumerGraph {
  val <!METRO_ERROR!>value<!>: Int

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes providerGraph: ProviderGraph): ProviderConsumerGraph
  }
}

@DependencyGraph
interface LazyGraph {
  val value: SuspendLazy<Long>

  @Provides suspend fun provideLong(): Long = 1L
}

@DependencyGraph
interface LazyConsumerGraph {
  val <!METRO_ERROR!>value<!>: Long

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Includes lazyGraph: LazyGraph): LazyConsumerGraph
  }
}
