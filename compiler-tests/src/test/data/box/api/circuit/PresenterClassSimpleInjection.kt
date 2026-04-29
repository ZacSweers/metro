// ENABLE_CIRCUIT

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
