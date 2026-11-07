// https://github.com/ZacSweers/metro/issues/3082
interface SpacesDatabase

class Database : SpacesDatabase

interface Repository

@Inject
class RoomRepository(val database: SpacesDatabase) : Repository

@ContributesTo(AppScope::class)
interface DatabaseBindings {
  @Binds fun Database.spaces(): SpacesDatabase
  @Binds fun RoomRepository.spaces(): Repository
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  fun spaces(): Repository

  @DependencyGraph.Factory
  fun interface Factory {
    fun create(@Provides database: Database): AppGraph
  }
}

fun box(): String {
  val database = Database()
  val graph = createGraphFactory<AppGraph.Factory>().create(database)
  val repository = assertIs<RoomRepository>(graph.spaces())
  assertSame(database, repository.database)
  return "OK"
}
