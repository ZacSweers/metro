// RUN_PIPELINE_TILL: BACKEND
// CHECK_REPORTS: graph-metadata/graph-parity-qualifiedclass-AppGraph.json
// CHECK_REPORTS: graph-metadata/graph-parity-qualifiedclass-AppGraph-Impl-ChildGraphImpl.json
// NORMALIZE_REPORT_SOURCE_LOCATIONS
// CHECK_REPORTS: keys-populated/parity/qualifiedclass/AppGraph/Impl
// CHECK_REPORTS: keys-populated/parity/qualifiedclass/AppGraph/Impl/ChildGraphImpl
// CHECK_REPORTS: keys-validated/parity/qualifiedclass/AppGraph/Impl
// CHECK_REPORTS: keys-validated/parity/qualifiedclass/AppGraph/Impl/ChildGraphImpl
// CHECK_REPORTS: keys-deferred/parity/qualifiedclass/AppGraph/Impl
// CHECK_REPORTS: keys-deferred/parity/qualifiedclass/AppGraph/Impl/ChildGraphImpl

package parity.qualifiedclass

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Qualifier
import dev.zacsweers.metro.SingleIn

@Qualifier annotation class Chosen

// A qualified class resolves requests with and without its qualifier. This class has no scope, so
// each key gets its own binding.
@Chosen @Inject class Engine

// A scoped class is one instance for both keys. The first key keeps the binding.
@Chosen @SingleIn(AppScope::class) @Inject class Radio

// Only the child requests this class. The parent holds its one instance.
@Chosen @SingleIn(AppScope::class) @Inject class Battery

// The parent requests this class with its qualifier. The child requests it with none.
@Chosen @SingleIn(AppScope::class) @Inject class Antenna

// Only a request with no qualifier reaches this class.
@Chosen @Inject class Wheel

@Inject class Car(val wheel: Wheel)

@GraphExtension
interface ChildGraph {
  @Chosen val chosenBattery: Battery

  val battery: Battery

  val radio: Radio

  val antenna: Antenna
}

@DependencyGraph(AppScope::class)
interface AppGraph {
  val engine: Engine

  @Chosen val chosenEngine: Engine

  val radio: Radio

  @Chosen val chosenRadio: Radio

  @Chosen val chosenAntenna: Antenna

  val car: Car

  val childGraph: ChildGraph
}

// METRO_JVM_ONLY
