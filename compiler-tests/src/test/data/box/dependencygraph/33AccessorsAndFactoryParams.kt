// https://github.com/ZacSweers/metro/issues/1318
// Also covers 32+ accessors

@DependencyGraph
interface AppGraph {
  @Named("00") val p00: Int
  @Named("01") val p01: Int
  @Named("02") val p02: Int
  @Named("03") val p03: Int
  @Named("04") val p04: Int
  @Named("05") val p05: Int
  @Named("06") val p06: Int
  @Named("07") val p07: Int
  @Named("08") val p08: Int
  @Named("09") val p09: Int
  @Named("10") val p10: Int
  @Named("11") val p11: Int
  @Named("12") val p12: Int
  @Named("13") val p13: Int
  @Named("14") val p14: Int
  @Named("15") val p15: Int
  @Named("16") val p16: Int
  @Named("17") val p17: Int
  @Named("18") val p18: Int
  @Named("19") val p19: Int
  @Named("20") val p20: Int
  @Named("21") val p21: Int
  @Named("22") val p22: Int
  @Named("23") val p23: Int
  @Named("24") val p24: Int
  @Named("25") val p25: Int
  @Named("26") val p26: Int
  @Named("27") val p27: Int
  @Named("28") val p28: Int
  @Named("29") val p29: Int
  @Named("30") val p30: Int
  @Named("31") val p31: Int
  @Named("32") val p32: Int

  @DependencyGraph.Factory
  interface Factory {
    fun create(
      @Provides @Named("00") p00: Int = 0,
      @Provides @Named("01") p01: Int = 1,
      @Provides @Named("02") p02: Int = 2,
      @Provides @Named("03") p03: Int = 3,
      @Provides @Named("04") p04: Int = 4,
      @Provides @Named("05") p05: Int = 5,
      @Provides @Named("06") p06: Int = 6,
      @Provides @Named("07") p07: Int = 7,
      @Provides @Named("08") p08: Int = 8,
      @Provides @Named("09") p09: Int = 9,
      @Provides @Named("10") p10: Int = 10,
      @Provides @Named("11") p11: Int = 11,
      @Provides @Named("12") p12: Int = 12,
      @Provides @Named("13") p13: Int = 13,
      @Provides @Named("14") p14: Int = 14,
      @Provides @Named("15") p15: Int = 15,
      @Provides @Named("16") p16: Int = 16,
      @Provides @Named("17") p17: Int = 17,
      @Provides @Named("18") p18: Int = 18,
      @Provides @Named("19") p19: Int = 19,
      @Provides @Named("20") p20: Int = 20,
      @Provides @Named("21") p21: Int = 21,
      @Provides @Named("22") p22: Int = 22,
      @Provides @Named("23") p23: Int = 23,
      @Provides @Named("24") p24: Int = 24,
      @Provides @Named("25") p25: Int = 25,
      @Provides @Named("26") p26: Int = 26,
      @Provides @Named("27") p27: Int = 27,
      @Provides @Named("28") p28: Int = 28,
      @Provides @Named("29") p29: Int = 29,
      @Provides @Named("30") p30: Int = 30,
      @Provides @Named("31") p31: Int = 31,
      @Provides @Named("32") p32: Int = 32, // Overflows a single bitfield
    ): AppGraph
  }
}

fun box(): String {
  val graph = createGraphFactory<AppGraph.Factory>().create()
  assertEquals(0, graph.p00)
  assertEquals(1, graph.p01)
  assertEquals(2, graph.p02)
  assertEquals(3, graph.p03)
  assertEquals(4, graph.p04)
  assertEquals(5, graph.p05)
  assertEquals(6, graph.p06)
  assertEquals(7, graph.p07)
  assertEquals(8, graph.p08)
  assertEquals(9, graph.p09)
  assertEquals(10, graph.p10)
  assertEquals(11, graph.p11)
  assertEquals(12, graph.p12)
  assertEquals(13, graph.p13)
  assertEquals(14, graph.p14)
  assertEquals(15, graph.p15)
  assertEquals(16, graph.p16)
  assertEquals(17, graph.p17)
  assertEquals(18, graph.p18)
  assertEquals(19, graph.p19)
  assertEquals(20, graph.p20)
  assertEquals(21, graph.p21)
  assertEquals(22, graph.p22)
  assertEquals(23, graph.p23)
  assertEquals(24, graph.p24)
  assertEquals(25, graph.p25)
  assertEquals(26, graph.p26)
  assertEquals(27, graph.p27)
  assertEquals(28, graph.p28)
  assertEquals(29, graph.p29)
  assertEquals(30, graph.p30)
  assertEquals(31, graph.p31)
  assertEquals(32, graph.p32)
  return "OK"
}
