// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.internal

import dev.zacsweers.metro.providerOf
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapLazyFactoryTest {
  @Test
  fun `lazy map factories support nullable values`() {
    val provider = providerOf<String?>(null)
    val built = MapLazyFactory.builder<String, String?>(1).put("built", provider).build()()
    assertNull(built.getValue("built").value)

    val singleton = MapLazyFactory.singleton("singleton", provider)()
    assertNull(singleton.getValue("singleton").value)

    assertTrue(MapLazyFactory.empty<String, String?>()().isEmpty())
  }

  @Test
  fun `provider lazy map factories support nullable values`() {
    val provider = providerOf<String?>(null)
    val built = MapProviderLazyFactory.builder<String, String?>(1).put("built", provider).build()()
    assertNull(built.getValue("built")().value)

    val singleton = MapProviderLazyFactory.singleton("singleton", provider)()
    assertNull(singleton.getValue("singleton")().value)

    assertTrue(MapProviderLazyFactory.empty<String, String?>()().isEmpty())
  }
}
