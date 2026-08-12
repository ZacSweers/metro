// RUN_PIPELINE_TILL: BACKEND

interface HttpClient

@DependencyGraph
interface AppGraph {
  @OptionalBinding fun optionalHttpClient(): HttpClient = error("unused")
  val <!MISSING_BINDING!>requiredHttpClient<!>: HttpClient
}
