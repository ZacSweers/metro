// Generic providers compose encoders for every key the graph requests.
sealed interface Json {
  data class Str(val value: String) : Json

  data class Arr(val items: List<Json>) : Json

  data class Obj(val fields: Map<String, Json>) : Json

  data object Null : Json
}

fun Json.render(): String =
  when (this) {
    is Json.Str -> "\"$value\""
    is Json.Arr -> items.joinToString(",", "[", "]") { it.render() }
    is Json.Obj -> fields.entries.joinToString(",", "{", "}") { "\"${it.key}\":${it.value.render()}" }
    Json.Null -> "null"
  }

fun interface Encoder<T> {
  fun encode(value: T): Json
}

data class User(val name: String, val nickname: String?, val tags: Map<String, List<String>>)

data class Team(val name: String, val members: List<User>)

@BindingContainer
object JsonEncoders {
  @Provides fun string(): Encoder<String> = Encoder { Json.Str(it) }

  @Provides
  fun <T> list(element: Encoder<T>): Encoder<List<T>> = Encoder { values ->
    Json.Arr(values.map(element::encode))
  }

  @Provides
  fun <T : Any> nullable(inner: Encoder<T>): Encoder<T?> = Encoder { value ->
    if (value == null) {
      Json.Null
    } else {
      inner.encode(value)
    }
  }

  @Provides
  fun <V> stringMap(value: Encoder<V>): Encoder<Map<String, V>> = Encoder { map ->
    Json.Obj(map.mapValues { value.encode(it.value) })
  }
}

@ContributesBinding(AppScope::class)
@Inject
class UserEncoder(
  private val string: Encoder<String>,
  private val nickname: Encoder<String?>,
  private val tags: Encoder<Map<String, List<String>>>,
) : Encoder<User> {
  override fun encode(value: User): Json =
    Json.Obj(
      mapOf(
        "name" to string.encode(value.name),
        "nickname" to nickname.encode(value.nickname),
        "tags" to tags.encode(value.tags),
      )
    )
}

@ContributesBinding(AppScope::class)
@Inject
class TeamEncoder(private val string: Encoder<String>, private val members: Encoder<List<User>>) :
  Encoder<Team> {
  override fun encode(value: Team): Json =
    Json.Obj(mapOf("name" to string.encode(value.name), "members" to members.encode(value.members)))
}

@Inject
class TeamExporter(private val encoder: Encoder<List<Team>>) {
  fun export(teams: List<Team>): String = encoder.encode(teams).render()
}

@DependencyGraph(AppScope::class, bindingContainers = [JsonEncoders::class])
interface AppGraph {
  val exporter: TeamExporter
}

fun box(): String {
  val graph = createGraph<AppGraph>()
  val teams =
    listOf(
      Team(
        "core",
        listOf(
          User("Ada", "ada", mapOf("langs" to listOf("kotlin", "java"))),
          User("Grace", null, emptyMap()),
        ),
      )
    )
  assertEquals(
    "[{\"name\":\"core\",\"members\":[" +
      "{\"name\":\"Ada\",\"nickname\":\"ada\",\"tags\":{\"langs\":[\"kotlin\",\"java\"]}}," +
      "{\"name\":\"Grace\",\"nickname\":null,\"tags\":{}}]}]",
    graph.exporter.export(teams),
  )
  return "OK"
}
