// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index.graph

import dev.zacsweers.metro.idea.index.GraphInterfaceBinding
import dev.zacsweers.metro.idea.model.ConsumerEntry
import dev.zacsweers.metro.idea.model.GraphDeclarationId
import dev.zacsweers.metro.idea.model.GraphDefaultImplementation
import dev.zacsweers.metro.idea.model.GraphExtensionFactoryAccessor
import dev.zacsweers.metro.idea.model.GraphReference
import org.jetbrains.kotlin.analysis.api.types.KaClassType
import org.jetbrains.kotlin.name.ClassId

/** Accumulates graph members while the owning Analysis API session is active. */
internal class GraphMemberTarget(
  val graphId: GraphDeclarationId?,
  val consumers: MutableList<ConsumerEntry>,
  val extensionCreations: MutableSet<GraphReference>,
  val extensionFactories: MutableList<GraphExtensionFactoryAccessor>,
  val injectedMemberOwnerIds: MutableSet<ClassId>,
  val bindingTemplates: MutableList<GraphInterfaceBinding>? = null,
  /** The session-local receiver preserves an inherited factory SAM's annotated subtype. */
  val factoryContext: KaClassType? = null,
  val defaultImplementations: MutableList<GraphDefaultImplementation> = mutableListOf(),
)
