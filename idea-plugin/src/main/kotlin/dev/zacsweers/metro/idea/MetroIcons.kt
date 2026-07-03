// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.idea

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

internal object MetroIcons {
  @JvmField val PROVIDER: Icon = IconLoader.getIcon("/icons/provider.svg", MetroIcons::class.java)
  @JvmField val CONSUMER: Icon = IconLoader.getIcon("/icons/consumer.svg", MetroIcons::class.java)
  @JvmField
  val CONSUMER_UNRESOLVED: Icon =
    IconLoader.getIcon("/icons/consumer_unresolved.svg", MetroIcons::class.java)
  @JvmField
  val CONSUMER_ASSISTED: Icon =
    IconLoader.getIcon("/icons/consumer_assisted.svg", MetroIcons::class.java)
  @JvmField val GRAPH: Icon = IconLoader.getIcon("/icons/graph.svg", MetroIcons::class.java)
  @JvmField val METRO: Icon = IconLoader.getIcon("/icons/metro.svg", MetroIcons::class.java)
  @JvmField val SCOPED: Icon = IconLoader.getIcon("/icons/scoped.svg", MetroIcons::class.java)
  @JvmField val UNSCOPED: Icon = IconLoader.getIcon("/icons/unscoped.svg", MetroIcons::class.java)
  @JvmField
  val MULTIBINDING: Icon = IconLoader.getIcon("/icons/multibinding.svg", MetroIcons::class.java)
  @JvmField
  val CONTRIBUTED: Icon = IconLoader.getIcon("/icons/contributed.svg", MetroIcons::class.java)
  @JvmField val ALIAS: Icon = IconLoader.getIcon("/icons/alias.svg", MetroIcons::class.java)
  @JvmField val UNUSED: Icon = IconLoader.getIcon("/icons/unused.svg", MetroIcons::class.java)
  @JvmField
  val GRAPH_VALIDATED: Icon =
    IconLoader.getIcon("/icons/graph_validated.svg", MetroIcons::class.java)
  @JvmField
  val GRAPH_PROBLEMS: Icon = IconLoader.getIcon("/icons/graph_problems.svg", MetroIcons::class.java)
}
