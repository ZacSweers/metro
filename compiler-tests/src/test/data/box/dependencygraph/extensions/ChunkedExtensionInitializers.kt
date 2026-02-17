// Verify that large graph extensions with many bindings (triggering field
// initialization chunking) correctly handle the receiver context.
// PR 883 fixed a bug where chunked initializers had the wrong receiver context.

@Scope annotation class AppScope
@Scope annotation class ChildScope

@SingleIn(AppScope::class) class P1 @Inject constructor()
@SingleIn(AppScope::class) class P2 @Inject constructor()
@SingleIn(AppScope::class) class P3 @Inject constructor()
@SingleIn(AppScope::class) class P4 @Inject constructor()
@SingleIn(AppScope::class) class P5 @Inject constructor()
@SingleIn(AppScope::class) class P6 @Inject constructor()
@SingleIn(AppScope::class) class P7 @Inject constructor()
@SingleIn(AppScope::class) class P8 @Inject constructor()
@SingleIn(AppScope::class) class P9 @Inject constructor()
@SingleIn(AppScope::class) class P10 @Inject constructor()
@SingleIn(AppScope::class) class P11 @Inject constructor()
@SingleIn(AppScope::class) class P12 @Inject constructor()
@SingleIn(AppScope::class) class P13 @Inject constructor()
@SingleIn(AppScope::class) class P14 @Inject constructor()
@SingleIn(AppScope::class) class P15 @Inject constructor()
@SingleIn(AppScope::class) class P16 @Inject constructor()
@SingleIn(AppScope::class) class P17 @Inject constructor()
@SingleIn(AppScope::class) class P18 @Inject constructor()
@SingleIn(AppScope::class) class P19 @Inject constructor()
@SingleIn(AppScope::class) class P20 @Inject constructor()
@SingleIn(AppScope::class) class P21 @Inject constructor()
@SingleIn(AppScope::class) class P22 @Inject constructor()
@SingleIn(AppScope::class) class P23 @Inject constructor()
@SingleIn(AppScope::class) class P24 @Inject constructor()
@SingleIn(AppScope::class) class P25 @Inject constructor()
@SingleIn(AppScope::class) class P26 @Inject constructor()
@SingleIn(AppScope::class) class P27 @Inject constructor()
@SingleIn(AppScope::class) class P28 @Inject constructor()
@SingleIn(AppScope::class) class P29 @Inject constructor()
@SingleIn(AppScope::class) class P30 @Inject constructor()

@DependencyGraph(AppScope::class)
interface AppGraph {
  val childFactory: ChildGraph.Factory
  val p1: P1
  val p30: P30
}

@GraphExtension(ChildScope::class)
interface ChildGraph {
  val p1: P1
  val p2: P2
  val p3: P3
  val p4: P4
  val p5: P5
  val p6: P6
  val p7: P7
  val p8: P8
  val p9: P9
  val p10: P10
  val p11: P11
  val p12: P12
  val p13: P13
  val p14: P14
  val p15: P15
  val p16: P16
  val p17: P17
  val p18: P18
  val p19: P19
  val p20: P20
  val p21: P21
  val p22: P22
  val p23: P23
  val p24: P24
  val p25: P25
  val p26: P26
  val p27: P27
  val p28: P28
  val p29: P29
  val p30: P30

  @GraphExtension.Factory
  @ContributesTo(AppScope::class)
  interface Factory {
    fun create(): ChildGraph
  }
}

fun box(): String {
  val appGraph = createGraph<AppGraph>()
  val child = appGraph.childFactory.create()

  if (child.p1 !== appGraph.p1) return "Fail p1"
  if (child.p30 !== appGraph.p30) return "Fail p30"

  return "OK"
}
