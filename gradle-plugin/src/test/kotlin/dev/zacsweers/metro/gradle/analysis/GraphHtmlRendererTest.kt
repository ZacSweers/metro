// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.gradle.analysis

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.containsExactly
import assertk.assertions.containsExactlyInAnyOrder
import assertk.assertions.containsNone
import assertk.assertions.doesNotContain
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEmpty
import assertk.assertions.isNull
import assertk.assertions.isTrue
import kotlin.test.Test
import kotlinx.serialization.json.Json

class GraphHtmlRendererTest {
  private val renderer = GraphHtmlRenderer()

  @Test
  fun `standalone child keeps its metadata and initial focus`() {
    val fixture = NestedChildScenario()
    val data = fixture.data

    data.assertFields("initialRegionId" to fixture.details.regionId)
    assertThat(data.regions().map { it.nullableString("parentId") })
      .containsExactly(
        null,
        fixture.parent.regionId,
        fixture.session.regionId,
      )
    for (field in listOf("graphName", "bindingExplanations", "scopes", "stats", "config")) {
      assertThat(data[field]).isEqualTo(fixture.standalone[field])
    }
  }

  @Test
  fun `standalone child longest path uses binding owners`() {
    val fixture = NestedChildScenario()
    val expected = fixture.standalone.strings("longestPath").map(fixture::expectedId)
    val longestPath = fixture.data.strings("longestPath")

    assertThat(longestPath).isEqualTo(expected)
    assertThat(longestPath).isNotEmpty()
    assertThat(fixture.data.nodeIds()).containsAllOccurrences(longestPath)
  }

  @Test
  fun `standalone child root paths use visible binding owners`() {
    val fixture = NestedChildScenario()
    val expected =
      fixture.standalone.pathsToRoot().entries.associate { (key, path) ->
        fixture.expectedId(key) to path.map(fixture::expectedId)
      }
    val paths = fixture.data.pathsToRoot()
    val nodeIds = fixture.data.nodeIds()

    assertThat(paths).isEqualTo(expected)
    assertThat(nodeIds).containsAllOccurrences(paths.keys)
    for (path in paths.values) {
      assertThat(nodeIds).containsAllOccurrences(path)
    }
  }

  @Test
  fun `standalone child keeps inherited bindings in owner regions`() {
    val fixture = NestedChildScenario()
    val data = fixture.data
    val application = data.nodes().single { it.hasField("fullKey", "Application") }
    val state = data.nodes().single { it.hasField("fullKey", "SessionState") }

    application.assertFields(
      "id" to fixture.expectedId("Application"),
      "regionId" to fixture.parent.regionId,
      "isGraphInput" to true,
    )
    state.assertFields(
      "id" to fixture.expectedId("SessionState"),
      "regionId" to fixture.session.regionId,
      "scoped" to true,
    )
    assertThat(data.region(fixture.details.regionId).strings("nodeIds"))
      .containsNone(
        application.string("id"),
        state.string("id"),
      )
  }

  @Test
  fun `standalone child root connects to inherited bindings`() {
    val fixture = NestedChildScenario()
    val data = fixture.data
    val details = fixture.details
    val root = data.roots().single()

    root.assertFields(
      "id" to details.inReport(details).accessorId("Details"),
      "rootOwner" to details.regionId,
    )
    assertThat(data.node("Details").string("regionId")).isEqualTo(root.string("rootOwner"))
    assertThat(data.targetsFrom("Details"))
      .containsExactlyInAnyOrder(
        fixture.expectedId("Application"),
        fixture.expectedId("SessionState"),
      )
  }

  @Test
  fun `standalone child includes ancestors up to the highest available parent`() {
    val parent = graph(parent = "MissingGrandparent") { binding("ParentValue") }
    val child = graph("ChildGraph", parent = parent.graph) { binding("ChildValue") }
    val data = GraphHtmlRenderer(graphs = listOf(parent, child)).buildData(child)
    data.assertFields("initialRegionId" to child.regionId)
    assertThat(data.regions().map { it.nullableString("parentId") })
      .containsExactly(null, parent.regionId)
  }

  @Test
  fun `standalone child keeps local references when its parent is missing or ambiguous`() {
    val parent = graph { binding("Cache", scoped = true) }
    val child =
      graph("ChildGraph", parent = parent.graph) {
        inherits("Cache", from = parent)
      }
    for (reports in
      listOf(listOf(child), listOf(parent, parent.copy(scopes = listOf("OtherScope")), child))) {
      val data = GraphHtmlRenderer(graphs = reports).buildData(child)
      assertThat(data["initialRegionId"]).isNull()
      assertThat(data.regions()).hasSize(1)
      assertThat(data.node("Cache").string("kind")).isEqualTo("GraphDependency")
      assertThat(data.links().any { it.isTrue("parentDependency") }).isFalse()
    }
  }

  @Test
  fun `standalone child does not create cyclic ancestor regions`() {
    val parent = graph(parent = "ChildGraph") { binding("ParentValue") }
    val child = graph("ChildGraph", parent = parent.graph) { binding("ChildValue") }
    for (requested in listOf(child, child.copy(parentGraph = child.graph))) {
      val data = GraphHtmlRenderer(graphs = listOf(parent, requested)).buildData(requested)
      assertThat(data["initialRegionId"]).isNull()
      val region = data.regions().single()
      region.assertFields("id" to requested.regionId, "parentId" to null, "kind" to "graph")
    }
  }

