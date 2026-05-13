// PARALLEL_THREADS: 4
// Four levels of graph extensions (root -> L1 -> L2 -> L3) with multiple graphs per level.
// Root has 3 children, each child has 2 grandchildren, each grandchild has 2 great-grandchildren.
// All levels share scoped singletons from ancestors, exercising parallel validation across
// a wide and deep graph hierarchy. Previously deadlocked with a fixed thread pool; now uses
// ForkJoinPool work-stealing to handle nested parallelMap calls.

// --- Scopes ---
abstract class FeatureAScope private constructor()

abstract class FeatureBScope private constructor()

abstract class FeatureCScope private constructor()

abstract class SubA1Scope private constructor()

abstract class SubA2Scope private constructor()

abstract class SubB1Scope private constructor()

abstract class SubB2Scope private constructor()

abstract class SubC1Scope private constructor()

abstract class SubC2Scope private constructor()

abstract class LeafA1aScope private constructor()

abstract class LeafA1bScope private constructor()

abstract class LeafA2aScope private constructor()

abstract class LeafA2bScope private constructor()

abstract class LeafB1aScope private constructor()

abstract class LeafB1bScope private constructor()

abstract class LeafB2aScope private constructor()

abstract class LeafB2bScope private constructor()

abstract class LeafC1aScope private constructor()

abstract class LeafC1bScope private constructor()

abstract class LeafC2aScope private constructor()

abstract class LeafC2bScope private constructor()

// --- Root binding (AppScope singleton shared by all descendants) ---
@Inject @SingleIn(AppScope::class) class RootConfig(val name: String)

// --- Level 1 services ---
@Inject @SingleIn(FeatureAScope::class) class FeatureAService(val root: RootConfig)

@Inject @SingleIn(FeatureBScope::class) class FeatureBService(val root: RootConfig)

@Inject @SingleIn(FeatureCScope::class) class FeatureCService(val root: RootConfig)

// --- Level 2 services (depend on parent L1 singletons) ---
@Inject @SingleIn(SubA1Scope::class) class SubA1Service(val parent: FeatureAService)

@Inject @SingleIn(SubA2Scope::class) class SubA2Service(val parent: FeatureAService)

@Inject @SingleIn(SubB1Scope::class) class SubB1Service(val parent: FeatureBService)

@Inject @SingleIn(SubB2Scope::class) class SubB2Service(val parent: FeatureBService)

@Inject @SingleIn(SubC1Scope::class) class SubC1Service(val parent: FeatureCService)

@Inject @SingleIn(SubC2Scope::class) class SubC2Service(val parent: FeatureCService)

// --- Level 3 services (depend on L2 singletons, transitively reaching root) ---
@Inject @SingleIn(LeafA1aScope::class) class LeafA1aService(val parent: SubA1Service)

@Inject @SingleIn(LeafA1bScope::class) class LeafA1bService(val parent: SubA1Service)

@Inject @SingleIn(LeafA2aScope::class) class LeafA2aService(val parent: SubA2Service)

@Inject @SingleIn(LeafA2bScope::class) class LeafA2bService(val parent: SubA2Service)

@Inject @SingleIn(LeafB1aScope::class) class LeafB1aService(val parent: SubB1Service)

@Inject @SingleIn(LeafB1bScope::class) class LeafB1bService(val parent: SubB1Service)

@Inject @SingleIn(LeafB2aScope::class) class LeafB2aService(val parent: SubB2Service)

@Inject @SingleIn(LeafB2bScope::class) class LeafB2bService(val parent: SubB2Service)

@Inject @SingleIn(LeafC1aScope::class) class LeafC1aService(val parent: SubC1Service)

@Inject @SingleIn(LeafC1bScope::class) class LeafC1bService(val parent: SubC1Service)

@Inject @SingleIn(LeafC2aScope::class) class LeafC2aService(val parent: SubC2Service)

@Inject @SingleIn(LeafC2bScope::class) class LeafC2bService(val parent: SubC2Service)

// =============================================================================
// Level 3: Leaf graphs (12 total, 2 per L2 graph)
// =============================================================================
// -- Under SubA1 --
@GraphExtension(LeafA1aScope::class)
interface LeafA1aGraph {
  val service: LeafA1aService

  @GraphExtension.Factory
  @ContributesTo(SubA1Scope::class)
  fun interface Factory {
    fun createLeafA1a(): LeafA1aGraph
  }
}

@GraphExtension(LeafA1bScope::class)
interface LeafA1bGraph {
  val service: LeafA1bService

  @GraphExtension.Factory
  @ContributesTo(SubA1Scope::class)
  fun interface Factory {
    fun createLeafA1b(): LeafA1bGraph
  }
}

// -- Under SubA2 --
@GraphExtension(LeafA2aScope::class)
interface LeafA2aGraph {
  val service: LeafA2aService

  @GraphExtension.Factory
  @ContributesTo(SubA2Scope::class)
  fun interface Factory {
    fun createLeafA2a(): LeafA2aGraph
  }
}

@GraphExtension(LeafA2bScope::class)
interface LeafA2bGraph {
  val service: LeafA2bService

