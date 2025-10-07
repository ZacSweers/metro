// MembershipRepo
interface MembershipRepo

@ContributesBinding(AppScope::class)
@Inject
class RealMembershipRepo(
  remoteConfig: Lazy<RemoteConfig>,
) : MembershipRepo {
  val hash = remoteConfig.value.hashCode()
}

@ContributesBinding(AppScope::class, replaces = [RealMembershipRepo::class])
@Inject
class FakeMembershipRepo(
  real: RealMembershipRepo,
  remoteConfig: Lazy<RemoteConfig>,
) : MembershipRepo {
  val hash = real.hashCode() + remoteConfig.value.hashCode()
}

// Remote Config
interface RemoteConfig

@ContributesBinding(AppScope::class, binding = binding<@Named("source_remote_config") RemoteConfig>())
@Inject
class CombinedRemoteConfig(
  private val amplitudeRemoteConfig: Lazy<AmplitudeRemoteConfig>,
) : RemoteConfig {
  val hash = amplitudeRemoteConfig.value.hashCode()
}

@ContributesBinding(AppScope::class)
@Inject
class OverridableRemoteConfig(
  @Named("source_remote_config") private val remoteConfig: RemoteConfig,
) : RemoteConfig {
  val hash = remoteConfig.hashCode()
}

@Inject
class AmplitudeRemoteConfig(
  private val remoteConfig: RemoteConfig,
  private val membershipRepo: MembershipRepo,
) : RemoteConfig {
  val hash = remoteConfig.hashCode() + membershipRepo.hashCode()
}

@DependencyGraph(AppScope::class)
interface CycleGraph {
  val membershipRepo: MembershipRepo
}

fun box(): String {
  val cycleGraph = createGraph<CycleGraph>()
  assertNotNull(cycleGraph.membershipRepo)
  return "OK"
}
