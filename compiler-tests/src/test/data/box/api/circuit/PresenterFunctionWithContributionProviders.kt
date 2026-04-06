// ENABLE_CIRCUIT
// GENERATE_CONTRIBUTION_PROVIDERS
// GENERATE_CONTRIBUTION_HINTS_IN_FIR
// MIN_COMPILER_VERSION: 2.3.20

// Ensures circuit-generated top-level classes work with generateContributionProviders enabled.
// Circuit generates top-level factory classes annotated with @ContributesIntoSet, which cannot
// in turn generate contribution provider holder classes (FIR limitation). The compiler should
// automatically handle this by generating a regular @Inject factory instead.

import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.screen.Screen
import com.slack.circuit.runtime.presenter.Presenter
import com.slack.circuit.codegen.annotations.CircuitInject
import androidx.compose.runtime.Composable

data object HomeScreen : Screen

data class HomeState(val message: String) : CircuitUiState

@CircuitInject(HomeScreen::class, AppScope::class)
@Composable
fun HomePresenter(): HomeState {
  return HomeState(message = "Hello")
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
