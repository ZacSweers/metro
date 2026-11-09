// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package libtest

import dev.zacsweers.metro.GraphExtension
import dev.zacsweers.metro.Provides

@GraphExtension
interface LibBlockChild {
  val message: String

  companion {
    context(input: LibParentService)
    @Provides fun provideMessage(): String = input.toString()
  }
}
