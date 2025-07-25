@DependencyGraph
internal interface StaticGraph {
  abstract val int: Int
    abstract get

  @Provides
  private fun provideInt(): Int {
    return 3
  }

  companion object Companion : StaticGraph {
    private constructor() /* primary */ {
      super/*Any*/()
      /* <init>() */

    }

    private /* final field */ val $$delegate_0: StaticGraph = createGraph<StaticGraph>()
    @GraphFactoryInvokeFunctionMarker
    operator fun invoke(): StaticGraph {
      return $$MetroGraph()
    }

    override val int: Int
      override get(): Int {
        return <this>.#$$delegate_0.<get-int>()
      }

    /* fake */ override operator fun equals(other: Any?): Boolean

    /* fake */ override fun hashCode(): Int

    /* fake */ override fun toString(): String

  }

  @Deprecated(message = "This synthesized declaration should not be used directly", level = DeprecationLevel.HIDDEN)
  @CallableMetadata(callableName = "provideInt", isPropertyAccessor = false, startOffset = 167, endOffset = 202)
  class ProvideInt$$MetroFactory : Factory<Int> {
    private constructor(instance: StaticGraph) /* primary */ {
      super/*Any*/()
      /* <init>() */

    }

    companion object Companion {
      private constructor() /* primary */ {
        super/*Any*/()
        /* <init>() */

      }

      fun create(instance: StaticGraph): Factory<Int> {
        return ProvideInt$$MetroFactory(instance = instance)
      }

      fun provideInt(instance: StaticGraph): Int {
        return instance.provideInt()
      }

      /* fake */ override operator fun equals(other: Any?): Boolean

      /* fake */ override fun hashCode(): Int

      /* fake */ override fun toString(): String

    }

    override operator fun invoke(): Int {
      return Companion.provideInt(instance = <this>.#instance)
    }

    /* fake */ override operator fun equals(other: Any?): Boolean

    /* fake */ override fun hashCode(): Int

    /* fake */ override fun toString(): String

    private /* final field */ val instance: StaticGraph = instance
    fun mirrorFunction(): Int {
      return error(message = "Never called")
    }

  }

  @Deprecated(message = "This synthesized declaration should not be used directly", level = DeprecationLevel.HIDDEN)
  class $$MetroGraph : StaticGraph {
    private constructor() /* primary */ {
      super/*Any*/()
      /* <init>() */

    }

    override val int: Int
    override get(): Int {
      return Companion.create(instance = <this>.#thisGraphInstance).invoke()
    }

    /* fake */ override operator fun equals(other: Any?): Boolean

    /* fake */ override fun hashCode(): Int

    /* fake */ override fun toString(): String

    private /* final field */ val thisGraphInstance: StaticGraph = <this>
    private /* final field */ val staticGraphProvider: Provider<StaticGraph> = Companion.invoke<StaticGraph>(value = <this>.#thisGraphInstance)
  }

  /* fake */ override operator fun equals(other: Any?): Boolean

  /* fake */ override fun hashCode(): Int

  /* fake */ override fun toString(): String

}

@DependencyGraph
internal interface StaticGraphWithFactory {
  abstract val int: Int
    abstract get

  @Factory
  interface Factory {
    abstract fun build(@Includes staticGraph: StaticGraph): StaticGraphWithFactory

    @Deprecated(message = "This synthesized declaration should not be used directly", level = DeprecationLevel.HIDDEN)
    object $$Impl : Factory {
      private constructor() /* primary */ {
        super/*Any*/()
        /* <init>() */

      }

      @GraphFactoryInvokeFunctionMarker
      override fun build(@Includes staticGraph: StaticGraph): StaticGraphWithFactory {
        return $$MetroGraph(staticGraph = staticGraph)
      }

      /* fake */ override operator fun equals(other: Any?): Boolean

      /* fake */ override fun hashCode(): Int

      /* fake */ override fun toString(): String

    }

    /* fake */ override operator fun equals(other: Any?): Boolean

    /* fake */ override fun hashCode(): Int

    /* fake */ override fun toString(): String

  }

  companion object Companion : StaticGraphWithFactory {
    private constructor() /* primary */ {
      super/*Any*/()
      /* <init>() */

    }

    private /* final field */ val $$delegate_0: StaticGraphWithFactory = createGraphFactory<Factory>().build(staticGraph = Companion)
    fun factory(): Factory {
      return $$Impl()
    }

    override val int: Int
      override get(): Int {
        return <this>.#$$delegate_0.<get-int>()
      }

    /* fake */ override operator fun equals(other: Any?): Boolean

    /* fake */ override fun hashCode(): Int

    /* fake */ override fun toString(): String

  }

  @Deprecated(message = "This synthesized declaration should not be used directly", level = DeprecationLevel.HIDDEN)
  class $$MetroGraph : StaticGraphWithFactory {
    private constructor(@Includes staticGraph: StaticGraph) /* primary */ {
      super/*Any*/()
      /* <init>() */

    }

    override val int: Int
    override get(): Int {
      return <this>.#staticGraphGetIntProvider.invoke()
    }

    /* fake */ override operator fun equals(other: Any?): Boolean

    /* fake */ override fun hashCode(): Int

    /* fake */ override fun toString(): String

    private /* final field */ val staticGraphInstance: StaticGraph = staticGraph
    private /* final field */ val staticGraphGetIntProvider: Provider<Int> = provider<Int>(provider = local fun <anonymous>(): Int {
      return <this>.#staticGraphInstance.<get-int>()
    }
    )
  }

  /* fake */ override operator fun equals(other: Any?): Boolean

  /* fake */ override fun hashCode(): Int

  /* fake */ override fun toString(): String

}

