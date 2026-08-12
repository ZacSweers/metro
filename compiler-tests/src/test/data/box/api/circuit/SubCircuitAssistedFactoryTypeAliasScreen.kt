// ENABLE_CIRCUIT
// GENERATE_CONTRIBUTION_HINTS_IN_FIR
// MIN_COMPILER_VERSION: 2.3.20

import androidx.compose.runtime.Composable
import com.slack.circuit.subcircuit.SubCircuitInject
import com.slack.circuit.subcircuit.SubCircuitOuterEvent
import com.slack.circuit.subcircuit.SubCircuitUiState
import com.slack.circuit.subcircuit.SubPresenter
import com.slack.circuit.subcircuit.SubPresenterFactory
import com.slack.circuit.subcircuit.SubScreen

sealed interface TypeAliasOuterEvent : SubCircuitOuterEvent

data class TypeAliasSubState(val value: String) : SubCircuitUiState

data class TypeAliasSubScreen(val value: String) : SubScreen<TypeAliasOuterEvent>

typealias TypeAliasSubScreenAlias = TypeAliasSubScreen

@AssistedInject
class TypeAliasSubPresenter(
  @Assisted val screen: TypeAliasSubScreen,
) : SubPresenter<TypeAliasOuterEvent, TypeAliasSubState> {
  @SubCircuitInject(scope = AppScope::class, screen = TypeAliasSubScreenAlias::class)
  @AssistedFactory
  fun interface Factory {
    fun create(screen: TypeAliasSubScreen): TypeAliasSubPresenter
  }

  @Composable
  override fun present(outerEventSink: (TypeAliasOuterEvent) -> Unit): TypeAliasSubState {
    return TypeAliasSubState(screen.value)
  }
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  val presenterFactories: Set<SubPresenterFactory>
}

fun box(): String {
  val factories = createGraph<AppGraph>().presenterFactories
  if (factories.size != 1) return "FAIL: expected one factory but got ${factories.size}"

  val screen = TypeAliasSubScreen("aliased")
  val presenter = factories.single().create(screen) as? TypeAliasSubPresenter
    ?: return "FAIL: factory did not create TypeAliasSubPresenter"
  return if (presenter.screen == screen) "OK" else "FAIL: screen was not forwarded"
}
