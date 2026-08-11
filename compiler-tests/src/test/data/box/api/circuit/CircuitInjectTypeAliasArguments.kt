// ENABLE_CIRCUIT
// GENERATE_CONTRIBUTION_HINTS_IN_FIR

import androidx.compose.runtime.Composable
import com.slack.circuit.codegen.annotations.CircuitInject
import com.slack.circuit.runtime.CircuitContext
import com.slack.circuit.runtime.CircuitUiState
import com.slack.circuit.runtime.Navigator
import com.slack.circuit.runtime.presenter.Presenter
import com.slack.circuit.runtime.screen.Screen

data object TypeAliasScreen : Screen

typealias TypeAliasScreenAlias = TypeAliasScreen
typealias TypeAliasScope = AppScope

data class TypeAliasState(val value: String) : CircuitUiState

@Inject
@CircuitInject(scope = TypeAliasScope::class, screen = TypeAliasScreenAlias::class)
class TypeAliasPresenter : Presenter<TypeAliasState> {
  @Composable override fun present(): TypeAliasState = TypeAliasState("presented")
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  val presenterFactories: Set<Presenter.Factory>
}

fun box(): String {
  val factories = createGraph<AppGraph>().presenterFactories
  if (factories.size != 1) return "FAIL: expected one factory but got ${factories.size}"

  val presenter =
    factories.single().create(TypeAliasScreen, Navigator.NoOp, CircuitContext.EMPTY)
      ?: return "FAIL: factory did not match TypeAliasScreen"
  return if (presenter is TypeAliasPresenter) "OK" else "FAIL: wrong presenter type"
}
