// RENDER_DIAGNOSTICS_FULL_TEXT
// WITH_DAGGER
// WITH_ANVIL
// WITH_KI_ANVIL
// https://github.com/ZacSweers/metro/issues/1572

import com.squareup.anvil.annotations.MergeComponent
import com.squareup.anvil.annotations.MergeSubcomponent
import dagger.Component
import dagger.Subcomponent

<!DEPENDENCY_GRAPH_ERROR!>@DependencyGraph<!>
<!DEPENDENCY_GRAPH_ERROR!>@MergeComponent(AppScope::class)<!>
<!DEPENDENCY_GRAPH_ERROR!>@software.amazon.lastmile.kotlin.inject.anvil.MergeComponent(AppScope::class)<!>
<!DEPENDENCY_GRAPH_ERROR!>@Component<!>
interface AppGraph {

}

<!DEPENDENCY_GRAPH_ERROR!>@GraphExtension<!>
<!DEPENDENCY_GRAPH_ERROR!>@MergeSubcomponent(AppScope::class)<!>
<!DEPENDENCY_GRAPH_ERROR!>@software.amazon.lastmile.kotlin.inject.anvil.MergeComponent(AppScope::class)<!>
<!DEPENDENCY_GRAPH_ERROR!>@Subcomponent<!>
interface LoggedInGraph

