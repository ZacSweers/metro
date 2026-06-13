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
}