  @Test
  fun `extension region connects through its factory`() {
    val fixture = ExtensionScenario()
    val region = fixture.data.regions().single { it.hasField("kind", "extension") }
    val bridge = fixture.data.links().single { it.isTrue("containment") }

    region.assertFields("parentId" to fixture.parent.regionId, "viaNodeId" to "ChildGraph.Factory")
    assertThat(region.strings("inputIds"))
      .containsExactlyInAnyOrder(fixture.childRefs.bindingId("Input"))
    bridge.assertFields(
      "source" to "ChildGraph.Factory",
      "target" to fixture.child.regionId,
      "rootMembership" to true,
    )
  }

  @Test
  fun `extension bindings have separate identities from parent bindings`() {
    val fixture = ExtensionScenario()
    val shared =
      fixture.data.nodes().filter { it.hasField("fullKey", "Shared") && !it.isTrue("isRootMember") }

    assertThat(shared.ids())
      .containsExactlyInAnyOrder("Shared", fixture.childRefs.bindingId("Shared"))
    assertThat(shared.strings("regionId"))
      .containsExactlyInAnyOrder(fixture.parent.regionId, fixture.child.regionId)
  }

  @Test
  fun `extension dependencies resolve to parent bindings`() {
    val fixture = ExtensionScenario()
    val data = fixture.data
    val region = data.region(fixture.child.regionId)
    val inherited = data.links().single { it.isTrue("parentDependency") }

    assertThat(data.nodes().filter { it.hasField("fullKey", "Cache") }).hasSize(1)
    assertThat(region.strings("nodeIds")).doesNotContain(fixture.childRefs.bindingId("Cache"))
    inherited.assertFields(
      "source" to fixture.childRefs.bindingId("Consumer"),
      "target" to "Cache",
      "edgeType" to "normal",
    )
    assertThat(inherited.objects("inheritedVia").single().string("key")).isEqualTo("Cache")
  }

  @Test
  fun `inherited binding uses its scoped extension owner`() {
    val fixture = InheritedExtensionScenario()
    val data = fixture.data
    val owner = data.node(fixture.ownerBindingId)
    val provenance = owner.objects("inheritedBindings").single()

    assertThat(data.nodeIds()).doesNotContain(fixture.inheritedBindingId)
    owner.assertFields("regionId" to fixture.session.regionId, "scoped" to true)
    provenance.assertFields("graphName" to fixture.details.graph)
    assertThat(provenance.objectField("binding").objectField("graphDependency").string("ownerKey"))
      .isEqualTo(fixture.session.graph)
  }

  @Test
  fun `inherited dependency edges keep their kinds and use the owner binding`() {
    val fixture = InheritedExtensionScenario()
    val data = fixture.data
    val root = data.roots().single()
    val inherited = data.links().filter { it.isTrue("parentDependency") }

    assertThat(inherited.sources())
      .containsExactlyInAnyOrder(
        fixture.detailsRefs.bindingId("Details"),
        fixture.detailsRefs.bindingId("StateAlias"),
        root.string("id"),
      )
    assertThat(inherited.targets().toSet()).containsExactlyInAnyOrder(fixture.ownerBindingId)
    assertThat(inherited.strings("edgeType"))
      .containsExactlyInAnyOrder("normal", "alias", "accessor")
    assertThat(data.links().sources()).doesNotContain(fixture.inheritedBindingId)
    assertThat(data.links().targets()).doesNotContain(fixture.inheritedBindingId)
  }

  @Test
  fun `captured extension instance stays in its owner region`() {
    val fixture = InheritedExtensionScenario()

    assertThat(fixture.data.nodeIds())
      .doesNotContain(fixture.detailsRefs.bindingId(fixture.session.graph))
    assertThat(fixture.data.nodeIds())
      .contains(fixture.sessionRefs.bindingId(fixture.session.graph))
  }

  @Test
  fun `captured graph owners collapse through recorded aliases`() {
    val owner = "AppGraph"
    val capturedAlias = "$owner.Impl"
    val input = "@Other $owner"
    val parent = graph {
      instance()
      binding("Cache", scoped = true)
    }
    val child =
      graph("$owner.Impl.ChildImpl", parent = owner, type = "ChildGraph") {
        instance(owner)
        alias(capturedAlias, owner)
        input(input)
        alias("Module", owner)
        inherits("Cache", from = parent, ownerKey = capturedAlias)
      }
    val data = GraphHtmlRenderer(graphs = listOf(parent, child)).buildData(parent)
    val childRefs = child.inReport(parent)
    assertThat(data.nodeIds())
      .containsNone(childRefs.bindingId(owner), childRefs.bindingId(capturedAlias))
    assertThat(data.nodeIds()).contains(childRefs.bindingId(input))
    val moduleLink = data.linksFrom(childRefs.bindingId("Module")).single()
    moduleLink.assertFields("target" to owner)
    val instance = data.node(owner)
    assertThat(
        instance.objects("inheritedBindings").map { it.objectField("binding").string("key") }
      )
      .containsExactlyInAnyOrder(
        owner,
        capturedAlias,
      )
  }

