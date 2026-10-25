// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package libtest

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Named
import dev.zacsweers.metro.Provides

class LibReceiverInput

class LibReceiverResult(val value: String)

/** Providers whose receivers and context parameters are dependencies. */
@BindingContainer
object LibReceiverProviders {
  @Provides
  context(@Named("libFirst") first: String)
  fun LibReceiverInput.provideLibResult(): LibReceiverResult = LibReceiverResult(first)

  @get:Provides
  val @receiver:Named("libFirst") String.provideLibLength: Long
    get() = length.toLong()
}