  @GraphExtension.Factory
  @ContributesTo(SubA2Scope::class)
  fun interface Factory {
    fun createLeafA2b(): LeafA2bGraph
  }
}

// -- Under SubB1 --
@GraphExtension(LeafB1aScope::class)
interface LeafB1aGraph {
  val service: LeafB1aService

  @GraphExtension.Factory
  @ContributesTo(SubB1Scope::class)
  fun interface Factory {
    fun createLeafB1a(): LeafB1aGraph
  }
}

@GraphExtension(LeafB1bScope::class)
interface LeafB1bGraph {
  val service: LeafB1bService

  @GraphExtension.Factory
  @ContributesTo(SubB1Scope::class)
  fun interface Factory {
    fun createLeafB1b(): LeafB1bGraph
  }
}

// -- Under SubB2 --
@GraphExtension(LeafB2aScope::class)
interface LeafB2aGraph {
  val service: LeafB2aService

  @GraphExtension.Factory
  @ContributesTo(SubB2Scope::class)
  fun interface Factory {
    fun createLeafB2a(): LeafB2aGraph
  }
}

@GraphExtension(LeafB2bScope::class)
interface LeafB2bGraph {
  val service: LeafB2bService

  @GraphExtension.Factory
  @ContributesTo(SubB2Scope::class)
  fun interface Factory {
    fun createLeafB2b(): LeafB2bGraph
  }
}

// -- Under SubC1 --
@GraphExtension(LeafC1aScope::class)
interface LeafC1aGraph {
  val service: LeafC1aService

  @GraphExtension.Factory
  @ContributesTo(SubC1Scope::class)
  fun interface Factory {
    fun createLeafC1a(): LeafC1aGraph
  }
}

@GraphExtension(LeafC1bScope::class)
interface LeafC1bGraph {
  val service: LeafC1bService

  @GraphExtension.Factory
  @ContributesTo(SubC1Scope::class)
  fun interface Factory {
    fun createLeafC1b(): LeafC1bGraph
  }
}

// -- Under SubC2 --
@GraphExtension(LeafC2aScope::class)
interface LeafC2aGraph {
  val service: LeafC2aService

  @GraphExtension.Factory
  @ContributesTo(SubC2Scope::class)
  fun interface Factory {
    fun createLeafC2a(): LeafC2aGraph
  }
}

@GraphExtension(LeafC2bScope::class)
interface LeafC2bGraph {
  val service: LeafC2bService

  @GraphExtension.Factory
  @ContributesTo(SubC2Scope::class)
  fun interface Factory {
    fun createLeafC2b(): LeafC2bGraph
  }
}

// =============================================================================
// Level 2: Sub-graphs (6 total, 2 per L1 graph) — each with explicit leaf factory accessors
// =============================================================================
// -- Under FeatureA --
@GraphExtension(SubA1Scope::class)
interface SubA1Graph {
  val service: SubA1Service
  val leafA1aFactory: LeafA1aGraph.Factory
  val leafA1bFactory: LeafA1bGraph.Factory

  @GraphExtension.Factory
  @ContributesTo(FeatureAScope::class)
  fun interface Factory {
    fun createSubA1(): SubA1Graph
  }
}

@GraphExtension(SubA2Scope::class)
interface SubA2Graph {
  val service: SubA2Service
  val leafA2aFactory: LeafA2aGraph.Factory
  val leafA2bFactory: LeafA2bGraph.Factory

  @GraphExtension.Factory
  @ContributesTo(FeatureAScope::class)
  fun interface Factory {
    fun createSubA2(): SubA2Graph
  }
}

// -- Under FeatureB --
@GraphExtension(SubB1Scope::class)
interface SubB1Graph {
  val service: SubB1Service
  val leafB1aFactory: LeafB1aGraph.Factory
  val leafB1bFactory: LeafB1bGraph.Factory

  @GraphExtension.Factory
  @ContributesTo(FeatureBScope::class)
  fun interface Factory {
    fun createSubB1(): SubB1Graph
  }
}

@GraphExtension(SubB2Scope::class)
interface SubB2Graph {
  val service: SubB2Service
  val leafB2aFactory: LeafB2aGraph.Factory
  val leafB2bFactory: LeafB2bGraph.Factory

  @GraphExtension.Factory
  @ContributesTo(FeatureBScope::class)
  fun interface Factory {
    fun createSubB2(): SubB2Graph
  }
}

// -- Under FeatureC --
@GraphExtension(SubC1Scope::class)
interface SubC1Graph {
  val service: SubC1Service
  val leafC1aFactory: LeafC1aGraph.Factory
  val leafC1bFactory: LeafC1bGraph.Factory

  @GraphExtension.Factory
  @ContributesTo(FeatureCScope::class)
  fun interface Factory {
    fun createSubC1(): SubC1Graph
  }
}

@GraphExtension(SubC2Scope::class)
interface SubC2Graph {
  val service: SubC2Service
  val leafC2aFactory: LeafC2aGraph.Factory
  val leafC2bFactory: LeafC2bGraph.Factory