  @Test
  fun `inherited references share the original graph input`() {
    val inputKey = "Application"
    val parent = graph { input(inputKey) }
    val childName = "AppGraph.Impl.ChildImpl"
    val child =
      graph(childName, parent = parent.graph, type = "ChildGraph") {
        inherits(inputKey, from = parent)
      }
    val grandchildName = "$childName.GrandchildImpl"
    val grandchild =
      graph(grandchildName, parent = childName, type = "GrandchildGraph") {
        inherits(inputKey, from = child)
        binding("Consumer", inputKey)
        accessor(inputKey, name = "application", isProperty = true)
      }
    val data = GraphHtmlRenderer(graphs = listOf(parent, child, grandchild)).buildData(parent)
    val input =
      data.nodes().single { it.hasField("fullKey", inputKey) && !it.isTrue("isRootMember") }
    input.assertFields("id" to inputKey, "isGraphInput" to true)
    assertThat(input.objects("inheritedBindings")).hasSize(2)
    val inherited = data.links().filter { it.isTrue("parentDependency") }
    assertThat(inherited).hasSize(2)
    assertThat(inherited.targets().toSet()).containsExactlyInAnyOrder(inputKey)
    for (link in inherited) {
      assertThat(link.objects("inheritedVia").strings("graphName"))
        .containsExactly(
          grandchildName,
          childName,
        )
    }
  }

  @Test
  fun `extension accessors provide named roots leading to child regions`() {
    val parent = graph {
      extension("ChildGraph")
      extensions =
        ExtensionsMetadata(
          accessors =
            listOf(ExtensionAccessorMetadata("ChildGraph", name = "child", isProperty = true))
        )
    }
    val childName = "AppGraph.Impl.ChildImpl"
    val child = graph(childName, parent = parent.graph, type = "ChildGraph") { binding("Value") }
    val data = GraphHtmlRenderer(graphs = listOf(parent, child)).buildData(parent)
    val entry = data.roots().single()
    entry.assertFields("name" to "child")
    assertThat(data.targetsFrom(parent.regionId)).contains(entry.string("id"))
    assertThat(data.targetsFrom(entry.string("id"))).contains("ChildGraph")
    assertThat(data.targetsFrom("ChildGraph")).contains(child.regionId)
  }

  @Test
  fun `extension factories already recorded as roots are not duplicated`() {
    val key = "ChildGraph.Factory"
    val graph = graph {
      extension(key, factory = true)
      accessor(key, name = "childFactory", isProperty = true)
      extensions =
        ExtensionsMetadata(
          factoryAccessors =
            listOf(ExtensionFactoryAccessorMetadata(key, name = "childFactory", isProperty = true))
        )
    }
    val data = renderer.buildData(graph)
    assertThat(data.roots()).hasSize(1)
  }

  @Test
  fun `an included graph without a report uses its supplied instance`() {
    val owner = "ExternalDependencies"
    val graph = graph {
      includes(owner)
      graphDependency("kotlin.String", ownerKey = owner)
    }
    val data = renderer.buildData(graph)
    assertThat(data.regions()).hasSize(1)
    val input = data.node(owner)
    input.assertFields("isIncludedGraph" to true, "isGraphInput" to true)
    val dependency = data.links().single()
    dependency.assertFields(
      "source" to "kotlin.String",
      "target" to owner,
      "edgeType" to "includes",
    )
  }

  @Test
  fun `included graph reports create peer regions`() {
    val scenario = IncludedGraphScenario()
    val data = scenario.data
    val region = data.regions().single { it.hasField("kind", "dependency") }
    region.assertFields("id" to scenario.producer.regionId, "parentId" to null)
    val accessor = data.roots().single { it.hasField("regionId", scenario.producer.regionId) }
    accessor.assertFields("name" to "endpoint")
    val input = data.node(scenario.sessionRefs.bindingId(scenario.producer.graph))
    input.assertFields("isGraphInput" to true)
    assertThat(input.string("dependencyRegionId")).isEqualTo(region.string("id"))
  }

  @Test
  fun `included graph inputs connect to producer accessors`() {
    val scenario = IncludedGraphScenario()
    val data = scenario.data
    val region = data.regions().single { it.hasField("kind", "dependency") }
    val accessor = data.roots().single { it.hasField("regionId", scenario.producer.regionId) }
    val includes = data.links().single { it.hasField("edgeType", "includes") }
    assertThat(includes.string("source"))
      .isEqualTo(scenario.sessionRefs.bindingId(scenario.producer.graph))
    assertThat(includes.string("target")).isEqualTo(accessor.string("id"))
    includes.assertFields("includedInstance" to true)
    assertThat(includes.objects("includedVia").single().string("key")).isEqualTo("Endpoint")
    assertThat(data.links().targets()).doesNotContain(region.string("ownerId"))
  }

  @Test
  fun `included graph consumers use producer accessors`() {
    val scenario = IncludedGraphScenario()
    val data = scenario.data
    val region = data.regions().single { it.hasField("kind", "dependency") }
    val accessor = data.roots().single { it.hasField("regionId", scenario.producer.regionId) }
    val consumerLink = data.linksFrom(scenario.sessionRefs.bindingId("Consumer")).single()
    assertThat(consumerLink.string("target")).isEqualTo(accessor.string("id"))
    consumerLink.assertFields("edgeType" to "normal")
    assertThat(consumerLink.objects("includedVia")).hasSize(1)
    assertThat(data.nodeIds()).doesNotContain(scenario.sessionRefs.bindingId("Endpoint"))
    val endpoint =
      data.nodes().single { it.hasField("fullKey", "Endpoint") && !it.isTrue("isRootMember") }
    assertThat(endpoint.string("regionId")).isEqualTo(region.string("id"))
    assertThat(data.linksFrom(accessor.string("id")).single().string("target"))
      .isEqualTo(endpoint.string("id"))
    val provenance = accessor.objects("includedBindings").single()
    assertThat(provenance.objectField("binding").objectField("graphDependency").string("ownerKey"))
      .isEqualTo(scenario.producer.graph)
  }

