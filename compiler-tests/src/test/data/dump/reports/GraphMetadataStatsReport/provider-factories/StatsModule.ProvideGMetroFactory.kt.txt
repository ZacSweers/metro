@Deprecated(message = "This synthesized declaration should not be used directly", level = DeprecationLevel.HIDDEN)
@CallableMetadata(callableName = "provideG", propertyName = "", startOffset = 897, endOffset = 935, newInstanceName = "provideG")
class ProvideGMetroFactory : Factory<G> {
  companion object Companion {
    private constructor() /* primary */ {
      super/*Any*/()
      /* <init>() */

    }

    /* fake */ override operator fun equals(other: Any?): Boolean

    /* fake */ override fun hashCode(): Int

    /* fake */ override fun toString(): String

    @HiddenFromObjC
    @JvmStatic
    @JsStatic
    fun create(f: Provider<F>): Factory<G> {
      return ProvideGMetroFactory(f = f)
    }

    @HiddenFromObjC
    @JvmStatic
    @JsStatic
    fun provideG(f: F): G {
      return StatsModule.provideG(f = f)
    }

  }

  /* fake */ override operator fun equals(other: Any?): Boolean

  /* fake */ override fun hashCode(): Int

  /* fake */ override fun toString(): String

  @HiddenFromObjC
  override operator fun invoke(): G {
    return ProvideGMetroFactory/* companion */.provideG(f = <this>.#f.invoke())
  }

  @HiddenFromObjC
  private constructor(f: Provider<F>) /* primary */ {
    super/*Any*/()
    /* <init>() */

  }

  private /* final field */ val f: Provider<F> = f
  @ComptimeOnly
  @HiddenFromObjC
  fun mirrorFunction(f: F): G {
    return error(message = "Never called")
  }

}
