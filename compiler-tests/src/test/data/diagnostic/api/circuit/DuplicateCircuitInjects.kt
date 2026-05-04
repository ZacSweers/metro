// ENABLE_CIRCUIT
// RENDER_DIAGNOSTICS_FULL_TEXT

// FILE: sources.kt

import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.screen.Screen

data class FavoritesScreen(val userId: String) : Screen {
  data class State(val count: Int) : CircuitUiState
}

// FILE: file1.kt
import com.slack.circuit.codegen.annotations.CircuitInject
import androidx.compose.runtime.Composable

@CircuitInject(FavoritesScreen::class, AppScope::class)
@Composable
fun <!CIRCUIT_INJECT_ERROR!>Favorites<!>(): FavoritesScreen.State {
  TODO()
}

// FILE: file2.kt
import com.slack.circuit.codegen.annotations.CircuitInject
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@CircuitInject(FavoritesScreen::class, AppScope::class)
@Composable
fun <!CIRCUIT_INJECT_ERROR!>Favorites<!>(state: FavoritesScreen.State, modifier: Modifier = Modifier) {

}