  @Test
  fun `included graphs require an unambiguous owner and a matching accessor`() {
    val (consumer, producer) = includedGraphFixture()
    val accessor = producer.roots!!.accessors.single()
    val unmatchedProducers =
      listOf(
        producer.copy(
          roots = RootsMetadata(accessors = listOf(accessor.copy(name = "otherEndpoint")))
        ),
        producer.copy(
          roots = RootsMetadata(accessors = listOf(accessor.copy(key = "OtherEndpoint")))
        ),
        producer.copy(roots = RootsMetadata(accessors = listOf(accessor.copy(name = null)))),
        producer.copy(
          roots = RootsMetadata(accessors = listOf(accessor, accessor.copy(isProperty = false)))
        ),
        producer.copy(graph = "OtherDependencies"),
      )
    val reportSets = unmatchedProducers.map { listOf(consumer, it) }.toMutableList()
    val otherProducer =
      producer.copy(
        bindings =
          listOf(bindingMetadata("Endpoint", kind = "Provided", declaration = "differentProvider"))
      )
    reportSets.add(listOf(consumer, producer, otherProducer))
    for (reports in reportSets) {
      val data = GraphHtmlRenderer(graphs = reports).buildData(consumer)
      assertThat(data.regions()).hasSize(1)
      assertThat(data.nodeIds()).contains("Endpoint")
      assertThat(data.links().single { it.hasField("edgeType", "includes") }.string("target"))
        .isEqualTo(producer.graph)
    }
  }

  @Test
  fun `separately supplied instances of one graph keep distinct bindings`() {
    val (consumer, producer) = includedGraphFixture()
    val main = graph()
    val first =
      consumer.copy(
        graph = "AppGraph.Impl.FirstImpl",
        graphType = "FirstGraph",
        parentGraph = main.graph,
      )
    val second =
      consumer.copy(
        graph = "AppGraph.Impl.SecondImpl",
        graphType = "SecondGraph",
        parentGraph = main.graph,
      )
    val data = GraphHtmlRenderer(graphs = listOf(main, first, second, producer)).buildData(main)
    val firstRefs = first.inReport(main)
    val secondRefs = second.inReport(main)
    assertThat(data.regions()).hasSize(3)
    val inputs = data.nodes().filter { it.isTrue("isIncludedGraph") }
    assertThat(inputs.ids())
      .containsExactlyInAnyOrder(
        firstRefs.bindingId(producer.graph),
        secondRefs.bindingId(producer.graph),
      )
    val getters = data.nodes().filter { it.hasField("fullKey", "Endpoint") }
    assertThat(getters.ids())
      .containsExactlyInAnyOrder(
        firstRefs.bindingId("Endpoint"),
        secondRefs.bindingId("Endpoint"),
      )
    assertThat(data.links().filter { it.hasField("edgeType", "includes") }).hasSize(2)
  }

  @Test
  fun `missing extension reports do not create regions or bindings`() {
    val graph = graph { extension("ChildGraph") }
    val data = renderer.buildData(graph)
    assertThat(data.regions()).hasSize(1)
    assertThat(data.nodeIds()).containsExactlyInAnyOrder(graph.regionId, "ChildGraph")
  }

  @Test
  fun `parent connections require a recorded matching owner`() {
    val parent = graph {
      binding("Cache")
      extension("ChildGraph")
    }
    val owners =
      listOf(
        GraphDependencyMetadata("OtherGraph", "OtherGraph", fromParent = true),
        GraphDependencyMetadata(parent.graph, fromParent = true),
      )
    for (owner in owners) {
      val child =
        graph("AppGraph.Impl.ChildImpl", parent = parent.graph, type = "ChildGraph") {
          graphDependency("Cache", owner.ownerKey, owner.ownerGraph, fromParent = owner.fromParent)
        }
      val data = GraphHtmlRenderer(graphs = listOf(parent, child)).buildData(parent)
      val childRefs = child.inReport(parent)
      assertThat(data.links().any { it.isTrue("parentDependency") }).isFalse()
      assertThat(data.nodeIds()).contains(childRefs.bindingId("Cache"))
    }
  }

  @Test
  fun `default dependencies use resolved bindings or default nodes`() {
    val graph = graph {
      binding(
        bindingMetadata(
          "Consumer",
          dependencies =
            listOf(
              DependencyMetadata("Service = ...", true),
              DependencyMetadata("Missing = ...", true),
            ),
        )
      )
      binding("Service")
    }
    val data = renderer.buildData(graph)
    val default = data.nodes().single { it.isTrue("isDefaultValue") }
    assertThat(default.objects("rawDependencies")).isEmpty()
    assertThat(data.links().targets()).containsExactlyInAnyOrder("Service", default.string("id"))
    val resolved = data.links().single { it.hasField("target", "Service") }
    resolved.assertFields("hasDefault" to true)
    for (edge in data.links()) {
      edge.assertFields("source" to "Consumer")
    }
  }

