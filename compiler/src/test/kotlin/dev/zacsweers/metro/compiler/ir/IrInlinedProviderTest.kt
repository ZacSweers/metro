// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir

import dev.zacsweers.metro.compiler.proto.EnumEntryProto
import dev.zacsweers.metro.compiler.proto.InlinedProviderProto
import dev.zacsweers.metro.compiler.proto.InlinedValueProto
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import org.junit.Test

class IrInlinedProviderTest {
  @Test
  fun `inline values survive metadata round trips`() {
    val values =
      listOf(
        InlinedValueProto(int_value = 0),
        InlinedValueProto(long_value = Long.MAX_VALUE),
        InlinedValueProto(float_value = -0.0f),
        InlinedValueProto(double_value = Double.MIN_VALUE),
        InlinedValueProto(bool_value = false),
        InlinedValueProto(string_value = ""),
        InlinedValueProto(char_value = 'Z'.code),
        InlinedValueProto(is_null = true),
        InlinedValueProto(object_class_id = "test/ObjectValue"),
        InlinedValueProto(enum_value = EnumEntryProto("test/EnumValue", "Entry")),
        InlinedValueProto(class_literal_class_id = "test/ClassValue"),
      )
    for (value in values) {
      val original = InlinedProviderProto(value_ = value)
      val decoded =
        InlinedProviderProto.ADAPTER.decode(InlinedProviderProto.ADAPTER.encode(original))
          as InlinedProviderProto
      val provider =
        assertNotNull(IrInlinedProvider.fromProto(decoded), "Lost inline value: $value")

      assertEquals(original, provider.toProto())
    }
  }

  @Test
  fun `missing inline metadata uses the provider fallback`() {
    assertNull(IrInlinedProvider.fromProto(null))
    assertNull(IrInlinedProvider.fromProto(InlinedProviderProto()))
    assertNull(IrInlinedProvider.fromProto(InlinedProviderProto(value_ = InlinedValueProto())))
  }
}
