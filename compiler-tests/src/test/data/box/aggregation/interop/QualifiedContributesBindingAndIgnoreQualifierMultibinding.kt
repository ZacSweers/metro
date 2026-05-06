// WITH_ANVIL

import com.squareup.anvil.annotations.ContributesBinding
import com.squareup.anvil.annotations.ContributesMultibinding

// Regression test for PR #2096. A qualified explicit binding and an explicit
// multibinding with ignoreQualifier = true on the same class must not share the
// same resolved qualified key.

@Qualifier
annotation class DatabaseQualifier

interface DatabaseFile {
  val path: String
}

@DatabaseQualifier
@ContributesBinding(AppScope::class)
@ContributesMultibinding(AppScope::class, ignoreQualifier = true)
@Inject
class DatabaseFileImpl : DatabaseFile {
  override val path: String = "database.db"
}

@DependencyGraph(scope = AppScope::class)
interface ExampleGraph {
  @DatabaseQualifier val qualifiedDatabaseFile: DatabaseFile
  val databaseFiles: Set<DatabaseFile>
}

fun box(): String {
  val graph = createGraph<ExampleGraph>()
  assertEquals("database.db", graph.qualifiedDatabaseFile.path)
  assertEquals(listOf("database.db"), graph.databaseFiles.map { it.path })
  return "OK"
}