  @Test
  fun `graph inputs have only their dependency connections`() {
    val graph = graph {
      binding("Input", kind = "BoundInstance")
      binding("Consumer", "Input")
    }
    val data = renderer.buildData(graph)
    val input = data.node("Input")
    val root = data.nodes().single { it.isTrue("isGraph") }
    input.assertFields("isGraphInput" to true)
    assertThat(input.objects("rawDependencies")).isEmpty()
    root.assertFields("isGraphInput" to false)
    val dependency = data.links().single()
    dependency.assertFields("source" to "Consumer", "target" to input.string("id"))
    assertThat(dependency["presentationOnly"]).isNull()
  }

  @Test
  fun `graph container and injectable graph have separate identities`() {
    val graph = graph {
      binding("AppGraph", kind = "BoundInstance")
    }
    val data = renderer.buildData(graph)
    val root = data.nodes().single { it.isTrue("isGraph") }
    val graphInstance = data.node("AppGraph")
    root.assertFields("id" to graph.regionId, "kind" to "Graph", "isGraphInput" to false)
    graphInstance.assertFields(
      "isGraph" to false,
      "isGraphInstance" to true,
      "isGraphInput" to false,
      "fullKey" to "AppGraph",
    )
    assertThat(data.links()).isEmpty()
  }

  @Test
  fun `multibinding contribution keeps its alias path`() {
    val collectionKey = "kotlin.collections.Set<Ui.Factory>"
    val aliasKey =
      "@dev.zacsweers.metro.internal.MultibindingElement(\"Ui.Factory\", \"525185073\") Ui.Factory"
    val targetKey = "SummarizerFactory"
    val collection =
      bindingMetadata(
          collectionKey,
          kind = "Multibinding",
          dependencies = listOf(DependencyMetadata(aliasKey, false)),
        )
        .copy(multibinding = MultibindingMetadata("SET", false, listOf(aliasKey)))
    val data =
      renderer.buildData(
        graph {
          binding(collection)
          alias(aliasKey, targetKey)
          binding(targetKey)
        }
      )
    val aliasNode = data.node(aliasKey)
    aliasNode.assertFields("synthetic" to true, "fullKey" to aliasKey, "aliasTarget" to targetKey)
    val collectionEdge = data.linksFrom(collectionKey).single()
    collectionEdge.assertFields("target" to aliasNode.string("id"), "edgeType" to "multibinding")
    val aliasEdge = data.linksFrom(aliasNode.string("id")).single()
    aliasEdge.assertFields("target" to targetKey, "edgeType" to "alias")
  }

  @Test
  fun `injector roots connect to dependencies in a graph with no accessors`() {
    val graph = graph {
      binding("Target", "Service", kind = "MembersInjected", declaration = "AppGraph.inject")
      binding("Service")
      injector("Target", name = "inject")
    }
    val data = renderer.buildData(graph)
    val injector = data.nodes().single { it.hasField("rootKind", "injector") }
    val membership = data.links().single { it.isTrue("rootMembership") }
    membership.assertFields("source" to graph.regionId, "target" to injector.string("id"))
    injector.assertFields(
      "name" to "inject()",
      "declaration" to "inject",
      "typeLabel" to "Target",
      "fullKey" to "Target",
    )
    val edge = data.links().single { it.hasField("edgeType", "injects") }
    edge.assertFields(
      "source" to injector.string("id"),
      "target" to "Service",
      "injectorTarget" to "Target",
    )
  }

  @Test
  fun `accessor roots are distinct from shared bindings and the graph instance`() {
    val graph = graph {
      binding("AppGraph", kind = "BoundInstance")
      binding("Module", "AppGraph", kind = "Alias", declaration = "AppGraph.provideModule")
      accessor("Module")
      accessor("Module")
    }
    val data = renderer.buildData(graph)
    val entries = data.roots()
    assertThat(entries).hasSize(2)
    assertThat(entries.ids().toSet()).hasSize(2)
    for (entry in entries) {
      entry.assertFields(
        "name" to "Module",
        "rootOwner" to graph.regionId,
        "rootKind" to "accessor",
        "declaration" to "",
        "synthetic" to false,
        "requestedKey" to "Module",
      )
      assertThat(data.targetsFrom(entry.string("id"))).containsExactlyInAnyOrder("Module")
    }
    assertThat(data.targetsFrom("Module")).containsExactlyInAnyOrder("AppGraph")
    assertThat(data.links().filter { it.isTrue("rootMembership") }).hasSize(2)
    assertThat(data.links().targets()).doesNotContain(graph.regionId)
  }

  @Test
  fun `accessors use declaration names and preserve requested types`() {
    val key = "@test.Qualifier kotlin.collections.Set<test.Service>"
    val graph = graph {
      binding(key, declaration = "provideServices")
      accessor(key, name = "services", isProperty = true)
      accessor(key, name = "createServices")
    }
    val data = renderer.buildData(graph)
    val entries = data.roots()
    assertThat(entries.strings("name")).containsExactlyInAnyOrder("services", "createServices()")
    assertThat(entries.strings("declaration"))
      .containsExactlyInAnyOrder("services", "createServices")
    assertThat(entries.map { it.boolean("isProperty") }).containsExactlyInAnyOrder(true, false)
    for (entry in entries) {
      entry.assertFields("fullKey" to key, "requestedKey" to key, "typeLabel" to "Set<Service>")
    }
  }

