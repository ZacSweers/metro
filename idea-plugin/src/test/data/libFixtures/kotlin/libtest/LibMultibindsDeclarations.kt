// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package libtest

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Multibinds

/** A binary multibinding declaration has no source annotation that the editor can change. */
@BindingContainer
interface LibMultibindsDeclarations {
  @Multibinds fun values(): Set<String>
}
