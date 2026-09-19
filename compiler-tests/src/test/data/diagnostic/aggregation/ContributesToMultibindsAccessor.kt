// RENDER_DIAGNOSTICS_FULL_TEXT

import kotlin.reflect.KClass

public interface Robot

@ContributesTo(AppScope::class)
public interface RobotGraph {
  @Multibinds(allowEmpty = true)
  public val robots: Map<KClass<*>, () -> Robot>
}

@ContributesTo(AppScope::class)
public interface RobotGraphWithoutMultibinds {
  public val robots: Map<KClass<*>, () -> Robot>
}