  @Test
  fun `inherited multibinding roots retain their declaration`() {
    val scenario = InheritedMultibindingScenario()
    val data = scenario.data
    val inherited = data.nodes().single { it.isTrue("isInheritedRoot") }
    inherited.assertFields(
      "name" to "ServiceAccessors.services",
      "rootOwner" to scenario.child.regionId,
      "regionId" to scenario.child.regionId,
      "declaringGraph" to scenario.parent.graph,
      "declaringType" to "test.ServiceAccessors",
      "origin" to "ServiceAccessors.kt:8:3",
      "requestedKey" to scenario.key,
    )
    val declared = data.roots().single { it.string("regionId") != scenario.child.regionId }
    declared.assertFields("name" to "services")
    assertThat(declared["isInheritedRoot"]).isNull()
  }

  @Test
  fun `inherited multibinding roots resolve child contributions`() {
    val scenario = InheritedMultibindingScenario()
    val data = scenario.data
    val inherited = data.nodes().single { it.isTrue("isInheritedRoot") }
    inherited.assertFields("resolutionUnavailable" to false)
    val resolved = data.targetsFrom(inherited.string("id")).single()
    val collection = data.node(resolved)
    collection.assertFields("regionId" to scenario.child.regionId)
    val contribution = data.nodes().single { it.hasField("fullKey", "ChildService") }
    assertThat(data.targetsFrom(resolved)).contains(contribution.string("id"))
  }

  @Test
  fun `inherited function roots keep function notation in standalone reports`() {
    val key = "kotlin.collections.Set<Service>"
    val graph =
      graph("ChildGraph") {
        binding(key, kind = "Multibinding")
        accessor(
          AccessorMetadata(
            key,
            name = "services",
            isInherited = true,
            declaringGraph = "AppGraph",
            declaringType = "test.AppGraph",
          )
        )
      }
    val data = renderer.buildData(graph)
    val root = data.roots().single()
    root.assertFields(
      "name" to "AppGraph.services()",
      "isProperty" to false,
      "rootOwner" to graph.regionId,
    )
    assertThat(data.targetsFrom(root.string("id"))).containsExactlyInAnyOrder(key)
  }

  @Test
  fun `deferred accessor roots preserve wrappers`() {
    val keys = listOf("dev.zacsweers.metro.Provider<Service>", "kotlin.Lazy<Service>")
    val graph = graph {
      binding("Service")
      for (key in keys) {
        accessor(key, isDeferrable = true)
      }
    }
    val data = renderer.buildData(graph)
    val dependencies = data.links().filter { it.hasField("rootKind", "accessor") }
    assertThat(dependencies).hasSize(2)
    assertThat(dependencies.strings("wrapperType")).containsExactlyInAnyOrder("Provider", "Lazy")
    for (edge in dependencies) {
      edge.assertFields("target" to "Service", "edgeType" to "deferrable", "isDeferrable" to true)
    }
  }

  @Test
  fun `wrapped map accessor uses the binding selected by the compiler`() {
    val requestedKey = "Map<kotlin.String, Provider<Service>>"
    val selectedKey = "Map<kotlin.String, Service>"
    val graph = graph {
      binding(selectedKey, kind = "Multibinding")
      accessor(requestedKey, isDeferrable = true)
      bindingExplanations = listOf(selectedLookup(requestedKey, selectedKey))
    }
    val data = renderer.buildData(graph)
    val entry = data.roots().single()
    entry.assertFields("fullKey" to requestedKey, "resolutionUnavailable" to false)
    data
      .linksFrom(entry.string("id"))
      .single()
      .assertFields(
        "target" to selectedKey,
        "requestedKey" to requestedKey,
        "wrapperType" to "Provider",
      )
  }

  @Test
  fun `unresolved accessor roots stay visible without dependency connections`() {
    val requestedKey = "Map<kotlin.String, Provider<Service>>"
    val first = "Map<kotlin.String, first.Service>"
    val second = "Map<kotlin.String, second.Service>"
    val graph = graph {
      binding(first)
      binding(second)
      accessor(requestedKey, isDeferrable = true)
      bindingExplanations =
        listOf(selectedLookup(requestedKey, first), selectedLookup(requestedKey, second))
    }
    val data = renderer.buildData(graph)
    val entry = data.roots().single()
    entry.assertFields("resolutionUnavailable" to true, "requestedKey" to requestedKey)
    data.links().single().assertFields("target" to entry.string("id"), "rootMembership" to true)
  }

  @Test
  fun `injector dependencies stay separate from a provided target instance`() {
    val graph = graph {
      binding("Target", kind = "BoundInstance")
      binding(
        bindingMetadata(
          "Target",
          kind = "MembersInjected",
          declaration = "inject",
          dependencies = listOf(DependencyMetadata("Service", false, "MembersInjected")),
        )
      )
      binding("Service")
      injector("Target")
    }
    val data = renderer.buildData(graph)
    val target = data.node("Target")
    assertThat(target.objects("rawDependencies")).isEmpty()
    assertThat(data.targetsFrom(target.string("id"))).isEmpty()
    val injection = data.links().single { it.hasField("rootKind", "injector") }
    injection.assertFields(
      "target" to "Service",
      "injectorTarget" to "Target",
      "wrapperType" to "MembersInjected",
    )
  }

  @Test
  fun `an unnamed injector uses its target type as the label`() {
    val key = "dev.zacsweers.metro.MembersInjector<test.Target<test.Value>>"
    val graph = graph {
      binding(key, "test.Service", kind = "MembersInjected")
      binding("test.Service")
      injector(key)
    }
    val data = renderer.buildData(graph)
    val injector = data.nodes().single { it.hasField("rootKind", "injector") }
    injector.assertFields("name" to "MembersInjector<Target<Value>>", "declaration" to "")
    assertThat(data.nodeIds()).doesNotContain(key)
    assertThat(data.targetsFrom(injector.string("id"))).containsExactlyInAnyOrder("test.Service")
  }

