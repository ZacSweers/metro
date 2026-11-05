// ENABLE_CIRCUIT
// FIX_EMPTY_SMAP_RANGES
// GENERATE_CONTRIBUTION_HINTS_IN_FIR

// https://github.com/ZacSweers/metro/pull/3046
// FIR contribution hints require a FIR factory even when IR class generation is enabled.
import androidx.compose.runtime.Composable
import com.slack.circuit.codegen.annotations.CircuitInject
import com.slack.circuit.runtime.CircuitContext
import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.Navigator
import com.slack.circuit.runtime.presenter.Presenter
import com.slack.circuit.runtime.screen.Screen

data object HomeScreen : Screen

data class HomeState(val message: String) : CircuitUiState

@CircuitInject(HomeScreen::class, AppScope::class)
@Composable
fun HomePresenter(message: String): HomeState = HomeState(message)

@DependencyGraph(AppScope::class)
interface AppGraph {
  val factories: Set<Presenter.Factory>

  @Provides fun message(): String = "provided"
}

fun box(): String {
  val factories = createGraph<AppGraph>().factories
  assertEquals(1, factories.size)
  assertNotNull(factories.single().create(HomeScreen, Navigator.NoOp, CircuitContext.EMPTY))
  return "OK"
}
