// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.ir.ClassFactory
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.createMetroMetadata
import dev.zacsweers.metro.compiler.ir.metroMetadata
import dev.zacsweers.metro.compiler.proto.InjectedClassProto
import org.jetbrains.kotlin.ir.declarations.IrClass

context(metroContext: IrMetroContext)
internal fun IrClass.writeInjectedClassMetadata(
  classFactory: ClassFactory?,
  memberInjectClass: MembersInjectorTransformer.MemberInjectClass?,
) {
  metroMetadata =
    createMetroMetadata(
      injected_class =
        InjectedClassProto(
          factory_class_name = classFactory?.factoryClass?.name?.asString(),
          member_injections = memberInjectClass?.toProto(),
        )
    )
}
