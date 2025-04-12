// Copyright (C) 2023 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.sample.circuit

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

// Copied because it's from the icons-extended dependency, which is huge
@Suppress("MagicNumber")
val Remove: ImageVector
  get() {
    _remove?.let {
      return it
    }
    val remove =
      materialIcon(name = "Filled.Remove") {
        materialPath {
          moveTo(19.0f, 13.0f)
          horizontalLineTo(5.0f)
          verticalLineToRelative(-2.0f)
          horizontalLineToRelative(14.0f)
          verticalLineToRelative(2.0f)
          close()
        }
      }

    _remove = remove
    return remove
  }

private var _remove: ImageVector? = null
