// ENABLE_CIRCUIT
// GENERATE_CONTRIBUTION_HINTS_IN_FIR
// MIN_COMPILER_VERSION: 2.3.20

// Ensures multi-module contribution is wired up correctly

// MODULE: screens
import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.screen.Screen

data class FavoritesScreen(val userId: String) : Screen {
  data class State(val count: Int) : CircuitUiState
}

data class HomeScreen(val tab: String) : Screen {
  data class State(val title: String) : CircuitUiState
}

// MODULE: lib1(screens)
import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.screen.Screen
import com.slack.circuit.runtime.presenter.Presenter
import com.slack.circuit.codegen.annotations.CircuitInject
import androidx.compose.runtime.Composable

@Inject
@CircuitInject(FavoritesScreen::class, AppScope::class)
class FavoritesPresenter : Presenter<FavoritesScreen.State> {
  @Composable
  override fun present(): FavoritesScreen.State {
    return FavoritesScreen.State(count = 42)
  }
}

// MODULE: lib2(screens)
import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.screen.Screen
import com.slack.circuit.runtime.presenter.Presenter
import com.slack.circuit.codegen.annotations.CircuitInject
import androidx.compose.runtime.Composable

@Inject
@CircuitInject(HomeScreen::class, AppScope::class)
class HomePresenter : Presenter<HomeScreen.State> {
  @Composable
  override fun present(): HomeScreen.State {
    return HomeScreen.State(title = "Home")
  }
}

// MODULE: main(lib1, lib2, screens)
import com.slack.circuit.runtime.presenter.Presenter

@DependencyGraph(AppScope::class)
interface AppGraph {
  val presenterFactories: Set<Presenter.Factory>
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val factories = graph.presenterFactories
  if (factories.size != 2) return "FAIL: expected 2 factories but got ${factories.size}"
  return "OK"
}
