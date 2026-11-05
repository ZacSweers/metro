// ENABLE_CIRCUIT
// FIX_EMPTY_SMAP_RANGES
// GENERATE_CONTRIBUTION_HINTS: false
// GENERATE_CLASSES_IN_IR: true
// MIN_COMPILER_VERSION: 2.4.20-dev-6138

// https://github.com/ZacSweers/metro/pull/3046
// Disabling contribution hints makes Circuit generate its factory entirely in IR.
import androidx.compose.runtime.Composable
import com.slack.circuit.codegen.annotations.CircuitInject
import com.slack.circuit.runtime.CircuitContext
import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.Navigator
import com.slack.circuit.runtime.presenter.Presenter
import com.slack.circuit.runtime.screen.Screen

data object HomeScreen : Screen

data object HomeState : CircuitUiState

@CircuitInject(HomeScreen::class, AppScope::class)
@Composable
fun HomePresenter(): HomeState = HomeState

@DependencyGraph(AppScope::class)
interface AppGraph {
  val factories: Set<Presenter.Factory>
}

fun box(): String {
  val factories = createGraph<AppGraph>().factories
  assertEquals(1, factories.size)
  assertNotNull(factories.single().create(HomeScreen, Navigator.NoOp, CircuitContext.EMPTY))
  return "OK"
}