  @Test
  fun `binding details preserve full keys and source declarations`() {
    val dependency =
      DependencyMetadata("dev.zacsweers.metro.Provider<test.Service>", false, "Provider")
    val graph = graph {
      binding(
        bindingMetadata(
          "test.Consumer",
          declaration = "test.Module.provideConsumer",
          dependencies = listOf(dependency),
        )
      )
      binding("test.Service")
    }
    val consumer = renderer.buildData(graph).node("test.Consumer")
    consumer.assertFields("declaration" to "test.Module.provideConsumer")
    consumer.objects("rawDependencies").single().assertFields("key" to dependency.key)
  }

  @Test
  fun `generated report is offline and escapes embedded data`() {
    val hostileKey = "</script><script>alert('x')</script>&__METRO_TITLE__"
    val html = renderer.generateHtml(graph { binding(hostileKey) })
    assertThat(html).doesNotContain("<script src=")
    assertThat(html).doesNotContain("<link href=\"http")
    assertThat(html).doesNotContain(hostileKey)
    assertThat(html).contains("\\u003c/script\\u003e")
    assertThat(html).contains("__METRO_TITLE__")
    assertThat(html).doesNotContain("__METRO_DATA__")
    assertThat(html).doesNotContain("__METRO_SCRIPT__")
  }

  @Test
  fun `index escapes graph and project names`() {
    val graph = graph("test.<Graph>&\"") {}
    val html = renderer.generateIndex(AggregatedGraphMetadata("<app>", 1, listOf(graph)))
    assertThat(html).contains("&lt;app&gt;")
    assertThat(html).contains("test.&lt;Graph&gt;&amp;&quot;")
    assertThat(html).contains("test-%3CGraph%3E%26%22.html")
    assertThat(html).doesNotContain("test.<Graph>")
  }

  @Test
  fun `nested generic names preserve their structure`() {
    assertThat(
        extractDisplayName(
          "kotlin.collections.Map<kotlin.String, kotlin.collections.List<out test.Presenter.Factory>>"
        )
      )
      .isEqualTo("Map<String, List<out Presenter.Factory>>")
  }

  @Test
  fun `node names use simple and nested class names`() {
    val data =
      renderer.buildData(
        graph("test.AppGraph") {
          binding("test.Service")
          binding("test.Presenter.Factory")
          binding("test.Screen.Factory")
        }
      )
    assertThat(data.nodes().strings("name"))
      .containsExactlyInAnyOrder("AppGraph", "Service", "Presenter.Factory", "Screen.Factory")
    data.objectField("typeNames").assertFields("test.Presenter.Factory" to "Presenter.Factory")
  }

  @Test
  fun `colliding top level and nested names keep their packages`() {
    val keys =
      listOf(
        "first.Service",
        "second.Service",
        "first.Presenter.Factory",
        "second.Presenter.Factory",
      )
    val data =
      renderer.buildData(
        graph {
          for (key in keys) {
            binding(key)
          }
        }
      )
    val typeNames = data.objectField("typeNames")
    for (key in keys) {
      data.node(key).assertFields("name" to key, "fullKey" to key)
      typeNames.assertFields(key to key)
    }
  }

  @Test
  fun `colliding generic arguments use consistent type names`() {
    val mapKey = "kotlin.collections.Map<first.Item, kotlin.collections.List<out second.Item>>"
    val boxKey = "Box<first.Item>"
    val data =
      renderer.buildData(
        graph {
          binding(mapKey)
          binding(boxKey)
        }
      )
    data.node(mapKey).assertFields("name" to "Map<first.Item, List<out second.Item>>")
    data.node(boxKey).assertFields("name" to "Box<first.Item>")
    data
      .objectField("typeNames")
      .assertFields(
        "first.Item" to "first.Item",
        "second.Item" to "second.Item",
        "kotlin.collections.List" to "List",
      )
  }

  @Test
  fun `qualified keys keep their type names and packages`() {
    val types =
      mapOf(
        "@other.Service(\"third.Service\") test.Service" to "test.Service",
        "@qualifiers.Named(\"fourth.Service ) value\") @qualifiers.Second test.Service" to
          "test.Service",
        "@test.qualifiers.ApplicationContext android.content.Context" to "android.content.Context",
        "@test.qualifiers.Named(\"a ) value\") android.content.Context" to
          "android.content.Context",
        "@test.qualifiers.Named(\"x\") @test.qualifiers.Second android.content.Context" to
          "android.content.Context",
      )
    val data =
      renderer.buildData(
        graph("test.AppGraph") {
          for (key in types.keys) {
            binding(key)
          }
        }
      )
    for ((key, type) in types) {
      data.node(key).assertFields("name" to type.substringAfterLast('.'), "fullKey" to key)
      assertThat(extractPackage(key)).isEqualTo(type.substringBeforeLast('.'))
      assertThat(extractDisplayName(key)).isEqualTo(type.substringAfterLast('.'))
    }
    assertThat(data.objectField("typeNames").keys)
      .containsExactlyInAnyOrder("test.AppGraph", "test.Service", "android.content.Context")
  }