  @GraphExtension.Factory
  @ContributesTo(FeatureCScope::class)
  fun interface Factory {
    fun createSubC2(): SubC2Graph
  }
}

// =============================================================================
// Level 1: Feature graphs (3 total) — each with explicit sub-graph factory accessors
// =============================================================================
@GraphExtension(FeatureAScope::class)
interface FeatureAGraph {
  val service: FeatureAService
  val subA1Factory: SubA1Graph.Factory
  val subA2Factory: SubA2Graph.Factory

  @GraphExtension.Factory
  @ContributesTo(AppScope::class)
  fun interface Factory {
    fun createFeatureA(): FeatureAGraph
  }
}

@GraphExtension(FeatureBScope::class)
interface FeatureBGraph {
  val service: FeatureBService
  val subB1Factory: SubB1Graph.Factory
  val subB2Factory: SubB2Graph.Factory

  @GraphExtension.Factory
  @ContributesTo(AppScope::class)
  fun interface Factory {
    fun createFeatureB(): FeatureBGraph
  }
}

@GraphExtension(FeatureCScope::class)
interface FeatureCGraph {
  val service: FeatureCService
  val subC1Factory: SubC1Graph.Factory
  val subC2Factory: SubC2Graph.Factory

  @GraphExtension.Factory
  @ContributesTo(AppScope::class)
  fun interface Factory {
    fun createFeatureC(): FeatureCGraph
  }
}

// =============================================================================
// Level 0: Root graph
// =============================================================================
@DependencyGraph(AppScope::class)
interface AppGraph {
  @Provides fun provideName(): String = "root"
}

// =============================================================================
// box() — walks the full 4-level hierarchy and validates ancestor sharing
// =============================================================================
fun box(): String {
  val app = createGraph<AppGraph>()

  // Level 1
  val featureA = app.createFeatureA()
  val featureB = app.createFeatureB()
  val featureC = app.createFeatureC()

  // All L1 services share the same root
  assertEquals("root", featureA.service.root.name)
  assertEquals("root", featureB.service.root.name)
  assertEquals("root", featureC.service.root.name)
  // Same root instance across features
  assertTrue(featureA.service.root === featureB.service.root)
  assertTrue(featureB.service.root === featureC.service.root)

  // Level 2
  val subA1 = featureA.subA1Factory.createSubA1()
  val subA2 = featureA.subA2Factory.createSubA2()
  val subB1 = featureB.subB1Factory.createSubB1()
  val subB2 = featureB.subB2Factory.createSubB2()
  val subC1 = featureC.subC1Factory.createSubC1()
  val subC2 = featureC.subC2Factory.createSubC2()

  // L2 services reference their L1 parent singleton
  assertTrue(subA1.service.parent === featureA.service)
  assertTrue(subA2.service.parent === featureA.service)
  assertTrue(subB1.service.parent === featureB.service)
  assertTrue(subB2.service.parent === featureB.service)
  assertTrue(subC1.service.parent === featureC.service)
  assertTrue(subC2.service.parent === featureC.service)

  // Level 3
  val leafA1a = subA1.leafA1aFactory.createLeafA1a()
  val leafA1b = subA1.leafA1bFactory.createLeafA1b()
  val leafA2a = subA2.leafA2aFactory.createLeafA2a()
  val leafA2b = subA2.leafA2bFactory.createLeafA2b()
  val leafB1a = subB1.leafB1aFactory.createLeafB1a()
  val leafB1b = subB1.leafB1bFactory.createLeafB1b()
  val leafB2a = subB2.leafB2aFactory.createLeafB2a()
  val leafB2b = subB2.leafB2bFactory.createLeafB2b()
  val leafC1a = subC1.leafC1aFactory.createLeafC1a()
  val leafC1b = subC1.leafC1bFactory.createLeafC1b()
  val leafC2a = subC2.leafC2aFactory.createLeafC2a()
  val leafC2b = subC2.leafC2bFactory.createLeafC2b()

  // L3 services reference their L2 parent singleton
  assertTrue(leafA1a.service.parent === subA1.service)
  assertTrue(leafA1b.service.parent === subA1.service)
  assertTrue(leafA2a.service.parent === subA2.service)
  assertTrue(leafA2b.service.parent === subA2.service)
  assertTrue(leafB1a.service.parent === subB1.service)
  assertTrue(leafB1b.service.parent === subB1.service)
  assertTrue(leafB2a.service.parent === subB2.service)
  assertTrue(leafB2b.service.parent === subB2.service)
  assertTrue(leafC1a.service.parent === subC1.service)
  assertTrue(leafC1b.service.parent === subC1.service)
  assertTrue(leafC2a.service.parent === subC2.service)
  assertTrue(leafC2b.service.parent === subC2.service)

  // Transitive: L3 -> L2 -> L1 -> root all share the same root instance
  assertTrue(leafA1a.service.parent.parent.root === featureA.service.root)
  assertTrue(leafB2b.service.parent.parent.root === featureB.service.root)
  assertTrue(leafC1a.service.parent.parent.root === featureC.service.root)
  assertTrue(leafA1a.service.parent.parent.root === leafC2b.service.parent.parent.root)

  return "OK"
}
