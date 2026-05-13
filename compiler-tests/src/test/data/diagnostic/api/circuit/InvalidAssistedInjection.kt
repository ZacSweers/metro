// ENABLE_CIRCUIT
// RENDER_DIAGNOSTICS_FULL_TEXT

import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.screen.Screen
import com.slack.circuit.runtime.presenter.Presenter
import com.slack.circuit.codegen.annotations.CircuitInject
import androidx.compose.runtime.Composable

data class FavoritesScreen(val userId: String) : Screen {
  data class State(val count: Int) : CircuitUiState
}

// When using @CircuitInject with an @AssistedInject-annotated class,
// it must be on the @AssistedFactory, not the class itself
@CircuitInject(FavoritesScreen::class, AppScope::class)
@AssistedInject
class <!CIRCUIT_INJECT_ERROR!>FavoritesPresenter<!>(
  @Assisted private val screen: FavoritesScreen
) : Presenter<FavoritesScreen.State> {
  @Composable
  override fun present(): FavoritesScreen.State {
    throw NotImplementedError()
  }

  @AssistedFactory
  fun interface Factory {
    fun create(@Assisted screen: FavoritesScreen): FavoritesPresenter
  }
}
