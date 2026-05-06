// ENABLE_CIRCUIT
// GENERATE_CONTRIBUTION_PROVIDERS: true
// GENERATE_CONTRIBUTION_HINTS_IN_FIR
// MIN_COMPILER_VERSION: 2.3.20

// Ensures circuit-generated nested factory classes work with generateContributionProviders enabled.
// Circuit generates nested Factory classes annotated with @ContributesIntoSet, which can't use the
// contribution provider path (predicates don't see FIR-generated classes). The compiler should
// automatically handle this by generating a standard nested MetroContribution instead.

import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.screen.Screen
import com.slack.circuit.runtime.presenter.Presenter
import com.slack.circuit.codegen.annotations.CircuitInject
import androidx.compose.runtime.Composable

data class FavoritesScreen(val userId: String) : Screen {
  data class State(val count: Int) : CircuitUiState
}

@Inject
@CircuitInject(FavoritesScreen::class, AppScope::class)
class FavoritesPresenter : Presenter<FavoritesScreen.State> {
  @Composable
  override fun present(): FavoritesScreen.State {
    return FavoritesScreen.State(count = 42)
  }
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  val presenterFactories: Set<Presenter.Factory>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val factories = graph.presenterFactories
  if (factories.isEmpty()) return "FAIL: no factories"
  return "OK"
}
