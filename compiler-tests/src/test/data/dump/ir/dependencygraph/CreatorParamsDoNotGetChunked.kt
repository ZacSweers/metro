// https://github.com/ZacSweers/metro/issues/721
class ProvidedExample

interface Included {
  val string: String
}

@DependencyGraph(AppScope::class)
interface TestGraph {

  val service30: Service30

  @DependencyGraph.Factory
  interface Factory {
    fun create(@Provides providedExample: ProvidedExample, @Includes included: Included): TestGraph
  }
}

@SingleIn(AppScope::class) @Inject class Service1(val providedExample: ProvidedExample)

@SingleIn(AppScope::class) @Inject class Service2(val s1: Service1)

@SingleIn(AppScope::class) @Inject class Service3(val s2: Service2)

@SingleIn(AppScope::class) @Inject class Service4(val s3: Service3)

@SingleIn(AppScope::class) @Inject class Service5(val s4: Service4)

@SingleIn(AppScope::class) @Inject class Service6(val s5: Service5)

@SingleIn(AppScope::class) @Inject class Service7(val s6: Service6)

@SingleIn(AppScope::class) @Inject class Service8(val s7: Service7)

@SingleIn(AppScope::class) @Inject class Service9(val s8: Service8)

@SingleIn(AppScope::class) @Inject class Service10(val s9: Service9)

@SingleIn(AppScope::class) @Inject class Service11(val s10: Service10)

@SingleIn(AppScope::class) @Inject class Service12(val s11: Service11)

@SingleIn(AppScope::class) @Inject class Service13(val s12: Service12)

@SingleIn(AppScope::class) @Inject class Service14(val s13: Service13)

@SingleIn(AppScope::class) @Inject class Service15(val s14: Service14)

@SingleIn(AppScope::class) @Inject class Service16(val s15: Service15)

@SingleIn(AppScope::class) @Inject class Service17(val s16: Service16)

@SingleIn(AppScope::class) @Inject class Service18(val s17: Service17)

@SingleIn(AppScope::class) @Inject class Service19(val s18: Service18)

@SingleIn(AppScope::class) @Inject class Service20(val s19: Service19)

@SingleIn(AppScope::class) @Inject class Service21(val s20: Service20)

@SingleIn(AppScope::class) @Inject class Service22(val s21: Service21)

@SingleIn(AppScope::class) @Inject class Service23(val s22: Service22)

@SingleIn(AppScope::class) @Inject class Service24(val s23: Service23)

@SingleIn(AppScope::class) @Inject class Service25(val s24: Service24)

@SingleIn(AppScope::class) @Inject class Service26(val s25: Service25)

@SingleIn(AppScope::class) @Inject class Service27(val s26: Service26)

@SingleIn(AppScope::class) @Inject class Service28(val s27: Service27)

@SingleIn(AppScope::class) @Inject class Service29(val s28: Service28)

@SingleIn(AppScope::class) @Inject class Service30(val s29: Service29)
