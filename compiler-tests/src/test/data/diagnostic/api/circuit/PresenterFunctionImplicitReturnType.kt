// RENDER_DIAGNOSTICS_FULL_TEXT
// ENABLE_CIRCUIT

import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.screen.Screen
import com.slack.circuit.codegen.annotations.CircuitInject
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

object HomeScreen : Screen {
  data class State(val message: String) : CircuitUiState
}

// Presenter functions with implicit return types should be an error
@CircuitInject(HomeScreen::class, AppScope::class)
@Composable
fun <!CIRCUIT_INJECT_ERROR!>HomePresenter<!>() = computeState()

// Explicit return type on presenter is fine
@CircuitInject(HomeScreen::class, AppScope::class)
@Composable
fun HomePresenterExplicit(): HomeScreen.State = computeState()

// UI function with explicit Unit return is fine
@CircuitInject(HomeScreen::class, AppScope::class)
@Composable
fun HomeUi(state: HomeScreen.State, modifier: Modifier): Unit { }

// UI function with implicit return type but Modifier param is fine (assumed UI)
@CircuitInject(HomeScreen::class, AppScope::class)
@Composable
fun HomeUiImplicit(state: HomeScreen.State, modifier: Modifier) { }

// UI function with implicit return type calling another
@CircuitInject(HomeScreen::class, AppScope::class)
@Composable
fun HomeUiImplicitWithCall(state: HomeScreen.State, modifier: Modifier) = SharedElementTransitionScope { }

@Composable
public fun SharedElementTransitionScope(
  content: @Composable () -> Unit,
) {

}

fun computeState(): HomeScreen.State = HomeScreen.State("hello")
