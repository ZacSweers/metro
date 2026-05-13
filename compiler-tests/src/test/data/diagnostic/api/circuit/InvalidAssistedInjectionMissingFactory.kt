// ENABLE_CIRCUIT
// RENDER_DIAGNOSTICS_FULL_TEXT

import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.Navigator
import com.slack.circuit.runtime.screen.Screen
import com.slack.circuit.runtime.presenter.Presenter
import com.slack.circuit.codegen.annotations.CircuitInject
import androidx.compose.runtime.Composable

data class FavoritesScreen(val userId: String) : Screen {
  data class State(val count: Int) : CircuitUiState
}

// @AssistedFactory + @CircuitInject must be nested inside the target class, not top-level
@CircuitInject(FavoritesScreen::class, AppScope::class)
@AssistedFactory
fun interface <!CIRCUIT_INJECT_ERROR!>FavoritesPresenterFactory<!> {
  fun create(@Assisted navigator: Navigator): FavoritesPresenter
}

@AssistedInject
class FavoritesPresenter(
  @Assisted private val navigator: Navigator
) : Presenter<FavoritesScreen.State> {
  @Composable
  override fun present(): FavoritesScreen.State {
    throw NotImplementedError()
  }
}
