import kotlin.test.*

@Inject class Class1(class2: Class2)

@Inject class Class2(class3: Class3)

@Inject class Class3(class4: Class4)

@Inject class Class4(class5: Class5)

@Inject class Class5(class6: Class6)

@Inject class Class6(class7: Class7)

@Inject class Class7(class8: Class8)

@Inject class Class8(class9: Class9)

@Inject class Class9(class10: Class10)

@Inject class Class10(class11: Class11)

@Inject class Class11(class12: Class12)

@Inject class Class12(class13: Class13)

@Inject class Class13(class14: Class14)

@Inject class Class14(class15: Class15)

@Inject class Class15(class16: Class16)

@Inject class Class16(class17: Class17)

@Inject class Class17(class18: Class18)

@Inject class Class18(class19: Class19)

@Inject class Class19(class20: Class20)

@Inject class Class20(class21: Class21)

@Inject class Class21(class22: Class22)

@Inject class Class22(class23: Class23)

@Inject class Class23(class24: Class24)

@Inject class Class24(class25: Class25)

@Inject class Class25(class26: Class26)

@Inject class Class26(class27: Class27)

@Inject class Class27(class28: Class28)

@Inject class Class28(class29: Class29)

@Inject class Class29(class30: Class30)

@Inject class Class30(class31: Class31)

@Inject class Class31(class32: Class32)

@Inject class Class32(class33: Class33)

@Inject class Class33(class34: Class34)

@Inject class Class34(class35: Class35)

@Inject class Class35(class36: Class36)

@Inject class Class36(class37: Class37)

@Inject class Class37(class38: Class38)

@Inject class Class38(class39: Class39)

@Inject class Class39(class40: Class40)

@Inject class Class40(class41: Class41)

@Inject class Class41(class42: Class42)

@Inject class Class42(class43: Class43)

@Inject class Class43(class44: Class44)

@Inject class Class44(class45: Class45)

@Inject class Class45(class46: Class46)

@Inject class Class46(class47: Class47)

@Inject class Class47(class48: Class48)

@Inject class Class48(class49: Class49)

@Inject class Class49(class50: Class50)

@Inject class Class50(class51: Class51)

@Inject class Class51(class52: Class52)

@Inject class Class52(class53: Class53)

@Inject class Class53(class54: Class54)

@Inject class Class54(class55: Class55)

@Inject class Class55(class56: Class56)

@Inject class Class56(class57: Class57)

@Inject class Class57(class58: Class58)

@Inject class Class58(class59: Class59)

@Inject class Class59(class60: Class60)

@Inject class Class60(class61: Class61)

@Inject class Class61(class62: Class62)

@Inject class Class62(class63: Class63)

@Inject class Class63(class64: Class64)

@Inject class Class64(class65: Class65)

@Inject class Class65(class66: Class66)

@Inject class Class66(class67: Class67)

@Inject class Class67(class68: Class68)

@Inject class Class68(class69: Class69)

@Inject class Class69(class70: Class70)

@Inject class Class70(class71: Class71)

@Inject class Class71(class72: Class72)

@Inject class Class72(class73: Class73)

@Inject class Class73(class74: Class74)

@Inject class Class74(class75: Class75)

@Inject class Class75(class76: Class76)

@Inject class Class76(class77: Class77)

@Inject class Class77(class78: Class78)

@Inject class Class78(class79: Class79)

@Inject class Class79(class80: Class80)

@Inject class Class80(class81: Class81)

@Inject class Class81(class82: Class82)

@Inject class Class82(class83: Class83)

@Inject class Class83(class84: Class84)

@Inject class Class84(class85: Class85)

@Inject class Class85(class86: Class86)

@Inject class Class86(class87: Class87)

@Inject class Class87(class88: Class88)

@Inject class Class88(class89: Class89)

@Inject class Class89(class90: Class90)

@Inject class Class90(class91: Class91)

@Inject class Class91(class92: Class92)

@Inject class Class92(class93: Class93)

@Inject class Class93(class94: Class94)

@Inject class Class94(class95: Class95)

@Inject class Class95(class96: Class96)

@Inject class Class96(class97: Class97)

@Inject class Class97(class98: Class98)

@Inject class Class98(class99: Class99)

@Inject class Class99(class100: Class100)

@Inject class Class100(class101: Class101)

@Inject class Class101(class1Provider: Provider<Class1>)

@DependencyGraph
interface LongCycleGraph {
  val class1: Class1
}

fun box(): String {
  val graph = createGraph<LongCycleGraph>()
  assertNotNull(graph.class1)
  return "OK"
}