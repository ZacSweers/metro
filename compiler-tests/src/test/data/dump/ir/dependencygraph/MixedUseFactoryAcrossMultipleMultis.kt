@DependencyGraph(AppScope::class)
interface AppGraph {
  @Provides fun provideString(): String = "key"

  val internalHeader: InternalHeader
  val interceptor: CommonAppHeadersInterceptor

  @Provides
  @Named("other")
  @IntoSet
  fun provideInternalHeader(header: InternalHeader): Pair<String, String> {
    return header.key to header.value
  }

  @Provides
  @ElementsIntoSet
  @Named("other")
  fun provideOtherHeaders(): Set<Pair<String, String>> {
    return emptySet()
  }

  @Provides
  @ElementsIntoSet
  fun provideHeaders(
    @Named("other") otherHeaders: Set<Pair<String, String>>
  ): Set<Pair<String, String>> {
    return otherHeaders
  }
}

@Inject
class InternalHeader(val key: String) {
  val value = "value"
}

@Inject class UserAgentProvider

@Inject class UserAgent(userAgentProvider: UserAgentProvider)

@Inject class ClientId

@Inject class ClientDevice(userAgentProvider: UserAgentProvider)

@Inject
class CommonAppHeadersInterceptor(
  additionalHeaders: Set<Pair<String, String>>,
  lazyDelegate: Lazy<CommonAppHeadersInterceptor>,
)