  @Test
  fun `default and assisted targets participate in type name collisions`() {
    val factory =
      bindingMetadata("Factory", kind = "Assisted")
        .copy(
          assistedTarget =
            AssistedTargetMetadata(
              "assisted.Service",
              "AssistedInject",
              nameHint = "Service",
              dependencies = emptyList(),
            )
        )
    val consumer =
      bindingMetadata(
        "Consumer",
        dependencies = listOf(DependencyMetadata("defaulted.Service = ...", true)),
      )
    val data =
      renderer.buildData(
        graph {
          binding(factory)
          binding(consumer)
        }
      )
    val default = data.nodes().single { it.isTrue("isDefaultValue") }
    val assisted = data.nodes().single { it.isTrue("isAssistedTarget") }
    default.assertFields("name" to "defaulted.Service")
    assisted.assertFields("name" to "assisted.Service")
  }

  private class NestedChildScenario {
    val parent = graph { input("Application") }
    val session =
      graph("AppGraph.Impl.SessionImpl", parent = parent.graph, type = "SessionGraph") {
        binding("SessionState", scoped = true)
      }
    val details =
      graph("${session.graph}.DetailsImpl", parent = session.graph, type = "DetailsGraph") {
        instance()
        inherits("Application", from = parent)
        inherits("SessionState", from = session)
        binding("Details", "Application", "SessionState")
        accessor("Details", name = "details", isProperty = true)
        scopes = listOf("DetailsScope")
        bindingExplanations = listOf(selectedLookup("SessionState", "SessionState"))
      }
    private val analysis = analysisReport(details)
    val standalone = GraphHtmlRenderer(analysis).buildData(details)
    val data = GraphHtmlRenderer(analysis, listOf(parent, session, details)).buildData(details)

    fun expectedId(key: String): String =
      when (key) {
        "Application" -> parent.inReport(details).bindingId(key)
        "SessionState" -> session.inReport(details).bindingId(key)
        else -> key
      }
  }

  private class ExtensionScenario {
    val parent = graph {
      binding("Shared")
      binding("Cache")
      extension("ChildGraph.Factory", type = "ChildGraph", factory = true)
    }
    val child =
      graph("AppGraph.Impl.ChildImpl", parent = parent.graph, type = "ChildGraph") {
        binding("Shared")
        instance(parent.graph)
        input("Input")
        inherits("Cache", from = parent, ownerKey = "AppGraph.Impl")
        binding("Consumer", "Cache")
        accessor("Shared", name = "shared", isProperty = true)
      }
    val childRefs = child.inReport(parent)
    val data = GraphHtmlRenderer(graphs = listOf(parent, child)).buildData(parent)
  }

  private class InheritedExtensionScenario {
    val parent = graph {
      binding("SessionState")
      extension("SessionGraph.Factory", type = "SessionGraph", factory = true)
    }
    val session =
      graph("AppGraph.Impl.SessionGraphImpl", parent = parent.graph, type = "SessionGraph") {
        binding("SessionState", scoped = true)
        instance()
        extension("DetailsGraph")
      }
    val details =
      graph("${session.graph}.DetailsGraphImpl", parent = session.graph, type = "DetailsGraph") {
        instance(session.graph)
        inherits("SessionState", from = session)
        binding("Details", "SessionState")
        alias("StateAlias", "SessionState")
        accessor("SessionState", name = "state", isProperty = true)
      }
    val sessionRefs = session.inReport(parent)
    val detailsRefs = details.inReport(parent)
    val inheritedBindingId = detailsRefs.bindingId("SessionState")
    val ownerBindingId = sessionRefs.bindingId("SessionState")
    val data = GraphHtmlRenderer(graphs = listOf(parent, session, details)).buildData(parent)
  }

  private inner class IncludedGraphScenario {
    private val reports = includedGraphFixture()
    val producer = reports.second
    private val main = graph {
      extension("SessionGraph.Factory", type = "SessionGraph", factory = true)
    }
    private val session =
      reports.first.copy(
        graph = "AppGraph.Impl.SessionImpl",
        graphType = "SessionGraph",
        parentGraph = main.graph,
      )
    val data = GraphHtmlRenderer(graphs = listOf(main, session, producer)).buildData(main)
    val sessionRefs = session.inReport(main)
  }

  private class InheritedMultibindingScenario {
    val key = "kotlin.collections.Set<Service>"
    private val accessor =
      Json.decodeFromString<AccessorMetadata>(
        """{"key":"$key","name":"services","isProperty":true,"isInherited":true,"declaringGraph":"AppGraph","declaringType":"test.ServiceAccessors","origin":"ServiceAccessors.kt:8:3"}"""
      )
    val parent = graph {
      binding(key, kind = "Multibinding")
      extension("ChildGraph")
      accessor(key, name = "services", isProperty = true)
    }
    val child =
      graph("AppGraph.Impl.ChildGraphImpl", parent = parent.graph, type = "ChildGraph") {
        binding(key, "ChildService", kind = "Multibinding")
        binding("ChildService")
        accessor(accessor)
      }
    val data = GraphHtmlRenderer(graphs = listOf(parent, child)).buildData(parent)
  }

  private fun includedGraphFixture(): Pair<GraphMetadata, GraphMetadata> {
    val owner = "Dependencies"
    val consumer = graph {
      includes(owner)
      graphDependency("Endpoint", ownerKey = owner, declaration = "endpoint")
      binding("Consumer", "Endpoint")
    }
    val producer =
      graph(owner) {
        binding("Endpoint", kind = "Provided", declaration = "provideEndpoint")
        accessor("Endpoint", name = "endpoint", isProperty = true)
      }
    return consumer to producer
  }
}
