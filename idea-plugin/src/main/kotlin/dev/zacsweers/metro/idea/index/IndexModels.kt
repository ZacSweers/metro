// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea.index

import dev.zacsweers.metro.idea.model.AssistedSite
import dev.zacsweers.metro.idea.model.BindingContainerEntry
import dev.zacsweers.metro.idea.model.BindingKind
import dev.zacsweers.metro.idea.model.ConsumerEntry
import dev.zacsweers.metro.idea.model.ContributionEntry
import dev.zacsweers.metro.idea.model.KaAnnotationSnapshot
import dev.zacsweers.metro.idea.model.KaBinding
import dev.zacsweers.metro.idea.model.KaContextualTypeKey
import dev.zacsweers.metro.idea.model.KaGraphNode
import dev.zacsweers.metro.idea.model.KaTypeKey
import org.jetbrains.kotlin.name.ClassId

/** Key plus display metadata for a consuming site. */
internal class ConsumedSite(
  val contextKey: KaContextualTypeKey,
  val isAbstractType: Boolean,
  /** For `Set`/`Map` aggregate sites, the multibinding id collecting contributed elements. */
  val multibindingId: String? = null,
  /** The consumed type's class, when it is a class type. */
  val typeClassId: ClassId? = null,
) {
  val key: KaTypeKey
    get() = contextKey.typeKey
}

/** Key plus display metadata for a single binding originated by a provider declaration. */
internal class BindingData(
  val key: KaTypeKey,
  val kind: BindingKind,
  val scope: KaAnnotationSnapshot?,
  val implementationName: String?,
  /** For `@Binds`-style bindings, the key of the source/impl binding this delegates to. */
  val consumedKey: KaTypeKey? = null,
  /** For multibinding contributions, the aggregate binding id. See [KaBinding]. */
  val multibindingId: String? = null,
  /** See [KaBinding.originClassId]. */
  val originClassId: ClassId? = null,
  /** See [KaBinding.replaces]. */
  val replaces: Set<ClassId> = emptySet(),
  /** See [KaBinding.contributionScopes]. */
  val contributionScopes: Set<ClassId> = emptySet(),
)

/** The Metro declarations extracted from a single file, cached against that file's PSI. */
internal class FileShard(
  val bindings: List<KaBinding>,
  val consumers: List<ConsumerEntry>,
  val graphs: List<KaGraphNode>,
  val contributions: List<ContributionEntry>,
  val assistedSites: List<AssistedSite>,
  val bindingContainers: List<BindingContainerEntry>,
) {
  companion object {
    val EMPTY =
      FileShard(
        emptyList(),
        emptyList(),
        emptyList(),
        emptyList(),
        emptyList(),
        emptyList(),
      )
  }
}
