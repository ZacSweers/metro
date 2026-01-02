package dev.zacsweers.metro.compiler.ir.transformers

import dev.zacsweers.metro.compiler.ir.ClassFactory
import dev.zacsweers.metro.compiler.ir.IrMetroContext
import dev.zacsweers.metro.compiler.ir.metroMetadata
import dev.zacsweers.metro.compiler.proto.InjectedClassProto
import dev.zacsweers.metro.compiler.proto.MetroMetadata
import org.jetbrains.kotlin.ir.declarations.IrClass

context(metroContext: IrMetroContext)
internal fun IrClass.writeInjectedClassMetadata(
  classFactory: ClassFactory?,
  memberInjectClass: MembersInjectorTransformer.MemberInjectClass?,
) {
  metroMetadata =
    MetroMetadata(
      injected_class =
        InjectedClassProto(
          factory_class_name = classFactory?.factoryClass?.name?.asString(),
          member_injections = memberInjectClass?.toProto(),
        )
    )
}
