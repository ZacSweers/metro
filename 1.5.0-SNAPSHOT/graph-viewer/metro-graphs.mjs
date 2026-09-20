import {
  ArrayListSerializer7k5wnrulb3y6 as ArrayListSerializer,
  StringSerializer_getInstance1p9zda6r6kb23 as StringSerializer_getInstance,
  PluginGeneratedSerialDescriptorqdzeg5asqhfg as PluginGeneratedSerialDescriptor,
  UnknownFieldException_init_$Create$3n7tjepqfwwvc as UnknownFieldException_init_$Create$,
  get_nullable197rfua9r7fsz as get_nullable,
  typeParametersSerializers2likxjr48tr7y as typeParametersSerializers,
  GeneratedSerializer1f7t7hssdd2ws as GeneratedSerializer,
  throwMissingFieldException2cmke0v3ynf14 as throwMissingFieldException,
  BooleanSerializer_getInstance22aja3hu17sxf as BooleanSerializer_getInstance,
  createAnnotatedEnumSerializer20ay4pme9p2h9 as createAnnotatedEnumSerializer,
  SerializerFactory1qv9hivitncuv as SerializerFactory,
  IntSerializer_getInstance3jyhns9ue706g as IntSerializer_getInstance,
  SerializableWithd2dap36updxd as SerializableWith,
  DoubleSerializer_getInstance1tuvibnyg07o1 as DoubleSerializer_getInstance,
  LinkedHashMapSerializermaoj2nyji7op as LinkedHashMapSerializer,
} from './kotlinx-serialization-kotlinx-serialization-core.mjs';
import {
  LazyThreadSafetyMode_PUBLICATION_getInstance23f213579at67 as LazyThreadSafetyMode_PUBLICATION_getInstance,
  lazy1261dae0bgscp as lazy,
  protoOf180f3jzyo7rfj as protoOf,
  initMetadataForCompanion1wyw17z38v6ac as initMetadataForCompanion,
  Unit_instance3kz1zqli6gr1r as Unit_instance,
  emptyList1g2z5xcrvp2zy as emptyList,
  equals2au1ep9vhcato as equals,
  THROW_CCE2g6jy02ryeudk as THROW_CCE,
  initMetadataForObject1cxne3s9w65el as initMetadataForObject,
  VOID3gxj6tk5isa35 as VOID,
  objectCreate1ve4bgxiu4x98 as objectCreate,
  toString1pkumu07cwy4m as toString,
  toString30pk9tzaqopn as toString_0,
  hashCodeq5arwsb9dgti as hashCode,
  initMetadataForClassbxx6q50dy2s7 as initMetadataForClass,
  getStringHashCode26igk1bx568vk as getStringHashCode,
  getBooleanHashCode1bbj3u6b3v0a7 as getBooleanHashCode,
  Companion_instance13way2il9a8jt as Companion_instance,
  Enum3alwj03lh1n41 as Enum,
  ArrayList_init_$Create$2xrifdqz5xirg as ArrayList_init_$Create$,
  LinkedHashSet_init_$Create$vv7nkva6wxy5 as LinkedHashSet_init_$Create$,
  emptyMapr06gerzljqtm as emptyMap,
  getNumberHashCode2l4nbdcihl25f as getNumberHashCode,
  listOf1jh22dvmctj1r as listOf,
  removePrefix279df90bhrqqg as removePrefix,
  removeSuffix3d61x5lsuvuho as removeSuffix,
  startsWith26w8qjqapeeq6 as startsWith,
  endsWith3cq61xxngobwh as endsWith,
} from './kotlin-kotlin-stdlib.mjs';
import { JsonObjectSerializer_getInstance15ehqyi55oo4q as JsonObjectSerializer_getInstance } from './kotlinx-serialization-kotlinx-serialization-json.mjs';
//region block: imports
var imul = Math.imul;
//endregion
//region block: pre-declaration
initMetadataForCompanion(Companion);
initMetadataForObject($serializer, '$serializer', VOID, VOID, [GeneratedSerializer]);
initMetadataForClass(BindingExplanation, 'BindingExplanation', VOID, VOID, VOID, VOID, VOID, {0: $serializer_getInstance});
initMetadataForCompanion(Companion_0);
initMetadataForObject($serializer_0, '$serializer', VOID, VOID, [GeneratedSerializer]);
initMetadataForClass(BindingExplanationCandidate, 'BindingExplanationCandidate', VOID, VOID, VOID, VOID, VOID, {0: $serializer_getInstance_0});
initMetadataForObject($serializer_1, '$serializer', VOID, VOID, [GeneratedSerializer]);
initMetadataForClass(BindingExplanationRequest, 'BindingExplanationRequest', VOID, VOID, VOID, VOID, VOID, {0: $serializer_getInstance_1});
initMetadataForCompanion(Companion_1, VOID, [SerializerFactory]);
initMetadataForClass(BindingExplanationOutcome, 'BindingExplanationOutcome', VOID, Enum, VOID, VOID, VOID, {0: Companion_getInstance_1});
initMetadataForCompanion(Companion_2, VOID, [SerializerFactory]);
initMetadataForClass(BindingCandidateStatus, 'BindingCandidateStatus', VOID, Enum, VOID, VOID, VOID, {0: Companion_getInstance_2});
initMetadataForObject($serializer_2, '$serializer', VOID, VOID, [GeneratedSerializer]);
initMetadataForClass(BindingExplanationContext, 'BindingExplanationContext', VOID, VOID, VOID, VOID, VOID, {0: $serializer_getInstance_2});
initMetadataForCompanion(Companion_3, VOID, [SerializerFactory]);
initMetadataForClass(BindingExplanationPhase, 'BindingExplanationPhase', VOID, Enum, VOID, VOID, VOID, {0: Companion_getInstance_3});
initMetadataForObject($serializer_3, '$serializer', VOID, VOID, [GeneratedSerializer]);
initMetadataForClass(BindingDeclaration, 'BindingDeclaration', VOID, VOID, VOID, VOID, VOID, {0: $serializer_getInstance_3});
initMetadataForObject($serializer_4, '$serializer', VOID, VOID, [GeneratedSerializer]);
initMetadataForClass(BindingSourceLocation, 'BindingSourceLocation', VOID, VOID, VOID, VOID, VOID, {0: $serializer_getInstance_4});
initMetadataForCompanion(Companion_4, VOID, [SerializerFactory]);
initMetadataForClass(BindingReason, 'BindingReason', VOID, Enum, VOID, VOID, VOID, {0: Companion_getInstance_4});
initMetadataForClass(AnalysisEdge, 'AnalysisEdge');
initMetadataForCompanion(Companion_5);
initMetadataForObject($serializer_5, '$serializer', VOID, VOID, [GeneratedSerializer]);
initMetadataForClass(FullAnalysisReport, 'FullAnalysisReport', VOID, VOID, VOID, VOID, VOID, {0: $serializer_getInstance_5});
initMetadataForCompanion(Companion_6);
initMetadataForObject($serializer_6, '$serializer', VOID, VOID, [GeneratedSerializer]);
initMetadataForClass(GraphAnalysis, 'GraphAnalysis', VOID, VOID, VOID, VOID, VOID, {0: $serializer_getInstance_6});
initMetadataForCompanion(Companion_7);
initMetadataForObject($serializer_7, '$serializer', VOID, VOID, [GeneratedSerializer]);
initMetadataForClass(FanScore, 'FanScore', VOID, VOID, VOID, VOID, VOID, {0: $serializer_getInstance_7});
initMetadataForCompanion(Companion_8);
initMetadataForObject($serializer_8, '$serializer', VOID, VOID, [GeneratedSerializer]);
initMetadataForClass(FanAnalysisResult, 'FanAnalysisResult', VOID, VOID, VOID, VOID, VOID, {0: $serializer_getInstance_8});
initMetadataForObject($serializer_9, '$serializer', VOID, VOID, [GeneratedSerializer]);
initMetadataForClass(CentralityScore, 'CentralityScore', VOID, VOID, VOID, VOID, VOID, {0: $serializer_getInstance_9});
initMetadataForCompanion(Companion_9);
initMetadataForObject($serializer_10, '$serializer', VOID, VOID, [GeneratedSerializer]);
initMetadataForClass(CentralityResult, 'CentralityResult', VOID, VOID, VOID, VOID, VOID, {0: $serializer_getInstance_10});
initMetadataForCompanion(Companion_10);
initMetadataForObject($serializer_11, '$serializer', VOID, VOID, [GeneratedSerializer]);
initMetadataForClass(DominatorNode, 'DominatorNode', VOID, VOID, VOID, VOID, VOID, {0: $serializer_getInstance_11});
initMetadataForCompanion(Companion_11);
initMetadataForObject($serializer_12, '$serializer', VOID, VOID, [GeneratedSerializer]);
initMetadataForClass(DominatorResult, 'DominatorResult', VOID, VOID, VOID, VOID, VOID, {0: $serializer_getInstance_12});
initMetadataForCompanion(Companion_12);
initMetadataForObject($serializer_13, '$serializer', VOID, VOID, [GeneratedSerializer]);
initMetadataForClass(PathsToRootResult, 'PathsToRootResult', VOID, VOID, VOID, VOID, VOID, {0: $serializer_getInstance_13});
initMetadataForCompanion(Companion_13);
initMetadataForObject($serializer_14, '$serializer', VOID, VOID, [GeneratedSerializer]);
initMetadataForClass(LongestPathResult, 'LongestPathResult', VOID, VOID, VOID, VOID, VOID, {0: $serializer_getInstance_14});
initMetadataForCompanion(Companion_14);
initMetadataForObject($serializer_15, '$serializer', VOID, VOID, [GeneratedSerializer]);
initMetadataForClass(GraphStatistics, 'GraphStatistics', VOID, VOID, VOID, VOID, VOID, {0: $serializer_getInstance_15});
initMetadataForCompanion(Companion_15);
initMetadataForObject($serializer_16, '$serializer', VOID, VOID, [GeneratedSerializer]);
initMetadataForClass(GraphMetadata, 'GraphMetadata', VOID, VOID, VOID, VOID, VOID, {0: $serializer_getInstance_16});
initMetadataForCompanion(Companion_16);
initMetadataForObject($serializer_17, '$serializer', VOID, VOID, [GeneratedSerializer]);
initMetadataForClass(GraphStatsMetadata, 'GraphStatsMetadata', GraphStatsMetadata, VOID, VOID, VOID, VOID, {0: $serializer_getInstance_17});
initMetadataForCompanion(Companion_17);
initMetadataForObject($serializer_18, '$serializer', VOID, VOID, [GeneratedSerializer]);
initMetadataForClass(BindingMetadata, 'BindingMetadata', VOID, VOID, VOID, VOID, VOID, {0: $serializer_getInstance_18});
initMetadataForCompanion(Companion_18);
initMetadataForObject($serializer_19, '$serializer', VOID, VOID, [GeneratedSerializer]);
initMetadataForClass(GraphDependencyMetadata, 'GraphDependencyMetadata', VOID, VOID, VOID, VOID, VOID, {0: $serializer_getInstance_19});
initMetadataForCompanion(Companion_19);
initMetadataForObject($serializer_20, '$serializer', VOID, VOID, [GeneratedSerializer]);
initMetadataForClass(DependencyMetadata, 'DependencyMetadata', VOID, VOID, VOID, VOID, VOID, {0: $serializer_getInstance_20});
initMetadataForCompanion(Companion_20);
initMetadataForObject($serializer_21, '$serializer', VOID, VOID, [GeneratedSerializer]);
initMetadataForClass(AccessorMetadata, 'AccessorMetadata', VOID, VOID, VOID, VOID, VOID, {0: $serializer_getInstance_21});
initMetadataForCompanion(Companion_21);
initMetadataForObject($serializer_22, '$serializer', VOID, VOID, [GeneratedSerializer]);
initMetadataForClass(RootsMetadata, 'RootsMetadata', RootsMetadata, VOID, VOID, VOID, VOID, {0: $serializer_getInstance_22});
initMetadataForCompanion(Companion_22);
initMetadataForObject($serializer_23, '$serializer', VOID, VOID, [GeneratedSerializer]);
initMetadataForClass(MultibindingMetadata, 'MultibindingMetadata', VOID, VOID, VOID, VOID, VOID, {0: $serializer_getInstance_23});
initMetadataForCompanion(Companion_23);
initMetadataForObject($serializer_24, '$serializer', VOID, VOID, [GeneratedSerializer]);
initMetadataForClass(OptionalWrapperMetadata, 'OptionalWrapperMetadata', VOID, VOID, VOID, VOID, VOID, {0: $serializer_getInstance_24});
initMetadataForCompanion(Companion_24);
initMetadataForObject($serializer_25, '$serializer', VOID, VOID, [GeneratedSerializer]);
initMetadataForClass(AssistedTargetMetadata, 'AssistedTargetMetadata', VOID, VOID, VOID, VOID, VOID, {0: $serializer_getInstance_25});
initMetadataForObject($serializer_26, '$serializer', VOID, VOID, [GeneratedSerializer]);
initMetadataForClass(InjectorMetadata, 'InjectorMetadata', VOID, VOID, VOID, VOID, VOID, {0: $serializer_getInstance_26});
initMetadataForObject($serializer_27, '$serializer', VOID, VOID, [GeneratedSerializer]);
initMetadataForClass(ExtensionAccessorMetadata, 'ExtensionAccessorMetadata', VOID, VOID, VOID, VOID, VOID, {0: $serializer_getInstance_27});
initMetadataForCompanion(Companion_25);
initMetadataForObject($serializer_28, '$serializer', VOID, VOID, [GeneratedSerializer]);
initMetadataForClass(ExtensionsMetadata, 'ExtensionsMetadata', ExtensionsMetadata, VOID, VOID, VOID, VOID, {0: $serializer_getInstance_28});
initMetadataForObject($serializer_29, '$serializer', VOID, VOID, [GeneratedSerializer]);
initMetadataForClass(ExtensionFactoryAccessorMetadata, 'ExtensionFactoryAccessorMetadata', VOID, VOID, VOID, VOID, VOID, {0: $serializer_getInstance_29});
initMetadataForObject($serializer_30, '$serializer', VOID, VOID, [GeneratedSerializer]);
initMetadataForClass(AssistedParameterMetadata, 'AssistedParameterMetadata', VOID, VOID, VOID, VOID, VOID, {0: $serializer_getInstance_30});
initMetadataForCompanion(Companion_26);
initMetadataForObject($serializer_31, '$serializer', VOID, VOID, [GeneratedSerializer]);
initMetadataForClass(GraphOptimizationStatsMetadata, 'GraphOptimizationStatsMetadata', GraphOptimizationStatsMetadata, VOID, VOID, VOID, VOID, {0: $serializer_getInstance_31});
//endregion
function BindingExplanation$Companion$$childSerializers$_anonymous__p4yka2() {
  return Companion_getInstance_3().serializer_9w0wvi_k$();
}
function BindingExplanation$Companion$$childSerializers$_anonymous__p4yka2_0() {
  return Companion_getInstance_1().serializer_9w0wvi_k$();
}
function BindingExplanation$Companion$$childSerializers$_anonymous__p4yka2_1() {
  return new ArrayListSerializer($serializer_getInstance_0());
}
function BindingExplanation$Companion$$childSerializers$_anonymous__p4yka2_2() {
  return new ArrayListSerializer(StringSerializer_getInstance());
}
function Companion() {
  Companion_instance_0 = this;
  var tmp = this;
  var tmp_0 = LazyThreadSafetyMode_PUBLICATION_getInstance();
  var tmp_1 = lazy(tmp_0, BindingExplanation$Companion$$childSerializers$_anonymous__p4yka2);
  var tmp_2 = LazyThreadSafetyMode_PUBLICATION_getInstance();
  var tmp_3 = lazy(tmp_2, BindingExplanation$Companion$$childSerializers$_anonymous__p4yka2_0);
  var tmp_4 = LazyThreadSafetyMode_PUBLICATION_getInstance();
  var tmp_5 = lazy(tmp_4, BindingExplanation$Companion$$childSerializers$_anonymous__p4yka2_1);
  var tmp_6 = LazyThreadSafetyMode_PUBLICATION_getInstance();
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  tmp.$childSerializers_1 = [null, tmp_1, tmp_3, tmp_5, null, lazy(tmp_6, BindingExplanation$Companion$$childSerializers$_anonymous__p4yka2_2)];
}
var Companion_instance_0;
function Companion_getInstance() {
  if (Companion_instance_0 == null)
    new Companion();
  return Companion_instance_0;
}
function $serializer() {
  $serializer_instance = this;
  var tmp0_serialDesc = new PluginGeneratedSerialDescriptor('dev.zacsweers.metro.compiler.graph.explanation.BindingExplanation', this, 6);
  tmp0_serialDesc.addElement_5pzumi_k$('context', false);
  tmp0_serialDesc.addElement_5pzumi_k$('phase', false);
  tmp0_serialDesc.addElement_5pzumi_k$('outcome', false);
  tmp0_serialDesc.addElement_5pzumi_k$('candidates', false);
  tmp0_serialDesc.addElement_5pzumi_k$('request', true);
  tmp0_serialDesc.addElement_5pzumi_k$('details', true);
  this.descriptor_1 = tmp0_serialDesc;
}
protoOf($serializer).serialize_z71xrc_k$ = function (encoder, value) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_output = encoder.beginStructure_yljocp_k$(tmp0_desc);
  var tmp2_cached = Companion_getInstance().$childSerializers_1;
  tmp1_output.encodeSerializableElement_isqxcl_k$(tmp0_desc, 0, $serializer_getInstance_2(), value.context_1);
  tmp1_output.encodeSerializableElement_isqxcl_k$(tmp0_desc, 1, tmp2_cached[1].get_value_j01efc_k$(), value.phase_1);
  tmp1_output.encodeSerializableElement_isqxcl_k$(tmp0_desc, 2, tmp2_cached[2].get_value_j01efc_k$(), value.outcome_1);
  tmp1_output.encodeSerializableElement_isqxcl_k$(tmp0_desc, 3, tmp2_cached[3].get_value_j01efc_k$(), value.candidates_1);
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 4) ? true : !(value.request_1 == null)) {
    tmp1_output.encodeNullableSerializableElement_5lquiv_k$(tmp0_desc, 4, $serializer_getInstance_1(), value.request_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 5) ? true : !equals(value.details_1, emptyList())) {
    tmp1_output.encodeSerializableElement_isqxcl_k$(tmp0_desc, 5, tmp2_cached[5].get_value_j01efc_k$(), value.details_1);
  }
  tmp1_output.endStructure_1xqz0n_k$(tmp0_desc);
};
protoOf($serializer).serialize_5ase3y_k$ = function (encoder, value) {
  return this.serialize_z71xrc_k$(encoder, value instanceof BindingExplanation ? value : THROW_CCE());
};
protoOf($serializer).deserialize_sy6x50_k$ = function (decoder) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_flag = true;
  var tmp2_index = 0;
  var tmp3_bitMask0 = 0;
  var tmp4_local0 = null;
  var tmp5_local1 = null;
  var tmp6_local2 = null;
  var tmp7_local3 = null;
  var tmp8_local4 = null;
  var tmp9_local5 = null;
  var tmp10_input = decoder.beginStructure_yljocp_k$(tmp0_desc);
  var tmp11_cached = Companion_getInstance().$childSerializers_1;
  if (tmp10_input.decodeSequentially_xlblqy_k$()) {
    tmp4_local0 = tmp10_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 0, $serializer_getInstance_2(), tmp4_local0);
    tmp3_bitMask0 = tmp3_bitMask0 | 1;
    tmp5_local1 = tmp10_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 1, tmp11_cached[1].get_value_j01efc_k$(), tmp5_local1);
    tmp3_bitMask0 = tmp3_bitMask0 | 2;
    tmp6_local2 = tmp10_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 2, tmp11_cached[2].get_value_j01efc_k$(), tmp6_local2);
    tmp3_bitMask0 = tmp3_bitMask0 | 4;
    tmp7_local3 = tmp10_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 3, tmp11_cached[3].get_value_j01efc_k$(), tmp7_local3);
    tmp3_bitMask0 = tmp3_bitMask0 | 8;
    tmp8_local4 = tmp10_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 4, $serializer_getInstance_1(), tmp8_local4);
    tmp3_bitMask0 = tmp3_bitMask0 | 16;
    tmp9_local5 = tmp10_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 5, tmp11_cached[5].get_value_j01efc_k$(), tmp9_local5);
    tmp3_bitMask0 = tmp3_bitMask0 | 32;
  } else
    while (tmp1_flag) {
      tmp2_index = tmp10_input.decodeElementIndex_bstkhp_k$(tmp0_desc);
      switch (tmp2_index) {
        case -1:
          tmp1_flag = false;
          break;
        case 0:
          tmp4_local0 = tmp10_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 0, $serializer_getInstance_2(), tmp4_local0);
          tmp3_bitMask0 = tmp3_bitMask0 | 1;
          break;
        case 1:
          tmp5_local1 = tmp10_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 1, tmp11_cached[1].get_value_j01efc_k$(), tmp5_local1);
          tmp3_bitMask0 = tmp3_bitMask0 | 2;
          break;
        case 2:
          tmp6_local2 = tmp10_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 2, tmp11_cached[2].get_value_j01efc_k$(), tmp6_local2);
          tmp3_bitMask0 = tmp3_bitMask0 | 4;
          break;
        case 3:
          tmp7_local3 = tmp10_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 3, tmp11_cached[3].get_value_j01efc_k$(), tmp7_local3);
          tmp3_bitMask0 = tmp3_bitMask0 | 8;
          break;
        case 4:
          tmp8_local4 = tmp10_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 4, $serializer_getInstance_1(), tmp8_local4);
          tmp3_bitMask0 = tmp3_bitMask0 | 16;
          break;
        case 5:
          tmp9_local5 = tmp10_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 5, tmp11_cached[5].get_value_j01efc_k$(), tmp9_local5);
          tmp3_bitMask0 = tmp3_bitMask0 | 32;
          break;
        default:
          throw UnknownFieldException_init_$Create$(tmp2_index);
      }
    }
  tmp10_input.endStructure_1xqz0n_k$(tmp0_desc);
  return BindingExplanation_init_$Create$(tmp3_bitMask0, tmp4_local0, tmp5_local1, tmp6_local2, tmp7_local3, tmp8_local4, tmp9_local5, null);
};
protoOf($serializer).get_descriptor_wjt6a0_k$ = function () {
  return this.descriptor_1;
};
protoOf($serializer).childSerializers_5ghqw5_k$ = function () {
  var tmp0_cached = Companion_getInstance().$childSerializers_1;
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  return [$serializer_getInstance_2(), tmp0_cached[1].get_value_j01efc_k$(), tmp0_cached[2].get_value_j01efc_k$(), tmp0_cached[3].get_value_j01efc_k$(), get_nullable($serializer_getInstance_1()), tmp0_cached[5].get_value_j01efc_k$()];
};
var $serializer_instance;
function $serializer_getInstance() {
  if ($serializer_instance == null)
    new $serializer();
  return $serializer_instance;
}
function BindingExplanation_init_$Init$(seen0, context, phase, outcome, candidates, request, details, serializationConstructorMarker, $this) {
  if (!(15 === (15 & seen0))) {
    throwMissingFieldException(seen0, 15, $serializer_getInstance().descriptor_1);
  }
  $this.context_1 = context;
  $this.phase_1 = phase;
  $this.outcome_1 = outcome;
  $this.candidates_1 = candidates;
  if (0 === (seen0 & 16))
    $this.request_1 = null;
  else
    $this.request_1 = request;
  if (0 === (seen0 & 32))
    $this.details_1 = emptyList();
  else
    $this.details_1 = details;
  return $this;
}
function BindingExplanation_init_$Create$(seen0, context, phase, outcome, candidates, request, details, serializationConstructorMarker) {
  return BindingExplanation_init_$Init$(seen0, context, phase, outcome, candidates, request, details, serializationConstructorMarker, objectCreate(protoOf(BindingExplanation)));
}
function BindingExplanation(context, phase, outcome, candidates, request, details) {
  Companion_getInstance();
  request = request === VOID ? null : request;
  details = details === VOID ? emptyList() : details;
  this.context_1 = context;
  this.phase_1 = phase;
  this.outcome_1 = outcome;
  this.candidates_1 = candidates;
  this.request_1 = request;
  this.details_1 = details;
}
protoOf(BindingExplanation).toString = function () {
  return 'BindingExplanation(context=' + this.context_1.toString() + ', phase=' + this.phase_1.toString() + ', outcome=' + this.outcome_1.toString() + ', candidates=' + toString(this.candidates_1) + ', request=' + toString_0(this.request_1) + ', details=' + toString(this.details_1) + ')';
};
protoOf(BindingExplanation).hashCode = function () {
  var result = this.context_1.hashCode();
  result = imul(result, 31) + this.phase_1.hashCode() | 0;
  result = imul(result, 31) + this.outcome_1.hashCode() | 0;
  result = imul(result, 31) + hashCode(this.candidates_1) | 0;
  result = imul(result, 31) + (this.request_1 == null ? 0 : this.request_1.hashCode()) | 0;
  result = imul(result, 31) + hashCode(this.details_1) | 0;
  return result;
};
protoOf(BindingExplanation).equals = function (other) {
  if (this === other)
    return true;
  if (!(other instanceof BindingExplanation))
    return false;
  if (!this.context_1.equals(other.context_1))
    return false;
  if (!this.phase_1.equals(other.phase_1))
    return false;
  if (!this.outcome_1.equals(other.outcome_1))
    return false;
  if (!equals(this.candidates_1, other.candidates_1))
    return false;
  if (!equals(this.request_1, other.request_1))
    return false;
  if (!equals(this.details_1, other.details_1))
    return false;
  return true;
};
function BindingExplanationCandidate$Companion$$childSerializers$_anonymous__fs20it() {
  return Companion_getInstance_2().serializer_9w0wvi_k$();
}
function BindingExplanationCandidate$Companion$$childSerializers$_anonymous__fs20it_0() {
  return Companion_getInstance_4().serializer_9w0wvi_k$();
}
function BindingExplanationCandidate$Companion$$childSerializers$_anonymous__fs20it_1() {
  return new ArrayListSerializer($serializer_getInstance_3());
}
function BindingExplanationCandidate$Companion$$childSerializers$_anonymous__fs20it_2() {
  return new ArrayListSerializer(StringSerializer_getInstance());
}
function Companion_0() {
  Companion_instance_1 = this;
  var tmp = this;
  var tmp_0 = LazyThreadSafetyMode_PUBLICATION_getInstance();
  var tmp_1 = lazy(tmp_0, BindingExplanationCandidate$Companion$$childSerializers$_anonymous__fs20it);
  var tmp_2 = LazyThreadSafetyMode_PUBLICATION_getInstance();
  var tmp_3 = lazy(tmp_2, BindingExplanationCandidate$Companion$$childSerializers$_anonymous__fs20it_0);
  var tmp_4 = LazyThreadSafetyMode_PUBLICATION_getInstance();
  var tmp_5 = lazy(tmp_4, BindingExplanationCandidate$Companion$$childSerializers$_anonymous__fs20it_1);
  var tmp_6 = LazyThreadSafetyMode_PUBLICATION_getInstance();
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  tmp.$childSerializers_1 = [null, null, tmp_1, tmp_3, null, null, tmp_5, lazy(tmp_6, BindingExplanationCandidate$Companion$$childSerializers$_anonymous__fs20it_2)];
}
var Companion_instance_1;
function Companion_getInstance_0() {
  if (Companion_instance_1 == null)
    new Companion_0();
  return Companion_instance_1;
}
function $serializer_0() {
  $serializer_instance_0 = this;
  var tmp0_serialDesc = new PluginGeneratedSerialDescriptor('dev.zacsweers.metro.compiler.graph.explanation.BindingExplanationCandidate', this, 8);
  tmp0_serialDesc.addElement_5pzumi_k$('id', false);
  tmp0_serialDesc.addElement_5pzumi_k$('key', false);
  tmp0_serialDesc.addElement_5pzumi_k$('status', false);
  tmp0_serialDesc.addElement_5pzumi_k$('reason', false);
  tmp0_serialDesc.addElement_5pzumi_k$('declaration', true);
  tmp0_serialDesc.addElement_5pzumi_k$('ownerGraphId', true);
  tmp0_serialDesc.addElement_5pzumi_k$('relatedDeclarations', true);
  tmp0_serialDesc.addElement_5pzumi_k$('details', true);
  this.descriptor_1 = tmp0_serialDesc;
}
protoOf($serializer_0).serialize_bigj1h_k$ = function (encoder, value) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_output = encoder.beginStructure_yljocp_k$(tmp0_desc);
  var tmp2_cached = Companion_getInstance_0().$childSerializers_1;
  tmp1_output.encodeStringElement_1n5wu2_k$(tmp0_desc, 0, value.id_1);
  tmp1_output.encodeStringElement_1n5wu2_k$(tmp0_desc, 1, value.key_1);
  tmp1_output.encodeSerializableElement_isqxcl_k$(tmp0_desc, 2, tmp2_cached[2].get_value_j01efc_k$(), value.status_1);
  tmp1_output.encodeSerializableElement_isqxcl_k$(tmp0_desc, 3, tmp2_cached[3].get_value_j01efc_k$(), value.reason_1);
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 4) ? true : !(value.declaration_1 == null)) {
    tmp1_output.encodeNullableSerializableElement_5lquiv_k$(tmp0_desc, 4, $serializer_getInstance_3(), value.declaration_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 5) ? true : !(value.ownerGraphId_1 == null)) {
    tmp1_output.encodeNullableSerializableElement_5lquiv_k$(tmp0_desc, 5, StringSerializer_getInstance(), value.ownerGraphId_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 6) ? true : !equals(value.relatedDeclarations_1, emptyList())) {
    tmp1_output.encodeSerializableElement_isqxcl_k$(tmp0_desc, 6, tmp2_cached[6].get_value_j01efc_k$(), value.relatedDeclarations_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 7) ? true : !equals(value.details_1, emptyList())) {
    tmp1_output.encodeSerializableElement_isqxcl_k$(tmp0_desc, 7, tmp2_cached[7].get_value_j01efc_k$(), value.details_1);
  }
  tmp1_output.endStructure_1xqz0n_k$(tmp0_desc);
};
protoOf($serializer_0).serialize_5ase3y_k$ = function (encoder, value) {
  return this.serialize_bigj1h_k$(encoder, value instanceof BindingExplanationCandidate ? value : THROW_CCE());
};
protoOf($serializer_0).deserialize_sy6x50_k$ = function (decoder) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_flag = true;
  var tmp2_index = 0;
  var tmp3_bitMask0 = 0;
  var tmp4_local0 = null;
  var tmp5_local1 = null;
  var tmp6_local2 = null;
  var tmp7_local3 = null;
  var tmp8_local4 = null;
  var tmp9_local5 = null;
  var tmp10_local6 = null;
  var tmp11_local7 = null;
  var tmp12_input = decoder.beginStructure_yljocp_k$(tmp0_desc);
  var tmp13_cached = Companion_getInstance_0().$childSerializers_1;
  if (tmp12_input.decodeSequentially_xlblqy_k$()) {
    tmp4_local0 = tmp12_input.decodeStringElement_3oenpg_k$(tmp0_desc, 0);
    tmp3_bitMask0 = tmp3_bitMask0 | 1;
    tmp5_local1 = tmp12_input.decodeStringElement_3oenpg_k$(tmp0_desc, 1);
    tmp3_bitMask0 = tmp3_bitMask0 | 2;
    tmp6_local2 = tmp12_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 2, tmp13_cached[2].get_value_j01efc_k$(), tmp6_local2);
    tmp3_bitMask0 = tmp3_bitMask0 | 4;
    tmp7_local3 = tmp12_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 3, tmp13_cached[3].get_value_j01efc_k$(), tmp7_local3);
    tmp3_bitMask0 = tmp3_bitMask0 | 8;
    tmp8_local4 = tmp12_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 4, $serializer_getInstance_3(), tmp8_local4);
    tmp3_bitMask0 = tmp3_bitMask0 | 16;
    tmp9_local5 = tmp12_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 5, StringSerializer_getInstance(), tmp9_local5);
    tmp3_bitMask0 = tmp3_bitMask0 | 32;
    tmp10_local6 = tmp12_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 6, tmp13_cached[6].get_value_j01efc_k$(), tmp10_local6);
    tmp3_bitMask0 = tmp3_bitMask0 | 64;
    tmp11_local7 = tmp12_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 7, tmp13_cached[7].get_value_j01efc_k$(), tmp11_local7);
    tmp3_bitMask0 = tmp3_bitMask0 | 128;
  } else
    while (tmp1_flag) {
      tmp2_index = tmp12_input.decodeElementIndex_bstkhp_k$(tmp0_desc);
      switch (tmp2_index) {
        case -1:
          tmp1_flag = false;
          break;
        case 0:
          tmp4_local0 = tmp12_input.decodeStringElement_3oenpg_k$(tmp0_desc, 0);
          tmp3_bitMask0 = tmp3_bitMask0 | 1;
          break;
        case 1:
          tmp5_local1 = tmp12_input.decodeStringElement_3oenpg_k$(tmp0_desc, 1);
          tmp3_bitMask0 = tmp3_bitMask0 | 2;
          break;
        case 2:
          tmp6_local2 = tmp12_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 2, tmp13_cached[2].get_value_j01efc_k$(), tmp6_local2);
          tmp3_bitMask0 = tmp3_bitMask0 | 4;
          break;
        case 3:
          tmp7_local3 = tmp12_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 3, tmp13_cached[3].get_value_j01efc_k$(), tmp7_local3);
          tmp3_bitMask0 = tmp3_bitMask0 | 8;
          break;
        case 4:
          tmp8_local4 = tmp12_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 4, $serializer_getInstance_3(), tmp8_local4);
          tmp3_bitMask0 = tmp3_bitMask0 | 16;
          break;
        case 5:
          tmp9_local5 = tmp12_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 5, StringSerializer_getInstance(), tmp9_local5);
          tmp3_bitMask0 = tmp3_bitMask0 | 32;
          break;
        case 6:
          tmp10_local6 = tmp12_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 6, tmp13_cached[6].get_value_j01efc_k$(), tmp10_local6);
          tmp3_bitMask0 = tmp3_bitMask0 | 64;
          break;
        case 7:
          tmp11_local7 = tmp12_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 7, tmp13_cached[7].get_value_j01efc_k$(), tmp11_local7);
          tmp3_bitMask0 = tmp3_bitMask0 | 128;
          break;
        default:
          throw UnknownFieldException_init_$Create$(tmp2_index);
      }
    }
  tmp12_input.endStructure_1xqz0n_k$(tmp0_desc);
  return BindingExplanationCandidate_init_$Create$(tmp3_bitMask0, tmp4_local0, tmp5_local1, tmp6_local2, tmp7_local3, tmp8_local4, tmp9_local5, tmp10_local6, tmp11_local7, null);
};
protoOf($serializer_0).get_descriptor_wjt6a0_k$ = function () {
  return this.descriptor_1;
};
protoOf($serializer_0).childSerializers_5ghqw5_k$ = function () {
  var tmp0_cached = Companion_getInstance_0().$childSerializers_1;
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  return [StringSerializer_getInstance(), StringSerializer_getInstance(), tmp0_cached[2].get_value_j01efc_k$(), tmp0_cached[3].get_value_j01efc_k$(), get_nullable($serializer_getInstance_3()), get_nullable(StringSerializer_getInstance()), tmp0_cached[6].get_value_j01efc_k$(), tmp0_cached[7].get_value_j01efc_k$()];
};
var $serializer_instance_0;
function $serializer_getInstance_0() {
  if ($serializer_instance_0 == null)
    new $serializer_0();
  return $serializer_instance_0;
}
function BindingExplanationCandidate_init_$Init$(seen0, id, key, status, reason, declaration, ownerGraphId, relatedDeclarations, details, serializationConstructorMarker, $this) {
  if (!(15 === (15 & seen0))) {
    throwMissingFieldException(seen0, 15, $serializer_getInstance_0().descriptor_1);
  }
  $this.id_1 = id;
  $this.key_1 = key;
  $this.status_1 = status;
  $this.reason_1 = reason;
  if (0 === (seen0 & 16))
    $this.declaration_1 = null;
  else
    $this.declaration_1 = declaration;
  if (0 === (seen0 & 32))
    $this.ownerGraphId_1 = null;
  else
    $this.ownerGraphId_1 = ownerGraphId;
  if (0 === (seen0 & 64))
    $this.relatedDeclarations_1 = emptyList();
  else
    $this.relatedDeclarations_1 = relatedDeclarations;
  if (0 === (seen0 & 128))
    $this.details_1 = emptyList();
  else
    $this.details_1 = details;
  return $this;
}
function BindingExplanationCandidate_init_$Create$(seen0, id, key, status, reason, declaration, ownerGraphId, relatedDeclarations, details, serializationConstructorMarker) {
  return BindingExplanationCandidate_init_$Init$(seen0, id, key, status, reason, declaration, ownerGraphId, relatedDeclarations, details, serializationConstructorMarker, objectCreate(protoOf(BindingExplanationCandidate)));
}
function BindingExplanationCandidate() {
}
protoOf(BindingExplanationCandidate).toString = function () {
  return 'BindingExplanationCandidate(id=' + this.id_1 + ', key=' + this.key_1 + ', status=' + this.status_1.toString() + ', reason=' + this.reason_1.toString() + ', declaration=' + toString_0(this.declaration_1) + ', ownerGraphId=' + this.ownerGraphId_1 + ', relatedDeclarations=' + toString(this.relatedDeclarations_1) + ', details=' + toString(this.details_1) + ')';
};
protoOf(BindingExplanationCandidate).hashCode = function () {
  var result = getStringHashCode(this.id_1);
  result = imul(result, 31) + getStringHashCode(this.key_1) | 0;
  result = imul(result, 31) + this.status_1.hashCode() | 0;
  result = imul(result, 31) + this.reason_1.hashCode() | 0;
  result = imul(result, 31) + (this.declaration_1 == null ? 0 : this.declaration_1.hashCode()) | 0;
  result = imul(result, 31) + (this.ownerGraphId_1 == null ? 0 : getStringHashCode(this.ownerGraphId_1)) | 0;
  result = imul(result, 31) + hashCode(this.relatedDeclarations_1) | 0;
  result = imul(result, 31) + hashCode(this.details_1) | 0;
  return result;
};
protoOf(BindingExplanationCandidate).equals = function (other) {
  if (this === other)
    return true;
  if (!(other instanceof BindingExplanationCandidate))
    return false;
  if (!(this.id_1 === other.id_1))
    return false;
  if (!(this.key_1 === other.key_1))
    return false;
  if (!this.status_1.equals(other.status_1))
    return false;
  if (!this.reason_1.equals(other.reason_1))
    return false;
  if (!equals(this.declaration_1, other.declaration_1))
    return false;
  if (!(this.ownerGraphId_1 == other.ownerGraphId_1))
    return false;
  if (!equals(this.relatedDeclarations_1, other.relatedDeclarations_1))
    return false;
  if (!equals(this.details_1, other.details_1))
    return false;
  return true;
};
function $serializer_1() {
  $serializer_instance_1 = this;
  var tmp0_serialDesc = new PluginGeneratedSerialDescriptor('dev.zacsweers.metro.compiler.graph.explanation.BindingExplanationRequest', this, 4);
  tmp0_serialDesc.addElement_5pzumi_k$('key', false);
  tmp0_serialDesc.addElement_5pzumi_k$('declaration', true);
  tmp0_serialDesc.addElement_5pzumi_k$('hasDefault', true);
  tmp0_serialDesc.addElement_5pzumi_k$('isOptional', true);
  this.descriptor_1 = tmp0_serialDesc;
}
protoOf($serializer_1).serialize_rdm8y7_k$ = function (encoder, value) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_output = encoder.beginStructure_yljocp_k$(tmp0_desc);
  tmp1_output.encodeStringElement_1n5wu2_k$(tmp0_desc, 0, value.key_1);
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 1) ? true : !(value.declaration_1 == null)) {
    tmp1_output.encodeNullableSerializableElement_5lquiv_k$(tmp0_desc, 1, $serializer_getInstance_3(), value.declaration_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 2) ? true : !(value.hasDefault_1 === false)) {
    tmp1_output.encodeBooleanElement_ydht7q_k$(tmp0_desc, 2, value.hasDefault_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 3) ? true : !(value.isOptional_1 === false)) {
    tmp1_output.encodeBooleanElement_ydht7q_k$(tmp0_desc, 3, value.isOptional_1);
  }
  tmp1_output.endStructure_1xqz0n_k$(tmp0_desc);
};
protoOf($serializer_1).serialize_5ase3y_k$ = function (encoder, value) {
  return this.serialize_rdm8y7_k$(encoder, value instanceof BindingExplanationRequest ? value : THROW_CCE());
};
protoOf($serializer_1).deserialize_sy6x50_k$ = function (decoder) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_flag = true;
  var tmp2_index = 0;
  var tmp3_bitMask0 = 0;
  var tmp4_local0 = null;
  var tmp5_local1 = null;
  var tmp6_local2 = false;
  var tmp7_local3 = false;
  var tmp8_input = decoder.beginStructure_yljocp_k$(tmp0_desc);
  if (tmp8_input.decodeSequentially_xlblqy_k$()) {
    tmp4_local0 = tmp8_input.decodeStringElement_3oenpg_k$(tmp0_desc, 0);
    tmp3_bitMask0 = tmp3_bitMask0 | 1;
    tmp5_local1 = tmp8_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 1, $serializer_getInstance_3(), tmp5_local1);
    tmp3_bitMask0 = tmp3_bitMask0 | 2;
    tmp6_local2 = tmp8_input.decodeBooleanElement_vuyhtj_k$(tmp0_desc, 2);
    tmp3_bitMask0 = tmp3_bitMask0 | 4;
    tmp7_local3 = tmp8_input.decodeBooleanElement_vuyhtj_k$(tmp0_desc, 3);
    tmp3_bitMask0 = tmp3_bitMask0 | 8;
  } else
    while (tmp1_flag) {
      tmp2_index = tmp8_input.decodeElementIndex_bstkhp_k$(tmp0_desc);
      switch (tmp2_index) {
        case -1:
          tmp1_flag = false;
          break;
        case 0:
          tmp4_local0 = tmp8_input.decodeStringElement_3oenpg_k$(tmp0_desc, 0);
          tmp3_bitMask0 = tmp3_bitMask0 | 1;
          break;
        case 1:
          tmp5_local1 = tmp8_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 1, $serializer_getInstance_3(), tmp5_local1);
          tmp3_bitMask0 = tmp3_bitMask0 | 2;
          break;
        case 2:
          tmp6_local2 = tmp8_input.decodeBooleanElement_vuyhtj_k$(tmp0_desc, 2);
          tmp3_bitMask0 = tmp3_bitMask0 | 4;
          break;
        case 3:
          tmp7_local3 = tmp8_input.decodeBooleanElement_vuyhtj_k$(tmp0_desc, 3);
          tmp3_bitMask0 = tmp3_bitMask0 | 8;
          break;
        default:
          throw UnknownFieldException_init_$Create$(tmp2_index);
      }
    }
  tmp8_input.endStructure_1xqz0n_k$(tmp0_desc);
  return BindingExplanationRequest_init_$Create$(tmp3_bitMask0, tmp4_local0, tmp5_local1, tmp6_local2, tmp7_local3, null);
};
protoOf($serializer_1).get_descriptor_wjt6a0_k$ = function () {
  return this.descriptor_1;
};
protoOf($serializer_1).childSerializers_5ghqw5_k$ = function () {
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  return [StringSerializer_getInstance(), get_nullable($serializer_getInstance_3()), BooleanSerializer_getInstance(), BooleanSerializer_getInstance()];
};
var $serializer_instance_1;
function $serializer_getInstance_1() {
  if ($serializer_instance_1 == null)
    new $serializer_1();
  return $serializer_instance_1;
}
function BindingExplanationRequest_init_$Init$(seen0, key, declaration, hasDefault, isOptional, serializationConstructorMarker, $this) {
  if (!(1 === (1 & seen0))) {
    throwMissingFieldException(seen0, 1, $serializer_getInstance_1().descriptor_1);
  }
  $this.key_1 = key;
  if (0 === (seen0 & 2))
    $this.declaration_1 = null;
  else
    $this.declaration_1 = declaration;
  if (0 === (seen0 & 4))
    $this.hasDefault_1 = false;
  else
    $this.hasDefault_1 = hasDefault;
  if (0 === (seen0 & 8))
    $this.isOptional_1 = false;
  else
    $this.isOptional_1 = isOptional;
  return $this;
}
function BindingExplanationRequest_init_$Create$(seen0, key, declaration, hasDefault, isOptional, serializationConstructorMarker) {
  return BindingExplanationRequest_init_$Init$(seen0, key, declaration, hasDefault, isOptional, serializationConstructorMarker, objectCreate(protoOf(BindingExplanationRequest)));
}
function BindingExplanationRequest() {
}
protoOf(BindingExplanationRequest).toString = function () {
  return 'BindingExplanationRequest(key=' + this.key_1 + ', declaration=' + toString_0(this.declaration_1) + ', hasDefault=' + this.hasDefault_1 + ', isOptional=' + this.isOptional_1 + ')';
};
protoOf(BindingExplanationRequest).hashCode = function () {
  var result = getStringHashCode(this.key_1);
  result = imul(result, 31) + (this.declaration_1 == null ? 0 : this.declaration_1.hashCode()) | 0;
  result = imul(result, 31) + getBooleanHashCode(this.hasDefault_1) | 0;
  result = imul(result, 31) + getBooleanHashCode(this.isOptional_1) | 0;
  return result;
};
protoOf(BindingExplanationRequest).equals = function (other) {
  if (this === other)
    return true;
  if (!(other instanceof BindingExplanationRequest))
    return false;
  if (!(this.key_1 === other.key_1))
    return false;
  if (!equals(this.declaration_1, other.declaration_1))
    return false;
  if (!(this.hasDefault_1 === other.hasDefault_1))
    return false;
  if (!(this.isOptional_1 === other.isOptional_1))
    return false;
  return true;
};
function _get_$cachedSerializer__te6jhj($this) {
  return $this.$cachedSerializer$delegate_1.get_value_j01efc_k$();
}
function BindingExplanationOutcome$Companion$_anonymous__au64nj() {
  var tmp = values();
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  var tmp_0 = ['selected', 'missing', 'conflict', 'invalid_request', 'filtered'];
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  var tmp$ret$3 = [null, null, null, null, null];
  return createAnnotatedEnumSerializer('dev.zacsweers.metro.compiler.graph.explanation.BindingExplanationOutcome', tmp, tmp_0, tmp$ret$3, null);
}
var static_init_called;
function static_init() {
  if (static_init_called)
    return Unit_instance;
  static_init_called = true;
  BindingExplanationOutcome_SELECTED_instance = new BindingExplanationOutcome('SELECTED', 0);
  BindingExplanationOutcome_MISSING_instance = new BindingExplanationOutcome('MISSING', 1);
  BindingExplanationOutcome_CONFLICT_instance = new BindingExplanationOutcome('CONFLICT', 2);
  BindingExplanationOutcome_INVALID_REQUEST_instance = new BindingExplanationOutcome('INVALID_REQUEST', 3);
  BindingExplanationOutcome_FILTERED_instance = new BindingExplanationOutcome('FILTERED', 4);
  if (Companion_instance_2 == null) {
    Companion_instance;
    new Companion_1();
  }
}
var BindingExplanationOutcome_SELECTED_instance;
var BindingExplanationOutcome_MISSING_instance;
var BindingExplanationOutcome_CONFLICT_instance;
var BindingExplanationOutcome_INVALID_REQUEST_instance;
var BindingExplanationOutcome_FILTERED_instance;
function values() {
  static_init();
  return [BindingExplanationOutcome_SELECTED_getInstance(), BindingExplanationOutcome_MISSING_getInstance(), BindingExplanationOutcome_CONFLICT_getInstance(), BindingExplanationOutcome_INVALID_REQUEST_getInstance(), BindingExplanationOutcome_FILTERED_getInstance()];
}
function Companion_1() {
  Companion_instance_2 = this;
  var tmp = this;
  var tmp_0 = LazyThreadSafetyMode_PUBLICATION_getInstance();
  tmp.$cachedSerializer$delegate_1 = lazy(tmp_0, BindingExplanationOutcome$Companion$_anonymous__au64nj);
}
protoOf(Companion_1).serializer_9w0wvi_k$ = function () {
  return _get_$cachedSerializer__te6jhj(this);
};
protoOf(Companion_1).serializer_nv39qc_k$ = function (typeParamsSerializers) {
  return this.serializer_9w0wvi_k$();
};
var Companion_instance_2;
function Companion_getInstance_1() {
  static_init();
  return Companion_instance_2;
}
function BindingExplanationOutcome(name, ordinal) {
  Enum.call(this, name, ordinal);
}
function _get_$cachedSerializer__te6jhj_0($this) {
  return $this.$cachedSerializer$delegate_1.get_value_j01efc_k$();
}
function BindingCandidateStatus$Companion$_anonymous__gox0n5() {
  var tmp = values_0();
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  var tmp_0 = ['selected', 'rejected', 'conflict'];
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  var tmp$ret$3 = [null, null, null];
  return createAnnotatedEnumSerializer('dev.zacsweers.metro.compiler.graph.explanation.BindingCandidateStatus', tmp, tmp_0, tmp$ret$3, null);
}
var static_init_called_0;
function static_init_0() {
  if (static_init_called_0)
    return Unit_instance;
  static_init_called_0 = true;
  BindingCandidateStatus_SELECTED_instance = new BindingCandidateStatus('SELECTED', 0);
  BindingCandidateStatus_REJECTED_instance = new BindingCandidateStatus('REJECTED', 1);
  BindingCandidateStatus_CONFLICT_instance = new BindingCandidateStatus('CONFLICT', 2);
  if (Companion_instance_3 == null) {
    Companion_instance;
    new Companion_2();
  }
}
var BindingCandidateStatus_SELECTED_instance;
var BindingCandidateStatus_REJECTED_instance;
var BindingCandidateStatus_CONFLICT_instance;
function values_0() {
  static_init_0();
  return [BindingCandidateStatus_SELECTED_getInstance(), BindingCandidateStatus_REJECTED_getInstance(), BindingCandidateStatus_CONFLICT_getInstance()];
}
function Companion_2() {
  Companion_instance_3 = this;
  var tmp = this;
  var tmp_0 = LazyThreadSafetyMode_PUBLICATION_getInstance();
  tmp.$cachedSerializer$delegate_1 = lazy(tmp_0, BindingCandidateStatus$Companion$_anonymous__gox0n5);
}
protoOf(Companion_2).serializer_9w0wvi_k$ = function () {
  return _get_$cachedSerializer__te6jhj_0(this);
};
protoOf(Companion_2).serializer_nv39qc_k$ = function (typeParamsSerializers) {
  return this.serializer_9w0wvi_k$();
};
var Companion_instance_3;
function Companion_getInstance_2() {
  static_init_0();
  return Companion_instance_3;
}
function BindingCandidateStatus(name, ordinal) {
  Enum.call(this, name, ordinal);
}
function $serializer_2() {
  $serializer_instance_2 = this;
  var tmp0_serialDesc = new PluginGeneratedSerialDescriptor('dev.zacsweers.metro.compiler.graph.explanation.BindingExplanationContext', this, 2);
  tmp0_serialDesc.addElement_5pzumi_k$('id', false);
  tmp0_serialDesc.addElement_5pzumi_k$('label', false);
  this.descriptor_1 = tmp0_serialDesc;
}
protoOf($serializer_2).serialize_aqbfwx_k$ = function (encoder, value) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_output = encoder.beginStructure_yljocp_k$(tmp0_desc);
  tmp1_output.encodeStringElement_1n5wu2_k$(tmp0_desc, 0, value.id_1);
  tmp1_output.encodeStringElement_1n5wu2_k$(tmp0_desc, 1, value.label_1);
  tmp1_output.endStructure_1xqz0n_k$(tmp0_desc);
};
protoOf($serializer_2).serialize_5ase3y_k$ = function (encoder, value) {
  return this.serialize_aqbfwx_k$(encoder, value instanceof BindingExplanationContext ? value : THROW_CCE());
};
protoOf($serializer_2).deserialize_sy6x50_k$ = function (decoder) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_flag = true;
  var tmp2_index = 0;
  var tmp3_bitMask0 = 0;
  var tmp4_local0 = null;
  var tmp5_local1 = null;
  var tmp6_input = decoder.beginStructure_yljocp_k$(tmp0_desc);
  if (tmp6_input.decodeSequentially_xlblqy_k$()) {
    tmp4_local0 = tmp6_input.decodeStringElement_3oenpg_k$(tmp0_desc, 0);
    tmp3_bitMask0 = tmp3_bitMask0 | 1;
    tmp5_local1 = tmp6_input.decodeStringElement_3oenpg_k$(tmp0_desc, 1);
    tmp3_bitMask0 = tmp3_bitMask0 | 2;
  } else
    while (tmp1_flag) {
      tmp2_index = tmp6_input.decodeElementIndex_bstkhp_k$(tmp0_desc);
      switch (tmp2_index) {
        case -1:
          tmp1_flag = false;
          break;
        case 0:
          tmp4_local0 = tmp6_input.decodeStringElement_3oenpg_k$(tmp0_desc, 0);
          tmp3_bitMask0 = tmp3_bitMask0 | 1;
          break;
        case 1:
          tmp5_local1 = tmp6_input.decodeStringElement_3oenpg_k$(tmp0_desc, 1);
          tmp3_bitMask0 = tmp3_bitMask0 | 2;
          break;
        default:
          throw UnknownFieldException_init_$Create$(tmp2_index);
      }
    }
  tmp6_input.endStructure_1xqz0n_k$(tmp0_desc);
  return BindingExplanationContext_init_$Create$(tmp3_bitMask0, tmp4_local0, tmp5_local1, null);
};
protoOf($serializer_2).get_descriptor_wjt6a0_k$ = function () {
  return this.descriptor_1;
};
protoOf($serializer_2).childSerializers_5ghqw5_k$ = function () {
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  return [StringSerializer_getInstance(), StringSerializer_getInstance()];
};
var $serializer_instance_2;
function $serializer_getInstance_2() {
  if ($serializer_instance_2 == null)
    new $serializer_2();
  return $serializer_instance_2;
}
function BindingExplanationContext_init_$Init$(seen0, id, label, serializationConstructorMarker, $this) {
  if (!(3 === (3 & seen0))) {
    throwMissingFieldException(seen0, 3, $serializer_getInstance_2().descriptor_1);
  }
  $this.id_1 = id;
  $this.label_1 = label;
  return $this;
}
function BindingExplanationContext_init_$Create$(seen0, id, label, serializationConstructorMarker) {
  return BindingExplanationContext_init_$Init$(seen0, id, label, serializationConstructorMarker, objectCreate(protoOf(BindingExplanationContext)));
}
function BindingExplanationContext() {
}
protoOf(BindingExplanationContext).toString = function () {
  return 'BindingExplanationContext(id=' + this.id_1 + ', label=' + this.label_1 + ')';
};
protoOf(BindingExplanationContext).hashCode = function () {
  var result = getStringHashCode(this.id_1);
  result = imul(result, 31) + getStringHashCode(this.label_1) | 0;
  return result;
};
protoOf(BindingExplanationContext).equals = function (other) {
  if (this === other)
    return true;
  if (!(other instanceof BindingExplanationContext))
    return false;
  if (!(this.id_1 === other.id_1))
    return false;
  if (!(this.label_1 === other.label_1))
    return false;
  return true;
};
function _get_$cachedSerializer__te6jhj_1($this) {
  return $this.$cachedSerializer$delegate_1.get_value_j01efc_k$();
}
function BindingExplanationPhase$Companion$_anonymous__5k9tag() {
  var tmp = values_1();
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  var tmp_0 = ['lookup', 'registration', 'candidate_filtering'];
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  var tmp$ret$3 = [null, null, null];
  return createAnnotatedEnumSerializer('dev.zacsweers.metro.compiler.graph.explanation.BindingExplanationPhase', tmp, tmp_0, tmp$ret$3, null);
}
var static_init_called_1;
function static_init_1() {
  if (static_init_called_1)
    return Unit_instance;
  static_init_called_1 = true;
  BindingExplanationPhase_LOOKUP_instance = new BindingExplanationPhase('LOOKUP', 0);
  BindingExplanationPhase_REGISTRATION_instance = new BindingExplanationPhase('REGISTRATION', 1);
  BindingExplanationPhase_CANDIDATE_FILTERING_instance = new BindingExplanationPhase('CANDIDATE_FILTERING', 2);
  if (Companion_instance_4 == null) {
    Companion_instance;
    new Companion_3();
  }
}
var BindingExplanationPhase_LOOKUP_instance;
var BindingExplanationPhase_REGISTRATION_instance;
var BindingExplanationPhase_CANDIDATE_FILTERING_instance;
function values_1() {
  static_init_1();
  return [BindingExplanationPhase_LOOKUP_getInstance(), BindingExplanationPhase_REGISTRATION_getInstance(), BindingExplanationPhase_CANDIDATE_FILTERING_getInstance()];
}
function Companion_3() {
  Companion_instance_4 = this;
  var tmp = this;
  var tmp_0 = LazyThreadSafetyMode_PUBLICATION_getInstance();
  tmp.$cachedSerializer$delegate_1 = lazy(tmp_0, BindingExplanationPhase$Companion$_anonymous__5k9tag);
}
protoOf(Companion_3).serializer_9w0wvi_k$ = function () {
  return _get_$cachedSerializer__te6jhj_1(this);
};
protoOf(Companion_3).serializer_nv39qc_k$ = function (typeParamsSerializers) {
  return this.serializer_9w0wvi_k$();
};
var Companion_instance_4;
function Companion_getInstance_3() {
  static_init_1();
  return Companion_instance_4;
}
function BindingExplanationPhase(name, ordinal) {
  Enum.call(this, name, ordinal);
}
function $serializer_3() {
  $serializer_instance_3 = this;
  var tmp0_serialDesc = new PluginGeneratedSerialDescriptor('dev.zacsweers.metro.compiler.graph.explanation.BindingDeclaration', this, 3);
  tmp0_serialDesc.addElement_5pzumi_k$('id', false);
  tmp0_serialDesc.addElement_5pzumi_k$('label', false);
  tmp0_serialDesc.addElement_5pzumi_k$('source', true);
  this.descriptor_1 = tmp0_serialDesc;
}
protoOf($serializer_3).serialize_jwvxll_k$ = function (encoder, value) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_output = encoder.beginStructure_yljocp_k$(tmp0_desc);
  tmp1_output.encodeStringElement_1n5wu2_k$(tmp0_desc, 0, value.id_1);
  tmp1_output.encodeStringElement_1n5wu2_k$(tmp0_desc, 1, value.label_1);
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 2) ? true : !(value.source_1 == null)) {
    tmp1_output.encodeNullableSerializableElement_5lquiv_k$(tmp0_desc, 2, $serializer_getInstance_4(), value.source_1);
  }
  tmp1_output.endStructure_1xqz0n_k$(tmp0_desc);
};
protoOf($serializer_3).serialize_5ase3y_k$ = function (encoder, value) {
  return this.serialize_jwvxll_k$(encoder, value instanceof BindingDeclaration ? value : THROW_CCE());
};
protoOf($serializer_3).deserialize_sy6x50_k$ = function (decoder) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_flag = true;
  var tmp2_index = 0;
  var tmp3_bitMask0 = 0;
  var tmp4_local0 = null;
  var tmp5_local1 = null;
  var tmp6_local2 = null;
  var tmp7_input = decoder.beginStructure_yljocp_k$(tmp0_desc);
  if (tmp7_input.decodeSequentially_xlblqy_k$()) {
    tmp4_local0 = tmp7_input.decodeStringElement_3oenpg_k$(tmp0_desc, 0);
    tmp3_bitMask0 = tmp3_bitMask0 | 1;
    tmp5_local1 = tmp7_input.decodeStringElement_3oenpg_k$(tmp0_desc, 1);
    tmp3_bitMask0 = tmp3_bitMask0 | 2;
    tmp6_local2 = tmp7_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 2, $serializer_getInstance_4(), tmp6_local2);
    tmp3_bitMask0 = tmp3_bitMask0 | 4;
  } else
    while (tmp1_flag) {
      tmp2_index = tmp7_input.decodeElementIndex_bstkhp_k$(tmp0_desc);
      switch (tmp2_index) {
        case -1:
          tmp1_flag = false;
          break;
        case 0:
          tmp4_local0 = tmp7_input.decodeStringElement_3oenpg_k$(tmp0_desc, 0);
          tmp3_bitMask0 = tmp3_bitMask0 | 1;
          break;
        case 1:
          tmp5_local1 = tmp7_input.decodeStringElement_3oenpg_k$(tmp0_desc, 1);
          tmp3_bitMask0 = tmp3_bitMask0 | 2;
          break;
        case 2:
          tmp6_local2 = tmp7_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 2, $serializer_getInstance_4(), tmp6_local2);
          tmp3_bitMask0 = tmp3_bitMask0 | 4;
          break;
        default:
          throw UnknownFieldException_init_$Create$(tmp2_index);
      }
    }
  tmp7_input.endStructure_1xqz0n_k$(tmp0_desc);
  return BindingDeclaration_init_$Create$(tmp3_bitMask0, tmp4_local0, tmp5_local1, tmp6_local2, null);
};
protoOf($serializer_3).get_descriptor_wjt6a0_k$ = function () {
  return this.descriptor_1;
};
protoOf($serializer_3).childSerializers_5ghqw5_k$ = function () {
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  return [StringSerializer_getInstance(), StringSerializer_getInstance(), get_nullable($serializer_getInstance_4())];
};
var $serializer_instance_3;
function $serializer_getInstance_3() {
  if ($serializer_instance_3 == null)
    new $serializer_3();
  return $serializer_instance_3;
}
function BindingDeclaration_init_$Init$(seen0, id, label, source, serializationConstructorMarker, $this) {
  if (!(3 === (3 & seen0))) {
    throwMissingFieldException(seen0, 3, $serializer_getInstance_3().descriptor_1);
  }
  $this.id_1 = id;
  $this.label_1 = label;
  if (0 === (seen0 & 4))
    $this.source_1 = null;
  else
    $this.source_1 = source;
  return $this;
}
function BindingDeclaration_init_$Create$(seen0, id, label, source, serializationConstructorMarker) {
  return BindingDeclaration_init_$Init$(seen0, id, label, source, serializationConstructorMarker, objectCreate(protoOf(BindingDeclaration)));
}
function BindingDeclaration() {
}
protoOf(BindingDeclaration).toString = function () {
  return 'BindingDeclaration(id=' + this.id_1 + ', label=' + this.label_1 + ', source=' + toString_0(this.source_1) + ')';
};
protoOf(BindingDeclaration).hashCode = function () {
  var result = getStringHashCode(this.id_1);
  result = imul(result, 31) + getStringHashCode(this.label_1) | 0;
  result = imul(result, 31) + (this.source_1 == null ? 0 : this.source_1.hashCode()) | 0;
  return result;
};
protoOf(BindingDeclaration).equals = function (other) {
  if (this === other)
    return true;
  if (!(other instanceof BindingDeclaration))
    return false;
  if (!(this.id_1 === other.id_1))
    return false;
  if (!(this.label_1 === other.label_1))
    return false;
  if (!equals(this.source_1, other.source_1))
    return false;
  return true;
};
function $serializer_4() {
  $serializer_instance_4 = this;
  var tmp0_serialDesc = new PluginGeneratedSerialDescriptor('dev.zacsweers.metro.compiler.graph.explanation.BindingSourceLocation', this, 3);
  tmp0_serialDesc.addElement_5pzumi_k$('path', false);
  tmp0_serialDesc.addElement_5pzumi_k$('line', true);
  tmp0_serialDesc.addElement_5pzumi_k$('column', true);
  this.descriptor_1 = tmp0_serialDesc;
}
protoOf($serializer_4).serialize_t8puc5_k$ = function (encoder, value) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_output = encoder.beginStructure_yljocp_k$(tmp0_desc);
  tmp1_output.encodeStringElement_1n5wu2_k$(tmp0_desc, 0, value.path_1);
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 1) ? true : !(value.line_1 == null)) {
    tmp1_output.encodeNullableSerializableElement_5lquiv_k$(tmp0_desc, 1, IntSerializer_getInstance(), value.line_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 2) ? true : !(value.column_1 == null)) {
    tmp1_output.encodeNullableSerializableElement_5lquiv_k$(tmp0_desc, 2, IntSerializer_getInstance(), value.column_1);
  }
  tmp1_output.endStructure_1xqz0n_k$(tmp0_desc);
};
protoOf($serializer_4).serialize_5ase3y_k$ = function (encoder, value) {
  return this.serialize_t8puc5_k$(encoder, value instanceof BindingSourceLocation ? value : THROW_CCE());
};
protoOf($serializer_4).deserialize_sy6x50_k$ = function (decoder) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_flag = true;
  var tmp2_index = 0;
  var tmp3_bitMask0 = 0;
  var tmp4_local0 = null;
  var tmp5_local1 = null;
  var tmp6_local2 = null;
  var tmp7_input = decoder.beginStructure_yljocp_k$(tmp0_desc);
  if (tmp7_input.decodeSequentially_xlblqy_k$()) {
    tmp4_local0 = tmp7_input.decodeStringElement_3oenpg_k$(tmp0_desc, 0);
    tmp3_bitMask0 = tmp3_bitMask0 | 1;
    tmp5_local1 = tmp7_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 1, IntSerializer_getInstance(), tmp5_local1);
    tmp3_bitMask0 = tmp3_bitMask0 | 2;
    tmp6_local2 = tmp7_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 2, IntSerializer_getInstance(), tmp6_local2);
    tmp3_bitMask0 = tmp3_bitMask0 | 4;
  } else
    while (tmp1_flag) {
      tmp2_index = tmp7_input.decodeElementIndex_bstkhp_k$(tmp0_desc);
      switch (tmp2_index) {
        case -1:
          tmp1_flag = false;
          break;
        case 0:
          tmp4_local0 = tmp7_input.decodeStringElement_3oenpg_k$(tmp0_desc, 0);
          tmp3_bitMask0 = tmp3_bitMask0 | 1;
          break;
        case 1:
          tmp5_local1 = tmp7_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 1, IntSerializer_getInstance(), tmp5_local1);
          tmp3_bitMask0 = tmp3_bitMask0 | 2;
          break;
        case 2:
          tmp6_local2 = tmp7_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 2, IntSerializer_getInstance(), tmp6_local2);
          tmp3_bitMask0 = tmp3_bitMask0 | 4;
          break;
        default:
          throw UnknownFieldException_init_$Create$(tmp2_index);
      }
    }
  tmp7_input.endStructure_1xqz0n_k$(tmp0_desc);
  return BindingSourceLocation_init_$Create$(tmp3_bitMask0, tmp4_local0, tmp5_local1, tmp6_local2, null);
};
protoOf($serializer_4).get_descriptor_wjt6a0_k$ = function () {
  return this.descriptor_1;
};
protoOf($serializer_4).childSerializers_5ghqw5_k$ = function () {
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  return [StringSerializer_getInstance(), get_nullable(IntSerializer_getInstance()), get_nullable(IntSerializer_getInstance())];
};
var $serializer_instance_4;
function $serializer_getInstance_4() {
  if ($serializer_instance_4 == null)
    new $serializer_4();
  return $serializer_instance_4;
}
function BindingSourceLocation_init_$Init$(seen0, path, line, column, serializationConstructorMarker, $this) {
  if (!(1 === (1 & seen0))) {
    throwMissingFieldException(seen0, 1, $serializer_getInstance_4().descriptor_1);
  }
  $this.path_1 = path;
  if (0 === (seen0 & 2))
    $this.line_1 = null;
  else
    $this.line_1 = line;
  if (0 === (seen0 & 4))
    $this.column_1 = null;
  else
    $this.column_1 = column;
  return $this;
}
function BindingSourceLocation_init_$Create$(seen0, path, line, column, serializationConstructorMarker) {
  return BindingSourceLocation_init_$Init$(seen0, path, line, column, serializationConstructorMarker, objectCreate(protoOf(BindingSourceLocation)));
}
function BindingSourceLocation() {
}
protoOf(BindingSourceLocation).toString = function () {
  return 'BindingSourceLocation(path=' + this.path_1 + ', line=' + this.line_1 + ', column=' + this.column_1 + ')';
};
protoOf(BindingSourceLocation).hashCode = function () {
  var result = getStringHashCode(this.path_1);
  result = imul(result, 31) + (this.line_1 == null ? 0 : this.line_1) | 0;
  result = imul(result, 31) + (this.column_1 == null ? 0 : this.column_1) | 0;
  return result;
};
protoOf(BindingSourceLocation).equals = function (other) {
  if (this === other)
    return true;
  if (!(other instanceof BindingSourceLocation))
    return false;
  if (!(this.path_1 === other.path_1))
    return false;
  if (!(this.line_1 == other.line_1))
    return false;
  if (!(this.column_1 == other.column_1))
    return false;
  return true;
};
function BindingExplanationOutcome_SELECTED_getInstance() {
  static_init();
  return BindingExplanationOutcome_SELECTED_instance;
}
function BindingExplanationOutcome_MISSING_getInstance() {
  static_init();
  return BindingExplanationOutcome_MISSING_instance;
}
function BindingExplanationOutcome_CONFLICT_getInstance() {
  static_init();
  return BindingExplanationOutcome_CONFLICT_instance;
}
function BindingExplanationOutcome_INVALID_REQUEST_getInstance() {
  static_init();
  return BindingExplanationOutcome_INVALID_REQUEST_instance;
}
function BindingExplanationOutcome_FILTERED_getInstance() {
  static_init();
  return BindingExplanationOutcome_FILTERED_instance;
}
function BindingCandidateStatus_SELECTED_getInstance() {
  static_init_0();
  return BindingCandidateStatus_SELECTED_instance;
}
function BindingCandidateStatus_REJECTED_getInstance() {
  static_init_0();
  return BindingCandidateStatus_REJECTED_instance;
}
function BindingCandidateStatus_CONFLICT_getInstance() {
  static_init_0();
  return BindingCandidateStatus_CONFLICT_instance;
}
function BindingExplanationPhase_LOOKUP_getInstance() {
  static_init_1();
  return BindingExplanationPhase_LOOKUP_instance;
}
function BindingExplanationPhase_REGISTRATION_getInstance() {
  static_init_1();
  return BindingExplanationPhase_REGISTRATION_instance;
}
function BindingExplanationPhase_CANDIDATE_FILTERING_getInstance() {
  static_init_1();
  return BindingExplanationPhase_CANDIDATE_FILTERING_instance;
}
function _get_$cachedSerializer__te6jhj_2($this) {
  return $this.$cachedSerializer$delegate_1.get_value_j01efc_k$();
}
function BindingReason$Companion$_anonymous__b5c2ai() {
  var tmp = values_2();
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  var tmp_0 = ['selected_explicit', 'selected_generated', 'selected_multibinding', 'selected_optional', 'selected_implicit', 'selected_parent', 'assisted_target', 'conflict', 'qualifier_mismatch', 'earlier_optional', 'higher_precedence', 'not_visible', 'contribution_unavailable', 'overridden', 'private_to_graph', 'dynamic_replacement', 'other_graph', 'nearer_input', 'excluded', 'incompatible_scope', 'contribution_scope', 'container_unavailable', 'replaced', 'lower_priority', 'incompatible_map_value'];
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  var tmp$ret$3 = [null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null];
  return createAnnotatedEnumSerializer('dev.zacsweers.metro.compiler.graph.explanation.BindingReason', tmp, tmp_0, tmp$ret$3, null);
}
var static_init_called_2;
function static_init_2() {
  if (static_init_called_2)
    return Unit_instance;
  static_init_called_2 = true;
  BindingReason_SELECTED_EXPLICIT_instance = new BindingReason('SELECTED_EXPLICIT', 0);
  BindingReason_SELECTED_GENERATED_instance = new BindingReason('SELECTED_GENERATED', 1);
  BindingReason_SELECTED_MULTIBINDING_instance = new BindingReason('SELECTED_MULTIBINDING', 2);
  BindingReason_SELECTED_OPTIONAL_instance = new BindingReason('SELECTED_OPTIONAL', 3);
  BindingReason_SELECTED_IMPLICIT_instance = new BindingReason('SELECTED_IMPLICIT', 4);
  BindingReason_SELECTED_PARENT_instance = new BindingReason('SELECTED_PARENT', 5);
  BindingReason_ASSISTED_TARGET_instance = new BindingReason('ASSISTED_TARGET', 6);
  BindingReason_CONFLICT_instance = new BindingReason('CONFLICT', 7);
  BindingReason_QUALIFIER_MISMATCH_instance = new BindingReason('QUALIFIER_MISMATCH', 8);
  BindingReason_EARLIER_OPTIONAL_instance = new BindingReason('EARLIER_OPTIONAL', 9);
  BindingReason_HIGHER_PRECEDENCE_instance = new BindingReason('HIGHER_PRECEDENCE', 10);
  BindingReason_NOT_VISIBLE_instance = new BindingReason('NOT_VISIBLE', 11);
  BindingReason_CONTRIBUTION_UNAVAILABLE_instance = new BindingReason('CONTRIBUTION_UNAVAILABLE', 12);
  BindingReason_OVERRIDDEN_instance = new BindingReason('OVERRIDDEN', 13);
  BindingReason_PRIVATE_TO_GRAPH_instance = new BindingReason('PRIVATE_TO_GRAPH', 14);
  BindingReason_DYNAMIC_REPLACEMENT_instance = new BindingReason('DYNAMIC_REPLACEMENT', 15);
  BindingReason_OTHER_GRAPH_instance = new BindingReason('OTHER_GRAPH', 16);
  BindingReason_NEARER_INPUT_instance = new BindingReason('NEARER_INPUT', 17);
  BindingReason_EXCLUDED_instance = new BindingReason('EXCLUDED', 18);
  BindingReason_INCOMPATIBLE_SCOPE_instance = new BindingReason('INCOMPATIBLE_SCOPE', 19);
  BindingReason_CONTRIBUTION_SCOPE_instance = new BindingReason('CONTRIBUTION_SCOPE', 20);
  BindingReason_CONTAINER_UNAVAILABLE_instance = new BindingReason('CONTAINER_UNAVAILABLE', 21);
  BindingReason_REPLACED_instance = new BindingReason('REPLACED', 22);
  BindingReason_LOWER_PRIORITY_instance = new BindingReason('LOWER_PRIORITY', 23);
  BindingReason_INCOMPATIBLE_MAP_VALUE_instance = new BindingReason('INCOMPATIBLE_MAP_VALUE', 24);
  if (Companion_instance_5 == null) {
    Companion_instance;
    new Companion_4();
  }
}
var BindingReason_SELECTED_EXPLICIT_instance;
var BindingReason_SELECTED_GENERATED_instance;
var BindingReason_SELECTED_MULTIBINDING_instance;
var BindingReason_SELECTED_OPTIONAL_instance;
var BindingReason_SELECTED_IMPLICIT_instance;
var BindingReason_SELECTED_PARENT_instance;
var BindingReason_ASSISTED_TARGET_instance;
var BindingReason_CONFLICT_instance;
var BindingReason_QUALIFIER_MISMATCH_instance;
var BindingReason_EARLIER_OPTIONAL_instance;
var BindingReason_HIGHER_PRECEDENCE_instance;
var BindingReason_NOT_VISIBLE_instance;
var BindingReason_CONTRIBUTION_UNAVAILABLE_instance;
var BindingReason_OVERRIDDEN_instance;
var BindingReason_PRIVATE_TO_GRAPH_instance;
var BindingReason_DYNAMIC_REPLACEMENT_instance;
var BindingReason_OTHER_GRAPH_instance;
var BindingReason_NEARER_INPUT_instance;
var BindingReason_EXCLUDED_instance;
var BindingReason_INCOMPATIBLE_SCOPE_instance;
var BindingReason_CONTRIBUTION_SCOPE_instance;
var BindingReason_CONTAINER_UNAVAILABLE_instance;
var BindingReason_REPLACED_instance;
var BindingReason_LOWER_PRIORITY_instance;
var BindingReason_INCOMPATIBLE_MAP_VALUE_instance;
function values_2() {
  static_init_2();
  return [BindingReason_SELECTED_EXPLICIT_getInstance(), BindingReason_SELECTED_GENERATED_getInstance(), BindingReason_SELECTED_MULTIBINDING_getInstance(), BindingReason_SELECTED_OPTIONAL_getInstance(), BindingReason_SELECTED_IMPLICIT_getInstance(), BindingReason_SELECTED_PARENT_getInstance(), BindingReason_ASSISTED_TARGET_getInstance(), BindingReason_CONFLICT_getInstance(), BindingReason_QUALIFIER_MISMATCH_getInstance(), BindingReason_EARLIER_OPTIONAL_getInstance(), BindingReason_HIGHER_PRECEDENCE_getInstance(), BindingReason_NOT_VISIBLE_getInstance(), BindingReason_CONTRIBUTION_UNAVAILABLE_getInstance(), BindingReason_OVERRIDDEN_getInstance(), BindingReason_PRIVATE_TO_GRAPH_getInstance(), BindingReason_DYNAMIC_REPLACEMENT_getInstance(), BindingReason_OTHER_GRAPH_getInstance(), BindingReason_NEARER_INPUT_getInstance(), BindingReason_EXCLUDED_getInstance(), BindingReason_INCOMPATIBLE_SCOPE_getInstance(), BindingReason_CONTRIBUTION_SCOPE_getInstance(), BindingReason_CONTAINER_UNAVAILABLE_getInstance(), BindingReason_REPLACED_getInstance(), BindingReason_LOWER_PRIORITY_getInstance(), BindingReason_INCOMPATIBLE_MAP_VALUE_getInstance()];
}
function Companion_4() {
  Companion_instance_5 = this;
  var tmp = this;
  var tmp_0 = LazyThreadSafetyMode_PUBLICATION_getInstance();
  tmp.$cachedSerializer$delegate_1 = lazy(tmp_0, BindingReason$Companion$_anonymous__b5c2ai);
}
protoOf(Companion_4).serializer_9w0wvi_k$ = function () {
  return _get_$cachedSerializer__te6jhj_2(this);
};
protoOf(Companion_4).serializer_nv39qc_k$ = function (typeParamsSerializers) {
  return this.serializer_9w0wvi_k$();
};
var Companion_instance_5;
function Companion_getInstance_4() {
  static_init_2();
  return Companion_instance_5;
}
function BindingReason(name, ordinal) {
  Enum.call(this, name, ordinal);
}
function BindingReason_SELECTED_EXPLICIT_getInstance() {
  static_init_2();
  return BindingReason_SELECTED_EXPLICIT_instance;
}
function BindingReason_SELECTED_GENERATED_getInstance() {
  static_init_2();
  return BindingReason_SELECTED_GENERATED_instance;
}
function BindingReason_SELECTED_MULTIBINDING_getInstance() {
  static_init_2();
  return BindingReason_SELECTED_MULTIBINDING_instance;
}
function BindingReason_SELECTED_OPTIONAL_getInstance() {
  static_init_2();
  return BindingReason_SELECTED_OPTIONAL_instance;
}
function BindingReason_SELECTED_IMPLICIT_getInstance() {
  static_init_2();
  return BindingReason_SELECTED_IMPLICIT_instance;
}
function BindingReason_SELECTED_PARENT_getInstance() {
  static_init_2();
  return BindingReason_SELECTED_PARENT_instance;
}
function BindingReason_ASSISTED_TARGET_getInstance() {
  static_init_2();
  return BindingReason_ASSISTED_TARGET_instance;
}
function BindingReason_CONFLICT_getInstance() {
  static_init_2();
  return BindingReason_CONFLICT_instance;
}
function BindingReason_QUALIFIER_MISMATCH_getInstance() {
  static_init_2();
  return BindingReason_QUALIFIER_MISMATCH_instance;
}
function BindingReason_EARLIER_OPTIONAL_getInstance() {
  static_init_2();
  return BindingReason_EARLIER_OPTIONAL_instance;
}
function BindingReason_HIGHER_PRECEDENCE_getInstance() {
  static_init_2();
  return BindingReason_HIGHER_PRECEDENCE_instance;
}
function BindingReason_NOT_VISIBLE_getInstance() {
  static_init_2();
  return BindingReason_NOT_VISIBLE_instance;
}
function BindingReason_CONTRIBUTION_UNAVAILABLE_getInstance() {
  static_init_2();
  return BindingReason_CONTRIBUTION_UNAVAILABLE_instance;
}
function BindingReason_OVERRIDDEN_getInstance() {
  static_init_2();
  return BindingReason_OVERRIDDEN_instance;
}
function BindingReason_PRIVATE_TO_GRAPH_getInstance() {
  static_init_2();
  return BindingReason_PRIVATE_TO_GRAPH_instance;
}
function BindingReason_DYNAMIC_REPLACEMENT_getInstance() {
  static_init_2();
  return BindingReason_DYNAMIC_REPLACEMENT_instance;
}
function BindingReason_OTHER_GRAPH_getInstance() {
  static_init_2();
  return BindingReason_OTHER_GRAPH_instance;
}
function BindingReason_NEARER_INPUT_getInstance() {
  static_init_2();
  return BindingReason_NEARER_INPUT_instance;
}
function BindingReason_EXCLUDED_getInstance() {
  static_init_2();
  return BindingReason_EXCLUDED_instance;
}
function BindingReason_INCOMPATIBLE_SCOPE_getInstance() {
  static_init_2();
  return BindingReason_INCOMPATIBLE_SCOPE_instance;
}
function BindingReason_CONTRIBUTION_SCOPE_getInstance() {
  static_init_2();
  return BindingReason_CONTRIBUTION_SCOPE_instance;
}
function BindingReason_CONTAINER_UNAVAILABLE_getInstance() {
  static_init_2();
  return BindingReason_CONTAINER_UNAVAILABLE_instance;
}
function BindingReason_REPLACED_getInstance() {
  static_init_2();
  return BindingReason_REPLACED_instance;
}
function BindingReason_LOWER_PRIORITY_getInstance() {
  static_init_2();
  return BindingReason_LOWER_PRIORITY_instance;
}
function BindingReason_INCOMPATIBLE_MAP_VALUE_getInstance() {
  static_init_2();
  return BindingReason_INCOMPATIBLE_MAP_VALUE_instance;
}
function AnalysisEdge(source, target, eager) {
  this.source_1 = source;
  this.target_1 = target;
  this.eager_1 = eager;
}
protoOf(AnalysisEdge).toString = function () {
  return 'AnalysisEdge(source=' + this.source_1 + ', target=' + this.target_1 + ', eager=' + this.eager_1 + ')';
};
protoOf(AnalysisEdge).hashCode = function () {
  var result = getStringHashCode(this.source_1);
  result = imul(result, 31) + getStringHashCode(this.target_1) | 0;
  result = imul(result, 31) + getBooleanHashCode(this.eager_1) | 0;
  return result;
};
protoOf(AnalysisEdge).equals = function (other) {
  if (this === other)
    return true;
  if (!(other instanceof AnalysisEdge))
    return false;
  if (!(this.source_1 === other.source_1))
    return false;
  if (!(this.target_1 === other.target_1))
    return false;
  if (!(this.eager_1 === other.eager_1))
    return false;
  return true;
};
function analysisEdges(_this__u8e3s4) {
  // Inline function 'kotlin.collections.buildList' call
  // Inline function 'kotlin.collections.buildListInternal' call
  // Inline function 'kotlin.apply' call
  var this_0 = ArrayList_init_$Create$();
  var tmp0 = _this__u8e3s4.bindings_1;
  // Inline function 'kotlin.collections.mutableSetOf' call
  // Inline function 'kotlin.collections.mapTo' call
  var destination = LinkedHashSet_init_$Create$();
  var _iterator__ex2g4s = tmp0.iterator_jk1svi_k$();
  while (_iterator__ex2g4s.hasNext_bitz1p_k$()) {
    var item = _iterator__ex2g4s.next_20eer_k$();
    var tmp$ret$6 = item.key_1;
    destination.add_utx5q5_k$(tmp$ret$6);
  }
  var bindingKeys = destination;
  var _iterator__ex2g4s_0 = _this__u8e3s4.bindings_1.iterator_jk1svi_k$();
  while (_iterator__ex2g4s_0.hasNext_bitz1p_k$()) {
    var binding = _iterator__ex2g4s_0.next_20eer_k$();
    var _iterator__ex2g4s_1 = binding.dependencies_1.iterator_jk1svi_k$();
    while (_iterator__ex2g4s_1.hasNext_bitz1p_k$()) {
      var dependency = _iterator__ex2g4s_1.next_20eer_k$();
      var target = unwrapTypeKey(dependency.key_1);
      if (bindingKeys.contains_aljjnj_k$(target)) {
        this_0.add_utx5q5_k$(new AnalysisEdge(binding.key_1, target, !dependency.get_isDeferrable_x0h2s3_k$()));
      }
    }
  }
  var tmp0_safe_receiver = _this__u8e3s4.roots_1;
  // Inline function 'kotlin.collections.orEmpty' call
  var tmp0_elvis_lhs = tmp0_safe_receiver == null ? null : tmp0_safe_receiver.accessors_1;
  var _iterator__ex2g4s_2 = (tmp0_elvis_lhs == null ? emptyList() : tmp0_elvis_lhs).iterator_jk1svi_k$();
  while (_iterator__ex2g4s_2.hasNext_bitz1p_k$()) {
    var accessor = _iterator__ex2g4s_2.next_20eer_k$();
    var target_0 = unwrapTypeKey(accessor.key_1);
    if (bindingKeys.contains_aljjnj_k$(target_0)) {
      this_0.add_utx5q5_k$(new AnalysisEdge(_this__u8e3s4.graph_1, target_0, false));
    }
  }
  return this_0.build_nmwvly_k$();
}
function FullAnalysisReport$Companion$$childSerializers$_anonymous__6xi1kl() {
  return new ArrayListSerializer($serializer_getInstance_6());
}
function Companion_5() {
  Companion_instance_6 = this;
  var tmp = this;
  var tmp_0 = LazyThreadSafetyMode_PUBLICATION_getInstance();
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  tmp.$childSerializers_1 = [null, lazy(tmp_0, FullAnalysisReport$Companion$$childSerializers$_anonymous__6xi1kl)];
}
var Companion_instance_6;
function Companion_getInstance_5() {
  if (Companion_instance_6 == null)
    new Companion_5();
  return Companion_instance_6;
}
function $serializer_5() {
  $serializer_instance_5 = this;
  var tmp0_serialDesc = new PluginGeneratedSerialDescriptor('dev.zacsweers.metro.gradle.analysis.FullAnalysisReport', this, 2);
  tmp0_serialDesc.addElement_5pzumi_k$('projectPath', false);
  tmp0_serialDesc.addElement_5pzumi_k$('graphs', false);
  this.descriptor_1 = tmp0_serialDesc;
}
protoOf($serializer_5).serialize_jlsg9z_k$ = function (encoder, value) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_output = encoder.beginStructure_yljocp_k$(tmp0_desc);
  var tmp2_cached = Companion_getInstance_5().$childSerializers_1;
  tmp1_output.encodeStringElement_1n5wu2_k$(tmp0_desc, 0, value.projectPath_1);
  tmp1_output.encodeSerializableElement_isqxcl_k$(tmp0_desc, 1, tmp2_cached[1].get_value_j01efc_k$(), value.graphs_1);
  tmp1_output.endStructure_1xqz0n_k$(tmp0_desc);
};
protoOf($serializer_5).serialize_5ase3y_k$ = function (encoder, value) {
  return this.serialize_jlsg9z_k$(encoder, value instanceof FullAnalysisReport ? value : THROW_CCE());
};
protoOf($serializer_5).deserialize_sy6x50_k$ = function (decoder) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_flag = true;
  var tmp2_index = 0;
  var tmp3_bitMask0 = 0;
  var tmp4_local0 = null;
  var tmp5_local1 = null;
  var tmp6_input = decoder.beginStructure_yljocp_k$(tmp0_desc);
  var tmp7_cached = Companion_getInstance_5().$childSerializers_1;
  if (tmp6_input.decodeSequentially_xlblqy_k$()) {
    tmp4_local0 = tmp6_input.decodeStringElement_3oenpg_k$(tmp0_desc, 0);
    tmp3_bitMask0 = tmp3_bitMask0 | 1;
    tmp5_local1 = tmp6_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 1, tmp7_cached[1].get_value_j01efc_k$(), tmp5_local1);
    tmp3_bitMask0 = tmp3_bitMask0 | 2;
  } else
    while (tmp1_flag) {
      tmp2_index = tmp6_input.decodeElementIndex_bstkhp_k$(tmp0_desc);
      switch (tmp2_index) {
        case -1:
          tmp1_flag = false;
          break;
        case 0:
          tmp4_local0 = tmp6_input.decodeStringElement_3oenpg_k$(tmp0_desc, 0);
          tmp3_bitMask0 = tmp3_bitMask0 | 1;
          break;
        case 1:
          tmp5_local1 = tmp6_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 1, tmp7_cached[1].get_value_j01efc_k$(), tmp5_local1);
          tmp3_bitMask0 = tmp3_bitMask0 | 2;
          break;
        default:
          throw UnknownFieldException_init_$Create$(tmp2_index);
      }
    }
  tmp6_input.endStructure_1xqz0n_k$(tmp0_desc);
  return FullAnalysisReport_init_$Create$(tmp3_bitMask0, tmp4_local0, tmp5_local1, null);
};
protoOf($serializer_5).get_descriptor_wjt6a0_k$ = function () {
  return this.descriptor_1;
};
protoOf($serializer_5).childSerializers_5ghqw5_k$ = function () {
  var tmp0_cached = Companion_getInstance_5().$childSerializers_1;
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  return [StringSerializer_getInstance(), tmp0_cached[1].get_value_j01efc_k$()];
};
var $serializer_instance_5;
function $serializer_getInstance_5() {
  if ($serializer_instance_5 == null)
    new $serializer_5();
  return $serializer_instance_5;
}
function FullAnalysisReport_init_$Init$(seen0, projectPath, graphs, serializationConstructorMarker, $this) {
  if (!(3 === (3 & seen0))) {
    throwMissingFieldException(seen0, 3, $serializer_getInstance_5().descriptor_1);
  }
  $this.projectPath_1 = projectPath;
  $this.graphs_1 = graphs;
  return $this;
}
function FullAnalysisReport_init_$Create$(seen0, projectPath, graphs, serializationConstructorMarker) {
  return FullAnalysisReport_init_$Init$(seen0, projectPath, graphs, serializationConstructorMarker, objectCreate(protoOf(FullAnalysisReport)));
}
function FullAnalysisReport(projectPath, graphs) {
  Companion_getInstance_5();
  this.projectPath_1 = projectPath;
  this.graphs_1 = graphs;
}
protoOf(FullAnalysisReport).toString = function () {
  return 'FullAnalysisReport(projectPath=' + this.projectPath_1 + ', graphs=' + toString(this.graphs_1) + ')';
};
protoOf(FullAnalysisReport).hashCode = function () {
  var result = getStringHashCode(this.projectPath_1);
  result = imul(result, 31) + hashCode(this.graphs_1) | 0;
  return result;
};
protoOf(FullAnalysisReport).equals = function (other) {
  if (this === other)
    return true;
  if (!(other instanceof FullAnalysisReport))
    return false;
  if (!(this.projectPath_1 === other.projectPath_1))
    return false;
  if (!equals(this.graphs_1, other.graphs_1))
    return false;
  return true;
};
function GraphAnalysis$Companion$$childSerializers$_anonymous__pk13og() {
  return new ArrayListSerializer($serializer_getInstance());
}
function Companion_6() {
  Companion_instance_7 = this;
  var tmp = this;
  var tmp_0 = LazyThreadSafetyMode_PUBLICATION_getInstance();
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  tmp.$childSerializers_1 = [null, null, null, null, null, null, null, lazy(tmp_0, GraphAnalysis$Companion$$childSerializers$_anonymous__pk13og)];
}
var Companion_instance_7;
function Companion_getInstance_6() {
  if (Companion_instance_7 == null)
    new Companion_6();
  return Companion_instance_7;
}
function $serializer_6() {
  $serializer_instance_6 = this;
  var tmp0_serialDesc = new PluginGeneratedSerialDescriptor('dev.zacsweers.metro.gradle.analysis.GraphAnalysis', this, 8);
  tmp0_serialDesc.addElement_5pzumi_k$('graphName', false);
  tmp0_serialDesc.addElement_5pzumi_k$('statistics', false);
  tmp0_serialDesc.addElement_5pzumi_k$('longestPath', false);
  tmp0_serialDesc.addElement_5pzumi_k$('dominator', false);
  tmp0_serialDesc.addElement_5pzumi_k$('centrality', false);
  tmp0_serialDesc.addElement_5pzumi_k$('fanAnalysis', false);
  tmp0_serialDesc.addElement_5pzumi_k$('pathsToRoot', true);
  tmp0_serialDesc.addElement_5pzumi_k$('bindingExplanations', true);
  this.descriptor_1 = tmp0_serialDesc;
}
protoOf($serializer_6).serialize_veeuak_k$ = function (encoder, value) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_output = encoder.beginStructure_yljocp_k$(tmp0_desc);
  var tmp2_cached = Companion_getInstance_6().$childSerializers_1;
  tmp1_output.encodeStringElement_1n5wu2_k$(tmp0_desc, 0, value.graphName_1);
  tmp1_output.encodeSerializableElement_isqxcl_k$(tmp0_desc, 1, $serializer_getInstance_15(), value.statistics_1);
  tmp1_output.encodeSerializableElement_isqxcl_k$(tmp0_desc, 2, $serializer_getInstance_14(), value.longestPath_1);
  tmp1_output.encodeSerializableElement_isqxcl_k$(tmp0_desc, 3, $serializer_getInstance_12(), value.dominator_1);
  tmp1_output.encodeSerializableElement_isqxcl_k$(tmp0_desc, 4, $serializer_getInstance_10(), value.centrality_1);
  tmp1_output.encodeSerializableElement_isqxcl_k$(tmp0_desc, 5, $serializer_getInstance_8(), value.fanAnalysis_1);
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 6) ? true : !value.pathsToRoot_1.equals(new PathsToRootResult('', emptyMap()))) {
    tmp1_output.encodeSerializableElement_isqxcl_k$(tmp0_desc, 6, $serializer_getInstance_13(), value.pathsToRoot_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 7) ? true : !equals(value.bindingExplanations_1, emptyList())) {
    tmp1_output.encodeSerializableElement_isqxcl_k$(tmp0_desc, 7, tmp2_cached[7].get_value_j01efc_k$(), value.bindingExplanations_1);
  }
  tmp1_output.endStructure_1xqz0n_k$(tmp0_desc);
};
protoOf($serializer_6).serialize_5ase3y_k$ = function (encoder, value) {
  return this.serialize_veeuak_k$(encoder, value instanceof GraphAnalysis ? value : THROW_CCE());
};
protoOf($serializer_6).deserialize_sy6x50_k$ = function (decoder) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_flag = true;
  var tmp2_index = 0;
  var tmp3_bitMask0 = 0;
  var tmp4_local0 = null;
  var tmp5_local1 = null;
  var tmp6_local2 = null;
  var tmp7_local3 = null;
  var tmp8_local4 = null;
  var tmp9_local5 = null;
  var tmp10_local6 = null;
  var tmp11_local7 = null;
  var tmp12_input = decoder.beginStructure_yljocp_k$(tmp0_desc);
  var tmp13_cached = Companion_getInstance_6().$childSerializers_1;
  if (tmp12_input.decodeSequentially_xlblqy_k$()) {
    tmp4_local0 = tmp12_input.decodeStringElement_3oenpg_k$(tmp0_desc, 0);
    tmp3_bitMask0 = tmp3_bitMask0 | 1;
    tmp5_local1 = tmp12_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 1, $serializer_getInstance_15(), tmp5_local1);
    tmp3_bitMask0 = tmp3_bitMask0 | 2;
    tmp6_local2 = tmp12_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 2, $serializer_getInstance_14(), tmp6_local2);
    tmp3_bitMask0 = tmp3_bitMask0 | 4;
    tmp7_local3 = tmp12_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 3, $serializer_getInstance_12(), tmp7_local3);
    tmp3_bitMask0 = tmp3_bitMask0 | 8;
    tmp8_local4 = tmp12_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 4, $serializer_getInstance_10(), tmp8_local4);
    tmp3_bitMask0 = tmp3_bitMask0 | 16;
    tmp9_local5 = tmp12_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 5, $serializer_getInstance_8(), tmp9_local5);
    tmp3_bitMask0 = tmp3_bitMask0 | 32;
    tmp10_local6 = tmp12_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 6, $serializer_getInstance_13(), tmp10_local6);
    tmp3_bitMask0 = tmp3_bitMask0 | 64;
    tmp11_local7 = tmp12_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 7, tmp13_cached[7].get_value_j01efc_k$(), tmp11_local7);
    tmp3_bitMask0 = tmp3_bitMask0 | 128;
  } else
    while (tmp1_flag) {
      tmp2_index = tmp12_input.decodeElementIndex_bstkhp_k$(tmp0_desc);
      switch (tmp2_index) {
        case -1:
          tmp1_flag = false;
          break;
        case 0:
          tmp4_local0 = tmp12_input.decodeStringElement_3oenpg_k$(tmp0_desc, 0);
          tmp3_bitMask0 = tmp3_bitMask0 | 1;
          break;
        case 1:
          tmp5_local1 = tmp12_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 1, $serializer_getInstance_15(), tmp5_local1);
          tmp3_bitMask0 = tmp3_bitMask0 | 2;
          break;
        case 2:
          tmp6_local2 = tmp12_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 2, $serializer_getInstance_14(), tmp6_local2);
          tmp3_bitMask0 = tmp3_bitMask0 | 4;
          break;
        case 3:
          tmp7_local3 = tmp12_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 3, $serializer_getInstance_12(), tmp7_local3);
          tmp3_bitMask0 = tmp3_bitMask0 | 8;
          break;
        case 4:
          tmp8_local4 = tmp12_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 4, $serializer_getInstance_10(), tmp8_local4);
          tmp3_bitMask0 = tmp3_bitMask0 | 16;
          break;
        case 5:
          tmp9_local5 = tmp12_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 5, $serializer_getInstance_8(), tmp9_local5);
          tmp3_bitMask0 = tmp3_bitMask0 | 32;
          break;
        case 6:
          tmp10_local6 = tmp12_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 6, $serializer_getInstance_13(), tmp10_local6);
          tmp3_bitMask0 = tmp3_bitMask0 | 64;
          break;
        case 7:
          tmp11_local7 = tmp12_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 7, tmp13_cached[7].get_value_j01efc_k$(), tmp11_local7);
          tmp3_bitMask0 = tmp3_bitMask0 | 128;
          break;
        default:
          throw UnknownFieldException_init_$Create$(tmp2_index);
      }
    }
  tmp12_input.endStructure_1xqz0n_k$(tmp0_desc);
  return GraphAnalysis_init_$Create$(tmp3_bitMask0, tmp4_local0, tmp5_local1, tmp6_local2, tmp7_local3, tmp8_local4, tmp9_local5, tmp10_local6, tmp11_local7, null);
};
protoOf($serializer_6).get_descriptor_wjt6a0_k$ = function () {
  return this.descriptor_1;
};
protoOf($serializer_6).childSerializers_5ghqw5_k$ = function () {
  var tmp0_cached = Companion_getInstance_6().$childSerializers_1;
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  return [StringSerializer_getInstance(), $serializer_getInstance_15(), $serializer_getInstance_14(), $serializer_getInstance_12(), $serializer_getInstance_10(), $serializer_getInstance_8(), $serializer_getInstance_13(), tmp0_cached[7].get_value_j01efc_k$()];
};
var $serializer_instance_6;
function $serializer_getInstance_6() {
  if ($serializer_instance_6 == null)
    new $serializer_6();
  return $serializer_instance_6;
}
function GraphAnalysis_init_$Init$(seen0, graphName, statistics, longestPath, dominator, centrality, fanAnalysis, pathsToRoot, bindingExplanations, serializationConstructorMarker, $this) {
  if (!(63 === (63 & seen0))) {
    throwMissingFieldException(seen0, 63, $serializer_getInstance_6().descriptor_1);
  }
  $this.graphName_1 = graphName;
  $this.statistics_1 = statistics;
  $this.longestPath_1 = longestPath;
  $this.dominator_1 = dominator;
  $this.centrality_1 = centrality;
  $this.fanAnalysis_1 = fanAnalysis;
  if (0 === (seen0 & 64))
    $this.pathsToRoot_1 = new PathsToRootResult('', emptyMap());
  else
    $this.pathsToRoot_1 = pathsToRoot;
  if (0 === (seen0 & 128))
    $this.bindingExplanations_1 = emptyList();
  else
    $this.bindingExplanations_1 = bindingExplanations;
  return $this;
}
function GraphAnalysis_init_$Create$(seen0, graphName, statistics, longestPath, dominator, centrality, fanAnalysis, pathsToRoot, bindingExplanations, serializationConstructorMarker) {
  return GraphAnalysis_init_$Init$(seen0, graphName, statistics, longestPath, dominator, centrality, fanAnalysis, pathsToRoot, bindingExplanations, serializationConstructorMarker, objectCreate(protoOf(GraphAnalysis)));
}
function GraphAnalysis(graphName, statistics, longestPath, dominator, centrality, fanAnalysis, pathsToRoot, bindingExplanations) {
  Companion_getInstance_6();
  pathsToRoot = pathsToRoot === VOID ? new PathsToRootResult('', emptyMap()) : pathsToRoot;
  bindingExplanations = bindingExplanations === VOID ? emptyList() : bindingExplanations;
  this.graphName_1 = graphName;
  this.statistics_1 = statistics;
  this.longestPath_1 = longestPath;
  this.dominator_1 = dominator;
  this.centrality_1 = centrality;
  this.fanAnalysis_1 = fanAnalysis;
  this.pathsToRoot_1 = pathsToRoot;
  this.bindingExplanations_1 = bindingExplanations;
}
protoOf(GraphAnalysis).toString = function () {
  return 'GraphAnalysis(graphName=' + this.graphName_1 + ', statistics=' + this.statistics_1.toString() + ', longestPath=' + this.longestPath_1.toString() + ', dominator=' + this.dominator_1.toString() + ', centrality=' + this.centrality_1.toString() + ', fanAnalysis=' + this.fanAnalysis_1.toString() + ', pathsToRoot=' + this.pathsToRoot_1.toString() + ', bindingExplanations=' + toString(this.bindingExplanations_1) + ')';
};
protoOf(GraphAnalysis).hashCode = function () {
  var result = getStringHashCode(this.graphName_1);
  result = imul(result, 31) + this.statistics_1.hashCode() | 0;
  result = imul(result, 31) + this.longestPath_1.hashCode() | 0;
  result = imul(result, 31) + this.dominator_1.hashCode() | 0;
  result = imul(result, 31) + this.centrality_1.hashCode() | 0;
  result = imul(result, 31) + this.fanAnalysis_1.hashCode() | 0;
  result = imul(result, 31) + this.pathsToRoot_1.hashCode() | 0;
  result = imul(result, 31) + hashCode(this.bindingExplanations_1) | 0;
  return result;
};
protoOf(GraphAnalysis).equals = function (other) {
  if (this === other)
    return true;
  if (!(other instanceof GraphAnalysis))
    return false;
  if (!(this.graphName_1 === other.graphName_1))
    return false;
  if (!this.statistics_1.equals(other.statistics_1))
    return false;
  if (!this.longestPath_1.equals(other.longestPath_1))
    return false;
  if (!this.dominator_1.equals(other.dominator_1))
    return false;
  if (!this.centrality_1.equals(other.centrality_1))
    return false;
  if (!this.fanAnalysis_1.equals(other.fanAnalysis_1))
    return false;
  if (!this.pathsToRoot_1.equals(other.pathsToRoot_1))
    return false;
  if (!equals(this.bindingExplanations_1, other.bindingExplanations_1))
    return false;
  return true;
};
function FanScore$Companion$$childSerializers$_anonymous__pk7n6d() {
  return new ArrayListSerializer(StringSerializer_getInstance());
}
function FanScore$Companion$$childSerializers$_anonymous__pk7n6d_0() {
  return new ArrayListSerializer(StringSerializer_getInstance());
}
function Companion_7() {
  Companion_instance_8 = this;
  var tmp = this;
  var tmp_0 = LazyThreadSafetyMode_PUBLICATION_getInstance();
  var tmp_1 = lazy(tmp_0, FanScore$Companion$$childSerializers$_anonymous__pk7n6d);
  var tmp_2 = LazyThreadSafetyMode_PUBLICATION_getInstance();
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  tmp.$childSerializers_1 = [null, null, null, null, tmp_1, lazy(tmp_2, FanScore$Companion$$childSerializers$_anonymous__pk7n6d_0)];
}
var Companion_instance_8;
function Companion_getInstance_7() {
  if (Companion_instance_8 == null)
    new Companion_7();
  return Companion_instance_8;
}
function $serializer_7() {
  $serializer_instance_7 = this;
  var tmp0_serialDesc = new PluginGeneratedSerialDescriptor('dev.zacsweers.metro.gradle.analysis.FanScore', this, 6);
  tmp0_serialDesc.addElement_5pzumi_k$('key', false);
  tmp0_serialDesc.addElement_5pzumi_k$('bindingKind', false);
  tmp0_serialDesc.addElement_5pzumi_k$('fanIn', false);
  tmp0_serialDesc.addElement_5pzumi_k$('fanOut', false);
  tmp0_serialDesc.addElement_5pzumi_k$('dependents', false);
  tmp0_serialDesc.addElement_5pzumi_k$('dependencies', false);
  this.descriptor_1 = tmp0_serialDesc;
}
protoOf($serializer_7).serialize_s9sddz_k$ = function (encoder, value) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_output = encoder.beginStructure_yljocp_k$(tmp0_desc);
  var tmp2_cached = Companion_getInstance_7().$childSerializers_1;
  tmp1_output.encodeStringElement_1n5wu2_k$(tmp0_desc, 0, value.key_1);
  tmp1_output.encodeStringElement_1n5wu2_k$(tmp0_desc, 1, value.bindingKind_1);
  tmp1_output.encodeIntElement_krhhce_k$(tmp0_desc, 2, value.fanIn_1);
  tmp1_output.encodeIntElement_krhhce_k$(tmp0_desc, 3, value.fanOut_1);
  tmp1_output.encodeSerializableElement_isqxcl_k$(tmp0_desc, 4, tmp2_cached[4].get_value_j01efc_k$(), value.dependents_1);
  tmp1_output.encodeSerializableElement_isqxcl_k$(tmp0_desc, 5, tmp2_cached[5].get_value_j01efc_k$(), value.dependencies_1);
  tmp1_output.endStructure_1xqz0n_k$(tmp0_desc);
};
protoOf($serializer_7).serialize_5ase3y_k$ = function (encoder, value) {
  return this.serialize_s9sddz_k$(encoder, value instanceof FanScore ? value : THROW_CCE());
};
protoOf($serializer_7).deserialize_sy6x50_k$ = function (decoder) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_flag = true;
  var tmp2_index = 0;
  var tmp3_bitMask0 = 0;
  var tmp4_local0 = null;
  var tmp5_local1 = null;
  var tmp6_local2 = 0;
  var tmp7_local3 = 0;
  var tmp8_local4 = null;
  var tmp9_local5 = null;
  var tmp10_input = decoder.beginStructure_yljocp_k$(tmp0_desc);
  var tmp11_cached = Companion_getInstance_7().$childSerializers_1;
  if (tmp10_input.decodeSequentially_xlblqy_k$()) {
    tmp4_local0 = tmp10_input.decodeStringElement_3oenpg_k$(tmp0_desc, 0);
    tmp3_bitMask0 = tmp3_bitMask0 | 1;
    tmp5_local1 = tmp10_input.decodeStringElement_3oenpg_k$(tmp0_desc, 1);
    tmp3_bitMask0 = tmp3_bitMask0 | 2;
    tmp6_local2 = tmp10_input.decodeIntElement_941u6a_k$(tmp0_desc, 2);
    tmp3_bitMask0 = tmp3_bitMask0 | 4;
    tmp7_local3 = tmp10_input.decodeIntElement_941u6a_k$(tmp0_desc, 3);
    tmp3_bitMask0 = tmp3_bitMask0 | 8;
    tmp8_local4 = tmp10_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 4, tmp11_cached[4].get_value_j01efc_k$(), tmp8_local4);
    tmp3_bitMask0 = tmp3_bitMask0 | 16;
    tmp9_local5 = tmp10_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 5, tmp11_cached[5].get_value_j01efc_k$(), tmp9_local5);
    tmp3_bitMask0 = tmp3_bitMask0 | 32;
  } else
    while (tmp1_flag) {
      tmp2_index = tmp10_input.decodeElementIndex_bstkhp_k$(tmp0_desc);
      switch (tmp2_index) {
        case -1:
          tmp1_flag = false;
          break;
        case 0:
          tmp4_local0 = tmp10_input.decodeStringElement_3oenpg_k$(tmp0_desc, 0);
          tmp3_bitMask0 = tmp3_bitMask0 | 1;
          break;
        case 1:
          tmp5_local1 = tmp10_input.decodeStringElement_3oenpg_k$(tmp0_desc, 1);
          tmp3_bitMask0 = tmp3_bitMask0 | 2;
          break;
        case 2:
          tmp6_local2 = tmp10_input.decodeIntElement_941u6a_k$(tmp0_desc, 2);
          tmp3_bitMask0 = tmp3_bitMask0 | 4;
          break;
        case 3:
          tmp7_local3 = tmp10_input.decodeIntElement_941u6a_k$(tmp0_desc, 3);
          tmp3_bitMask0 = tmp3_bitMask0 | 8;
          break;
        case 4:
          tmp8_local4 = tmp10_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 4, tmp11_cached[4].get_value_j01efc_k$(), tmp8_local4);
          tmp3_bitMask0 = tmp3_bitMask0 | 16;
          break;
        case 5:
          tmp9_local5 = tmp10_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 5, tmp11_cached[5].get_value_j01efc_k$(), tmp9_local5);
          tmp3_bitMask0 = tmp3_bitMask0 | 32;
          break;
        default:
          throw UnknownFieldException_init_$Create$(tmp2_index);
      }
    }
  tmp10_input.endStructure_1xqz0n_k$(tmp0_desc);
  return FanScore_init_$Create$(tmp3_bitMask0, tmp4_local0, tmp5_local1, tmp6_local2, tmp7_local3, tmp8_local4, tmp9_local5, null);
};
protoOf($serializer_7).get_descriptor_wjt6a0_k$ = function () {
  return this.descriptor_1;
};
protoOf($serializer_7).childSerializers_5ghqw5_k$ = function () {
  var tmp0_cached = Companion_getInstance_7().$childSerializers_1;
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  return [StringSerializer_getInstance(), StringSerializer_getInstance(), IntSerializer_getInstance(), IntSerializer_getInstance(), tmp0_cached[4].get_value_j01efc_k$(), tmp0_cached[5].get_value_j01efc_k$()];
};
var $serializer_instance_7;
function $serializer_getInstance_7() {
  if ($serializer_instance_7 == null)
    new $serializer_7();
  return $serializer_instance_7;
}
function FanScore_init_$Init$(seen0, key, bindingKind, fanIn, fanOut, dependents, dependencies, serializationConstructorMarker, $this) {
  if (!(63 === (63 & seen0))) {
    throwMissingFieldException(seen0, 63, $serializer_getInstance_7().descriptor_1);
  }
  $this.key_1 = key;
  $this.bindingKind_1 = bindingKind;
  $this.fanIn_1 = fanIn;
  $this.fanOut_1 = fanOut;
  $this.dependents_1 = dependents;
  $this.dependencies_1 = dependencies;
  return $this;
}
function FanScore_init_$Create$(seen0, key, bindingKind, fanIn, fanOut, dependents, dependencies, serializationConstructorMarker) {
  return FanScore_init_$Init$(seen0, key, bindingKind, fanIn, fanOut, dependents, dependencies, serializationConstructorMarker, objectCreate(protoOf(FanScore)));
}
function FanScore() {
}
protoOf(FanScore).toString = function () {
  return 'FanScore(key=' + this.key_1 + ', bindingKind=' + this.bindingKind_1 + ', fanIn=' + this.fanIn_1 + ', fanOut=' + this.fanOut_1 + ', dependents=' + toString(this.dependents_1) + ', dependencies=' + toString(this.dependencies_1) + ')';
};
protoOf(FanScore).hashCode = function () {
  var result = getStringHashCode(this.key_1);
  result = imul(result, 31) + getStringHashCode(this.bindingKind_1) | 0;
  result = imul(result, 31) + this.fanIn_1 | 0;
  result = imul(result, 31) + this.fanOut_1 | 0;
  result = imul(result, 31) + hashCode(this.dependents_1) | 0;
  result = imul(result, 31) + hashCode(this.dependencies_1) | 0;
  return result;
};
protoOf(FanScore).equals = function (other) {
  if (this === other)
    return true;
  if (!(other instanceof FanScore))
    return false;
  if (!(this.key_1 === other.key_1))
    return false;
  if (!(this.bindingKind_1 === other.bindingKind_1))
    return false;
  if (!(this.fanIn_1 === other.fanIn_1))
    return false;
  if (!(this.fanOut_1 === other.fanOut_1))
    return false;
  if (!equals(this.dependents_1, other.dependents_1))
    return false;
  if (!equals(this.dependencies_1, other.dependencies_1))
    return false;
  return true;
};
function FanAnalysisResult$Companion$$childSerializers$_anonymous__tr28wy() {
  return new ArrayListSerializer($serializer_getInstance_7());
}
function FanAnalysisResult$Companion$$childSerializers$_anonymous__tr28wy_0() {
  return new ArrayListSerializer($serializer_getInstance_7());
}
function FanAnalysisResult$Companion$$childSerializers$_anonymous__tr28wy_1() {
  return new ArrayListSerializer($serializer_getInstance_7());
}
function Companion_8() {
  Companion_instance_9 = this;
  var tmp = this;
  var tmp_0 = LazyThreadSafetyMode_PUBLICATION_getInstance();
  var tmp_1 = lazy(tmp_0, FanAnalysisResult$Companion$$childSerializers$_anonymous__tr28wy);
  var tmp_2 = LazyThreadSafetyMode_PUBLICATION_getInstance();
  var tmp_3 = lazy(tmp_2, FanAnalysisResult$Companion$$childSerializers$_anonymous__tr28wy_0);
  var tmp_4 = LazyThreadSafetyMode_PUBLICATION_getInstance();
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  tmp.$childSerializers_1 = [tmp_1, tmp_3, lazy(tmp_4, FanAnalysisResult$Companion$$childSerializers$_anonymous__tr28wy_1), null, null];
}
var Companion_instance_9;
function Companion_getInstance_8() {
  if (Companion_instance_9 == null)
    new Companion_8();
  return Companion_instance_9;
}
function $serializer_8() {
  $serializer_instance_8 = this;
  var tmp0_serialDesc = new PluginGeneratedSerialDescriptor('dev.zacsweers.metro.gradle.analysis.FanAnalysisResult', this, 5);
  tmp0_serialDesc.addElement_5pzumi_k$('bindings', false);
  tmp0_serialDesc.addElement_5pzumi_k$('highFanIn', false);
  tmp0_serialDesc.addElement_5pzumi_k$('highFanOut', false);
  tmp0_serialDesc.addElement_5pzumi_k$('averageFanIn', false);
  tmp0_serialDesc.addElement_5pzumi_k$('averageFanOut', false);
  this.descriptor_1 = tmp0_serialDesc;
}
protoOf($serializer_8).serialize_4iixcu_k$ = function (encoder, value) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_output = encoder.beginStructure_yljocp_k$(tmp0_desc);
  var tmp2_cached = Companion_getInstance_8().$childSerializers_1;
  tmp1_output.encodeSerializableElement_isqxcl_k$(tmp0_desc, 0, tmp2_cached[0].get_value_j01efc_k$(), value.bindings_1);
  tmp1_output.encodeSerializableElement_isqxcl_k$(tmp0_desc, 1, tmp2_cached[1].get_value_j01efc_k$(), value.highFanIn_1);
  tmp1_output.encodeSerializableElement_isqxcl_k$(tmp0_desc, 2, tmp2_cached[2].get_value_j01efc_k$(), value.highFanOut_1);
  tmp1_output.encodeDoubleElement_a6rqhe_k$(tmp0_desc, 3, value.averageFanIn_1);
  tmp1_output.encodeDoubleElement_a6rqhe_k$(tmp0_desc, 4, value.averageFanOut_1);
  tmp1_output.endStructure_1xqz0n_k$(tmp0_desc);
};
protoOf($serializer_8).serialize_5ase3y_k$ = function (encoder, value) {
  return this.serialize_4iixcu_k$(encoder, value instanceof FanAnalysisResult ? value : THROW_CCE());
};
protoOf($serializer_8).deserialize_sy6x50_k$ = function (decoder) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_flag = true;
  var tmp2_index = 0;
  var tmp3_bitMask0 = 0;
  var tmp4_local0 = null;
  var tmp5_local1 = null;
  var tmp6_local2 = null;
  var tmp7_local3 = 0.0;
  var tmp8_local4 = 0.0;
  var tmp9_input = decoder.beginStructure_yljocp_k$(tmp0_desc);
  var tmp10_cached = Companion_getInstance_8().$childSerializers_1;
  if (tmp9_input.decodeSequentially_xlblqy_k$()) {
    tmp4_local0 = tmp9_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 0, tmp10_cached[0].get_value_j01efc_k$(), tmp4_local0);
    tmp3_bitMask0 = tmp3_bitMask0 | 1;
    tmp5_local1 = tmp9_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 1, tmp10_cached[1].get_value_j01efc_k$(), tmp5_local1);
    tmp3_bitMask0 = tmp3_bitMask0 | 2;
    tmp6_local2 = tmp9_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 2, tmp10_cached[2].get_value_j01efc_k$(), tmp6_local2);
    tmp3_bitMask0 = tmp3_bitMask0 | 4;
    tmp7_local3 = tmp9_input.decodeDoubleElement_isei84_k$(tmp0_desc, 3);
    tmp3_bitMask0 = tmp3_bitMask0 | 8;
    tmp8_local4 = tmp9_input.decodeDoubleElement_isei84_k$(tmp0_desc, 4);
    tmp3_bitMask0 = tmp3_bitMask0 | 16;
  } else
    while (tmp1_flag) {
      tmp2_index = tmp9_input.decodeElementIndex_bstkhp_k$(tmp0_desc);
      switch (tmp2_index) {
        case -1:
          tmp1_flag = false;
          break;
        case 0:
          tmp4_local0 = tmp9_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 0, tmp10_cached[0].get_value_j01efc_k$(), tmp4_local0);
          tmp3_bitMask0 = tmp3_bitMask0 | 1;
          break;
        case 1:
          tmp5_local1 = tmp9_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 1, tmp10_cached[1].get_value_j01efc_k$(), tmp5_local1);
          tmp3_bitMask0 = tmp3_bitMask0 | 2;
          break;
        case 2:
          tmp6_local2 = tmp9_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 2, tmp10_cached[2].get_value_j01efc_k$(), tmp6_local2);
          tmp3_bitMask0 = tmp3_bitMask0 | 4;
          break;
        case 3:
          tmp7_local3 = tmp9_input.decodeDoubleElement_isei84_k$(tmp0_desc, 3);
          tmp3_bitMask0 = tmp3_bitMask0 | 8;
          break;
        case 4:
          tmp8_local4 = tmp9_input.decodeDoubleElement_isei84_k$(tmp0_desc, 4);
          tmp3_bitMask0 = tmp3_bitMask0 | 16;
          break;
        default:
          throw UnknownFieldException_init_$Create$(tmp2_index);
      }
    }
  tmp9_input.endStructure_1xqz0n_k$(tmp0_desc);
  return FanAnalysisResult_init_$Create$(tmp3_bitMask0, tmp4_local0, tmp5_local1, tmp6_local2, tmp7_local3, tmp8_local4, null);
};
protoOf($serializer_8).get_descriptor_wjt6a0_k$ = function () {
  return this.descriptor_1;
};
protoOf($serializer_8).childSerializers_5ghqw5_k$ = function () {
  var tmp0_cached = Companion_getInstance_8().$childSerializers_1;
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  return [tmp0_cached[0].get_value_j01efc_k$(), tmp0_cached[1].get_value_j01efc_k$(), tmp0_cached[2].get_value_j01efc_k$(), DoubleSerializer_getInstance(), DoubleSerializer_getInstance()];
};
var $serializer_instance_8;
function $serializer_getInstance_8() {
  if ($serializer_instance_8 == null)
    new $serializer_8();
  return $serializer_instance_8;
}
function FanAnalysisResult_init_$Init$(seen0, bindings, highFanIn, highFanOut, averageFanIn, averageFanOut, serializationConstructorMarker, $this) {
  if (!(31 === (31 & seen0))) {
    throwMissingFieldException(seen0, 31, $serializer_getInstance_8().descriptor_1);
  }
  $this.bindings_1 = bindings;
  $this.highFanIn_1 = highFanIn;
  $this.highFanOut_1 = highFanOut;
  $this.averageFanIn_1 = averageFanIn;
  $this.averageFanOut_1 = averageFanOut;
  return $this;
}
function FanAnalysisResult_init_$Create$(seen0, bindings, highFanIn, highFanOut, averageFanIn, averageFanOut, serializationConstructorMarker) {
  return FanAnalysisResult_init_$Init$(seen0, bindings, highFanIn, highFanOut, averageFanIn, averageFanOut, serializationConstructorMarker, objectCreate(protoOf(FanAnalysisResult)));
}
function FanAnalysisResult() {
}
protoOf(FanAnalysisResult).toString = function () {
  return 'FanAnalysisResult(bindings=' + toString(this.bindings_1) + ', highFanIn=' + toString(this.highFanIn_1) + ', highFanOut=' + toString(this.highFanOut_1) + ', averageFanIn=' + this.averageFanIn_1 + ', averageFanOut=' + this.averageFanOut_1 + ')';
};
protoOf(FanAnalysisResult).hashCode = function () {
  var result = hashCode(this.bindings_1);
  result = imul(result, 31) + hashCode(this.highFanIn_1) | 0;
  result = imul(result, 31) + hashCode(this.highFanOut_1) | 0;
  result = imul(result, 31) + getNumberHashCode(this.averageFanIn_1) | 0;
  result = imul(result, 31) + getNumberHashCode(this.averageFanOut_1) | 0;
  return result;
};
protoOf(FanAnalysisResult).equals = function (other) {
  if (this === other)
    return true;
  if (!(other instanceof FanAnalysisResult))
    return false;
  if (!equals(this.bindings_1, other.bindings_1))
    return false;
  if (!equals(this.highFanIn_1, other.highFanIn_1))
    return false;
  if (!equals(this.highFanOut_1, other.highFanOut_1))
    return false;
  if (!equals(this.averageFanIn_1, other.averageFanIn_1))
    return false;
  if (!equals(this.averageFanOut_1, other.averageFanOut_1))
    return false;
  return true;
};
function $serializer_9() {
  $serializer_instance_9 = this;
  var tmp0_serialDesc = new PluginGeneratedSerialDescriptor('dev.zacsweers.metro.gradle.analysis.CentralityScore', this, 4);
  tmp0_serialDesc.addElement_5pzumi_k$('key', false);
  tmp0_serialDesc.addElement_5pzumi_k$('bindingKind', false);
  tmp0_serialDesc.addElement_5pzumi_k$('betweennessCentrality', false);
  tmp0_serialDesc.addElement_5pzumi_k$('normalizedCentrality', false);
  this.descriptor_1 = tmp0_serialDesc;
}
protoOf($serializer_9).serialize_cl4fh1_k$ = function (encoder, value) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_output = encoder.beginStructure_yljocp_k$(tmp0_desc);
  tmp1_output.encodeStringElement_1n5wu2_k$(tmp0_desc, 0, value.key_1);
  tmp1_output.encodeStringElement_1n5wu2_k$(tmp0_desc, 1, value.bindingKind_1);
  tmp1_output.encodeDoubleElement_a6rqhe_k$(tmp0_desc, 2, value.betweennessCentrality_1);
  tmp1_output.encodeDoubleElement_a6rqhe_k$(tmp0_desc, 3, value.normalizedCentrality_1);
  tmp1_output.endStructure_1xqz0n_k$(tmp0_desc);
};
protoOf($serializer_9).serialize_5ase3y_k$ = function (encoder, value) {
  return this.serialize_cl4fh1_k$(encoder, value instanceof CentralityScore ? value : THROW_CCE());
};
protoOf($serializer_9).deserialize_sy6x50_k$ = function (decoder) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_flag = true;
  var tmp2_index = 0;
  var tmp3_bitMask0 = 0;
  var tmp4_local0 = null;
  var tmp5_local1 = null;
  var tmp6_local2 = 0.0;
  var tmp7_local3 = 0.0;
  var tmp8_input = decoder.beginStructure_yljocp_k$(tmp0_desc);
  if (tmp8_input.decodeSequentially_xlblqy_k$()) {
    tmp4_local0 = tmp8_input.decodeStringElement_3oenpg_k$(tmp0_desc, 0);
    tmp3_bitMask0 = tmp3_bitMask0 | 1;
    tmp5_local1 = tmp8_input.decodeStringElement_3oenpg_k$(tmp0_desc, 1);
    tmp3_bitMask0 = tmp3_bitMask0 | 2;
    tmp6_local2 = tmp8_input.decodeDoubleElement_isei84_k$(tmp0_desc, 2);
    tmp3_bitMask0 = tmp3_bitMask0 | 4;
    tmp7_local3 = tmp8_input.decodeDoubleElement_isei84_k$(tmp0_desc, 3);
    tmp3_bitMask0 = tmp3_bitMask0 | 8;
  } else
    while (tmp1_flag) {
      tmp2_index = tmp8_input.decodeElementIndex_bstkhp_k$(tmp0_desc);
      switch (tmp2_index) {
        case -1:
          tmp1_flag = false;
          break;
        case 0:
          tmp4_local0 = tmp8_input.decodeStringElement_3oenpg_k$(tmp0_desc, 0);
          tmp3_bitMask0 = tmp3_bitMask0 | 1;
          break;
        case 1:
          tmp5_local1 = tmp8_input.decodeStringElement_3oenpg_k$(tmp0_desc, 1);
          tmp3_bitMask0 = tmp3_bitMask0 | 2;
          break;
        case 2:
          tmp6_local2 = tmp8_input.decodeDoubleElement_isei84_k$(tmp0_desc, 2);
          tmp3_bitMask0 = tmp3_bitMask0 | 4;
          break;
        case 3:
          tmp7_local3 = tmp8_input.decodeDoubleElement_isei84_k$(tmp0_desc, 3);
          tmp3_bitMask0 = tmp3_bitMask0 | 8;
          break;
        default:
          throw UnknownFieldException_init_$Create$(tmp2_index);
      }
    }
  tmp8_input.endStructure_1xqz0n_k$(tmp0_desc);
  return CentralityScore_init_$Create$(tmp3_bitMask0, tmp4_local0, tmp5_local1, tmp6_local2, tmp7_local3, null);
};
protoOf($serializer_9).get_descriptor_wjt6a0_k$ = function () {
  return this.descriptor_1;
};
protoOf($serializer_9).childSerializers_5ghqw5_k$ = function () {
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  return [StringSerializer_getInstance(), StringSerializer_getInstance(), DoubleSerializer_getInstance(), DoubleSerializer_getInstance()];
};
var $serializer_instance_9;
function $serializer_getInstance_9() {
  if ($serializer_instance_9 == null)
    new $serializer_9();
  return $serializer_instance_9;
}
function CentralityScore_init_$Init$(seen0, key, bindingKind, betweennessCentrality, normalizedCentrality, serializationConstructorMarker, $this) {
  if (!(15 === (15 & seen0))) {
    throwMissingFieldException(seen0, 15, $serializer_getInstance_9().descriptor_1);
  }
  $this.key_1 = key;
  $this.bindingKind_1 = bindingKind;
  $this.betweennessCentrality_1 = betweennessCentrality;
  $this.normalizedCentrality_1 = normalizedCentrality;
  return $this;
}
function CentralityScore_init_$Create$(seen0, key, bindingKind, betweennessCentrality, normalizedCentrality, serializationConstructorMarker) {
  return CentralityScore_init_$Init$(seen0, key, bindingKind, betweennessCentrality, normalizedCentrality, serializationConstructorMarker, objectCreate(protoOf(CentralityScore)));
}
function CentralityScore() {
}
protoOf(CentralityScore).toString = function () {
  return 'CentralityScore(key=' + this.key_1 + ', bindingKind=' + this.bindingKind_1 + ', betweennessCentrality=' + this.betweennessCentrality_1 + ', normalizedCentrality=' + this.normalizedCentrality_1 + ')';
};
protoOf(CentralityScore).hashCode = function () {
  var result = getStringHashCode(this.key_1);
  result = imul(result, 31) + getStringHashCode(this.bindingKind_1) | 0;
  result = imul(result, 31) + getNumberHashCode(this.betweennessCentrality_1) | 0;
  result = imul(result, 31) + getNumberHashCode(this.normalizedCentrality_1) | 0;
  return result;
};
protoOf(CentralityScore).equals = function (other) {
  if (this === other)
    return true;
  if (!(other instanceof CentralityScore))
    return false;
  if (!(this.key_1 === other.key_1))
    return false;
  if (!(this.bindingKind_1 === other.bindingKind_1))
    return false;
  if (!equals(this.betweennessCentrality_1, other.betweennessCentrality_1))
    return false;
  if (!equals(this.normalizedCentrality_1, other.normalizedCentrality_1))
    return false;
  return true;
};
function CentralityResult$Companion$$childSerializers$_anonymous__ucn444() {
  return new ArrayListSerializer($serializer_getInstance_9());
}
function Companion_9() {
  Companion_instance_10 = this;
  var tmp = this;
  var tmp_0 = LazyThreadSafetyMode_PUBLICATION_getInstance();
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  tmp.$childSerializers_1 = [lazy(tmp_0, CentralityResult$Companion$$childSerializers$_anonymous__ucn444)];
}
var Companion_instance_10;
function Companion_getInstance_9() {
  if (Companion_instance_10 == null)
    new Companion_9();
  return Companion_instance_10;
}
function $serializer_10() {
  $serializer_instance_10 = this;
  var tmp0_serialDesc = new PluginGeneratedSerialDescriptor('dev.zacsweers.metro.gradle.analysis.CentralityResult', this, 1);
  tmp0_serialDesc.addElement_5pzumi_k$('centralityScores', false);
  this.descriptor_1 = tmp0_serialDesc;
}
protoOf($serializer_10).serialize_4qgsqq_k$ = function (encoder, value) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_output = encoder.beginStructure_yljocp_k$(tmp0_desc);
  var tmp2_cached = Companion_getInstance_9().$childSerializers_1;
  tmp1_output.encodeSerializableElement_isqxcl_k$(tmp0_desc, 0, tmp2_cached[0].get_value_j01efc_k$(), value.centralityScores_1);
  tmp1_output.endStructure_1xqz0n_k$(tmp0_desc);
};
protoOf($serializer_10).serialize_5ase3y_k$ = function (encoder, value) {
  return this.serialize_4qgsqq_k$(encoder, value instanceof CentralityResult ? value : THROW_CCE());
};
protoOf($serializer_10).deserialize_sy6x50_k$ = function (decoder) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_flag = true;
  var tmp2_index = 0;
  var tmp3_bitMask0 = 0;
  var tmp4_local0 = null;
  var tmp5_input = decoder.beginStructure_yljocp_k$(tmp0_desc);
  var tmp6_cached = Companion_getInstance_9().$childSerializers_1;
  if (tmp5_input.decodeSequentially_xlblqy_k$()) {
    tmp4_local0 = tmp5_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 0, tmp6_cached[0].get_value_j01efc_k$(), tmp4_local0);
    tmp3_bitMask0 = tmp3_bitMask0 | 1;
  } else
    while (tmp1_flag) {
      tmp2_index = tmp5_input.decodeElementIndex_bstkhp_k$(tmp0_desc);
      switch (tmp2_index) {
        case -1:
          tmp1_flag = false;
          break;
        case 0:
          tmp4_local0 = tmp5_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 0, tmp6_cached[0].get_value_j01efc_k$(), tmp4_local0);
          tmp3_bitMask0 = tmp3_bitMask0 | 1;
          break;
        default:
          throw UnknownFieldException_init_$Create$(tmp2_index);
      }
    }
  tmp5_input.endStructure_1xqz0n_k$(tmp0_desc);
  return CentralityResult_init_$Create$(tmp3_bitMask0, tmp4_local0, null);
};
protoOf($serializer_10).get_descriptor_wjt6a0_k$ = function () {
  return this.descriptor_1;
};
protoOf($serializer_10).childSerializers_5ghqw5_k$ = function () {
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  return [Companion_getInstance_9().$childSerializers_1[0].get_value_j01efc_k$()];
};
var $serializer_instance_10;
function $serializer_getInstance_10() {
  if ($serializer_instance_10 == null)
    new $serializer_10();
  return $serializer_instance_10;
}
function CentralityResult_init_$Init$(seen0, centralityScores, serializationConstructorMarker, $this) {
  if (!(1 === (1 & seen0))) {
    throwMissingFieldException(seen0, 1, $serializer_getInstance_10().descriptor_1);
  }
  $this.centralityScores_1 = centralityScores;
  return $this;
}
function CentralityResult_init_$Create$(seen0, centralityScores, serializationConstructorMarker) {
  return CentralityResult_init_$Init$(seen0, centralityScores, serializationConstructorMarker, objectCreate(protoOf(CentralityResult)));
}
function CentralityResult() {
}
protoOf(CentralityResult).toString = function () {
  return 'CentralityResult(centralityScores=' + toString(this.centralityScores_1) + ')';
};
protoOf(CentralityResult).hashCode = function () {
  return hashCode(this.centralityScores_1);
};
protoOf(CentralityResult).equals = function (other) {
  if (this === other)
    return true;
  if (!(other instanceof CentralityResult))
    return false;
  if (!equals(this.centralityScores_1, other.centralityScores_1))
    return false;
  return true;
};
function DominatorNode$Companion$$childSerializers$_anonymous__ldb5dh() {
  return new ArrayListSerializer(StringSerializer_getInstance());
}
function Companion_10() {
  Companion_instance_11 = this;
  var tmp = this;
  var tmp_0 = LazyThreadSafetyMode_PUBLICATION_getInstance();
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  tmp.$childSerializers_1 = [null, null, null, lazy(tmp_0, DominatorNode$Companion$$childSerializers$_anonymous__ldb5dh)];
}
var Companion_instance_11;
function Companion_getInstance_10() {
  if (Companion_instance_11 == null)
    new Companion_10();
  return Companion_instance_11;
}
function $serializer_11() {
  $serializer_instance_11 = this;
  var tmp0_serialDesc = new PluginGeneratedSerialDescriptor('dev.zacsweers.metro.gradle.analysis.DominatorNode', this, 4);
  tmp0_serialDesc.addElement_5pzumi_k$('key', false);
  tmp0_serialDesc.addElement_5pzumi_k$('bindingKind', false);
  tmp0_serialDesc.addElement_5pzumi_k$('dominatedCount', false);
  tmp0_serialDesc.addElement_5pzumi_k$('dominatedKeys', false);
  this.descriptor_1 = tmp0_serialDesc;
}
protoOf($serializer_11).serialize_enai35_k$ = function (encoder, value) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_output = encoder.beginStructure_yljocp_k$(tmp0_desc);
  var tmp2_cached = Companion_getInstance_10().$childSerializers_1;
  tmp1_output.encodeStringElement_1n5wu2_k$(tmp0_desc, 0, value.key_1);
  tmp1_output.encodeStringElement_1n5wu2_k$(tmp0_desc, 1, value.bindingKind_1);
  tmp1_output.encodeIntElement_krhhce_k$(tmp0_desc, 2, value.dominatedCount_1);
  tmp1_output.encodeSerializableElement_isqxcl_k$(tmp0_desc, 3, tmp2_cached[3].get_value_j01efc_k$(), value.dominatedKeys_1);
  tmp1_output.endStructure_1xqz0n_k$(tmp0_desc);
};
protoOf($serializer_11).serialize_5ase3y_k$ = function (encoder, value) {
  return this.serialize_enai35_k$(encoder, value instanceof DominatorNode ? value : THROW_CCE());
};
protoOf($serializer_11).deserialize_sy6x50_k$ = function (decoder) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_flag = true;
  var tmp2_index = 0;
  var tmp3_bitMask0 = 0;
  var tmp4_local0 = null;
  var tmp5_local1 = null;
  var tmp6_local2 = 0;
  var tmp7_local3 = null;
  var tmp8_input = decoder.beginStructure_yljocp_k$(tmp0_desc);
  var tmp9_cached = Companion_getInstance_10().$childSerializers_1;
  if (tmp8_input.decodeSequentially_xlblqy_k$()) {
    tmp4_local0 = tmp8_input.decodeStringElement_3oenpg_k$(tmp0_desc, 0);
    tmp3_bitMask0 = tmp3_bitMask0 | 1;
    tmp5_local1 = tmp8_input.decodeStringElement_3oenpg_k$(tmp0_desc, 1);
    tmp3_bitMask0 = tmp3_bitMask0 | 2;
    tmp6_local2 = tmp8_input.decodeIntElement_941u6a_k$(tmp0_desc, 2);
    tmp3_bitMask0 = tmp3_bitMask0 | 4;
    tmp7_local3 = tmp8_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 3, tmp9_cached[3].get_value_j01efc_k$(), tmp7_local3);
    tmp3_bitMask0 = tmp3_bitMask0 | 8;
  } else
    while (tmp1_flag) {
      tmp2_index = tmp8_input.decodeElementIndex_bstkhp_k$(tmp0_desc);
      switch (tmp2_index) {
        case -1:
          tmp1_flag = false;
          break;
        case 0:
          tmp4_local0 = tmp8_input.decodeStringElement_3oenpg_k$(tmp0_desc, 0);
          tmp3_bitMask0 = tmp3_bitMask0 | 1;
          break;
        case 1:
          tmp5_local1 = tmp8_input.decodeStringElement_3oenpg_k$(tmp0_desc, 1);
          tmp3_bitMask0 = tmp3_bitMask0 | 2;
          break;
        case 2:
          tmp6_local2 = tmp8_input.decodeIntElement_941u6a_k$(tmp0_desc, 2);
          tmp3_bitMask0 = tmp3_bitMask0 | 4;
          break;
        case 3:
          tmp7_local3 = tmp8_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 3, tmp9_cached[3].get_value_j01efc_k$(), tmp7_local3);
          tmp3_bitMask0 = tmp3_bitMask0 | 8;
          break;
        default:
          throw UnknownFieldException_init_$Create$(tmp2_index);
      }
    }
  tmp8_input.endStructure_1xqz0n_k$(tmp0_desc);
  return DominatorNode_init_$Create$(tmp3_bitMask0, tmp4_local0, tmp5_local1, tmp6_local2, tmp7_local3, null);
};
protoOf($serializer_11).get_descriptor_wjt6a0_k$ = function () {
  return this.descriptor_1;
};
protoOf($serializer_11).childSerializers_5ghqw5_k$ = function () {
  var tmp0_cached = Companion_getInstance_10().$childSerializers_1;
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  return [StringSerializer_getInstance(), StringSerializer_getInstance(), IntSerializer_getInstance(), tmp0_cached[3].get_value_j01efc_k$()];
};
var $serializer_instance_11;
function $serializer_getInstance_11() {
  if ($serializer_instance_11 == null)
    new $serializer_11();
  return $serializer_instance_11;
}
function DominatorNode_init_$Init$(seen0, key, bindingKind, dominatedCount, dominatedKeys, serializationConstructorMarker, $this) {
  if (!(15 === (15 & seen0))) {
    throwMissingFieldException(seen0, 15, $serializer_getInstance_11().descriptor_1);
  }
  $this.key_1 = key;
  $this.bindingKind_1 = bindingKind;
  $this.dominatedCount_1 = dominatedCount;
  $this.dominatedKeys_1 = dominatedKeys;
  return $this;
}
function DominatorNode_init_$Create$(seen0, key, bindingKind, dominatedCount, dominatedKeys, serializationConstructorMarker) {
  return DominatorNode_init_$Init$(seen0, key, bindingKind, dominatedCount, dominatedKeys, serializationConstructorMarker, objectCreate(protoOf(DominatorNode)));
}
function DominatorNode() {
}
protoOf(DominatorNode).toString = function () {
  return 'DominatorNode(key=' + this.key_1 + ', bindingKind=' + this.bindingKind_1 + ', dominatedCount=' + this.dominatedCount_1 + ', dominatedKeys=' + toString(this.dominatedKeys_1) + ')';
};
protoOf(DominatorNode).hashCode = function () {
  var result = getStringHashCode(this.key_1);
  result = imul(result, 31) + getStringHashCode(this.bindingKind_1) | 0;
  result = imul(result, 31) + this.dominatedCount_1 | 0;
  result = imul(result, 31) + hashCode(this.dominatedKeys_1) | 0;
  return result;
};
protoOf(DominatorNode).equals = function (other) {
  if (this === other)
    return true;
  if (!(other instanceof DominatorNode))
    return false;
  if (!(this.key_1 === other.key_1))
    return false;
  if (!(this.bindingKind_1 === other.bindingKind_1))
    return false;
  if (!(this.dominatedCount_1 === other.dominatedCount_1))
    return false;
  if (!equals(this.dominatedKeys_1, other.dominatedKeys_1))
    return false;
  return true;
};
function DominatorResult$Companion$$childSerializers$_anonymous__h5bbo0() {
  return new ArrayListSerializer($serializer_getInstance_11());
}
function Companion_11() {
  Companion_instance_12 = this;
  var tmp = this;
  var tmp_0 = LazyThreadSafetyMode_PUBLICATION_getInstance();
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  tmp.$childSerializers_1 = [lazy(tmp_0, DominatorResult$Companion$$childSerializers$_anonymous__h5bbo0)];
}
var Companion_instance_12;
function Companion_getInstance_11() {
  if (Companion_instance_12 == null)
    new Companion_11();
  return Companion_instance_12;
}
function $serializer_12() {
  $serializer_instance_12 = this;
  var tmp0_serialDesc = new PluginGeneratedSerialDescriptor('dev.zacsweers.metro.gradle.analysis.DominatorResult', this, 1);
  tmp0_serialDesc.addElement_5pzumi_k$('dominators', false);
  this.descriptor_1 = tmp0_serialDesc;
}
protoOf($serializer_12).serialize_pu8fpo_k$ = function (encoder, value) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_output = encoder.beginStructure_yljocp_k$(tmp0_desc);
  var tmp2_cached = Companion_getInstance_11().$childSerializers_1;
  tmp1_output.encodeSerializableElement_isqxcl_k$(tmp0_desc, 0, tmp2_cached[0].get_value_j01efc_k$(), value.dominators_1);
  tmp1_output.endStructure_1xqz0n_k$(tmp0_desc);
};
protoOf($serializer_12).serialize_5ase3y_k$ = function (encoder, value) {
  return this.serialize_pu8fpo_k$(encoder, value instanceof DominatorResult ? value : THROW_CCE());
};
protoOf($serializer_12).deserialize_sy6x50_k$ = function (decoder) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_flag = true;
  var tmp2_index = 0;
  var tmp3_bitMask0 = 0;
  var tmp4_local0 = null;
  var tmp5_input = decoder.beginStructure_yljocp_k$(tmp0_desc);
  var tmp6_cached = Companion_getInstance_11().$childSerializers_1;
  if (tmp5_input.decodeSequentially_xlblqy_k$()) {
    tmp4_local0 = tmp5_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 0, tmp6_cached[0].get_value_j01efc_k$(), tmp4_local0);
    tmp3_bitMask0 = tmp3_bitMask0 | 1;
  } else
    while (tmp1_flag) {
      tmp2_index = tmp5_input.decodeElementIndex_bstkhp_k$(tmp0_desc);
      switch (tmp2_index) {
        case -1:
          tmp1_flag = false;
          break;
        case 0:
          tmp4_local0 = tmp5_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 0, tmp6_cached[0].get_value_j01efc_k$(), tmp4_local0);
          tmp3_bitMask0 = tmp3_bitMask0 | 1;
          break;
        default:
          throw UnknownFieldException_init_$Create$(tmp2_index);
      }
    }
  tmp5_input.endStructure_1xqz0n_k$(tmp0_desc);
  return DominatorResult_init_$Create$(tmp3_bitMask0, tmp4_local0, null);
};
protoOf($serializer_12).get_descriptor_wjt6a0_k$ = function () {
  return this.descriptor_1;
};
protoOf($serializer_12).childSerializers_5ghqw5_k$ = function () {
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  return [Companion_getInstance_11().$childSerializers_1[0].get_value_j01efc_k$()];
};
var $serializer_instance_12;
function $serializer_getInstance_12() {
  if ($serializer_instance_12 == null)
    new $serializer_12();
  return $serializer_instance_12;
}
function DominatorResult_init_$Init$(seen0, dominators, serializationConstructorMarker, $this) {
  if (!(1 === (1 & seen0))) {
    throwMissingFieldException(seen0, 1, $serializer_getInstance_12().descriptor_1);
  }
  $this.dominators_1 = dominators;
  return $this;
}
function DominatorResult_init_$Create$(seen0, dominators, serializationConstructorMarker) {
  return DominatorResult_init_$Init$(seen0, dominators, serializationConstructorMarker, objectCreate(protoOf(DominatorResult)));
}
function DominatorResult() {
}
protoOf(DominatorResult).toString = function () {
  return 'DominatorResult(dominators=' + toString(this.dominators_1) + ')';
};
protoOf(DominatorResult).hashCode = function () {
  return hashCode(this.dominators_1);
};
protoOf(DominatorResult).equals = function (other) {
  if (this === other)
    return true;
  if (!(other instanceof DominatorResult))
    return false;
  if (!equals(this.dominators_1, other.dominators_1))
    return false;
  return true;
};
function PathsToRootResult$Companion$$childSerializers$_anonymous__47nxni() {
  return new LinkedHashMapSerializer(StringSerializer_getInstance(), new ArrayListSerializer(StringSerializer_getInstance()));
}
function Companion_12() {
  Companion_instance_13 = this;
  var tmp = this;
  var tmp_0 = LazyThreadSafetyMode_PUBLICATION_getInstance();
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  tmp.$childSerializers_1 = [null, lazy(tmp_0, PathsToRootResult$Companion$$childSerializers$_anonymous__47nxni)];
}
var Companion_instance_13;
function Companion_getInstance_12() {
  if (Companion_instance_13 == null)
    new Companion_12();
  return Companion_instance_13;
}
function $serializer_13() {
  $serializer_instance_13 = this;
  var tmp0_serialDesc = new PluginGeneratedSerialDescriptor('dev.zacsweers.metro.gradle.analysis.PathsToRootResult', this, 2);
  tmp0_serialDesc.addElement_5pzumi_k$('rootKey', false);
  tmp0_serialDesc.addElement_5pzumi_k$('paths', false);
  this.descriptor_1 = tmp0_serialDesc;
}
protoOf($serializer_13).serialize_qy0day_k$ = function (encoder, value) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_output = encoder.beginStructure_yljocp_k$(tmp0_desc);
  var tmp2_cached = Companion_getInstance_12().$childSerializers_1;
  tmp1_output.encodeStringElement_1n5wu2_k$(tmp0_desc, 0, value.rootKey_1);
  tmp1_output.encodeSerializableElement_isqxcl_k$(tmp0_desc, 1, tmp2_cached[1].get_value_j01efc_k$(), value.paths_1);
  tmp1_output.endStructure_1xqz0n_k$(tmp0_desc);
};
protoOf($serializer_13).serialize_5ase3y_k$ = function (encoder, value) {
  return this.serialize_qy0day_k$(encoder, value instanceof PathsToRootResult ? value : THROW_CCE());
};
protoOf($serializer_13).deserialize_sy6x50_k$ = function (decoder) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_flag = true;
  var tmp2_index = 0;
  var tmp3_bitMask0 = 0;
  var tmp4_local0 = null;
  var tmp5_local1 = null;
  var tmp6_input = decoder.beginStructure_yljocp_k$(tmp0_desc);
  var tmp7_cached = Companion_getInstance_12().$childSerializers_1;
  if (tmp6_input.decodeSequentially_xlblqy_k$()) {
    tmp4_local0 = tmp6_input.decodeStringElement_3oenpg_k$(tmp0_desc, 0);
    tmp3_bitMask0 = tmp3_bitMask0 | 1;
    tmp5_local1 = tmp6_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 1, tmp7_cached[1].get_value_j01efc_k$(), tmp5_local1);
    tmp3_bitMask0 = tmp3_bitMask0 | 2;
  } else
    while (tmp1_flag) {
      tmp2_index = tmp6_input.decodeElementIndex_bstkhp_k$(tmp0_desc);
      switch (tmp2_index) {
        case -1:
          tmp1_flag = false;
          break;
        case 0:
          tmp4_local0 = tmp6_input.decodeStringElement_3oenpg_k$(tmp0_desc, 0);
          tmp3_bitMask0 = tmp3_bitMask0 | 1;
          break;
        case 1:
          tmp5_local1 = tmp6_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 1, tmp7_cached[1].get_value_j01efc_k$(), tmp5_local1);
          tmp3_bitMask0 = tmp3_bitMask0 | 2;
          break;
        default:
          throw UnknownFieldException_init_$Create$(tmp2_index);
      }
    }
  tmp6_input.endStructure_1xqz0n_k$(tmp0_desc);
  return PathsToRootResult_init_$Create$(tmp3_bitMask0, tmp4_local0, tmp5_local1, null);
};
protoOf($serializer_13).get_descriptor_wjt6a0_k$ = function () {
  return this.descriptor_1;
};
protoOf($serializer_13).childSerializers_5ghqw5_k$ = function () {
  var tmp0_cached = Companion_getInstance_12().$childSerializers_1;
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  return [StringSerializer_getInstance(), tmp0_cached[1].get_value_j01efc_k$()];
};
var $serializer_instance_13;
function $serializer_getInstance_13() {
  if ($serializer_instance_13 == null)
    new $serializer_13();
  return $serializer_instance_13;
}
function PathsToRootResult_init_$Init$(seen0, rootKey, paths, serializationConstructorMarker, $this) {
  if (!(3 === (3 & seen0))) {
    throwMissingFieldException(seen0, 3, $serializer_getInstance_13().descriptor_1);
  }
  $this.rootKey_1 = rootKey;
  $this.paths_1 = paths;
  return $this;
}
function PathsToRootResult_init_$Create$(seen0, rootKey, paths, serializationConstructorMarker) {
  return PathsToRootResult_init_$Init$(seen0, rootKey, paths, serializationConstructorMarker, objectCreate(protoOf(PathsToRootResult)));
}
function PathsToRootResult(rootKey, paths) {
  Companion_getInstance_12();
  this.rootKey_1 = rootKey;
  this.paths_1 = paths;
}
protoOf(PathsToRootResult).toString = function () {
  return 'PathsToRootResult(rootKey=' + this.rootKey_1 + ', paths=' + toString(this.paths_1) + ')';
};
protoOf(PathsToRootResult).hashCode = function () {
  var result = getStringHashCode(this.rootKey_1);
  result = imul(result, 31) + hashCode(this.paths_1) | 0;
  return result;
};
protoOf(PathsToRootResult).equals = function (other) {
  if (this === other)
    return true;
  if (!(other instanceof PathsToRootResult))
    return false;
  if (!(this.rootKey_1 === other.rootKey_1))
    return false;
  if (!equals(this.paths_1, other.paths_1))
    return false;
  return true;
};
function LongestPathResult$Companion$$childSerializers$_anonymous__qlo8ki() {
  return new ArrayListSerializer(new ArrayListSerializer(StringSerializer_getInstance()));
}
function LongestPathResult$Companion$$childSerializers$_anonymous__qlo8ki_0() {
  return new LinkedHashMapSerializer(IntSerializer_getInstance(), IntSerializer_getInstance());
}
function Companion_13() {
  Companion_instance_14 = this;
  var tmp = this;
  var tmp_0 = LazyThreadSafetyMode_PUBLICATION_getInstance();
  var tmp_1 = lazy(tmp_0, LongestPathResult$Companion$$childSerializers$_anonymous__qlo8ki);
  var tmp_2 = LazyThreadSafetyMode_PUBLICATION_getInstance();
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  tmp.$childSerializers_1 = [null, tmp_1, null, lazy(tmp_2, LongestPathResult$Companion$$childSerializers$_anonymous__qlo8ki_0)];
}
var Companion_instance_14;
function Companion_getInstance_13() {
  if (Companion_instance_14 == null)
    new Companion_13();
  return Companion_instance_14;
}
function $serializer_14() {
  $serializer_instance_14 = this;
  var tmp0_serialDesc = new PluginGeneratedSerialDescriptor('dev.zacsweers.metro.gradle.analysis.LongestPathResult', this, 4);
  tmp0_serialDesc.addElement_5pzumi_k$('longestPathLength', false);
  tmp0_serialDesc.addElement_5pzumi_k$('longestPaths', false);
  tmp0_serialDesc.addElement_5pzumi_k$('averagePathLength', false);
  tmp0_serialDesc.addElement_5pzumi_k$('pathLengthDistribution', false);
  this.descriptor_1 = tmp0_serialDesc;
}
protoOf($serializer_14).serialize_zh0bfm_k$ = function (encoder, value) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_output = encoder.beginStructure_yljocp_k$(tmp0_desc);
  var tmp2_cached = Companion_getInstance_13().$childSerializers_1;
  tmp1_output.encodeIntElement_krhhce_k$(tmp0_desc, 0, value.longestPathLength_1);
  tmp1_output.encodeSerializableElement_isqxcl_k$(tmp0_desc, 1, tmp2_cached[1].get_value_j01efc_k$(), value.longestPaths_1);
  tmp1_output.encodeDoubleElement_a6rqhe_k$(tmp0_desc, 2, value.averagePathLength_1);
  tmp1_output.encodeSerializableElement_isqxcl_k$(tmp0_desc, 3, tmp2_cached[3].get_value_j01efc_k$(), value.pathLengthDistribution_1);
  tmp1_output.endStructure_1xqz0n_k$(tmp0_desc);
};
protoOf($serializer_14).serialize_5ase3y_k$ = function (encoder, value) {
  return this.serialize_zh0bfm_k$(encoder, value instanceof LongestPathResult ? value : THROW_CCE());
};
protoOf($serializer_14).deserialize_sy6x50_k$ = function (decoder) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_flag = true;
  var tmp2_index = 0;
  var tmp3_bitMask0 = 0;
  var tmp4_local0 = 0;
  var tmp5_local1 = null;
  var tmp6_local2 = 0.0;
  var tmp7_local3 = null;
  var tmp8_input = decoder.beginStructure_yljocp_k$(tmp0_desc);
  var tmp9_cached = Companion_getInstance_13().$childSerializers_1;
  if (tmp8_input.decodeSequentially_xlblqy_k$()) {
    tmp4_local0 = tmp8_input.decodeIntElement_941u6a_k$(tmp0_desc, 0);
    tmp3_bitMask0 = tmp3_bitMask0 | 1;
    tmp5_local1 = tmp8_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 1, tmp9_cached[1].get_value_j01efc_k$(), tmp5_local1);
    tmp3_bitMask0 = tmp3_bitMask0 | 2;
    tmp6_local2 = tmp8_input.decodeDoubleElement_isei84_k$(tmp0_desc, 2);
    tmp3_bitMask0 = tmp3_bitMask0 | 4;
    tmp7_local3 = tmp8_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 3, tmp9_cached[3].get_value_j01efc_k$(), tmp7_local3);
    tmp3_bitMask0 = tmp3_bitMask0 | 8;
  } else
    while (tmp1_flag) {
      tmp2_index = tmp8_input.decodeElementIndex_bstkhp_k$(tmp0_desc);
      switch (tmp2_index) {
        case -1:
          tmp1_flag = false;
          break;
        case 0:
          tmp4_local0 = tmp8_input.decodeIntElement_941u6a_k$(tmp0_desc, 0);
          tmp3_bitMask0 = tmp3_bitMask0 | 1;
          break;
        case 1:
          tmp5_local1 = tmp8_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 1, tmp9_cached[1].get_value_j01efc_k$(), tmp5_local1);
          tmp3_bitMask0 = tmp3_bitMask0 | 2;
          break;
        case 2:
          tmp6_local2 = tmp8_input.decodeDoubleElement_isei84_k$(tmp0_desc, 2);
          tmp3_bitMask0 = tmp3_bitMask0 | 4;
          break;
        case 3:
          tmp7_local3 = tmp8_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 3, tmp9_cached[3].get_value_j01efc_k$(), tmp7_local3);
          tmp3_bitMask0 = tmp3_bitMask0 | 8;
          break;
        default:
          throw UnknownFieldException_init_$Create$(tmp2_index);
      }
    }
  tmp8_input.endStructure_1xqz0n_k$(tmp0_desc);
  return LongestPathResult_init_$Create$(tmp3_bitMask0, tmp4_local0, tmp5_local1, tmp6_local2, tmp7_local3, null);
};
protoOf($serializer_14).get_descriptor_wjt6a0_k$ = function () {
  return this.descriptor_1;
};
protoOf($serializer_14).childSerializers_5ghqw5_k$ = function () {
  var tmp0_cached = Companion_getInstance_13().$childSerializers_1;
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  return [IntSerializer_getInstance(), tmp0_cached[1].get_value_j01efc_k$(), DoubleSerializer_getInstance(), tmp0_cached[3].get_value_j01efc_k$()];
};
var $serializer_instance_14;
function $serializer_getInstance_14() {
  if ($serializer_instance_14 == null)
    new $serializer_14();
  return $serializer_instance_14;
}
function LongestPathResult_init_$Init$(seen0, longestPathLength, longestPaths, averagePathLength, pathLengthDistribution, serializationConstructorMarker, $this) {
  if (!(15 === (15 & seen0))) {
    throwMissingFieldException(seen0, 15, $serializer_getInstance_14().descriptor_1);
  }
  $this.longestPathLength_1 = longestPathLength;
  $this.longestPaths_1 = longestPaths;
  $this.averagePathLength_1 = averagePathLength;
  $this.pathLengthDistribution_1 = pathLengthDistribution;
  return $this;
}
function LongestPathResult_init_$Create$(seen0, longestPathLength, longestPaths, averagePathLength, pathLengthDistribution, serializationConstructorMarker) {
  return LongestPathResult_init_$Init$(seen0, longestPathLength, longestPaths, averagePathLength, pathLengthDistribution, serializationConstructorMarker, objectCreate(protoOf(LongestPathResult)));
}
function LongestPathResult() {
}
protoOf(LongestPathResult).toString = function () {
  return 'LongestPathResult(longestPathLength=' + this.longestPathLength_1 + ', longestPaths=' + toString(this.longestPaths_1) + ', averagePathLength=' + this.averagePathLength_1 + ', pathLengthDistribution=' + toString(this.pathLengthDistribution_1) + ')';
};
protoOf(LongestPathResult).hashCode = function () {
  var result = this.longestPathLength_1;
  result = imul(result, 31) + hashCode(this.longestPaths_1) | 0;
  result = imul(result, 31) + getNumberHashCode(this.averagePathLength_1) | 0;
  result = imul(result, 31) + hashCode(this.pathLengthDistribution_1) | 0;
  return result;
};
protoOf(LongestPathResult).equals = function (other) {
  if (this === other)
    return true;
  if (!(other instanceof LongestPathResult))
    return false;
  if (!(this.longestPathLength_1 === other.longestPathLength_1))
    return false;
  if (!equals(this.longestPaths_1, other.longestPaths_1))
    return false;
  if (!equals(this.averagePathLength_1, other.averagePathLength_1))
    return false;
  if (!equals(this.pathLengthDistribution_1, other.pathLengthDistribution_1))
    return false;
  return true;
};
function GraphStatistics$Companion$$childSerializers$_anonymous__3nmd7d() {
  return new LinkedHashMapSerializer(StringSerializer_getInstance(), IntSerializer_getInstance());
}
function Companion_14() {
  Companion_instance_15 = this;
  var tmp = this;
  var tmp_0 = LazyThreadSafetyMode_PUBLICATION_getInstance();
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  tmp.$childSerializers_1 = [null, null, null, lazy(tmp_0, GraphStatistics$Companion$$childSerializers$_anonymous__3nmd7d), null, null, null, null, null, null, null];
}
var Companion_instance_15;
function Companion_getInstance_14() {
  if (Companion_instance_15 == null)
    new Companion_14();
  return Companion_instance_15;
}
function $serializer_15() {
  $serializer_instance_15 = this;
  var tmp0_serialDesc = new PluginGeneratedSerialDescriptor('dev.zacsweers.metro.gradle.analysis.GraphStatistics', this, 11);
  tmp0_serialDesc.addElement_5pzumi_k$('totalBindings', false);
  tmp0_serialDesc.addElement_5pzumi_k$('scopedBindings', false);
  tmp0_serialDesc.addElement_5pzumi_k$('unscopedBindings', false);
  tmp0_serialDesc.addElement_5pzumi_k$('bindingsByKind', false);
  tmp0_serialDesc.addElement_5pzumi_k$('averageDependencies', false);
  tmp0_serialDesc.addElement_5pzumi_k$('maxDependencies', false);
  tmp0_serialDesc.addElement_5pzumi_k$('maxDependenciesBinding', false);
  tmp0_serialDesc.addElement_5pzumi_k$('rootBindings', false);
  tmp0_serialDesc.addElement_5pzumi_k$('leafBindings', false);
  tmp0_serialDesc.addElement_5pzumi_k$('multibindingCount', false);
  tmp0_serialDesc.addElement_5pzumi_k$('aliasCount', false);
  this.descriptor_1 = tmp0_serialDesc;
}
protoOf($serializer_15).serialize_lwm9c3_k$ = function (encoder, value) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_output = encoder.beginStructure_yljocp_k$(tmp0_desc);
  var tmp2_cached = Companion_getInstance_14().$childSerializers_1;
  tmp1_output.encodeIntElement_krhhce_k$(tmp0_desc, 0, value.totalBindings_1);
  tmp1_output.encodeIntElement_krhhce_k$(tmp0_desc, 1, value.scopedBindings_1);
  tmp1_output.encodeIntElement_krhhce_k$(tmp0_desc, 2, value.unscopedBindings_1);
  tmp1_output.encodeSerializableElement_isqxcl_k$(tmp0_desc, 3, tmp2_cached[3].get_value_j01efc_k$(), value.bindingsByKind_1);
  tmp1_output.encodeDoubleElement_a6rqhe_k$(tmp0_desc, 4, value.averageDependencies_1);
  tmp1_output.encodeIntElement_krhhce_k$(tmp0_desc, 5, value.maxDependencies_1);
  tmp1_output.encodeNullableSerializableElement_5lquiv_k$(tmp0_desc, 6, StringSerializer_getInstance(), value.maxDependenciesBinding_1);
  tmp1_output.encodeIntElement_krhhce_k$(tmp0_desc, 7, value.rootBindings_1);
  tmp1_output.encodeIntElement_krhhce_k$(tmp0_desc, 8, value.leafBindings_1);
  tmp1_output.encodeIntElement_krhhce_k$(tmp0_desc, 9, value.multibindingCount_1);
  tmp1_output.encodeIntElement_krhhce_k$(tmp0_desc, 10, value.aliasCount_1);
  tmp1_output.endStructure_1xqz0n_k$(tmp0_desc);
};
protoOf($serializer_15).serialize_5ase3y_k$ = function (encoder, value) {
  return this.serialize_lwm9c3_k$(encoder, value instanceof GraphStatistics ? value : THROW_CCE());
};
protoOf($serializer_15).deserialize_sy6x50_k$ = function (decoder) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_flag = true;
  var tmp2_index = 0;
  var tmp3_bitMask0 = 0;
  var tmp4_local0 = 0;
  var tmp5_local1 = 0;
  var tmp6_local2 = 0;
  var tmp7_local3 = null;
  var tmp8_local4 = 0.0;
  var tmp9_local5 = 0;
  var tmp10_local6 = null;
  var tmp11_local7 = 0;
  var tmp12_local8 = 0;
  var tmp13_local9 = 0;
  var tmp14_local10 = 0;
  var tmp15_input = decoder.beginStructure_yljocp_k$(tmp0_desc);
  var tmp16_cached = Companion_getInstance_14().$childSerializers_1;
  if (tmp15_input.decodeSequentially_xlblqy_k$()) {
    tmp4_local0 = tmp15_input.decodeIntElement_941u6a_k$(tmp0_desc, 0);
    tmp3_bitMask0 = tmp3_bitMask0 | 1;
    tmp5_local1 = tmp15_input.decodeIntElement_941u6a_k$(tmp0_desc, 1);
    tmp3_bitMask0 = tmp3_bitMask0 | 2;
    tmp6_local2 = tmp15_input.decodeIntElement_941u6a_k$(tmp0_desc, 2);
    tmp3_bitMask0 = tmp3_bitMask0 | 4;
    tmp7_local3 = tmp15_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 3, tmp16_cached[3].get_value_j01efc_k$(), tmp7_local3);
    tmp3_bitMask0 = tmp3_bitMask0 | 8;
    tmp8_local4 = tmp15_input.decodeDoubleElement_isei84_k$(tmp0_desc, 4);
    tmp3_bitMask0 = tmp3_bitMask0 | 16;
    tmp9_local5 = tmp15_input.decodeIntElement_941u6a_k$(tmp0_desc, 5);
    tmp3_bitMask0 = tmp3_bitMask0 | 32;
    tmp10_local6 = tmp15_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 6, StringSerializer_getInstance(), tmp10_local6);
    tmp3_bitMask0 = tmp3_bitMask0 | 64;
    tmp11_local7 = tmp15_input.decodeIntElement_941u6a_k$(tmp0_desc, 7);
    tmp3_bitMask0 = tmp3_bitMask0 | 128;
    tmp12_local8 = tmp15_input.decodeIntElement_941u6a_k$(tmp0_desc, 8);
    tmp3_bitMask0 = tmp3_bitMask0 | 256;
    tmp13_local9 = tmp15_input.decodeIntElement_941u6a_k$(tmp0_desc, 9);
    tmp3_bitMask0 = tmp3_bitMask0 | 512;
    tmp14_local10 = tmp15_input.decodeIntElement_941u6a_k$(tmp0_desc, 10);
    tmp3_bitMask0 = tmp3_bitMask0 | 1024;
  } else
    while (tmp1_flag) {
      tmp2_index = tmp15_input.decodeElementIndex_bstkhp_k$(tmp0_desc);
      switch (tmp2_index) {
        case -1:
          tmp1_flag = false;
          break;
        case 0:
          tmp4_local0 = tmp15_input.decodeIntElement_941u6a_k$(tmp0_desc, 0);
          tmp3_bitMask0 = tmp3_bitMask0 | 1;
          break;
        case 1:
          tmp5_local1 = tmp15_input.decodeIntElement_941u6a_k$(tmp0_desc, 1);
          tmp3_bitMask0 = tmp3_bitMask0 | 2;
          break;
        case 2:
          tmp6_local2 = tmp15_input.decodeIntElement_941u6a_k$(tmp0_desc, 2);
          tmp3_bitMask0 = tmp3_bitMask0 | 4;
          break;
        case 3:
          tmp7_local3 = tmp15_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 3, tmp16_cached[3].get_value_j01efc_k$(), tmp7_local3);
          tmp3_bitMask0 = tmp3_bitMask0 | 8;
          break;
        case 4:
          tmp8_local4 = tmp15_input.decodeDoubleElement_isei84_k$(tmp0_desc, 4);
          tmp3_bitMask0 = tmp3_bitMask0 | 16;
          break;
        case 5:
          tmp9_local5 = tmp15_input.decodeIntElement_941u6a_k$(tmp0_desc, 5);
          tmp3_bitMask0 = tmp3_bitMask0 | 32;
          break;
        case 6:
          tmp10_local6 = tmp15_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 6, StringSerializer_getInstance(), tmp10_local6);
          tmp3_bitMask0 = tmp3_bitMask0 | 64;
          break;
        case 7:
          tmp11_local7 = tmp15_input.decodeIntElement_941u6a_k$(tmp0_desc, 7);
          tmp3_bitMask0 = tmp3_bitMask0 | 128;
          break;
        case 8:
          tmp12_local8 = tmp15_input.decodeIntElement_941u6a_k$(tmp0_desc, 8);
          tmp3_bitMask0 = tmp3_bitMask0 | 256;
          break;
        case 9:
          tmp13_local9 = tmp15_input.decodeIntElement_941u6a_k$(tmp0_desc, 9);
          tmp3_bitMask0 = tmp3_bitMask0 | 512;
          break;
        case 10:
          tmp14_local10 = tmp15_input.decodeIntElement_941u6a_k$(tmp0_desc, 10);
          tmp3_bitMask0 = tmp3_bitMask0 | 1024;
          break;
        default:
          throw UnknownFieldException_init_$Create$(tmp2_index);
      }
    }
  tmp15_input.endStructure_1xqz0n_k$(tmp0_desc);
  return GraphStatistics_init_$Create$(tmp3_bitMask0, tmp4_local0, tmp5_local1, tmp6_local2, tmp7_local3, tmp8_local4, tmp9_local5, tmp10_local6, tmp11_local7, tmp12_local8, tmp13_local9, tmp14_local10, null);
};
protoOf($serializer_15).get_descriptor_wjt6a0_k$ = function () {
  return this.descriptor_1;
};
protoOf($serializer_15).childSerializers_5ghqw5_k$ = function () {
  var tmp0_cached = Companion_getInstance_14().$childSerializers_1;
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  return [IntSerializer_getInstance(), IntSerializer_getInstance(), IntSerializer_getInstance(), tmp0_cached[3].get_value_j01efc_k$(), DoubleSerializer_getInstance(), IntSerializer_getInstance(), get_nullable(StringSerializer_getInstance()), IntSerializer_getInstance(), IntSerializer_getInstance(), IntSerializer_getInstance(), IntSerializer_getInstance()];
};
var $serializer_instance_15;
function $serializer_getInstance_15() {
  if ($serializer_instance_15 == null)
    new $serializer_15();
  return $serializer_instance_15;
}
function GraphStatistics_init_$Init$(seen0, totalBindings, scopedBindings, unscopedBindings, bindingsByKind, averageDependencies, maxDependencies, maxDependenciesBinding, rootBindings, leafBindings, multibindingCount, aliasCount, serializationConstructorMarker, $this) {
  if (!(2047 === (2047 & seen0))) {
    throwMissingFieldException(seen0, 2047, $serializer_getInstance_15().descriptor_1);
  }
  $this.totalBindings_1 = totalBindings;
  $this.scopedBindings_1 = scopedBindings;
  $this.unscopedBindings_1 = unscopedBindings;
  $this.bindingsByKind_1 = bindingsByKind;
  $this.averageDependencies_1 = averageDependencies;
  $this.maxDependencies_1 = maxDependencies;
  $this.maxDependenciesBinding_1 = maxDependenciesBinding;
  $this.rootBindings_1 = rootBindings;
  $this.leafBindings_1 = leafBindings;
  $this.multibindingCount_1 = multibindingCount;
  $this.aliasCount_1 = aliasCount;
  return $this;
}
function GraphStatistics_init_$Create$(seen0, totalBindings, scopedBindings, unscopedBindings, bindingsByKind, averageDependencies, maxDependencies, maxDependenciesBinding, rootBindings, leafBindings, multibindingCount, aliasCount, serializationConstructorMarker) {
  return GraphStatistics_init_$Init$(seen0, totalBindings, scopedBindings, unscopedBindings, bindingsByKind, averageDependencies, maxDependencies, maxDependenciesBinding, rootBindings, leafBindings, multibindingCount, aliasCount, serializationConstructorMarker, objectCreate(protoOf(GraphStatistics)));
}
function GraphStatistics() {
}
protoOf(GraphStatistics).toString = function () {
  return 'GraphStatistics(totalBindings=' + this.totalBindings_1 + ', scopedBindings=' + this.scopedBindings_1 + ', unscopedBindings=' + this.unscopedBindings_1 + ', bindingsByKind=' + toString(this.bindingsByKind_1) + ', averageDependencies=' + this.averageDependencies_1 + ', maxDependencies=' + this.maxDependencies_1 + ', maxDependenciesBinding=' + this.maxDependenciesBinding_1 + ', rootBindings=' + this.rootBindings_1 + ', leafBindings=' + this.leafBindings_1 + ', multibindingCount=' + this.multibindingCount_1 + ', aliasCount=' + this.aliasCount_1 + ')';
};
protoOf(GraphStatistics).hashCode = function () {
  var result = this.totalBindings_1;
  result = imul(result, 31) + this.scopedBindings_1 | 0;
  result = imul(result, 31) + this.unscopedBindings_1 | 0;
  result = imul(result, 31) + hashCode(this.bindingsByKind_1) | 0;
  result = imul(result, 31) + getNumberHashCode(this.averageDependencies_1) | 0;
  result = imul(result, 31) + this.maxDependencies_1 | 0;
  result = imul(result, 31) + (this.maxDependenciesBinding_1 == null ? 0 : getStringHashCode(this.maxDependenciesBinding_1)) | 0;
  result = imul(result, 31) + this.rootBindings_1 | 0;
  result = imul(result, 31) + this.leafBindings_1 | 0;
  result = imul(result, 31) + this.multibindingCount_1 | 0;
  result = imul(result, 31) + this.aliasCount_1 | 0;
  return result;
};
protoOf(GraphStatistics).equals = function (other) {
  if (this === other)
    return true;
  if (!(other instanceof GraphStatistics))
    return false;
  if (!(this.totalBindings_1 === other.totalBindings_1))
    return false;
  if (!(this.scopedBindings_1 === other.scopedBindings_1))
    return false;
  if (!(this.unscopedBindings_1 === other.unscopedBindings_1))
    return false;
  if (!equals(this.bindingsByKind_1, other.bindingsByKind_1))
    return false;
  if (!equals(this.averageDependencies_1, other.averageDependencies_1))
    return false;
  if (!(this.maxDependencies_1 === other.maxDependencies_1))
    return false;
  if (!(this.maxDependenciesBinding_1 == other.maxDependenciesBinding_1))
    return false;
  if (!(this.rootBindings_1 === other.rootBindings_1))
    return false;
  if (!(this.leafBindings_1 === other.leafBindings_1))
    return false;
  if (!(this.multibindingCount_1 === other.multibindingCount_1))
    return false;
  if (!(this.aliasCount_1 === other.aliasCount_1))
    return false;
  return true;
};
function GraphMetadata$Companion$$childSerializers$_anonymous__r0lhyr() {
  return new ArrayListSerializer(StringSerializer_getInstance());
}
function GraphMetadata$Companion$$childSerializers$_anonymous__r0lhyr_0() {
  return new ArrayListSerializer(StringSerializer_getInstance());
}
function GraphMetadata$Companion$$childSerializers$_anonymous__r0lhyr_1() {
  return new ArrayListSerializer($serializer_getInstance_18());
}
function GraphMetadata$Companion$$childSerializers$_anonymous__r0lhyr_2() {
  return new ArrayListSerializer($serializer_getInstance());
}
function GraphMetadata$Companion$$childSerializers$_anonymous__r0lhyr_3() {
  return new ArrayListSerializer(StringSerializer_getInstance());
}
function Companion_15() {
  Companion_instance_16 = this;
  var tmp = this;
  var tmp_0 = LazyThreadSafetyMode_PUBLICATION_getInstance();
  var tmp_1 = lazy(tmp_0, GraphMetadata$Companion$$childSerializers$_anonymous__r0lhyr);
  var tmp_2 = LazyThreadSafetyMode_PUBLICATION_getInstance();
  var tmp_3 = lazy(tmp_2, GraphMetadata$Companion$$childSerializers$_anonymous__r0lhyr_0);
  var tmp_4 = LazyThreadSafetyMode_PUBLICATION_getInstance();
  var tmp_5 = lazy(tmp_4, GraphMetadata$Companion$$childSerializers$_anonymous__r0lhyr_1);
  var tmp_6 = LazyThreadSafetyMode_PUBLICATION_getInstance();
  var tmp_7 = lazy(tmp_6, GraphMetadata$Companion$$childSerializers$_anonymous__r0lhyr_2);
  var tmp_8 = LazyThreadSafetyMode_PUBLICATION_getInstance();
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  tmp.$childSerializers_1 = [null, tmp_1, tmp_3, null, null, null, null, tmp_5, tmp_7, null, null, lazy(tmp_8, GraphMetadata$Companion$$childSerializers$_anonymous__r0lhyr_3)];
}
var Companion_instance_16;
function Companion_getInstance_15() {
  if (Companion_instance_16 == null)
    new Companion_15();
  return Companion_instance_16;
}
function $serializer_16() {
  $serializer_instance_16 = this;
  var tmp0_serialDesc = new PluginGeneratedSerialDescriptor('dev.zacsweers.metro.gradle.analysis.GraphMetadata', this, 12);
  tmp0_serialDesc.addElement_5pzumi_k$('graph', false);
  tmp0_serialDesc.addElement_5pzumi_k$('scopes', false);
  tmp0_serialDesc.addElement_5pzumi_k$('aggregationScopes', false);
  tmp0_serialDesc.addElement_5pzumi_k$('roots', true);
  tmp0_serialDesc.addElement_5pzumi_k$('extensions', true);
  tmp0_serialDesc.addElement_5pzumi_k$('config', true);
  tmp0_serialDesc.addElement_5pzumi_k$('stats', true);
  tmp0_serialDesc.addElement_5pzumi_k$('bindings', false);
  tmp0_serialDesc.addElement_5pzumi_k$('bindingExplanations', true);
  tmp0_serialDesc.addElement_5pzumi_k$('graphType', true);
  tmp0_serialDesc.addElement_5pzumi_k$('parentGraph', true);
  tmp0_serialDesc.addElement_5pzumi_k$('includedGraphKeys', true);
  this.descriptor_1 = tmp0_serialDesc;
}
protoOf($serializer_16).serialize_ecaolr_k$ = function (encoder, value) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_output = encoder.beginStructure_yljocp_k$(tmp0_desc);
  var tmp2_cached = Companion_getInstance_15().$childSerializers_1;
  tmp1_output.encodeStringElement_1n5wu2_k$(tmp0_desc, 0, value.graph_1);
  tmp1_output.encodeSerializableElement_isqxcl_k$(tmp0_desc, 1, tmp2_cached[1].get_value_j01efc_k$(), value.scopes_1);
  tmp1_output.encodeSerializableElement_isqxcl_k$(tmp0_desc, 2, tmp2_cached[2].get_value_j01efc_k$(), value.aggregationScopes_1);
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 3) ? true : !(value.roots_1 == null)) {
    tmp1_output.encodeNullableSerializableElement_5lquiv_k$(tmp0_desc, 3, $serializer_getInstance_22(), value.roots_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 4) ? true : !(value.extensions_1 == null)) {
    tmp1_output.encodeNullableSerializableElement_5lquiv_k$(tmp0_desc, 4, $serializer_getInstance_28(), value.extensions_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 5) ? true : !(value.config_1 == null)) {
    tmp1_output.encodeNullableSerializableElement_5lquiv_k$(tmp0_desc, 5, JsonObjectSerializer_getInstance(), value.config_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 6) ? true : !(value.stats_1 == null)) {
    tmp1_output.encodeNullableSerializableElement_5lquiv_k$(tmp0_desc, 6, $serializer_getInstance_17(), value.stats_1);
  }
  tmp1_output.encodeSerializableElement_isqxcl_k$(tmp0_desc, 7, tmp2_cached[7].get_value_j01efc_k$(), value.bindings_1);
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 8) ? true : !equals(value.bindingExplanations_1, emptyList())) {
    tmp1_output.encodeSerializableElement_isqxcl_k$(tmp0_desc, 8, tmp2_cached[8].get_value_j01efc_k$(), value.bindingExplanations_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 9) ? true : !(value.graphType_1 == null)) {
    tmp1_output.encodeNullableSerializableElement_5lquiv_k$(tmp0_desc, 9, StringSerializer_getInstance(), value.graphType_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 10) ? true : !(value.parentGraph_1 == null)) {
    tmp1_output.encodeNullableSerializableElement_5lquiv_k$(tmp0_desc, 10, StringSerializer_getInstance(), value.parentGraph_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 11) ? true : !equals(value.includedGraphKeys_1, emptyList())) {
    tmp1_output.encodeSerializableElement_isqxcl_k$(tmp0_desc, 11, tmp2_cached[11].get_value_j01efc_k$(), value.includedGraphKeys_1);
  }
  tmp1_output.endStructure_1xqz0n_k$(tmp0_desc);
};
protoOf($serializer_16).serialize_5ase3y_k$ = function (encoder, value) {
  return this.serialize_ecaolr_k$(encoder, value instanceof GraphMetadata ? value : THROW_CCE());
};
protoOf($serializer_16).deserialize_sy6x50_k$ = function (decoder) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_flag = true;
  var tmp2_index = 0;
  var tmp3_bitMask0 = 0;
  var tmp4_local0 = null;
  var tmp5_local1 = null;
  var tmp6_local2 = null;
  var tmp7_local3 = null;
  var tmp8_local4 = null;
  var tmp9_local5 = null;
  var tmp10_local6 = null;
  var tmp11_local7 = null;
  var tmp12_local8 = null;
  var tmp13_local9 = null;
  var tmp14_local10 = null;
  var tmp15_local11 = null;
  var tmp16_input = decoder.beginStructure_yljocp_k$(tmp0_desc);
  var tmp17_cached = Companion_getInstance_15().$childSerializers_1;
  if (tmp16_input.decodeSequentially_xlblqy_k$()) {
    tmp4_local0 = tmp16_input.decodeStringElement_3oenpg_k$(tmp0_desc, 0);
    tmp3_bitMask0 = tmp3_bitMask0 | 1;
    tmp5_local1 = tmp16_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 1, tmp17_cached[1].get_value_j01efc_k$(), tmp5_local1);
    tmp3_bitMask0 = tmp3_bitMask0 | 2;
    tmp6_local2 = tmp16_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 2, tmp17_cached[2].get_value_j01efc_k$(), tmp6_local2);
    tmp3_bitMask0 = tmp3_bitMask0 | 4;
    tmp7_local3 = tmp16_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 3, $serializer_getInstance_22(), tmp7_local3);
    tmp3_bitMask0 = tmp3_bitMask0 | 8;
    tmp8_local4 = tmp16_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 4, $serializer_getInstance_28(), tmp8_local4);
    tmp3_bitMask0 = tmp3_bitMask0 | 16;
    tmp9_local5 = tmp16_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 5, JsonObjectSerializer_getInstance(), tmp9_local5);
    tmp3_bitMask0 = tmp3_bitMask0 | 32;
    tmp10_local6 = tmp16_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 6, $serializer_getInstance_17(), tmp10_local6);
    tmp3_bitMask0 = tmp3_bitMask0 | 64;
    tmp11_local7 = tmp16_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 7, tmp17_cached[7].get_value_j01efc_k$(), tmp11_local7);
    tmp3_bitMask0 = tmp3_bitMask0 | 128;
    tmp12_local8 = tmp16_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 8, tmp17_cached[8].get_value_j01efc_k$(), tmp12_local8);
    tmp3_bitMask0 = tmp3_bitMask0 | 256;
    tmp13_local9 = tmp16_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 9, StringSerializer_getInstance(), tmp13_local9);
    tmp3_bitMask0 = tmp3_bitMask0 | 512;
    tmp14_local10 = tmp16_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 10, StringSerializer_getInstance(), tmp14_local10);
    tmp3_bitMask0 = tmp3_bitMask0 | 1024;
    tmp15_local11 = tmp16_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 11, tmp17_cached[11].get_value_j01efc_k$(), tmp15_local11);
    tmp3_bitMask0 = tmp3_bitMask0 | 2048;
  } else
    while (tmp1_flag) {
      tmp2_index = tmp16_input.decodeElementIndex_bstkhp_k$(tmp0_desc);
      switch (tmp2_index) {
        case -1:
          tmp1_flag = false;
          break;
        case 0:
          tmp4_local0 = tmp16_input.decodeStringElement_3oenpg_k$(tmp0_desc, 0);
          tmp3_bitMask0 = tmp3_bitMask0 | 1;
          break;
        case 1:
          tmp5_local1 = tmp16_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 1, tmp17_cached[1].get_value_j01efc_k$(), tmp5_local1);
          tmp3_bitMask0 = tmp3_bitMask0 | 2;
          break;
        case 2:
          tmp6_local2 = tmp16_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 2, tmp17_cached[2].get_value_j01efc_k$(), tmp6_local2);
          tmp3_bitMask0 = tmp3_bitMask0 | 4;
          break;
        case 3:
          tmp7_local3 = tmp16_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 3, $serializer_getInstance_22(), tmp7_local3);
          tmp3_bitMask0 = tmp3_bitMask0 | 8;
          break;
        case 4:
          tmp8_local4 = tmp16_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 4, $serializer_getInstance_28(), tmp8_local4);
          tmp3_bitMask0 = tmp3_bitMask0 | 16;
          break;
        case 5:
          tmp9_local5 = tmp16_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 5, JsonObjectSerializer_getInstance(), tmp9_local5);
          tmp3_bitMask0 = tmp3_bitMask0 | 32;
          break;
        case 6:
          tmp10_local6 = tmp16_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 6, $serializer_getInstance_17(), tmp10_local6);
          tmp3_bitMask0 = tmp3_bitMask0 | 64;
          break;
        case 7:
          tmp11_local7 = tmp16_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 7, tmp17_cached[7].get_value_j01efc_k$(), tmp11_local7);
          tmp3_bitMask0 = tmp3_bitMask0 | 128;
          break;
        case 8:
          tmp12_local8 = tmp16_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 8, tmp17_cached[8].get_value_j01efc_k$(), tmp12_local8);
          tmp3_bitMask0 = tmp3_bitMask0 | 256;
          break;
        case 9:
          tmp13_local9 = tmp16_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 9, StringSerializer_getInstance(), tmp13_local9);
          tmp3_bitMask0 = tmp3_bitMask0 | 512;
          break;
        case 10:
          tmp14_local10 = tmp16_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 10, StringSerializer_getInstance(), tmp14_local10);
          tmp3_bitMask0 = tmp3_bitMask0 | 1024;
          break;
        case 11:
          tmp15_local11 = tmp16_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 11, tmp17_cached[11].get_value_j01efc_k$(), tmp15_local11);
          tmp3_bitMask0 = tmp3_bitMask0 | 2048;
          break;
        default:
          throw UnknownFieldException_init_$Create$(tmp2_index);
      }
    }
  tmp16_input.endStructure_1xqz0n_k$(tmp0_desc);
  return GraphMetadata_init_$Create$(tmp3_bitMask0, tmp4_local0, tmp5_local1, tmp6_local2, tmp7_local3, tmp8_local4, tmp9_local5, tmp10_local6, tmp11_local7, tmp12_local8, tmp13_local9, tmp14_local10, tmp15_local11, null);
};
protoOf($serializer_16).get_descriptor_wjt6a0_k$ = function () {
  return this.descriptor_1;
};
protoOf($serializer_16).childSerializers_5ghqw5_k$ = function () {
  var tmp0_cached = Companion_getInstance_15().$childSerializers_1;
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  return [StringSerializer_getInstance(), tmp0_cached[1].get_value_j01efc_k$(), tmp0_cached[2].get_value_j01efc_k$(), get_nullable($serializer_getInstance_22()), get_nullable($serializer_getInstance_28()), get_nullable(JsonObjectSerializer_getInstance()), get_nullable($serializer_getInstance_17()), tmp0_cached[7].get_value_j01efc_k$(), tmp0_cached[8].get_value_j01efc_k$(), get_nullable(StringSerializer_getInstance()), get_nullable(StringSerializer_getInstance()), tmp0_cached[11].get_value_j01efc_k$()];
};
var $serializer_instance_16;
function $serializer_getInstance_16() {
  if ($serializer_instance_16 == null)
    new $serializer_16();
  return $serializer_instance_16;
}
function GraphMetadata_init_$Init$(seen0, graph, scopes, aggregationScopes, roots, extensions, config, stats, bindings, bindingExplanations, graphType, parentGraph, includedGraphKeys, serializationConstructorMarker, $this) {
  if (!(135 === (135 & seen0))) {
    throwMissingFieldException(seen0, 135, $serializer_getInstance_16().descriptor_1);
  }
  $this.graph_1 = graph;
  $this.scopes_1 = scopes;
  $this.aggregationScopes_1 = aggregationScopes;
  if (0 === (seen0 & 8))
    $this.roots_1 = null;
  else
    $this.roots_1 = roots;
  if (0 === (seen0 & 16))
    $this.extensions_1 = null;
  else
    $this.extensions_1 = extensions;
  if (0 === (seen0 & 32))
    $this.config_1 = null;
  else
    $this.config_1 = config;
  if (0 === (seen0 & 64))
    $this.stats_1 = null;
  else
    $this.stats_1 = stats;
  $this.bindings_1 = bindings;
  if (0 === (seen0 & 256))
    $this.bindingExplanations_1 = emptyList();
  else
    $this.bindingExplanations_1 = bindingExplanations;
  if (0 === (seen0 & 512))
    $this.graphType_1 = null;
  else
    $this.graphType_1 = graphType;
  if (0 === (seen0 & 1024))
    $this.parentGraph_1 = null;
  else
    $this.parentGraph_1 = parentGraph;
  if (0 === (seen0 & 2048))
    $this.includedGraphKeys_1 = emptyList();
  else
    $this.includedGraphKeys_1 = includedGraphKeys;
  return $this;
}
function GraphMetadata_init_$Create$(seen0, graph, scopes, aggregationScopes, roots, extensions, config, stats, bindings, bindingExplanations, graphType, parentGraph, includedGraphKeys, serializationConstructorMarker) {
  return GraphMetadata_init_$Init$(seen0, graph, scopes, aggregationScopes, roots, extensions, config, stats, bindings, bindingExplanations, graphType, parentGraph, includedGraphKeys, serializationConstructorMarker, objectCreate(protoOf(GraphMetadata)));
}
function GraphMetadata(graph, scopes, aggregationScopes, roots, extensions, config, stats, bindings, bindingExplanations, graphType, parentGraph, includedGraphKeys) {
  Companion_getInstance_15();
  roots = roots === VOID ? null : roots;
  extensions = extensions === VOID ? null : extensions;
  config = config === VOID ? null : config;
  stats = stats === VOID ? null : stats;
  bindingExplanations = bindingExplanations === VOID ? emptyList() : bindingExplanations;
  graphType = graphType === VOID ? null : graphType;
  parentGraph = parentGraph === VOID ? null : parentGraph;
  includedGraphKeys = includedGraphKeys === VOID ? emptyList() : includedGraphKeys;
  this.graph_1 = graph;
  this.scopes_1 = scopes;
  this.aggregationScopes_1 = aggregationScopes;
  this.roots_1 = roots;
  this.extensions_1 = extensions;
  this.config_1 = config;
  this.stats_1 = stats;
  this.bindings_1 = bindings;
  this.bindingExplanations_1 = bindingExplanations;
  this.graphType_1 = graphType;
  this.parentGraph_1 = parentGraph;
  this.includedGraphKeys_1 = includedGraphKeys;
}
protoOf(GraphMetadata).toString = function () {
  return 'GraphMetadata(graph=' + this.graph_1 + ', scopes=' + toString(this.scopes_1) + ', aggregationScopes=' + toString(this.aggregationScopes_1) + ', roots=' + toString_0(this.roots_1) + ', extensions=' + toString_0(this.extensions_1) + ', config=' + toString_0(this.config_1) + ', stats=' + toString_0(this.stats_1) + ', bindings=' + toString(this.bindings_1) + ', bindingExplanations=' + toString(this.bindingExplanations_1) + ', graphType=' + this.graphType_1 + ', parentGraph=' + this.parentGraph_1 + ', includedGraphKeys=' + toString(this.includedGraphKeys_1) + ')';
};
protoOf(GraphMetadata).hashCode = function () {
  var result = getStringHashCode(this.graph_1);
  result = imul(result, 31) + hashCode(this.scopes_1) | 0;
  result = imul(result, 31) + hashCode(this.aggregationScopes_1) | 0;
  result = imul(result, 31) + (this.roots_1 == null ? 0 : this.roots_1.hashCode()) | 0;
  result = imul(result, 31) + (this.extensions_1 == null ? 0 : this.extensions_1.hashCode()) | 0;
  result = imul(result, 31) + (this.config_1 == null ? 0 : this.config_1.hashCode()) | 0;
  result = imul(result, 31) + (this.stats_1 == null ? 0 : this.stats_1.hashCode()) | 0;
  result = imul(result, 31) + hashCode(this.bindings_1) | 0;
  result = imul(result, 31) + hashCode(this.bindingExplanations_1) | 0;
  result = imul(result, 31) + (this.graphType_1 == null ? 0 : getStringHashCode(this.graphType_1)) | 0;
  result = imul(result, 31) + (this.parentGraph_1 == null ? 0 : getStringHashCode(this.parentGraph_1)) | 0;
  result = imul(result, 31) + hashCode(this.includedGraphKeys_1) | 0;
  return result;
};
protoOf(GraphMetadata).equals = function (other) {
  if (this === other)
    return true;
  if (!(other instanceof GraphMetadata))
    return false;
  if (!(this.graph_1 === other.graph_1))
    return false;
  if (!equals(this.scopes_1, other.scopes_1))
    return false;
  if (!equals(this.aggregationScopes_1, other.aggregationScopes_1))
    return false;
  if (!equals(this.roots_1, other.roots_1))
    return false;
  if (!equals(this.extensions_1, other.extensions_1))
    return false;
  if (!equals(this.config_1, other.config_1))
    return false;
  if (!equals(this.stats_1, other.stats_1))
    return false;
  if (!equals(this.bindings_1, other.bindings_1))
    return false;
  if (!equals(this.bindingExplanations_1, other.bindingExplanations_1))
    return false;
  if (!(this.graphType_1 == other.graphType_1))
    return false;
  if (!(this.parentGraph_1 == other.parentGraph_1))
    return false;
  if (!equals(this.includedGraphKeys_1, other.includedGraphKeys_1))
    return false;
  return true;
};
function Companion_16() {
}
var Companion_instance_17;
function Companion_getInstance_16() {
  return Companion_instance_17;
}
function $serializer_17() {
  $serializer_instance_17 = this;
  var tmp0_serialDesc = new PluginGeneratedSerialDescriptor('dev.zacsweers.metro.gradle.analysis.GraphStatsMetadata', this, 22);
  tmp0_serialDesc.addElement_5pzumi_k$('providerFactories', true);
  tmp0_serialDesc.addElement_5pzumi_k$('bindsCallables', true);
  tmp0_serialDesc.addElement_5pzumi_k$('multibindsCallables', true);
  tmp0_serialDesc.addElement_5pzumi_k$('optionalBindings', true);
  tmp0_serialDesc.addElement_5pzumi_k$('accessors', true);
  tmp0_serialDesc.addElement_5pzumi_k$('injectors', true);
  tmp0_serialDesc.addElement_5pzumi_k$('graphExtensionAccessors', true);
  tmp0_serialDesc.addElement_5pzumi_k$('graphExtensionFactories', true);
  tmp0_serialDesc.addElement_5pzumi_k$('includedGraphs', true);
  tmp0_serialDesc.addElement_5pzumi_k$('bindingContainers', true);
  tmp0_serialDesc.addElement_5pzumi_k$('dynamicBindings', true);
  tmp0_serialDesc.addElement_5pzumi_k$('graphPrivateKeys', true);
  tmp0_serialDesc.addElement_5pzumi_k$('publishedBindsKeys', true);
  tmp0_serialDesc.addElement_5pzumi_k$('populatedKeys', true);
  tmp0_serialDesc.addElement_5pzumi_k$('validatedKeys', true);
  tmp0_serialDesc.addElement_5pzumi_k$('reachableKeys', true);
  tmp0_serialDesc.addElement_5pzumi_k$('deferredKeys', true);
  tmp0_serialDesc.addElement_5pzumi_k$('unusedInputs', true);
  tmp0_serialDesc.addElement_5pzumi_k$('providerProperties', true);
  tmp0_serialDesc.addElement_5pzumi_k$('scopedProviderProperties', true);
  tmp0_serialDesc.addElement_5pzumi_k$('shards', true);
  tmp0_serialDesc.addElement_5pzumi_k$('optimizations', true);
  this.descriptor_1 = tmp0_serialDesc;
}
protoOf($serializer_17).serialize_mxpyzc_k$ = function (encoder, value) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_output = encoder.beginStructure_yljocp_k$(tmp0_desc);
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 0) ? true : !(value.providerFactories_1 === 0)) {
    tmp1_output.encodeIntElement_krhhce_k$(tmp0_desc, 0, value.providerFactories_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 1) ? true : !(value.bindsCallables_1 === 0)) {
    tmp1_output.encodeIntElement_krhhce_k$(tmp0_desc, 1, value.bindsCallables_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 2) ? true : !(value.multibindsCallables_1 === 0)) {
    tmp1_output.encodeIntElement_krhhce_k$(tmp0_desc, 2, value.multibindsCallables_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 3) ? true : !(value.optionalBindings_1 === 0)) {
    tmp1_output.encodeIntElement_krhhce_k$(tmp0_desc, 3, value.optionalBindings_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 4) ? true : !(value.accessors_1 === 0)) {
    tmp1_output.encodeIntElement_krhhce_k$(tmp0_desc, 4, value.accessors_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 5) ? true : !(value.injectors_1 === 0)) {
    tmp1_output.encodeIntElement_krhhce_k$(tmp0_desc, 5, value.injectors_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 6) ? true : !(value.graphExtensionAccessors_1 === 0)) {
    tmp1_output.encodeIntElement_krhhce_k$(tmp0_desc, 6, value.graphExtensionAccessors_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 7) ? true : !(value.graphExtensionFactories_1 === 0)) {
    tmp1_output.encodeIntElement_krhhce_k$(tmp0_desc, 7, value.graphExtensionFactories_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 8) ? true : !(value.includedGraphs_1 === 0)) {
    tmp1_output.encodeIntElement_krhhce_k$(tmp0_desc, 8, value.includedGraphs_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 9) ? true : !(value.bindingContainers_1 === 0)) {
    tmp1_output.encodeIntElement_krhhce_k$(tmp0_desc, 9, value.bindingContainers_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 10) ? true : !(value.dynamicBindings_1 === 0)) {
    tmp1_output.encodeIntElement_krhhce_k$(tmp0_desc, 10, value.dynamicBindings_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 11) ? true : !(value.graphPrivateKeys_1 === 0)) {
    tmp1_output.encodeIntElement_krhhce_k$(tmp0_desc, 11, value.graphPrivateKeys_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 12) ? true : !(value.publishedBindsKeys_1 === 0)) {
    tmp1_output.encodeIntElement_krhhce_k$(tmp0_desc, 12, value.publishedBindsKeys_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 13) ? true : !(value.populatedKeys_1 === 0)) {
    tmp1_output.encodeIntElement_krhhce_k$(tmp0_desc, 13, value.populatedKeys_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 14) ? true : !(value.validatedKeys_1 === 0)) {
    tmp1_output.encodeIntElement_krhhce_k$(tmp0_desc, 14, value.validatedKeys_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 15) ? true : !(value.reachableKeys_1 === 0)) {
    tmp1_output.encodeIntElement_krhhce_k$(tmp0_desc, 15, value.reachableKeys_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 16) ? true : !(value.deferredKeys_1 === 0)) {
    tmp1_output.encodeIntElement_krhhce_k$(tmp0_desc, 16, value.deferredKeys_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 17) ? true : !(value.unusedInputs_1 === 0)) {
    tmp1_output.encodeIntElement_krhhce_k$(tmp0_desc, 17, value.unusedInputs_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 18) ? true : !(value.providerProperties_1 === 0)) {
    tmp1_output.encodeIntElement_krhhce_k$(tmp0_desc, 18, value.providerProperties_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 19) ? true : !(value.scopedProviderProperties_1 === 0)) {
    tmp1_output.encodeIntElement_krhhce_k$(tmp0_desc, 19, value.scopedProviderProperties_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 20) ? true : !(value.shards_1 === 0)) {
    tmp1_output.encodeIntElement_krhhce_k$(tmp0_desc, 20, value.shards_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 21) ? true : !value.optimizations_1.equals(new GraphOptimizationStatsMetadata())) {
    tmp1_output.encodeSerializableElement_isqxcl_k$(tmp0_desc, 21, $serializer_getInstance_31(), value.optimizations_1);
  }
  tmp1_output.endStructure_1xqz0n_k$(tmp0_desc);
};
protoOf($serializer_17).serialize_5ase3y_k$ = function (encoder, value) {
  return this.serialize_mxpyzc_k$(encoder, value instanceof GraphStatsMetadata ? value : THROW_CCE());
};
protoOf($serializer_17).deserialize_sy6x50_k$ = function (decoder) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_flag = true;
  var tmp2_index = 0;
  var tmp3_bitMask0 = 0;
  var tmp4_local0 = 0;
  var tmp5_local1 = 0;
  var tmp6_local2 = 0;
  var tmp7_local3 = 0;
  var tmp8_local4 = 0;
  var tmp9_local5 = 0;
  var tmp10_local6 = 0;
  var tmp11_local7 = 0;
  var tmp12_local8 = 0;
  var tmp13_local9 = 0;
  var tmp14_local10 = 0;
  var tmp15_local11 = 0;
  var tmp16_local12 = 0;
  var tmp17_local13 = 0;
  var tmp18_local14 = 0;
  var tmp19_local15 = 0;
  var tmp20_local16 = 0;
  var tmp21_local17 = 0;
  var tmp22_local18 = 0;
  var tmp23_local19 = 0;
  var tmp24_local20 = 0;
  var tmp25_local21 = null;
  var tmp26_input = decoder.beginStructure_yljocp_k$(tmp0_desc);
  if (tmp26_input.decodeSequentially_xlblqy_k$()) {
    tmp4_local0 = tmp26_input.decodeIntElement_941u6a_k$(tmp0_desc, 0);
    tmp3_bitMask0 = tmp3_bitMask0 | 1;
    tmp5_local1 = tmp26_input.decodeIntElement_941u6a_k$(tmp0_desc, 1);
    tmp3_bitMask0 = tmp3_bitMask0 | 2;
    tmp6_local2 = tmp26_input.decodeIntElement_941u6a_k$(tmp0_desc, 2);
    tmp3_bitMask0 = tmp3_bitMask0 | 4;
    tmp7_local3 = tmp26_input.decodeIntElement_941u6a_k$(tmp0_desc, 3);
    tmp3_bitMask0 = tmp3_bitMask0 | 8;
    tmp8_local4 = tmp26_input.decodeIntElement_941u6a_k$(tmp0_desc, 4);
    tmp3_bitMask0 = tmp3_bitMask0 | 16;
    tmp9_local5 = tmp26_input.decodeIntElement_941u6a_k$(tmp0_desc, 5);
    tmp3_bitMask0 = tmp3_bitMask0 | 32;
    tmp10_local6 = tmp26_input.decodeIntElement_941u6a_k$(tmp0_desc, 6);
    tmp3_bitMask0 = tmp3_bitMask0 | 64;
    tmp11_local7 = tmp26_input.decodeIntElement_941u6a_k$(tmp0_desc, 7);
    tmp3_bitMask0 = tmp3_bitMask0 | 128;
    tmp12_local8 = tmp26_input.decodeIntElement_941u6a_k$(tmp0_desc, 8);
    tmp3_bitMask0 = tmp3_bitMask0 | 256;
    tmp13_local9 = tmp26_input.decodeIntElement_941u6a_k$(tmp0_desc, 9);
    tmp3_bitMask0 = tmp3_bitMask0 | 512;
    tmp14_local10 = tmp26_input.decodeIntElement_941u6a_k$(tmp0_desc, 10);
    tmp3_bitMask0 = tmp3_bitMask0 | 1024;
    tmp15_local11 = tmp26_input.decodeIntElement_941u6a_k$(tmp0_desc, 11);
    tmp3_bitMask0 = tmp3_bitMask0 | 2048;
    tmp16_local12 = tmp26_input.decodeIntElement_941u6a_k$(tmp0_desc, 12);
    tmp3_bitMask0 = tmp3_bitMask0 | 4096;
    tmp17_local13 = tmp26_input.decodeIntElement_941u6a_k$(tmp0_desc, 13);
    tmp3_bitMask0 = tmp3_bitMask0 | 8192;
    tmp18_local14 = tmp26_input.decodeIntElement_941u6a_k$(tmp0_desc, 14);
    tmp3_bitMask0 = tmp3_bitMask0 | 16384;
    tmp19_local15 = tmp26_input.decodeIntElement_941u6a_k$(tmp0_desc, 15);
    tmp3_bitMask0 = tmp3_bitMask0 | 32768;
    tmp20_local16 = tmp26_input.decodeIntElement_941u6a_k$(tmp0_desc, 16);
    tmp3_bitMask0 = tmp3_bitMask0 | 65536;
    tmp21_local17 = tmp26_input.decodeIntElement_941u6a_k$(tmp0_desc, 17);
    tmp3_bitMask0 = tmp3_bitMask0 | 131072;
    tmp22_local18 = tmp26_input.decodeIntElement_941u6a_k$(tmp0_desc, 18);
    tmp3_bitMask0 = tmp3_bitMask0 | 262144;
    tmp23_local19 = tmp26_input.decodeIntElement_941u6a_k$(tmp0_desc, 19);
    tmp3_bitMask0 = tmp3_bitMask0 | 524288;
    tmp24_local20 = tmp26_input.decodeIntElement_941u6a_k$(tmp0_desc, 20);
    tmp3_bitMask0 = tmp3_bitMask0 | 1048576;
    tmp25_local21 = tmp26_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 21, $serializer_getInstance_31(), tmp25_local21);
    tmp3_bitMask0 = tmp3_bitMask0 | 2097152;
  } else
    while (tmp1_flag) {
      tmp2_index = tmp26_input.decodeElementIndex_bstkhp_k$(tmp0_desc);
      switch (tmp2_index) {
        case -1:
          tmp1_flag = false;
          break;
        case 0:
          tmp4_local0 = tmp26_input.decodeIntElement_941u6a_k$(tmp0_desc, 0);
          tmp3_bitMask0 = tmp3_bitMask0 | 1;
          break;
        case 1:
          tmp5_local1 = tmp26_input.decodeIntElement_941u6a_k$(tmp0_desc, 1);
          tmp3_bitMask0 = tmp3_bitMask0 | 2;
          break;
        case 2:
          tmp6_local2 = tmp26_input.decodeIntElement_941u6a_k$(tmp0_desc, 2);
          tmp3_bitMask0 = tmp3_bitMask0 | 4;
          break;
        case 3:
          tmp7_local3 = tmp26_input.decodeIntElement_941u6a_k$(tmp0_desc, 3);
          tmp3_bitMask0 = tmp3_bitMask0 | 8;
          break;
        case 4:
          tmp8_local4 = tmp26_input.decodeIntElement_941u6a_k$(tmp0_desc, 4);
          tmp3_bitMask0 = tmp3_bitMask0 | 16;
          break;
        case 5:
          tmp9_local5 = tmp26_input.decodeIntElement_941u6a_k$(tmp0_desc, 5);
          tmp3_bitMask0 = tmp3_bitMask0 | 32;
          break;
        case 6:
          tmp10_local6 = tmp26_input.decodeIntElement_941u6a_k$(tmp0_desc, 6);
          tmp3_bitMask0 = tmp3_bitMask0 | 64;
          break;
        case 7:
          tmp11_local7 = tmp26_input.decodeIntElement_941u6a_k$(tmp0_desc, 7);
          tmp3_bitMask0 = tmp3_bitMask0 | 128;
          break;
        case 8:
          tmp12_local8 = tmp26_input.decodeIntElement_941u6a_k$(tmp0_desc, 8);
          tmp3_bitMask0 = tmp3_bitMask0 | 256;
          break;
        case 9:
          tmp13_local9 = tmp26_input.decodeIntElement_941u6a_k$(tmp0_desc, 9);
          tmp3_bitMask0 = tmp3_bitMask0 | 512;
          break;
        case 10:
          tmp14_local10 = tmp26_input.decodeIntElement_941u6a_k$(tmp0_desc, 10);
          tmp3_bitMask0 = tmp3_bitMask0 | 1024;
          break;
        case 11:
          tmp15_local11 = tmp26_input.decodeIntElement_941u6a_k$(tmp0_desc, 11);
          tmp3_bitMask0 = tmp3_bitMask0 | 2048;
          break;
        case 12:
          tmp16_local12 = tmp26_input.decodeIntElement_941u6a_k$(tmp0_desc, 12);
          tmp3_bitMask0 = tmp3_bitMask0 | 4096;
          break;
        case 13:
          tmp17_local13 = tmp26_input.decodeIntElement_941u6a_k$(tmp0_desc, 13);
          tmp3_bitMask0 = tmp3_bitMask0 | 8192;
          break;
        case 14:
          tmp18_local14 = tmp26_input.decodeIntElement_941u6a_k$(tmp0_desc, 14);
          tmp3_bitMask0 = tmp3_bitMask0 | 16384;
          break;
        case 15:
          tmp19_local15 = tmp26_input.decodeIntElement_941u6a_k$(tmp0_desc, 15);
          tmp3_bitMask0 = tmp3_bitMask0 | 32768;
          break;
        case 16:
          tmp20_local16 = tmp26_input.decodeIntElement_941u6a_k$(tmp0_desc, 16);
          tmp3_bitMask0 = tmp3_bitMask0 | 65536;
          break;
        case 17:
          tmp21_local17 = tmp26_input.decodeIntElement_941u6a_k$(tmp0_desc, 17);
          tmp3_bitMask0 = tmp3_bitMask0 | 131072;
          break;
        case 18:
          tmp22_local18 = tmp26_input.decodeIntElement_941u6a_k$(tmp0_desc, 18);
          tmp3_bitMask0 = tmp3_bitMask0 | 262144;
          break;
        case 19:
          tmp23_local19 = tmp26_input.decodeIntElement_941u6a_k$(tmp0_desc, 19);
          tmp3_bitMask0 = tmp3_bitMask0 | 524288;
          break;
        case 20:
          tmp24_local20 = tmp26_input.decodeIntElement_941u6a_k$(tmp0_desc, 20);
          tmp3_bitMask0 = tmp3_bitMask0 | 1048576;
          break;
        case 21:
          tmp25_local21 = tmp26_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 21, $serializer_getInstance_31(), tmp25_local21);
          tmp3_bitMask0 = tmp3_bitMask0 | 2097152;
          break;
        default:
          throw UnknownFieldException_init_$Create$(tmp2_index);
      }
    }
  tmp26_input.endStructure_1xqz0n_k$(tmp0_desc);
  return GraphStatsMetadata_init_$Create$(tmp3_bitMask0, tmp4_local0, tmp5_local1, tmp6_local2, tmp7_local3, tmp8_local4, tmp9_local5, tmp10_local6, tmp11_local7, tmp12_local8, tmp13_local9, tmp14_local10, tmp15_local11, tmp16_local12, tmp17_local13, tmp18_local14, tmp19_local15, tmp20_local16, tmp21_local17, tmp22_local18, tmp23_local19, tmp24_local20, tmp25_local21, null);
};
protoOf($serializer_17).get_descriptor_wjt6a0_k$ = function () {
  return this.descriptor_1;
};
protoOf($serializer_17).childSerializers_5ghqw5_k$ = function () {
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  return [IntSerializer_getInstance(), IntSerializer_getInstance(), IntSerializer_getInstance(), IntSerializer_getInstance(), IntSerializer_getInstance(), IntSerializer_getInstance(), IntSerializer_getInstance(), IntSerializer_getInstance(), IntSerializer_getInstance(), IntSerializer_getInstance(), IntSerializer_getInstance(), IntSerializer_getInstance(), IntSerializer_getInstance(), IntSerializer_getInstance(), IntSerializer_getInstance(), IntSerializer_getInstance(), IntSerializer_getInstance(), IntSerializer_getInstance(), IntSerializer_getInstance(), IntSerializer_getInstance(), IntSerializer_getInstance(), $serializer_getInstance_31()];
};
var $serializer_instance_17;
function $serializer_getInstance_17() {
  if ($serializer_instance_17 == null)
    new $serializer_17();
  return $serializer_instance_17;
}
function GraphStatsMetadata_init_$Init$(seen0, providerFactories, bindsCallables, multibindsCallables, optionalBindings, accessors, injectors, graphExtensionAccessors, graphExtensionFactories, includedGraphs, bindingContainers, dynamicBindings, graphPrivateKeys, publishedBindsKeys, populatedKeys, validatedKeys, reachableKeys, deferredKeys, unusedInputs, providerProperties, scopedProviderProperties, shards, optimizations, serializationConstructorMarker, $this) {
  if (!(0 === (0 & seen0))) {
    throwMissingFieldException(seen0, 0, $serializer_getInstance_17().descriptor_1);
  }
  if (0 === (seen0 & 1))
    $this.providerFactories_1 = 0;
  else
    $this.providerFactories_1 = providerFactories;
  if (0 === (seen0 & 2))
    $this.bindsCallables_1 = 0;
  else
    $this.bindsCallables_1 = bindsCallables;
  if (0 === (seen0 & 4))
    $this.multibindsCallables_1 = 0;
  else
    $this.multibindsCallables_1 = multibindsCallables;
  if (0 === (seen0 & 8))
    $this.optionalBindings_1 = 0;
  else
    $this.optionalBindings_1 = optionalBindings;
  if (0 === (seen0 & 16))
    $this.accessors_1 = 0;
  else
    $this.accessors_1 = accessors;
  if (0 === (seen0 & 32))
    $this.injectors_1 = 0;
  else
    $this.injectors_1 = injectors;
  if (0 === (seen0 & 64))
    $this.graphExtensionAccessors_1 = 0;
  else
    $this.graphExtensionAccessors_1 = graphExtensionAccessors;
  if (0 === (seen0 & 128))
    $this.graphExtensionFactories_1 = 0;
  else
    $this.graphExtensionFactories_1 = graphExtensionFactories;
  if (0 === (seen0 & 256))
    $this.includedGraphs_1 = 0;
  else
    $this.includedGraphs_1 = includedGraphs;
  if (0 === (seen0 & 512))
    $this.bindingContainers_1 = 0;
  else
    $this.bindingContainers_1 = bindingContainers;
  if (0 === (seen0 & 1024))
    $this.dynamicBindings_1 = 0;
  else
    $this.dynamicBindings_1 = dynamicBindings;
  if (0 === (seen0 & 2048))
    $this.graphPrivateKeys_1 = 0;
  else
    $this.graphPrivateKeys_1 = graphPrivateKeys;
  if (0 === (seen0 & 4096))
    $this.publishedBindsKeys_1 = 0;
  else
    $this.publishedBindsKeys_1 = publishedBindsKeys;
  if (0 === (seen0 & 8192))
    $this.populatedKeys_1 = 0;
  else
    $this.populatedKeys_1 = populatedKeys;
  if (0 === (seen0 & 16384))
    $this.validatedKeys_1 = 0;
  else
    $this.validatedKeys_1 = validatedKeys;
  if (0 === (seen0 & 32768))
    $this.reachableKeys_1 = 0;
  else
    $this.reachableKeys_1 = reachableKeys;
  if (0 === (seen0 & 65536))
    $this.deferredKeys_1 = 0;
  else
    $this.deferredKeys_1 = deferredKeys;
  if (0 === (seen0 & 131072))
    $this.unusedInputs_1 = 0;
  else
    $this.unusedInputs_1 = unusedInputs;
  if (0 === (seen0 & 262144))
    $this.providerProperties_1 = 0;
  else
    $this.providerProperties_1 = providerProperties;
  if (0 === (seen0 & 524288))
    $this.scopedProviderProperties_1 = 0;
  else
    $this.scopedProviderProperties_1 = scopedProviderProperties;
  if (0 === (seen0 & 1048576))
    $this.shards_1 = 0;
  else
    $this.shards_1 = shards;
  if (0 === (seen0 & 2097152))
    $this.optimizations_1 = new GraphOptimizationStatsMetadata();
  else
    $this.optimizations_1 = optimizations;
  return $this;
}
function GraphStatsMetadata_init_$Create$(seen0, providerFactories, bindsCallables, multibindsCallables, optionalBindings, accessors, injectors, graphExtensionAccessors, graphExtensionFactories, includedGraphs, bindingContainers, dynamicBindings, graphPrivateKeys, publishedBindsKeys, populatedKeys, validatedKeys, reachableKeys, deferredKeys, unusedInputs, providerProperties, scopedProviderProperties, shards, optimizations, serializationConstructorMarker) {
  return GraphStatsMetadata_init_$Init$(seen0, providerFactories, bindsCallables, multibindsCallables, optionalBindings, accessors, injectors, graphExtensionAccessors, graphExtensionFactories, includedGraphs, bindingContainers, dynamicBindings, graphPrivateKeys, publishedBindsKeys, populatedKeys, validatedKeys, reachableKeys, deferredKeys, unusedInputs, providerProperties, scopedProviderProperties, shards, optimizations, serializationConstructorMarker, objectCreate(protoOf(GraphStatsMetadata)));
}
function GraphStatsMetadata(providerFactories, bindsCallables, multibindsCallables, optionalBindings, accessors, injectors, graphExtensionAccessors, graphExtensionFactories, includedGraphs, bindingContainers, dynamicBindings, graphPrivateKeys, publishedBindsKeys, populatedKeys, validatedKeys, reachableKeys, deferredKeys, unusedInputs, providerProperties, scopedProviderProperties, shards, optimizations) {
  providerFactories = providerFactories === VOID ? 0 : providerFactories;
  bindsCallables = bindsCallables === VOID ? 0 : bindsCallables;
  multibindsCallables = multibindsCallables === VOID ? 0 : multibindsCallables;
  optionalBindings = optionalBindings === VOID ? 0 : optionalBindings;
  accessors = accessors === VOID ? 0 : accessors;
  injectors = injectors === VOID ? 0 : injectors;
  graphExtensionAccessors = graphExtensionAccessors === VOID ? 0 : graphExtensionAccessors;
  graphExtensionFactories = graphExtensionFactories === VOID ? 0 : graphExtensionFactories;
  includedGraphs = includedGraphs === VOID ? 0 : includedGraphs;
  bindingContainers = bindingContainers === VOID ? 0 : bindingContainers;
  dynamicBindings = dynamicBindings === VOID ? 0 : dynamicBindings;
  graphPrivateKeys = graphPrivateKeys === VOID ? 0 : graphPrivateKeys;
  publishedBindsKeys = publishedBindsKeys === VOID ? 0 : publishedBindsKeys;
  populatedKeys = populatedKeys === VOID ? 0 : populatedKeys;
  validatedKeys = validatedKeys === VOID ? 0 : validatedKeys;
  reachableKeys = reachableKeys === VOID ? 0 : reachableKeys;
  deferredKeys = deferredKeys === VOID ? 0 : deferredKeys;
  unusedInputs = unusedInputs === VOID ? 0 : unusedInputs;
  providerProperties = providerProperties === VOID ? 0 : providerProperties;
  scopedProviderProperties = scopedProviderProperties === VOID ? 0 : scopedProviderProperties;
  shards = shards === VOID ? 0 : shards;
  optimizations = optimizations === VOID ? new GraphOptimizationStatsMetadata() : optimizations;
  this.providerFactories_1 = providerFactories;
  this.bindsCallables_1 = bindsCallables;
  this.multibindsCallables_1 = multibindsCallables;
  this.optionalBindings_1 = optionalBindings;
  this.accessors_1 = accessors;
  this.injectors_1 = injectors;
  this.graphExtensionAccessors_1 = graphExtensionAccessors;
  this.graphExtensionFactories_1 = graphExtensionFactories;
  this.includedGraphs_1 = includedGraphs;
  this.bindingContainers_1 = bindingContainers;
  this.dynamicBindings_1 = dynamicBindings;
  this.graphPrivateKeys_1 = graphPrivateKeys;
  this.publishedBindsKeys_1 = publishedBindsKeys;
  this.populatedKeys_1 = populatedKeys;
  this.validatedKeys_1 = validatedKeys;
  this.reachableKeys_1 = reachableKeys;
  this.deferredKeys_1 = deferredKeys;
  this.unusedInputs_1 = unusedInputs;
  this.providerProperties_1 = providerProperties;
  this.scopedProviderProperties_1 = scopedProviderProperties;
  this.shards_1 = shards;
  this.optimizations_1 = optimizations;
}
protoOf(GraphStatsMetadata).toString = function () {
  return 'GraphStatsMetadata(providerFactories=' + this.providerFactories_1 + ', bindsCallables=' + this.bindsCallables_1 + ', multibindsCallables=' + this.multibindsCallables_1 + ', optionalBindings=' + this.optionalBindings_1 + ', accessors=' + this.accessors_1 + ', injectors=' + this.injectors_1 + ', graphExtensionAccessors=' + this.graphExtensionAccessors_1 + ', graphExtensionFactories=' + this.graphExtensionFactories_1 + ', includedGraphs=' + this.includedGraphs_1 + ', bindingContainers=' + this.bindingContainers_1 + ', dynamicBindings=' + this.dynamicBindings_1 + ', graphPrivateKeys=' + this.graphPrivateKeys_1 + ', publishedBindsKeys=' + this.publishedBindsKeys_1 + ', populatedKeys=' + this.populatedKeys_1 + ', validatedKeys=' + this.validatedKeys_1 + ', reachableKeys=' + this.reachableKeys_1 + ', deferredKeys=' + this.deferredKeys_1 + ', unusedInputs=' + this.unusedInputs_1 + ', providerProperties=' + this.providerProperties_1 + ', scopedProviderProperties=' + this.scopedProviderProperties_1 + ', shards=' + this.shards_1 + ', optimizations=' + this.optimizations_1.toString() + ')';
};
protoOf(GraphStatsMetadata).hashCode = function () {
  var result = this.providerFactories_1;
  result = imul(result, 31) + this.bindsCallables_1 | 0;
  result = imul(result, 31) + this.multibindsCallables_1 | 0;
  result = imul(result, 31) + this.optionalBindings_1 | 0;
  result = imul(result, 31) + this.accessors_1 | 0;
  result = imul(result, 31) + this.injectors_1 | 0;
  result = imul(result, 31) + this.graphExtensionAccessors_1 | 0;
  result = imul(result, 31) + this.graphExtensionFactories_1 | 0;
  result = imul(result, 31) + this.includedGraphs_1 | 0;
  result = imul(result, 31) + this.bindingContainers_1 | 0;
  result = imul(result, 31) + this.dynamicBindings_1 | 0;
  result = imul(result, 31) + this.graphPrivateKeys_1 | 0;
  result = imul(result, 31) + this.publishedBindsKeys_1 | 0;
  result = imul(result, 31) + this.populatedKeys_1 | 0;
  result = imul(result, 31) + this.validatedKeys_1 | 0;
  result = imul(result, 31) + this.reachableKeys_1 | 0;
  result = imul(result, 31) + this.deferredKeys_1 | 0;
  result = imul(result, 31) + this.unusedInputs_1 | 0;
  result = imul(result, 31) + this.providerProperties_1 | 0;
  result = imul(result, 31) + this.scopedProviderProperties_1 | 0;
  result = imul(result, 31) + this.shards_1 | 0;
  result = imul(result, 31) + this.optimizations_1.hashCode() | 0;
  return result;
};
protoOf(GraphStatsMetadata).equals = function (other) {
  if (this === other)
    return true;
  if (!(other instanceof GraphStatsMetadata))
    return false;
  if (!(this.providerFactories_1 === other.providerFactories_1))
    return false;
  if (!(this.bindsCallables_1 === other.bindsCallables_1))
    return false;
  if (!(this.multibindsCallables_1 === other.multibindsCallables_1))
    return false;
  if (!(this.optionalBindings_1 === other.optionalBindings_1))
    return false;
  if (!(this.accessors_1 === other.accessors_1))
    return false;
  if (!(this.injectors_1 === other.injectors_1))
    return false;
  if (!(this.graphExtensionAccessors_1 === other.graphExtensionAccessors_1))
    return false;
  if (!(this.graphExtensionFactories_1 === other.graphExtensionFactories_1))
    return false;
  if (!(this.includedGraphs_1 === other.includedGraphs_1))
    return false;
  if (!(this.bindingContainers_1 === other.bindingContainers_1))
    return false;
  if (!(this.dynamicBindings_1 === other.dynamicBindings_1))
    return false;
  if (!(this.graphPrivateKeys_1 === other.graphPrivateKeys_1))
    return false;
  if (!(this.publishedBindsKeys_1 === other.publishedBindsKeys_1))
    return false;
  if (!(this.populatedKeys_1 === other.populatedKeys_1))
    return false;
  if (!(this.validatedKeys_1 === other.validatedKeys_1))
    return false;
  if (!(this.reachableKeys_1 === other.reachableKeys_1))
    return false;
  if (!(this.deferredKeys_1 === other.deferredKeys_1))
    return false;
  if (!(this.unusedInputs_1 === other.unusedInputs_1))
    return false;
  if (!(this.providerProperties_1 === other.providerProperties_1))
    return false;
  if (!(this.scopedProviderProperties_1 === other.scopedProviderProperties_1))
    return false;
  if (!(this.shards_1 === other.shards_1))
    return false;
  if (!this.optimizations_1.equals(other.optimizations_1))
    return false;
  return true;
};
function BindingMetadata$Companion$$childSerializers$_anonymous__1o8lg6() {
  return new ArrayListSerializer($serializer_getInstance_20());
}
function Companion_17() {
  Companion_instance_18 = this;
  var tmp = this;
  var tmp_0 = LazyThreadSafetyMode_PUBLICATION_getInstance();
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  tmp.$childSerializers_1 = [null, null, null, null, null, lazy(tmp_0, BindingMetadata$Companion$$childSerializers$_anonymous__1o8lg6), null, null, null, null, null, null, null, null, null, null];
}
var Companion_instance_18;
function Companion_getInstance_17() {
  if (Companion_instance_18 == null)
    new Companion_17();
  return Companion_instance_18;
}
function $serializer_18() {
  $serializer_instance_18 = this;
  var tmp0_serialDesc = new PluginGeneratedSerialDescriptor('dev.zacsweers.metro.gradle.analysis.BindingMetadata', this, 16);
  tmp0_serialDesc.addElement_5pzumi_k$('key', false);
  tmp0_serialDesc.addElement_5pzumi_k$('bindingKind', false);
  tmp0_serialDesc.addElement_5pzumi_k$('scope', true);
  tmp0_serialDesc.addElement_5pzumi_k$('isScoped', false);
  tmp0_serialDesc.addElement_5pzumi_k$('nameHint', false);
  tmp0_serialDesc.addElement_5pzumi_k$('dependencies', false);
  tmp0_serialDesc.addElement_5pzumi_k$('origin', true);
  tmp0_serialDesc.addElement_5pzumi_k$('declaration', true);
  tmp0_serialDesc.addElement_5pzumi_k$('multibinding', true);
  tmp0_serialDesc.addElement_5pzumi_k$('optionalWrapper', true);
  tmp0_serialDesc.addElement_5pzumi_k$('aliasTarget', true);
  tmp0_serialDesc.addElement_5pzumi_k$('isSynthetic', true);
  tmp0_serialDesc.addElement_5pzumi_k$('assistedTarget', true);
  tmp0_serialDesc.addElement_5pzumi_k$('isGraphInput', true);
  tmp0_serialDesc.addElement_5pzumi_k$('extensionType', true);
  tmp0_serialDesc.addElement_5pzumi_k$('graphDependency', true);
  this.descriptor_1 = tmp0_serialDesc;
}
protoOf($serializer_18).serialize_aetqp2_k$ = function (encoder, value) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_output = encoder.beginStructure_yljocp_k$(tmp0_desc);
  var tmp2_cached = Companion_getInstance_17().$childSerializers_1;
  tmp1_output.encodeStringElement_1n5wu2_k$(tmp0_desc, 0, value.key_1);
  tmp1_output.encodeStringElement_1n5wu2_k$(tmp0_desc, 1, value.bindingKind_1);
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 2) ? true : !(value.scope_1 == null)) {
    tmp1_output.encodeNullableSerializableElement_5lquiv_k$(tmp0_desc, 2, StringSerializer_getInstance(), value.scope_1);
  }
  tmp1_output.encodeBooleanElement_ydht7q_k$(tmp0_desc, 3, value.isScoped_1);
  tmp1_output.encodeStringElement_1n5wu2_k$(tmp0_desc, 4, value.nameHint_1);
  tmp1_output.encodeSerializableElement_isqxcl_k$(tmp0_desc, 5, tmp2_cached[5].get_value_j01efc_k$(), value.dependencies_1);
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 6) ? true : !(value.origin_1 == null)) {
    tmp1_output.encodeNullableSerializableElement_5lquiv_k$(tmp0_desc, 6, StringSerializer_getInstance(), value.origin_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 7) ? true : !(value.declaration_1 == null)) {
    tmp1_output.encodeNullableSerializableElement_5lquiv_k$(tmp0_desc, 7, StringSerializer_getInstance(), value.declaration_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 8) ? true : !(value.multibinding_1 == null)) {
    tmp1_output.encodeNullableSerializableElement_5lquiv_k$(tmp0_desc, 8, $serializer_getInstance_23(), value.multibinding_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 9) ? true : !(value.optionalWrapper_1 == null)) {
    tmp1_output.encodeNullableSerializableElement_5lquiv_k$(tmp0_desc, 9, $serializer_getInstance_24(), value.optionalWrapper_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 10) ? true : !(value.aliasTarget_1 == null)) {
    tmp1_output.encodeNullableSerializableElement_5lquiv_k$(tmp0_desc, 10, StringSerializer_getInstance(), value.aliasTarget_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 11) ? true : !(value.isSynthetic_1 === false)) {
    tmp1_output.encodeBooleanElement_ydht7q_k$(tmp0_desc, 11, value.isSynthetic_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 12) ? true : !(value.assistedTarget_1 == null)) {
    tmp1_output.encodeNullableSerializableElement_5lquiv_k$(tmp0_desc, 12, $serializer_getInstance_25(), value.assistedTarget_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 13) ? true : !(value.isGraphInput_1 == null)) {
    tmp1_output.encodeNullableSerializableElement_5lquiv_k$(tmp0_desc, 13, BooleanSerializer_getInstance(), value.isGraphInput_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 14) ? true : !(value.extensionType_1 == null)) {
    tmp1_output.encodeNullableSerializableElement_5lquiv_k$(tmp0_desc, 14, StringSerializer_getInstance(), value.extensionType_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 15) ? true : !(value.graphDependency_1 == null)) {
    tmp1_output.encodeNullableSerializableElement_5lquiv_k$(tmp0_desc, 15, $serializer_getInstance_19(), value.graphDependency_1);
  }
  tmp1_output.endStructure_1xqz0n_k$(tmp0_desc);
};
protoOf($serializer_18).serialize_5ase3y_k$ = function (encoder, value) {
  return this.serialize_aetqp2_k$(encoder, value instanceof BindingMetadata ? value : THROW_CCE());
};
protoOf($serializer_18).deserialize_sy6x50_k$ = function (decoder) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_flag = true;
  var tmp2_index = 0;
  var tmp3_bitMask0 = 0;
  var tmp4_local0 = null;
  var tmp5_local1 = null;
  var tmp6_local2 = null;
  var tmp7_local3 = false;
  var tmp8_local4 = null;
  var tmp9_local5 = null;
  var tmp10_local6 = null;
  var tmp11_local7 = null;
  var tmp12_local8 = null;
  var tmp13_local9 = null;
  var tmp14_local10 = null;
  var tmp15_local11 = false;
  var tmp16_local12 = null;
  var tmp17_local13 = null;
  var tmp18_local14 = null;
  var tmp19_local15 = null;
  var tmp20_input = decoder.beginStructure_yljocp_k$(tmp0_desc);
  var tmp21_cached = Companion_getInstance_17().$childSerializers_1;
  if (tmp20_input.decodeSequentially_xlblqy_k$()) {
    tmp4_local0 = tmp20_input.decodeStringElement_3oenpg_k$(tmp0_desc, 0);
    tmp3_bitMask0 = tmp3_bitMask0 | 1;
    tmp5_local1 = tmp20_input.decodeStringElement_3oenpg_k$(tmp0_desc, 1);
    tmp3_bitMask0 = tmp3_bitMask0 | 2;
    tmp6_local2 = tmp20_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 2, StringSerializer_getInstance(), tmp6_local2);
    tmp3_bitMask0 = tmp3_bitMask0 | 4;
    tmp7_local3 = tmp20_input.decodeBooleanElement_vuyhtj_k$(tmp0_desc, 3);
    tmp3_bitMask0 = tmp3_bitMask0 | 8;
    tmp8_local4 = tmp20_input.decodeStringElement_3oenpg_k$(tmp0_desc, 4);
    tmp3_bitMask0 = tmp3_bitMask0 | 16;
    tmp9_local5 = tmp20_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 5, tmp21_cached[5].get_value_j01efc_k$(), tmp9_local5);
    tmp3_bitMask0 = tmp3_bitMask0 | 32;
    tmp10_local6 = tmp20_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 6, StringSerializer_getInstance(), tmp10_local6);
    tmp3_bitMask0 = tmp3_bitMask0 | 64;
    tmp11_local7 = tmp20_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 7, StringSerializer_getInstance(), tmp11_local7);
    tmp3_bitMask0 = tmp3_bitMask0 | 128;
    tmp12_local8 = tmp20_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 8, $serializer_getInstance_23(), tmp12_local8);
    tmp3_bitMask0 = tmp3_bitMask0 | 256;
    tmp13_local9 = tmp20_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 9, $serializer_getInstance_24(), tmp13_local9);
    tmp3_bitMask0 = tmp3_bitMask0 | 512;
    tmp14_local10 = tmp20_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 10, StringSerializer_getInstance(), tmp14_local10);
    tmp3_bitMask0 = tmp3_bitMask0 | 1024;
    tmp15_local11 = tmp20_input.decodeBooleanElement_vuyhtj_k$(tmp0_desc, 11);
    tmp3_bitMask0 = tmp3_bitMask0 | 2048;
    tmp16_local12 = tmp20_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 12, $serializer_getInstance_25(), tmp16_local12);
    tmp3_bitMask0 = tmp3_bitMask0 | 4096;
    tmp17_local13 = tmp20_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 13, BooleanSerializer_getInstance(), tmp17_local13);
    tmp3_bitMask0 = tmp3_bitMask0 | 8192;
    tmp18_local14 = tmp20_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 14, StringSerializer_getInstance(), tmp18_local14);
    tmp3_bitMask0 = tmp3_bitMask0 | 16384;
    tmp19_local15 = tmp20_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 15, $serializer_getInstance_19(), tmp19_local15);
    tmp3_bitMask0 = tmp3_bitMask0 | 32768;
  } else
    while (tmp1_flag) {
      tmp2_index = tmp20_input.decodeElementIndex_bstkhp_k$(tmp0_desc);
      switch (tmp2_index) {
        case -1:
          tmp1_flag = false;
          break;
        case 0:
          tmp4_local0 = tmp20_input.decodeStringElement_3oenpg_k$(tmp0_desc, 0);
          tmp3_bitMask0 = tmp3_bitMask0 | 1;
          break;
        case 1:
          tmp5_local1 = tmp20_input.decodeStringElement_3oenpg_k$(tmp0_desc, 1);
          tmp3_bitMask0 = tmp3_bitMask0 | 2;
          break;
        case 2:
          tmp6_local2 = tmp20_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 2, StringSerializer_getInstance(), tmp6_local2);
          tmp3_bitMask0 = tmp3_bitMask0 | 4;
          break;
        case 3:
          tmp7_local3 = tmp20_input.decodeBooleanElement_vuyhtj_k$(tmp0_desc, 3);
          tmp3_bitMask0 = tmp3_bitMask0 | 8;
          break;
        case 4:
          tmp8_local4 = tmp20_input.decodeStringElement_3oenpg_k$(tmp0_desc, 4);
          tmp3_bitMask0 = tmp3_bitMask0 | 16;
          break;
        case 5:
          tmp9_local5 = tmp20_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 5, tmp21_cached[5].get_value_j01efc_k$(), tmp9_local5);
          tmp3_bitMask0 = tmp3_bitMask0 | 32;
          break;
        case 6:
          tmp10_local6 = tmp20_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 6, StringSerializer_getInstance(), tmp10_local6);
          tmp3_bitMask0 = tmp3_bitMask0 | 64;
          break;
        case 7:
          tmp11_local7 = tmp20_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 7, StringSerializer_getInstance(), tmp11_local7);
          tmp3_bitMask0 = tmp3_bitMask0 | 128;
          break;
        case 8:
          tmp12_local8 = tmp20_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 8, $serializer_getInstance_23(), tmp12_local8);
          tmp3_bitMask0 = tmp3_bitMask0 | 256;
          break;
        case 9:
          tmp13_local9 = tmp20_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 9, $serializer_getInstance_24(), tmp13_local9);
          tmp3_bitMask0 = tmp3_bitMask0 | 512;
          break;
        case 10:
          tmp14_local10 = tmp20_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 10, StringSerializer_getInstance(), tmp14_local10);
          tmp3_bitMask0 = tmp3_bitMask0 | 1024;
          break;
        case 11:
          tmp15_local11 = tmp20_input.decodeBooleanElement_vuyhtj_k$(tmp0_desc, 11);
          tmp3_bitMask0 = tmp3_bitMask0 | 2048;
          break;
        case 12:
          tmp16_local12 = tmp20_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 12, $serializer_getInstance_25(), tmp16_local12);
          tmp3_bitMask0 = tmp3_bitMask0 | 4096;
          break;
        case 13:
          tmp17_local13 = tmp20_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 13, BooleanSerializer_getInstance(), tmp17_local13);
          tmp3_bitMask0 = tmp3_bitMask0 | 8192;
          break;
        case 14:
          tmp18_local14 = tmp20_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 14, StringSerializer_getInstance(), tmp18_local14);
          tmp3_bitMask0 = tmp3_bitMask0 | 16384;
          break;
        case 15:
          tmp19_local15 = tmp20_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 15, $serializer_getInstance_19(), tmp19_local15);
          tmp3_bitMask0 = tmp3_bitMask0 | 32768;
          break;
        default:
          throw UnknownFieldException_init_$Create$(tmp2_index);
      }
    }
  tmp20_input.endStructure_1xqz0n_k$(tmp0_desc);
  return BindingMetadata_init_$Create$(tmp3_bitMask0, tmp4_local0, tmp5_local1, tmp6_local2, tmp7_local3, tmp8_local4, tmp9_local5, tmp10_local6, tmp11_local7, tmp12_local8, tmp13_local9, tmp14_local10, tmp15_local11, tmp16_local12, tmp17_local13, tmp18_local14, tmp19_local15, null);
};
protoOf($serializer_18).get_descriptor_wjt6a0_k$ = function () {
  return this.descriptor_1;
};
protoOf($serializer_18).childSerializers_5ghqw5_k$ = function () {
  var tmp0_cached = Companion_getInstance_17().$childSerializers_1;
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  return [StringSerializer_getInstance(), StringSerializer_getInstance(), get_nullable(StringSerializer_getInstance()), BooleanSerializer_getInstance(), StringSerializer_getInstance(), tmp0_cached[5].get_value_j01efc_k$(), get_nullable(StringSerializer_getInstance()), get_nullable(StringSerializer_getInstance()), get_nullable($serializer_getInstance_23()), get_nullable($serializer_getInstance_24()), get_nullable(StringSerializer_getInstance()), BooleanSerializer_getInstance(), get_nullable($serializer_getInstance_25()), get_nullable(BooleanSerializer_getInstance()), get_nullable(StringSerializer_getInstance()), get_nullable($serializer_getInstance_19())];
};
var $serializer_instance_18;
function $serializer_getInstance_18() {
  if ($serializer_instance_18 == null)
    new $serializer_18();
  return $serializer_instance_18;
}
function BindingMetadata_init_$Init$(seen0, key, bindingKind, scope, isScoped, nameHint, dependencies, origin, declaration, multibinding, optionalWrapper, aliasTarget, isSynthetic, assistedTarget, isGraphInput, extensionType, graphDependency, serializationConstructorMarker, $this) {
  if (!(59 === (59 & seen0))) {
    throwMissingFieldException(seen0, 59, $serializer_getInstance_18().descriptor_1);
  }
  $this.key_1 = key;
  $this.bindingKind_1 = bindingKind;
  if (0 === (seen0 & 4))
    $this.scope_1 = null;
  else
    $this.scope_1 = scope;
  $this.isScoped_1 = isScoped;
  $this.nameHint_1 = nameHint;
  $this.dependencies_1 = dependencies;
  if (0 === (seen0 & 64))
    $this.origin_1 = null;
  else
    $this.origin_1 = origin;
  if (0 === (seen0 & 128))
    $this.declaration_1 = null;
  else
    $this.declaration_1 = declaration;
  if (0 === (seen0 & 256))
    $this.multibinding_1 = null;
  else
    $this.multibinding_1 = multibinding;
  if (0 === (seen0 & 512))
    $this.optionalWrapper_1 = null;
  else
    $this.optionalWrapper_1 = optionalWrapper;
  if (0 === (seen0 & 1024))
    $this.aliasTarget_1 = null;
  else
    $this.aliasTarget_1 = aliasTarget;
  if (0 === (seen0 & 2048))
    $this.isSynthetic_1 = false;
  else
    $this.isSynthetic_1 = isSynthetic;
  if (0 === (seen0 & 4096))
    $this.assistedTarget_1 = null;
  else
    $this.assistedTarget_1 = assistedTarget;
  if (0 === (seen0 & 8192))
    $this.isGraphInput_1 = null;
  else
    $this.isGraphInput_1 = isGraphInput;
  if (0 === (seen0 & 16384))
    $this.extensionType_1 = null;
  else
    $this.extensionType_1 = extensionType;
  if (0 === (seen0 & 32768))
    $this.graphDependency_1 = null;
  else
    $this.graphDependency_1 = graphDependency;
  return $this;
}
function BindingMetadata_init_$Create$(seen0, key, bindingKind, scope, isScoped, nameHint, dependencies, origin, declaration, multibinding, optionalWrapper, aliasTarget, isSynthetic, assistedTarget, isGraphInput, extensionType, graphDependency, serializationConstructorMarker) {
  return BindingMetadata_init_$Init$(seen0, key, bindingKind, scope, isScoped, nameHint, dependencies, origin, declaration, multibinding, optionalWrapper, aliasTarget, isSynthetic, assistedTarget, isGraphInput, extensionType, graphDependency, serializationConstructorMarker, objectCreate(protoOf(BindingMetadata)));
}
function BindingMetadata(key, bindingKind, scope, isScoped, nameHint, dependencies, origin, declaration, multibinding, optionalWrapper, aliasTarget, isSynthetic, assistedTarget, isGraphInput, extensionType, graphDependency) {
  Companion_getInstance_17();
  scope = scope === VOID ? null : scope;
  origin = origin === VOID ? null : origin;
  declaration = declaration === VOID ? null : declaration;
  multibinding = multibinding === VOID ? null : multibinding;
  optionalWrapper = optionalWrapper === VOID ? null : optionalWrapper;
  aliasTarget = aliasTarget === VOID ? null : aliasTarget;
  isSynthetic = isSynthetic === VOID ? false : isSynthetic;
  assistedTarget = assistedTarget === VOID ? null : assistedTarget;
  isGraphInput = isGraphInput === VOID ? null : isGraphInput;
  extensionType = extensionType === VOID ? null : extensionType;
  graphDependency = graphDependency === VOID ? null : graphDependency;
  this.key_1 = key;
  this.bindingKind_1 = bindingKind;
  this.scope_1 = scope;
  this.isScoped_1 = isScoped;
  this.nameHint_1 = nameHint;
  this.dependencies_1 = dependencies;
  this.origin_1 = origin;
  this.declaration_1 = declaration;
  this.multibinding_1 = multibinding;
  this.optionalWrapper_1 = optionalWrapper;
  this.aliasTarget_1 = aliasTarget;
  this.isSynthetic_1 = isSynthetic;
  this.assistedTarget_1 = assistedTarget;
  this.isGraphInput_1 = isGraphInput;
  this.extensionType_1 = extensionType;
  this.graphDependency_1 = graphDependency;
}
protoOf(BindingMetadata).toString = function () {
  return 'BindingMetadata(key=' + this.key_1 + ', bindingKind=' + this.bindingKind_1 + ', scope=' + this.scope_1 + ', isScoped=' + this.isScoped_1 + ', nameHint=' + this.nameHint_1 + ', dependencies=' + toString(this.dependencies_1) + ', origin=' + this.origin_1 + ', declaration=' + this.declaration_1 + ', multibinding=' + toString_0(this.multibinding_1) + ', optionalWrapper=' + toString_0(this.optionalWrapper_1) + ', aliasTarget=' + this.aliasTarget_1 + ', isSynthetic=' + this.isSynthetic_1 + ', assistedTarget=' + toString_0(this.assistedTarget_1) + ', isGraphInput=' + this.isGraphInput_1 + ', extensionType=' + this.extensionType_1 + ', graphDependency=' + toString_0(this.graphDependency_1) + ')';
};
protoOf(BindingMetadata).hashCode = function () {
  var result = getStringHashCode(this.key_1);
  result = imul(result, 31) + getStringHashCode(this.bindingKind_1) | 0;
  result = imul(result, 31) + (this.scope_1 == null ? 0 : getStringHashCode(this.scope_1)) | 0;
  result = imul(result, 31) + getBooleanHashCode(this.isScoped_1) | 0;
  result = imul(result, 31) + getStringHashCode(this.nameHint_1) | 0;
  result = imul(result, 31) + hashCode(this.dependencies_1) | 0;
  result = imul(result, 31) + (this.origin_1 == null ? 0 : getStringHashCode(this.origin_1)) | 0;
  result = imul(result, 31) + (this.declaration_1 == null ? 0 : getStringHashCode(this.declaration_1)) | 0;
  result = imul(result, 31) + (this.multibinding_1 == null ? 0 : this.multibinding_1.hashCode()) | 0;
  result = imul(result, 31) + (this.optionalWrapper_1 == null ? 0 : this.optionalWrapper_1.hashCode()) | 0;
  result = imul(result, 31) + (this.aliasTarget_1 == null ? 0 : getStringHashCode(this.aliasTarget_1)) | 0;
  result = imul(result, 31) + getBooleanHashCode(this.isSynthetic_1) | 0;
  result = imul(result, 31) + (this.assistedTarget_1 == null ? 0 : this.assistedTarget_1.hashCode()) | 0;
  result = imul(result, 31) + (this.isGraphInput_1 == null ? 0 : getBooleanHashCode(this.isGraphInput_1)) | 0;
  result = imul(result, 31) + (this.extensionType_1 == null ? 0 : getStringHashCode(this.extensionType_1)) | 0;
  result = imul(result, 31) + (this.graphDependency_1 == null ? 0 : this.graphDependency_1.hashCode()) | 0;
  return result;
};
protoOf(BindingMetadata).equals = function (other) {
  if (this === other)
    return true;
  if (!(other instanceof BindingMetadata))
    return false;
  if (!(this.key_1 === other.key_1))
    return false;
  if (!(this.bindingKind_1 === other.bindingKind_1))
    return false;
  if (!(this.scope_1 == other.scope_1))
    return false;
  if (!(this.isScoped_1 === other.isScoped_1))
    return false;
  if (!(this.nameHint_1 === other.nameHint_1))
    return false;
  if (!equals(this.dependencies_1, other.dependencies_1))
    return false;
  if (!(this.origin_1 == other.origin_1))
    return false;
  if (!(this.declaration_1 == other.declaration_1))
    return false;
  if (!equals(this.multibinding_1, other.multibinding_1))
    return false;
  if (!equals(this.optionalWrapper_1, other.optionalWrapper_1))
    return false;
  if (!(this.aliasTarget_1 == other.aliasTarget_1))
    return false;
  if (!(this.isSynthetic_1 === other.isSynthetic_1))
    return false;
  if (!equals(this.assistedTarget_1, other.assistedTarget_1))
    return false;
  if (!(this.isGraphInput_1 == other.isGraphInput_1))
    return false;
  if (!(this.extensionType_1 == other.extensionType_1))
    return false;
  if (!equals(this.graphDependency_1, other.graphDependency_1))
    return false;
  return true;
};
function Companion_18() {
}
var Companion_instance_19;
function Companion_getInstance_18() {
  return Companion_instance_19;
}
function $serializer_19() {
  $serializer_instance_19 = this;
  var tmp0_serialDesc = new PluginGeneratedSerialDescriptor('dev.zacsweers.metro.gradle.analysis.GraphDependencyMetadata', this, 3);
  tmp0_serialDesc.addElement_5pzumi_k$('ownerKey', false);
  tmp0_serialDesc.addElement_5pzumi_k$('ownerGraph', true);
  tmp0_serialDesc.addElement_5pzumi_k$('fromParent', true);
  this.descriptor_1 = tmp0_serialDesc;
}
protoOf($serializer_19).serialize_d4fhba_k$ = function (encoder, value) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_output = encoder.beginStructure_yljocp_k$(tmp0_desc);
  tmp1_output.encodeStringElement_1n5wu2_k$(tmp0_desc, 0, value.ownerKey_1);
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 1) ? true : !(value.ownerGraph_1 == null)) {
    tmp1_output.encodeNullableSerializableElement_5lquiv_k$(tmp0_desc, 1, StringSerializer_getInstance(), value.ownerGraph_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 2) ? true : !(value.fromParent_1 === false)) {
    tmp1_output.encodeBooleanElement_ydht7q_k$(tmp0_desc, 2, value.fromParent_1);
  }
  tmp1_output.endStructure_1xqz0n_k$(tmp0_desc);
};
protoOf($serializer_19).serialize_5ase3y_k$ = function (encoder, value) {
  return this.serialize_d4fhba_k$(encoder, value instanceof GraphDependencyMetadata ? value : THROW_CCE());
};
protoOf($serializer_19).deserialize_sy6x50_k$ = function (decoder) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_flag = true;
  var tmp2_index = 0;
  var tmp3_bitMask0 = 0;
  var tmp4_local0 = null;
  var tmp5_local1 = null;
  var tmp6_local2 = false;
  var tmp7_input = decoder.beginStructure_yljocp_k$(tmp0_desc);
  if (tmp7_input.decodeSequentially_xlblqy_k$()) {
    tmp4_local0 = tmp7_input.decodeStringElement_3oenpg_k$(tmp0_desc, 0);
    tmp3_bitMask0 = tmp3_bitMask0 | 1;
    tmp5_local1 = tmp7_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 1, StringSerializer_getInstance(), tmp5_local1);
    tmp3_bitMask0 = tmp3_bitMask0 | 2;
    tmp6_local2 = tmp7_input.decodeBooleanElement_vuyhtj_k$(tmp0_desc, 2);
    tmp3_bitMask0 = tmp3_bitMask0 | 4;
  } else
    while (tmp1_flag) {
      tmp2_index = tmp7_input.decodeElementIndex_bstkhp_k$(tmp0_desc);
      switch (tmp2_index) {
        case -1:
          tmp1_flag = false;
          break;
        case 0:
          tmp4_local0 = tmp7_input.decodeStringElement_3oenpg_k$(tmp0_desc, 0);
          tmp3_bitMask0 = tmp3_bitMask0 | 1;
          break;
        case 1:
          tmp5_local1 = tmp7_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 1, StringSerializer_getInstance(), tmp5_local1);
          tmp3_bitMask0 = tmp3_bitMask0 | 2;
          break;
        case 2:
          tmp6_local2 = tmp7_input.decodeBooleanElement_vuyhtj_k$(tmp0_desc, 2);
          tmp3_bitMask0 = tmp3_bitMask0 | 4;
          break;
        default:
          throw UnknownFieldException_init_$Create$(tmp2_index);
      }
    }
  tmp7_input.endStructure_1xqz0n_k$(tmp0_desc);
  return GraphDependencyMetadata_init_$Create$(tmp3_bitMask0, tmp4_local0, tmp5_local1, tmp6_local2, null);
};
protoOf($serializer_19).get_descriptor_wjt6a0_k$ = function () {
  return this.descriptor_1;
};
protoOf($serializer_19).childSerializers_5ghqw5_k$ = function () {
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  return [StringSerializer_getInstance(), get_nullable(StringSerializer_getInstance()), BooleanSerializer_getInstance()];
};
var $serializer_instance_19;
function $serializer_getInstance_19() {
  if ($serializer_instance_19 == null)
    new $serializer_19();
  return $serializer_instance_19;
}
function GraphDependencyMetadata_init_$Init$(seen0, ownerKey, ownerGraph, fromParent, serializationConstructorMarker, $this) {
  if (!(1 === (1 & seen0))) {
    throwMissingFieldException(seen0, 1, $serializer_getInstance_19().descriptor_1);
  }
  $this.ownerKey_1 = ownerKey;
  if (0 === (seen0 & 2))
    $this.ownerGraph_1 = null;
  else
    $this.ownerGraph_1 = ownerGraph;
  if (0 === (seen0 & 4))
    $this.fromParent_1 = false;
  else
    $this.fromParent_1 = fromParent;
  return $this;
}
function GraphDependencyMetadata_init_$Create$(seen0, ownerKey, ownerGraph, fromParent, serializationConstructorMarker) {
  return GraphDependencyMetadata_init_$Init$(seen0, ownerKey, ownerGraph, fromParent, serializationConstructorMarker, objectCreate(protoOf(GraphDependencyMetadata)));
}
function GraphDependencyMetadata(ownerKey, ownerGraph, fromParent) {
  ownerGraph = ownerGraph === VOID ? null : ownerGraph;
  fromParent = fromParent === VOID ? false : fromParent;
  this.ownerKey_1 = ownerKey;
  this.ownerGraph_1 = ownerGraph;
  this.fromParent_1 = fromParent;
}
protoOf(GraphDependencyMetadata).toString = function () {
  return 'GraphDependencyMetadata(ownerKey=' + this.ownerKey_1 + ', ownerGraph=' + this.ownerGraph_1 + ', fromParent=' + this.fromParent_1 + ')';
};
protoOf(GraphDependencyMetadata).hashCode = function () {
  var result = getStringHashCode(this.ownerKey_1);
  result = imul(result, 31) + (this.ownerGraph_1 == null ? 0 : getStringHashCode(this.ownerGraph_1)) | 0;
  result = imul(result, 31) + getBooleanHashCode(this.fromParent_1) | 0;
  return result;
};
protoOf(GraphDependencyMetadata).equals = function (other) {
  if (this === other)
    return true;
  if (!(other instanceof GraphDependencyMetadata))
    return false;
  if (!(this.ownerKey_1 === other.ownerKey_1))
    return false;
  if (!(this.ownerGraph_1 == other.ownerGraph_1))
    return false;
  if (!(this.fromParent_1 === other.fromParent_1))
    return false;
  return true;
};
function Companion_19() {
}
var Companion_instance_20;
function Companion_getInstance_19() {
  return Companion_instance_20;
}
function $serializer_20() {
  $serializer_instance_20 = this;
  var tmp0_serialDesc = new PluginGeneratedSerialDescriptor('dev.zacsweers.metro.gradle.analysis.DependencyMetadata', this, 3);
  tmp0_serialDesc.addElement_5pzumi_k$('key', false);
  tmp0_serialDesc.addElement_5pzumi_k$('hasDefault', false);
  tmp0_serialDesc.addElement_5pzumi_k$('wrapperType', true);
  this.descriptor_1 = tmp0_serialDesc;
}
protoOf($serializer_20).serialize_rvnovy_k$ = function (encoder, value) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_output = encoder.beginStructure_yljocp_k$(tmp0_desc);
  tmp1_output.encodeStringElement_1n5wu2_k$(tmp0_desc, 0, value.key_1);
  tmp1_output.encodeBooleanElement_ydht7q_k$(tmp0_desc, 1, value.hasDefault_1);
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 2) ? true : !(value.wrapperType_1 == null)) {
    tmp1_output.encodeNullableSerializableElement_5lquiv_k$(tmp0_desc, 2, StringSerializer_getInstance(), value.wrapperType_1);
  }
  tmp1_output.endStructure_1xqz0n_k$(tmp0_desc);
};
protoOf($serializer_20).serialize_5ase3y_k$ = function (encoder, value) {
  return this.serialize_rvnovy_k$(encoder, value instanceof DependencyMetadata ? value : THROW_CCE());
};
protoOf($serializer_20).deserialize_sy6x50_k$ = function (decoder) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_flag = true;
  var tmp2_index = 0;
  var tmp3_bitMask0 = 0;
  var tmp4_local0 = null;
  var tmp5_local1 = false;
  var tmp6_local2 = null;
  var tmp7_input = decoder.beginStructure_yljocp_k$(tmp0_desc);
  if (tmp7_input.decodeSequentially_xlblqy_k$()) {
    tmp4_local0 = tmp7_input.decodeStringElement_3oenpg_k$(tmp0_desc, 0);
    tmp3_bitMask0 = tmp3_bitMask0 | 1;
    tmp5_local1 = tmp7_input.decodeBooleanElement_vuyhtj_k$(tmp0_desc, 1);
    tmp3_bitMask0 = tmp3_bitMask0 | 2;
    tmp6_local2 = tmp7_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 2, StringSerializer_getInstance(), tmp6_local2);
    tmp3_bitMask0 = tmp3_bitMask0 | 4;
  } else
    while (tmp1_flag) {
      tmp2_index = tmp7_input.decodeElementIndex_bstkhp_k$(tmp0_desc);
      switch (tmp2_index) {
        case -1:
          tmp1_flag = false;
          break;
        case 0:
          tmp4_local0 = tmp7_input.decodeStringElement_3oenpg_k$(tmp0_desc, 0);
          tmp3_bitMask0 = tmp3_bitMask0 | 1;
          break;
        case 1:
          tmp5_local1 = tmp7_input.decodeBooleanElement_vuyhtj_k$(tmp0_desc, 1);
          tmp3_bitMask0 = tmp3_bitMask0 | 2;
          break;
        case 2:
          tmp6_local2 = tmp7_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 2, StringSerializer_getInstance(), tmp6_local2);
          tmp3_bitMask0 = tmp3_bitMask0 | 4;
          break;
        default:
          throw UnknownFieldException_init_$Create$(tmp2_index);
      }
    }
  tmp7_input.endStructure_1xqz0n_k$(tmp0_desc);
  return DependencyMetadata_init_$Create$(tmp3_bitMask0, tmp4_local0, tmp5_local1, tmp6_local2, null);
};
protoOf($serializer_20).get_descriptor_wjt6a0_k$ = function () {
  return this.descriptor_1;
};
protoOf($serializer_20).childSerializers_5ghqw5_k$ = function () {
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  return [StringSerializer_getInstance(), BooleanSerializer_getInstance(), get_nullable(StringSerializer_getInstance())];
};
var $serializer_instance_20;
function $serializer_getInstance_20() {
  if ($serializer_instance_20 == null)
    new $serializer_20();
  return $serializer_instance_20;
}
function DependencyMetadata_init_$Init$(seen0, key, hasDefault, wrapperType, serializationConstructorMarker, $this) {
  if (!(3 === (3 & seen0))) {
    throwMissingFieldException(seen0, 3, $serializer_getInstance_20().descriptor_1);
  }
  $this.key_1 = key;
  $this.hasDefault_1 = hasDefault;
  if (0 === (seen0 & 4))
    $this.wrapperType_1 = null;
  else
    $this.wrapperType_1 = wrapperType;
  return $this;
}
function DependencyMetadata_init_$Create$(seen0, key, hasDefault, wrapperType, serializationConstructorMarker) {
  return DependencyMetadata_init_$Init$(seen0, key, hasDefault, wrapperType, serializationConstructorMarker, objectCreate(protoOf(DependencyMetadata)));
}
function DependencyMetadata(key, hasDefault, wrapperType) {
  wrapperType = wrapperType === VOID ? null : wrapperType;
  this.key_1 = key;
  this.hasDefault_1 = hasDefault;
  this.wrapperType_1 = wrapperType;
}
protoOf(DependencyMetadata).get_isDeferrable_x0h2s3_k$ = function () {
  return !(this.wrapperType_1 == null);
};
protoOf(DependencyMetadata).toString = function () {
  return 'DependencyMetadata(key=' + this.key_1 + ', hasDefault=' + this.hasDefault_1 + ', wrapperType=' + this.wrapperType_1 + ')';
};
protoOf(DependencyMetadata).hashCode = function () {
  var result = getStringHashCode(this.key_1);
  result = imul(result, 31) + getBooleanHashCode(this.hasDefault_1) | 0;
  result = imul(result, 31) + (this.wrapperType_1 == null ? 0 : getStringHashCode(this.wrapperType_1)) | 0;
  return result;
};
protoOf(DependencyMetadata).equals = function (other) {
  if (this === other)
    return true;
  if (!(other instanceof DependencyMetadata))
    return false;
  if (!(this.key_1 === other.key_1))
    return false;
  if (!(this.hasDefault_1 === other.hasDefault_1))
    return false;
  if (!(this.wrapperType_1 == other.wrapperType_1))
    return false;
  return true;
};
function Companion_20() {
}
var Companion_instance_21;
function Companion_getInstance_20() {
  return Companion_instance_21;
}
function $serializer_21() {
  $serializer_instance_21 = this;
  var tmp0_serialDesc = new PluginGeneratedSerialDescriptor('dev.zacsweers.metro.gradle.analysis.AccessorMetadata', this, 8);
  tmp0_serialDesc.addElement_5pzumi_k$('key', false);
  tmp0_serialDesc.addElement_5pzumi_k$('isDeferrable', true);
  tmp0_serialDesc.addElement_5pzumi_k$('name', true);
  tmp0_serialDesc.addElement_5pzumi_k$('isProperty', true);
  tmp0_serialDesc.addElement_5pzumi_k$('isInherited', true);
  tmp0_serialDesc.addElement_5pzumi_k$('declaringGraph', true);
  tmp0_serialDesc.addElement_5pzumi_k$('declaringType', true);
  tmp0_serialDesc.addElement_5pzumi_k$('origin', true);
  this.descriptor_1 = tmp0_serialDesc;
}
protoOf($serializer_21).serialize_l6xc3m_k$ = function (encoder, value) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_output = encoder.beginStructure_yljocp_k$(tmp0_desc);
  tmp1_output.encodeStringElement_1n5wu2_k$(tmp0_desc, 0, value.key_1);
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 1) ? true : !(value.isDeferrable_1 === false)) {
    tmp1_output.encodeBooleanElement_ydht7q_k$(tmp0_desc, 1, value.isDeferrable_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 2) ? true : !(value.name_1 == null)) {
    tmp1_output.encodeNullableSerializableElement_5lquiv_k$(tmp0_desc, 2, StringSerializer_getInstance(), value.name_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 3) ? true : !(value.isProperty_1 === false)) {
    tmp1_output.encodeBooleanElement_ydht7q_k$(tmp0_desc, 3, value.isProperty_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 4) ? true : !(value.isInherited_1 === false)) {
    tmp1_output.encodeBooleanElement_ydht7q_k$(tmp0_desc, 4, value.isInherited_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 5) ? true : !(value.declaringGraph_1 == null)) {
    tmp1_output.encodeNullableSerializableElement_5lquiv_k$(tmp0_desc, 5, StringSerializer_getInstance(), value.declaringGraph_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 6) ? true : !(value.declaringType_1 == null)) {
    tmp1_output.encodeNullableSerializableElement_5lquiv_k$(tmp0_desc, 6, StringSerializer_getInstance(), value.declaringType_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 7) ? true : !(value.origin_1 == null)) {
    tmp1_output.encodeNullableSerializableElement_5lquiv_k$(tmp0_desc, 7, StringSerializer_getInstance(), value.origin_1);
  }
  tmp1_output.endStructure_1xqz0n_k$(tmp0_desc);
};
protoOf($serializer_21).serialize_5ase3y_k$ = function (encoder, value) {
  return this.serialize_l6xc3m_k$(encoder, value instanceof AccessorMetadata ? value : THROW_CCE());
};
protoOf($serializer_21).deserialize_sy6x50_k$ = function (decoder) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_flag = true;
  var tmp2_index = 0;
  var tmp3_bitMask0 = 0;
  var tmp4_local0 = null;
  var tmp5_local1 = false;
  var tmp6_local2 = null;
  var tmp7_local3 = false;
  var tmp8_local4 = false;
  var tmp9_local5 = null;
  var tmp10_local6 = null;
  var tmp11_local7 = null;
  var tmp12_input = decoder.beginStructure_yljocp_k$(tmp0_desc);
  if (tmp12_input.decodeSequentially_xlblqy_k$()) {
    tmp4_local0 = tmp12_input.decodeStringElement_3oenpg_k$(tmp0_desc, 0);
    tmp3_bitMask0 = tmp3_bitMask0 | 1;
    tmp5_local1 = tmp12_input.decodeBooleanElement_vuyhtj_k$(tmp0_desc, 1);
    tmp3_bitMask0 = tmp3_bitMask0 | 2;
    tmp6_local2 = tmp12_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 2, StringSerializer_getInstance(), tmp6_local2);
    tmp3_bitMask0 = tmp3_bitMask0 | 4;
    tmp7_local3 = tmp12_input.decodeBooleanElement_vuyhtj_k$(tmp0_desc, 3);
    tmp3_bitMask0 = tmp3_bitMask0 | 8;
    tmp8_local4 = tmp12_input.decodeBooleanElement_vuyhtj_k$(tmp0_desc, 4);
    tmp3_bitMask0 = tmp3_bitMask0 | 16;
    tmp9_local5 = tmp12_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 5, StringSerializer_getInstance(), tmp9_local5);
    tmp3_bitMask0 = tmp3_bitMask0 | 32;
    tmp10_local6 = tmp12_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 6, StringSerializer_getInstance(), tmp10_local6);
    tmp3_bitMask0 = tmp3_bitMask0 | 64;
    tmp11_local7 = tmp12_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 7, StringSerializer_getInstance(), tmp11_local7);
    tmp3_bitMask0 = tmp3_bitMask0 | 128;
  } else
    while (tmp1_flag) {
      tmp2_index = tmp12_input.decodeElementIndex_bstkhp_k$(tmp0_desc);
      switch (tmp2_index) {
        case -1:
          tmp1_flag = false;
          break;
        case 0:
          tmp4_local0 = tmp12_input.decodeStringElement_3oenpg_k$(tmp0_desc, 0);
          tmp3_bitMask0 = tmp3_bitMask0 | 1;
          break;
        case 1:
          tmp5_local1 = tmp12_input.decodeBooleanElement_vuyhtj_k$(tmp0_desc, 1);
          tmp3_bitMask0 = tmp3_bitMask0 | 2;
          break;
        case 2:
          tmp6_local2 = tmp12_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 2, StringSerializer_getInstance(), tmp6_local2);
          tmp3_bitMask0 = tmp3_bitMask0 | 4;
          break;
        case 3:
          tmp7_local3 = tmp12_input.decodeBooleanElement_vuyhtj_k$(tmp0_desc, 3);
          tmp3_bitMask0 = tmp3_bitMask0 | 8;
          break;
        case 4:
          tmp8_local4 = tmp12_input.decodeBooleanElement_vuyhtj_k$(tmp0_desc, 4);
          tmp3_bitMask0 = tmp3_bitMask0 | 16;
          break;
        case 5:
          tmp9_local5 = tmp12_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 5, StringSerializer_getInstance(), tmp9_local5);
          tmp3_bitMask0 = tmp3_bitMask0 | 32;
          break;
        case 6:
          tmp10_local6 = tmp12_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 6, StringSerializer_getInstance(), tmp10_local6);
          tmp3_bitMask0 = tmp3_bitMask0 | 64;
          break;
        case 7:
          tmp11_local7 = tmp12_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 7, StringSerializer_getInstance(), tmp11_local7);
          tmp3_bitMask0 = tmp3_bitMask0 | 128;
          break;
        default:
          throw UnknownFieldException_init_$Create$(tmp2_index);
      }
    }
  tmp12_input.endStructure_1xqz0n_k$(tmp0_desc);
  return AccessorMetadata_init_$Create$(tmp3_bitMask0, tmp4_local0, tmp5_local1, tmp6_local2, tmp7_local3, tmp8_local4, tmp9_local5, tmp10_local6, tmp11_local7, null);
};
protoOf($serializer_21).get_descriptor_wjt6a0_k$ = function () {
  return this.descriptor_1;
};
protoOf($serializer_21).childSerializers_5ghqw5_k$ = function () {
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  return [StringSerializer_getInstance(), BooleanSerializer_getInstance(), get_nullable(StringSerializer_getInstance()), BooleanSerializer_getInstance(), BooleanSerializer_getInstance(), get_nullable(StringSerializer_getInstance()), get_nullable(StringSerializer_getInstance()), get_nullable(StringSerializer_getInstance())];
};
var $serializer_instance_21;
function $serializer_getInstance_21() {
  if ($serializer_instance_21 == null)
    new $serializer_21();
  return $serializer_instance_21;
}
function AccessorMetadata_init_$Init$(seen0, key, isDeferrable, name, isProperty, isInherited, declaringGraph, declaringType, origin, serializationConstructorMarker, $this) {
  if (!(1 === (1 & seen0))) {
    throwMissingFieldException(seen0, 1, $serializer_getInstance_21().descriptor_1);
  }
  $this.key_1 = key;
  if (0 === (seen0 & 2))
    $this.isDeferrable_1 = false;
  else
    $this.isDeferrable_1 = isDeferrable;
  if (0 === (seen0 & 4))
    $this.name_1 = null;
  else
    $this.name_1 = name;
  if (0 === (seen0 & 8))
    $this.isProperty_1 = false;
  else
    $this.isProperty_1 = isProperty;
  if (0 === (seen0 & 16))
    $this.isInherited_1 = false;
  else
    $this.isInherited_1 = isInherited;
  if (0 === (seen0 & 32))
    $this.declaringGraph_1 = null;
  else
    $this.declaringGraph_1 = declaringGraph;
  if (0 === (seen0 & 64))
    $this.declaringType_1 = null;
  else
    $this.declaringType_1 = declaringType;
  if (0 === (seen0 & 128))
    $this.origin_1 = null;
  else
    $this.origin_1 = origin;
  return $this;
}
function AccessorMetadata_init_$Create$(seen0, key, isDeferrable, name, isProperty, isInherited, declaringGraph, declaringType, origin, serializationConstructorMarker) {
  return AccessorMetadata_init_$Init$(seen0, key, isDeferrable, name, isProperty, isInherited, declaringGraph, declaringType, origin, serializationConstructorMarker, objectCreate(protoOf(AccessorMetadata)));
}
function AccessorMetadata(key, isDeferrable, name, isProperty, isInherited, declaringGraph, declaringType, origin) {
  isDeferrable = isDeferrable === VOID ? false : isDeferrable;
  name = name === VOID ? null : name;
  isProperty = isProperty === VOID ? false : isProperty;
  isInherited = isInherited === VOID ? false : isInherited;
  declaringGraph = declaringGraph === VOID ? null : declaringGraph;
  declaringType = declaringType === VOID ? null : declaringType;
  origin = origin === VOID ? null : origin;
  this.key_1 = key;
  this.isDeferrable_1 = isDeferrable;
  this.name_1 = name;
  this.isProperty_1 = isProperty;
  this.isInherited_1 = isInherited;
  this.declaringGraph_1 = declaringGraph;
  this.declaringType_1 = declaringType;
  this.origin_1 = origin;
}
protoOf(AccessorMetadata).toString = function () {
  return 'AccessorMetadata(key=' + this.key_1 + ', isDeferrable=' + this.isDeferrable_1 + ', name=' + this.name_1 + ', isProperty=' + this.isProperty_1 + ', isInherited=' + this.isInherited_1 + ', declaringGraph=' + this.declaringGraph_1 + ', declaringType=' + this.declaringType_1 + ', origin=' + this.origin_1 + ')';
};
protoOf(AccessorMetadata).hashCode = function () {
  var result = getStringHashCode(this.key_1);
  result = imul(result, 31) + getBooleanHashCode(this.isDeferrable_1) | 0;
  result = imul(result, 31) + (this.name_1 == null ? 0 : getStringHashCode(this.name_1)) | 0;
  result = imul(result, 31) + getBooleanHashCode(this.isProperty_1) | 0;
  result = imul(result, 31) + getBooleanHashCode(this.isInherited_1) | 0;
  result = imul(result, 31) + (this.declaringGraph_1 == null ? 0 : getStringHashCode(this.declaringGraph_1)) | 0;
  result = imul(result, 31) + (this.declaringType_1 == null ? 0 : getStringHashCode(this.declaringType_1)) | 0;
  result = imul(result, 31) + (this.origin_1 == null ? 0 : getStringHashCode(this.origin_1)) | 0;
  return result;
};
protoOf(AccessorMetadata).equals = function (other) {
  if (this === other)
    return true;
  if (!(other instanceof AccessorMetadata))
    return false;
  if (!(this.key_1 === other.key_1))
    return false;
  if (!(this.isDeferrable_1 === other.isDeferrable_1))
    return false;
  if (!(this.name_1 == other.name_1))
    return false;
  if (!(this.isProperty_1 === other.isProperty_1))
    return false;
  if (!(this.isInherited_1 === other.isInherited_1))
    return false;
  if (!(this.declaringGraph_1 == other.declaringGraph_1))
    return false;
  if (!(this.declaringType_1 == other.declaringType_1))
    return false;
  if (!(this.origin_1 == other.origin_1))
    return false;
  return true;
};
function RootsMetadata$Companion$$childSerializers$_anonymous__po78hi() {
  return new ArrayListSerializer($serializer_getInstance_21());
}
function RootsMetadata$Companion$$childSerializers$_anonymous__po78hi_0() {
  return new ArrayListSerializer($serializer_getInstance_26());
}
function Companion_21() {
  Companion_instance_22 = this;
  var tmp = this;
  var tmp_0 = LazyThreadSafetyMode_PUBLICATION_getInstance();
  var tmp_1 = lazy(tmp_0, RootsMetadata$Companion$$childSerializers$_anonymous__po78hi);
  var tmp_2 = LazyThreadSafetyMode_PUBLICATION_getInstance();
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  tmp.$childSerializers_1 = [tmp_1, lazy(tmp_2, RootsMetadata$Companion$$childSerializers$_anonymous__po78hi_0)];
}
var Companion_instance_22;
function Companion_getInstance_21() {
  if (Companion_instance_22 == null)
    new Companion_21();
  return Companion_instance_22;
}
function $serializer_22() {
  $serializer_instance_22 = this;
  var tmp0_serialDesc = new PluginGeneratedSerialDescriptor('dev.zacsweers.metro.gradle.analysis.RootsMetadata', this, 2);
  tmp0_serialDesc.addElement_5pzumi_k$('accessors', true);
  tmp0_serialDesc.addElement_5pzumi_k$('injectors', true);
  this.descriptor_1 = tmp0_serialDesc;
}
protoOf($serializer_22).serialize_dh0i2a_k$ = function (encoder, value) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_output = encoder.beginStructure_yljocp_k$(tmp0_desc);
  var tmp2_cached = Companion_getInstance_21().$childSerializers_1;
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 0) ? true : !equals(value.accessors_1, emptyList())) {
    tmp1_output.encodeSerializableElement_isqxcl_k$(tmp0_desc, 0, tmp2_cached[0].get_value_j01efc_k$(), value.accessors_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 1) ? true : !equals(value.injectors_1, emptyList())) {
    tmp1_output.encodeSerializableElement_isqxcl_k$(tmp0_desc, 1, tmp2_cached[1].get_value_j01efc_k$(), value.injectors_1);
  }
  tmp1_output.endStructure_1xqz0n_k$(tmp0_desc);
};
protoOf($serializer_22).serialize_5ase3y_k$ = function (encoder, value) {
  return this.serialize_dh0i2a_k$(encoder, value instanceof RootsMetadata ? value : THROW_CCE());
};
protoOf($serializer_22).deserialize_sy6x50_k$ = function (decoder) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_flag = true;
  var tmp2_index = 0;
  var tmp3_bitMask0 = 0;
  var tmp4_local0 = null;
  var tmp5_local1 = null;
  var tmp6_input = decoder.beginStructure_yljocp_k$(tmp0_desc);
  var tmp7_cached = Companion_getInstance_21().$childSerializers_1;
  if (tmp6_input.decodeSequentially_xlblqy_k$()) {
    tmp4_local0 = tmp6_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 0, tmp7_cached[0].get_value_j01efc_k$(), tmp4_local0);
    tmp3_bitMask0 = tmp3_bitMask0 | 1;
    tmp5_local1 = tmp6_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 1, tmp7_cached[1].get_value_j01efc_k$(), tmp5_local1);
    tmp3_bitMask0 = tmp3_bitMask0 | 2;
  } else
    while (tmp1_flag) {
      tmp2_index = tmp6_input.decodeElementIndex_bstkhp_k$(tmp0_desc);
      switch (tmp2_index) {
        case -1:
          tmp1_flag = false;
          break;
        case 0:
          tmp4_local0 = tmp6_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 0, tmp7_cached[0].get_value_j01efc_k$(), tmp4_local0);
          tmp3_bitMask0 = tmp3_bitMask0 | 1;
          break;
        case 1:
          tmp5_local1 = tmp6_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 1, tmp7_cached[1].get_value_j01efc_k$(), tmp5_local1);
          tmp3_bitMask0 = tmp3_bitMask0 | 2;
          break;
        default:
          throw UnknownFieldException_init_$Create$(tmp2_index);
      }
    }
  tmp6_input.endStructure_1xqz0n_k$(tmp0_desc);
  return RootsMetadata_init_$Create$(tmp3_bitMask0, tmp4_local0, tmp5_local1, null);
};
protoOf($serializer_22).get_descriptor_wjt6a0_k$ = function () {
  return this.descriptor_1;
};
protoOf($serializer_22).childSerializers_5ghqw5_k$ = function () {
  var tmp0_cached = Companion_getInstance_21().$childSerializers_1;
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  return [tmp0_cached[0].get_value_j01efc_k$(), tmp0_cached[1].get_value_j01efc_k$()];
};
var $serializer_instance_22;
function $serializer_getInstance_22() {
  if ($serializer_instance_22 == null)
    new $serializer_22();
  return $serializer_instance_22;
}
function RootsMetadata_init_$Init$(seen0, accessors, injectors, serializationConstructorMarker, $this) {
  if (!(0 === (0 & seen0))) {
    throwMissingFieldException(seen0, 0, $serializer_getInstance_22().descriptor_1);
  }
  if (0 === (seen0 & 1))
    $this.accessors_1 = emptyList();
  else
    $this.accessors_1 = accessors;
  if (0 === (seen0 & 2))
    $this.injectors_1 = emptyList();
  else
    $this.injectors_1 = injectors;
  return $this;
}
function RootsMetadata_init_$Create$(seen0, accessors, injectors, serializationConstructorMarker) {
  return RootsMetadata_init_$Init$(seen0, accessors, injectors, serializationConstructorMarker, objectCreate(protoOf(RootsMetadata)));
}
function RootsMetadata(accessors, injectors) {
  Companion_getInstance_21();
  accessors = accessors === VOID ? emptyList() : accessors;
  injectors = injectors === VOID ? emptyList() : injectors;
  this.accessors_1 = accessors;
  this.injectors_1 = injectors;
}
protoOf(RootsMetadata).toString = function () {
  return 'RootsMetadata(accessors=' + toString(this.accessors_1) + ', injectors=' + toString(this.injectors_1) + ')';
};
protoOf(RootsMetadata).hashCode = function () {
  var result = hashCode(this.accessors_1);
  result = imul(result, 31) + hashCode(this.injectors_1) | 0;
  return result;
};
protoOf(RootsMetadata).equals = function (other) {
  if (this === other)
    return true;
  if (!(other instanceof RootsMetadata))
    return false;
  if (!equals(this.accessors_1, other.accessors_1))
    return false;
  if (!equals(this.injectors_1, other.injectors_1))
    return false;
  return true;
};
function MultibindingMetadata$Companion$$childSerializers$_anonymous__uriuun() {
  return new ArrayListSerializer(StringSerializer_getInstance());
}
function Companion_22() {
  Companion_instance_23 = this;
  var tmp = this;
  var tmp_0 = LazyThreadSafetyMode_PUBLICATION_getInstance();
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  tmp.$childSerializers_1 = [null, null, lazy(tmp_0, MultibindingMetadata$Companion$$childSerializers$_anonymous__uriuun)];
}
var Companion_instance_23;
function Companion_getInstance_22() {
  if (Companion_instance_23 == null)
    new Companion_22();
  return Companion_instance_23;
}
function $serializer_23() {
  $serializer_instance_23 = this;
  var tmp0_serialDesc = new PluginGeneratedSerialDescriptor('dev.zacsweers.metro.gradle.analysis.MultibindingMetadata', this, 3);
  tmp0_serialDesc.addElement_5pzumi_k$('type', false);
  tmp0_serialDesc.addElement_5pzumi_k$('allowEmpty', false);
  tmp0_serialDesc.addElement_5pzumi_k$('sources', false);
  this.descriptor_1 = tmp0_serialDesc;
}
protoOf($serializer_23).serialize_9314hv_k$ = function (encoder, value) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_output = encoder.beginStructure_yljocp_k$(tmp0_desc);
  var tmp2_cached = Companion_getInstance_22().$childSerializers_1;
  tmp1_output.encodeStringElement_1n5wu2_k$(tmp0_desc, 0, value.type_1);
  tmp1_output.encodeBooleanElement_ydht7q_k$(tmp0_desc, 1, value.allowEmpty_1);
  tmp1_output.encodeSerializableElement_isqxcl_k$(tmp0_desc, 2, tmp2_cached[2].get_value_j01efc_k$(), value.sources_1);
  tmp1_output.endStructure_1xqz0n_k$(tmp0_desc);
};
protoOf($serializer_23).serialize_5ase3y_k$ = function (encoder, value) {
  return this.serialize_9314hv_k$(encoder, value instanceof MultibindingMetadata ? value : THROW_CCE());
};
protoOf($serializer_23).deserialize_sy6x50_k$ = function (decoder) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_flag = true;
  var tmp2_index = 0;
  var tmp3_bitMask0 = 0;
  var tmp4_local0 = null;
  var tmp5_local1 = false;
  var tmp6_local2 = null;
  var tmp7_input = decoder.beginStructure_yljocp_k$(tmp0_desc);
  var tmp8_cached = Companion_getInstance_22().$childSerializers_1;
  if (tmp7_input.decodeSequentially_xlblqy_k$()) {
    tmp4_local0 = tmp7_input.decodeStringElement_3oenpg_k$(tmp0_desc, 0);
    tmp3_bitMask0 = tmp3_bitMask0 | 1;
    tmp5_local1 = tmp7_input.decodeBooleanElement_vuyhtj_k$(tmp0_desc, 1);
    tmp3_bitMask0 = tmp3_bitMask0 | 2;
    tmp6_local2 = tmp7_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 2, tmp8_cached[2].get_value_j01efc_k$(), tmp6_local2);
    tmp3_bitMask0 = tmp3_bitMask0 | 4;
  } else
    while (tmp1_flag) {
      tmp2_index = tmp7_input.decodeElementIndex_bstkhp_k$(tmp0_desc);
      switch (tmp2_index) {
        case -1:
          tmp1_flag = false;
          break;
        case 0:
          tmp4_local0 = tmp7_input.decodeStringElement_3oenpg_k$(tmp0_desc, 0);
          tmp3_bitMask0 = tmp3_bitMask0 | 1;
          break;
        case 1:
          tmp5_local1 = tmp7_input.decodeBooleanElement_vuyhtj_k$(tmp0_desc, 1);
          tmp3_bitMask0 = tmp3_bitMask0 | 2;
          break;
        case 2:
          tmp6_local2 = tmp7_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 2, tmp8_cached[2].get_value_j01efc_k$(), tmp6_local2);
          tmp3_bitMask0 = tmp3_bitMask0 | 4;
          break;
        default:
          throw UnknownFieldException_init_$Create$(tmp2_index);
      }
    }
  tmp7_input.endStructure_1xqz0n_k$(tmp0_desc);
  return MultibindingMetadata_init_$Create$(tmp3_bitMask0, tmp4_local0, tmp5_local1, tmp6_local2, null);
};
protoOf($serializer_23).get_descriptor_wjt6a0_k$ = function () {
  return this.descriptor_1;
};
protoOf($serializer_23).childSerializers_5ghqw5_k$ = function () {
  var tmp0_cached = Companion_getInstance_22().$childSerializers_1;
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  return [StringSerializer_getInstance(), BooleanSerializer_getInstance(), tmp0_cached[2].get_value_j01efc_k$()];
};
var $serializer_instance_23;
function $serializer_getInstance_23() {
  if ($serializer_instance_23 == null)
    new $serializer_23();
  return $serializer_instance_23;
}
function MultibindingMetadata_init_$Init$(seen0, type, allowEmpty, sources, serializationConstructorMarker, $this) {
  if (!(7 === (7 & seen0))) {
    throwMissingFieldException(seen0, 7, $serializer_getInstance_23().descriptor_1);
  }
  $this.type_1 = type;
  $this.allowEmpty_1 = allowEmpty;
  $this.sources_1 = sources;
  return $this;
}
function MultibindingMetadata_init_$Create$(seen0, type, allowEmpty, sources, serializationConstructorMarker) {
  return MultibindingMetadata_init_$Init$(seen0, type, allowEmpty, sources, serializationConstructorMarker, objectCreate(protoOf(MultibindingMetadata)));
}
function MultibindingMetadata(type, allowEmpty, sources) {
  Companion_getInstance_22();
  this.type_1 = type;
  this.allowEmpty_1 = allowEmpty;
  this.sources_1 = sources;
}
protoOf(MultibindingMetadata).toString = function () {
  return 'MultibindingMetadata(type=' + this.type_1 + ', allowEmpty=' + this.allowEmpty_1 + ', sources=' + toString(this.sources_1) + ')';
};
protoOf(MultibindingMetadata).hashCode = function () {
  var result = getStringHashCode(this.type_1);
  result = imul(result, 31) + getBooleanHashCode(this.allowEmpty_1) | 0;
  result = imul(result, 31) + hashCode(this.sources_1) | 0;
  return result;
};
protoOf(MultibindingMetadata).equals = function (other) {
  if (this === other)
    return true;
  if (!(other instanceof MultibindingMetadata))
    return false;
  if (!(this.type_1 === other.type_1))
    return false;
  if (!(this.allowEmpty_1 === other.allowEmpty_1))
    return false;
  if (!equals(this.sources_1, other.sources_1))
    return false;
  return true;
};
function Companion_23() {
}
var Companion_instance_24;
function Companion_getInstance_23() {
  return Companion_instance_24;
}
function $serializer_24() {
  $serializer_instance_24 = this;
  var tmp0_serialDesc = new PluginGeneratedSerialDescriptor('dev.zacsweers.metro.gradle.analysis.OptionalWrapperMetadata', this, 3);
  tmp0_serialDesc.addElement_5pzumi_k$('wrappedType', false);
  tmp0_serialDesc.addElement_5pzumi_k$('allowsAbsent', false);
  tmp0_serialDesc.addElement_5pzumi_k$('wrapperKey', false);
  this.descriptor_1 = tmp0_serialDesc;
}
protoOf($serializer_24).serialize_g2zmic_k$ = function (encoder, value) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_output = encoder.beginStructure_yljocp_k$(tmp0_desc);
  tmp1_output.encodeStringElement_1n5wu2_k$(tmp0_desc, 0, value.wrappedType_1);
  tmp1_output.encodeBooleanElement_ydht7q_k$(tmp0_desc, 1, value.allowsAbsent_1);
  tmp1_output.encodeStringElement_1n5wu2_k$(tmp0_desc, 2, value.wrapperKey_1);
  tmp1_output.endStructure_1xqz0n_k$(tmp0_desc);
};
protoOf($serializer_24).serialize_5ase3y_k$ = function (encoder, value) {
  return this.serialize_g2zmic_k$(encoder, value instanceof OptionalWrapperMetadata ? value : THROW_CCE());
};
protoOf($serializer_24).deserialize_sy6x50_k$ = function (decoder) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_flag = true;
  var tmp2_index = 0;
  var tmp3_bitMask0 = 0;
  var tmp4_local0 = null;
  var tmp5_local1 = false;
  var tmp6_local2 = null;
  var tmp7_input = decoder.beginStructure_yljocp_k$(tmp0_desc);
  if (tmp7_input.decodeSequentially_xlblqy_k$()) {
    tmp4_local0 = tmp7_input.decodeStringElement_3oenpg_k$(tmp0_desc, 0);
    tmp3_bitMask0 = tmp3_bitMask0 | 1;
    tmp5_local1 = tmp7_input.decodeBooleanElement_vuyhtj_k$(tmp0_desc, 1);
    tmp3_bitMask0 = tmp3_bitMask0 | 2;
    tmp6_local2 = tmp7_input.decodeStringElement_3oenpg_k$(tmp0_desc, 2);
    tmp3_bitMask0 = tmp3_bitMask0 | 4;
  } else
    while (tmp1_flag) {
      tmp2_index = tmp7_input.decodeElementIndex_bstkhp_k$(tmp0_desc);
      switch (tmp2_index) {
        case -1:
          tmp1_flag = false;
          break;
        case 0:
          tmp4_local0 = tmp7_input.decodeStringElement_3oenpg_k$(tmp0_desc, 0);
          tmp3_bitMask0 = tmp3_bitMask0 | 1;
          break;
        case 1:
          tmp5_local1 = tmp7_input.decodeBooleanElement_vuyhtj_k$(tmp0_desc, 1);
          tmp3_bitMask0 = tmp3_bitMask0 | 2;
          break;
        case 2:
          tmp6_local2 = tmp7_input.decodeStringElement_3oenpg_k$(tmp0_desc, 2);
          tmp3_bitMask0 = tmp3_bitMask0 | 4;
          break;
        default:
          throw UnknownFieldException_init_$Create$(tmp2_index);
      }
    }
  tmp7_input.endStructure_1xqz0n_k$(tmp0_desc);
  return OptionalWrapperMetadata_init_$Create$(tmp3_bitMask0, tmp4_local0, tmp5_local1, tmp6_local2, null);
};
protoOf($serializer_24).get_descriptor_wjt6a0_k$ = function () {
  return this.descriptor_1;
};
protoOf($serializer_24).childSerializers_5ghqw5_k$ = function () {
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  return [StringSerializer_getInstance(), BooleanSerializer_getInstance(), StringSerializer_getInstance()];
};
var $serializer_instance_24;
function $serializer_getInstance_24() {
  if ($serializer_instance_24 == null)
    new $serializer_24();
  return $serializer_instance_24;
}
function OptionalWrapperMetadata_init_$Init$(seen0, wrappedType, allowsAbsent, wrapperKey, serializationConstructorMarker, $this) {
  if (!(7 === (7 & seen0))) {
    throwMissingFieldException(seen0, 7, $serializer_getInstance_24().descriptor_1);
  }
  $this.wrappedType_1 = wrappedType;
  $this.allowsAbsent_1 = allowsAbsent;
  $this.wrapperKey_1 = wrapperKey;
  return $this;
}
function OptionalWrapperMetadata_init_$Create$(seen0, wrappedType, allowsAbsent, wrapperKey, serializationConstructorMarker) {
  return OptionalWrapperMetadata_init_$Init$(seen0, wrappedType, allowsAbsent, wrapperKey, serializationConstructorMarker, objectCreate(protoOf(OptionalWrapperMetadata)));
}
function OptionalWrapperMetadata(wrappedType, allowsAbsent, wrapperKey) {
  this.wrappedType_1 = wrappedType;
  this.allowsAbsent_1 = allowsAbsent;
  this.wrapperKey_1 = wrapperKey;
}
protoOf(OptionalWrapperMetadata).toString = function () {
  return 'OptionalWrapperMetadata(wrappedType=' + this.wrappedType_1 + ', allowsAbsent=' + this.allowsAbsent_1 + ', wrapperKey=' + this.wrapperKey_1 + ')';
};
protoOf(OptionalWrapperMetadata).hashCode = function () {
  var result = getStringHashCode(this.wrappedType_1);
  result = imul(result, 31) + getBooleanHashCode(this.allowsAbsent_1) | 0;
  result = imul(result, 31) + getStringHashCode(this.wrapperKey_1) | 0;
  return result;
};
protoOf(OptionalWrapperMetadata).equals = function (other) {
  if (this === other)
    return true;
  if (!(other instanceof OptionalWrapperMetadata))
    return false;
  if (!(this.wrappedType_1 === other.wrappedType_1))
    return false;
  if (!(this.allowsAbsent_1 === other.allowsAbsent_1))
    return false;
  if (!(this.wrapperKey_1 === other.wrapperKey_1))
    return false;
  return true;
};
function AssistedTargetMetadata$Companion$$childSerializers$_anonymous__r3uxem() {
  return new ArrayListSerializer($serializer_getInstance_20());
}
function AssistedTargetMetadata$Companion$$childSerializers$_anonymous__r3uxem_0() {
  return new ArrayListSerializer($serializer_getInstance_30());
}
function Companion_24() {
  Companion_instance_25 = this;
  var tmp = this;
  var tmp_0 = LazyThreadSafetyMode_PUBLICATION_getInstance();
  var tmp_1 = lazy(tmp_0, AssistedTargetMetadata$Companion$$childSerializers$_anonymous__r3uxem);
  var tmp_2 = LazyThreadSafetyMode_PUBLICATION_getInstance();
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  tmp.$childSerializers_1 = [null, null, null, null, null, tmp_1, null, null, null, null, null, lazy(tmp_2, AssistedTargetMetadata$Companion$$childSerializers$_anonymous__r3uxem_0)];
}
var Companion_instance_25;
function Companion_getInstance_24() {
  if (Companion_instance_25 == null)
    new Companion_24();
  return Companion_instance_25;
}
function $serializer_25() {
  $serializer_instance_25 = this;
  var tmp0_serialDesc = new PluginGeneratedSerialDescriptor('dev.zacsweers.metro.gradle.analysis.AssistedTargetMetadata', this, 12);
  tmp0_serialDesc.addElement_5pzumi_k$('key', false);
  tmp0_serialDesc.addElement_5pzumi_k$('bindingKind', false);
  tmp0_serialDesc.addElement_5pzumi_k$('scope', true);
  tmp0_serialDesc.addElement_5pzumi_k$('isScoped', true);
  tmp0_serialDesc.addElement_5pzumi_k$('nameHint', false);
  tmp0_serialDesc.addElement_5pzumi_k$('dependencies', false);
  tmp0_serialDesc.addElement_5pzumi_k$('origin', true);
  tmp0_serialDesc.addElement_5pzumi_k$('declaration', true);
  tmp0_serialDesc.addElement_5pzumi_k$('multibinding', true);
  tmp0_serialDesc.addElement_5pzumi_k$('optionalWrapper', true);
  tmp0_serialDesc.addElement_5pzumi_k$('isSynthetic', true);
  tmp0_serialDesc.addElement_5pzumi_k$('assistedParameters', true);
  this.descriptor_1 = tmp0_serialDesc;
}
protoOf($serializer_25).serialize_fgidsg_k$ = function (encoder, value) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_output = encoder.beginStructure_yljocp_k$(tmp0_desc);
  var tmp2_cached = Companion_getInstance_24().$childSerializers_1;
  tmp1_output.encodeStringElement_1n5wu2_k$(tmp0_desc, 0, value.key_1);
  tmp1_output.encodeStringElement_1n5wu2_k$(tmp0_desc, 1, value.bindingKind_1);
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 2) ? true : !(value.scope_1 == null)) {
    tmp1_output.encodeNullableSerializableElement_5lquiv_k$(tmp0_desc, 2, StringSerializer_getInstance(), value.scope_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 3) ? true : !(value.isScoped_1 === false)) {
    tmp1_output.encodeBooleanElement_ydht7q_k$(tmp0_desc, 3, value.isScoped_1);
  }
  tmp1_output.encodeStringElement_1n5wu2_k$(tmp0_desc, 4, value.nameHint_1);
  tmp1_output.encodeSerializableElement_isqxcl_k$(tmp0_desc, 5, tmp2_cached[5].get_value_j01efc_k$(), value.dependencies_1);
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 6) ? true : !(value.origin_1 == null)) {
    tmp1_output.encodeNullableSerializableElement_5lquiv_k$(tmp0_desc, 6, StringSerializer_getInstance(), value.origin_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 7) ? true : !(value.declaration_1 == null)) {
    tmp1_output.encodeNullableSerializableElement_5lquiv_k$(tmp0_desc, 7, StringSerializer_getInstance(), value.declaration_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 8) ? true : !(value.multibinding_1 == null)) {
    tmp1_output.encodeNullableSerializableElement_5lquiv_k$(tmp0_desc, 8, $serializer_getInstance_23(), value.multibinding_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 9) ? true : !(value.optionalWrapper_1 == null)) {
    tmp1_output.encodeNullableSerializableElement_5lquiv_k$(tmp0_desc, 9, $serializer_getInstance_24(), value.optionalWrapper_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 10) ? true : !(value.isSynthetic_1 === false)) {
    tmp1_output.encodeBooleanElement_ydht7q_k$(tmp0_desc, 10, value.isSynthetic_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 11) ? true : !equals(value.assistedParameters_1, emptyList())) {
    tmp1_output.encodeSerializableElement_isqxcl_k$(tmp0_desc, 11, tmp2_cached[11].get_value_j01efc_k$(), value.assistedParameters_1);
  }
  tmp1_output.endStructure_1xqz0n_k$(tmp0_desc);
};
protoOf($serializer_25).serialize_5ase3y_k$ = function (encoder, value) {
  return this.serialize_fgidsg_k$(encoder, value instanceof AssistedTargetMetadata ? value : THROW_CCE());
};
protoOf($serializer_25).deserialize_sy6x50_k$ = function (decoder) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_flag = true;
  var tmp2_index = 0;
  var tmp3_bitMask0 = 0;
  var tmp4_local0 = null;
  var tmp5_local1 = null;
  var tmp6_local2 = null;
  var tmp7_local3 = false;
  var tmp8_local4 = null;
  var tmp9_local5 = null;
  var tmp10_local6 = null;
  var tmp11_local7 = null;
  var tmp12_local8 = null;
  var tmp13_local9 = null;
  var tmp14_local10 = false;
  var tmp15_local11 = null;
  var tmp16_input = decoder.beginStructure_yljocp_k$(tmp0_desc);
  var tmp17_cached = Companion_getInstance_24().$childSerializers_1;
  if (tmp16_input.decodeSequentially_xlblqy_k$()) {
    tmp4_local0 = tmp16_input.decodeStringElement_3oenpg_k$(tmp0_desc, 0);
    tmp3_bitMask0 = tmp3_bitMask0 | 1;
    tmp5_local1 = tmp16_input.decodeStringElement_3oenpg_k$(tmp0_desc, 1);
    tmp3_bitMask0 = tmp3_bitMask0 | 2;
    tmp6_local2 = tmp16_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 2, StringSerializer_getInstance(), tmp6_local2);
    tmp3_bitMask0 = tmp3_bitMask0 | 4;
    tmp7_local3 = tmp16_input.decodeBooleanElement_vuyhtj_k$(tmp0_desc, 3);
    tmp3_bitMask0 = tmp3_bitMask0 | 8;
    tmp8_local4 = tmp16_input.decodeStringElement_3oenpg_k$(tmp0_desc, 4);
    tmp3_bitMask0 = tmp3_bitMask0 | 16;
    tmp9_local5 = tmp16_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 5, tmp17_cached[5].get_value_j01efc_k$(), tmp9_local5);
    tmp3_bitMask0 = tmp3_bitMask0 | 32;
    tmp10_local6 = tmp16_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 6, StringSerializer_getInstance(), tmp10_local6);
    tmp3_bitMask0 = tmp3_bitMask0 | 64;
    tmp11_local7 = tmp16_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 7, StringSerializer_getInstance(), tmp11_local7);
    tmp3_bitMask0 = tmp3_bitMask0 | 128;
    tmp12_local8 = tmp16_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 8, $serializer_getInstance_23(), tmp12_local8);
    tmp3_bitMask0 = tmp3_bitMask0 | 256;
    tmp13_local9 = tmp16_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 9, $serializer_getInstance_24(), tmp13_local9);
    tmp3_bitMask0 = tmp3_bitMask0 | 512;
    tmp14_local10 = tmp16_input.decodeBooleanElement_vuyhtj_k$(tmp0_desc, 10);
    tmp3_bitMask0 = tmp3_bitMask0 | 1024;
    tmp15_local11 = tmp16_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 11, tmp17_cached[11].get_value_j01efc_k$(), tmp15_local11);
    tmp3_bitMask0 = tmp3_bitMask0 | 2048;
  } else
    while (tmp1_flag) {
      tmp2_index = tmp16_input.decodeElementIndex_bstkhp_k$(tmp0_desc);
      switch (tmp2_index) {
        case -1:
          tmp1_flag = false;
          break;
        case 0:
          tmp4_local0 = tmp16_input.decodeStringElement_3oenpg_k$(tmp0_desc, 0);
          tmp3_bitMask0 = tmp3_bitMask0 | 1;
          break;
        case 1:
          tmp5_local1 = tmp16_input.decodeStringElement_3oenpg_k$(tmp0_desc, 1);
          tmp3_bitMask0 = tmp3_bitMask0 | 2;
          break;
        case 2:
          tmp6_local2 = tmp16_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 2, StringSerializer_getInstance(), tmp6_local2);
          tmp3_bitMask0 = tmp3_bitMask0 | 4;
          break;
        case 3:
          tmp7_local3 = tmp16_input.decodeBooleanElement_vuyhtj_k$(tmp0_desc, 3);
          tmp3_bitMask0 = tmp3_bitMask0 | 8;
          break;
        case 4:
          tmp8_local4 = tmp16_input.decodeStringElement_3oenpg_k$(tmp0_desc, 4);
          tmp3_bitMask0 = tmp3_bitMask0 | 16;
          break;
        case 5:
          tmp9_local5 = tmp16_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 5, tmp17_cached[5].get_value_j01efc_k$(), tmp9_local5);
          tmp3_bitMask0 = tmp3_bitMask0 | 32;
          break;
        case 6:
          tmp10_local6 = tmp16_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 6, StringSerializer_getInstance(), tmp10_local6);
          tmp3_bitMask0 = tmp3_bitMask0 | 64;
          break;
        case 7:
          tmp11_local7 = tmp16_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 7, StringSerializer_getInstance(), tmp11_local7);
          tmp3_bitMask0 = tmp3_bitMask0 | 128;
          break;
        case 8:
          tmp12_local8 = tmp16_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 8, $serializer_getInstance_23(), tmp12_local8);
          tmp3_bitMask0 = tmp3_bitMask0 | 256;
          break;
        case 9:
          tmp13_local9 = tmp16_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 9, $serializer_getInstance_24(), tmp13_local9);
          tmp3_bitMask0 = tmp3_bitMask0 | 512;
          break;
        case 10:
          tmp14_local10 = tmp16_input.decodeBooleanElement_vuyhtj_k$(tmp0_desc, 10);
          tmp3_bitMask0 = tmp3_bitMask0 | 1024;
          break;
        case 11:
          tmp15_local11 = tmp16_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 11, tmp17_cached[11].get_value_j01efc_k$(), tmp15_local11);
          tmp3_bitMask0 = tmp3_bitMask0 | 2048;
          break;
        default:
          throw UnknownFieldException_init_$Create$(tmp2_index);
      }
    }
  tmp16_input.endStructure_1xqz0n_k$(tmp0_desc);
  return AssistedTargetMetadata_init_$Create$(tmp3_bitMask0, tmp4_local0, tmp5_local1, tmp6_local2, tmp7_local3, tmp8_local4, tmp9_local5, tmp10_local6, tmp11_local7, tmp12_local8, tmp13_local9, tmp14_local10, tmp15_local11, null);
};
protoOf($serializer_25).get_descriptor_wjt6a0_k$ = function () {
  return this.descriptor_1;
};
protoOf($serializer_25).childSerializers_5ghqw5_k$ = function () {
  var tmp0_cached = Companion_getInstance_24().$childSerializers_1;
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  return [StringSerializer_getInstance(), StringSerializer_getInstance(), get_nullable(StringSerializer_getInstance()), BooleanSerializer_getInstance(), StringSerializer_getInstance(), tmp0_cached[5].get_value_j01efc_k$(), get_nullable(StringSerializer_getInstance()), get_nullable(StringSerializer_getInstance()), get_nullable($serializer_getInstance_23()), get_nullable($serializer_getInstance_24()), BooleanSerializer_getInstance(), tmp0_cached[11].get_value_j01efc_k$()];
};
var $serializer_instance_25;
function $serializer_getInstance_25() {
  if ($serializer_instance_25 == null)
    new $serializer_25();
  return $serializer_instance_25;
}
function AssistedTargetMetadata_init_$Init$(seen0, key, bindingKind, scope, isScoped, nameHint, dependencies, origin, declaration, multibinding, optionalWrapper, isSynthetic, assistedParameters, serializationConstructorMarker, $this) {
  if (!(51 === (51 & seen0))) {
    throwMissingFieldException(seen0, 51, $serializer_getInstance_25().descriptor_1);
  }
  $this.key_1 = key;
  $this.bindingKind_1 = bindingKind;
  if (0 === (seen0 & 4))
    $this.scope_1 = null;
  else
    $this.scope_1 = scope;
  if (0 === (seen0 & 8))
    $this.isScoped_1 = false;
  else
    $this.isScoped_1 = isScoped;
  $this.nameHint_1 = nameHint;
  $this.dependencies_1 = dependencies;
  if (0 === (seen0 & 64))
    $this.origin_1 = null;
  else
    $this.origin_1 = origin;
  if (0 === (seen0 & 128))
    $this.declaration_1 = null;
  else
    $this.declaration_1 = declaration;
  if (0 === (seen0 & 256))
    $this.multibinding_1 = null;
  else
    $this.multibinding_1 = multibinding;
  if (0 === (seen0 & 512))
    $this.optionalWrapper_1 = null;
  else
    $this.optionalWrapper_1 = optionalWrapper;
  if (0 === (seen0 & 1024))
    $this.isSynthetic_1 = false;
  else
    $this.isSynthetic_1 = isSynthetic;
  if (0 === (seen0 & 2048))
    $this.assistedParameters_1 = emptyList();
  else
    $this.assistedParameters_1 = assistedParameters;
  return $this;
}
function AssistedTargetMetadata_init_$Create$(seen0, key, bindingKind, scope, isScoped, nameHint, dependencies, origin, declaration, multibinding, optionalWrapper, isSynthetic, assistedParameters, serializationConstructorMarker) {
  return AssistedTargetMetadata_init_$Init$(seen0, key, bindingKind, scope, isScoped, nameHint, dependencies, origin, declaration, multibinding, optionalWrapper, isSynthetic, assistedParameters, serializationConstructorMarker, objectCreate(protoOf(AssistedTargetMetadata)));
}
function AssistedTargetMetadata() {
}
protoOf(AssistedTargetMetadata).toString = function () {
  return 'AssistedTargetMetadata(key=' + this.key_1 + ', bindingKind=' + this.bindingKind_1 + ', scope=' + this.scope_1 + ', isScoped=' + this.isScoped_1 + ', nameHint=' + this.nameHint_1 + ', dependencies=' + toString(this.dependencies_1) + ', origin=' + this.origin_1 + ', declaration=' + this.declaration_1 + ', multibinding=' + toString_0(this.multibinding_1) + ', optionalWrapper=' + toString_0(this.optionalWrapper_1) + ', isSynthetic=' + this.isSynthetic_1 + ', assistedParameters=' + toString(this.assistedParameters_1) + ')';
};
protoOf(AssistedTargetMetadata).hashCode = function () {
  var result = getStringHashCode(this.key_1);
  result = imul(result, 31) + getStringHashCode(this.bindingKind_1) | 0;
  result = imul(result, 31) + (this.scope_1 == null ? 0 : getStringHashCode(this.scope_1)) | 0;
  result = imul(result, 31) + getBooleanHashCode(this.isScoped_1) | 0;
  result = imul(result, 31) + getStringHashCode(this.nameHint_1) | 0;
  result = imul(result, 31) + hashCode(this.dependencies_1) | 0;
  result = imul(result, 31) + (this.origin_1 == null ? 0 : getStringHashCode(this.origin_1)) | 0;
  result = imul(result, 31) + (this.declaration_1 == null ? 0 : getStringHashCode(this.declaration_1)) | 0;
  result = imul(result, 31) + (this.multibinding_1 == null ? 0 : this.multibinding_1.hashCode()) | 0;
  result = imul(result, 31) + (this.optionalWrapper_1 == null ? 0 : this.optionalWrapper_1.hashCode()) | 0;
  result = imul(result, 31) + getBooleanHashCode(this.isSynthetic_1) | 0;
  result = imul(result, 31) + hashCode(this.assistedParameters_1) | 0;
  return result;
};
protoOf(AssistedTargetMetadata).equals = function (other) {
  if (this === other)
    return true;
  if (!(other instanceof AssistedTargetMetadata))
    return false;
  if (!(this.key_1 === other.key_1))
    return false;
  if (!(this.bindingKind_1 === other.bindingKind_1))
    return false;
  if (!(this.scope_1 == other.scope_1))
    return false;
  if (!(this.isScoped_1 === other.isScoped_1))
    return false;
  if (!(this.nameHint_1 === other.nameHint_1))
    return false;
  if (!equals(this.dependencies_1, other.dependencies_1))
    return false;
  if (!(this.origin_1 == other.origin_1))
    return false;
  if (!(this.declaration_1 == other.declaration_1))
    return false;
  if (!equals(this.multibinding_1, other.multibinding_1))
    return false;
  if (!equals(this.optionalWrapper_1, other.optionalWrapper_1))
    return false;
  if (!(this.isSynthetic_1 === other.isSynthetic_1))
    return false;
  if (!equals(this.assistedParameters_1, other.assistedParameters_1))
    return false;
  return true;
};
function $serializer_26() {
  $serializer_instance_26 = this;
  var tmp0_serialDesc = new PluginGeneratedSerialDescriptor('dev.zacsweers.metro.gradle.analysis.InjectorMetadata', this, 2);
  tmp0_serialDesc.addElement_5pzumi_k$('key', false);
  tmp0_serialDesc.addElement_5pzumi_k$('name', true);
  this.descriptor_1 = tmp0_serialDesc;
}
protoOf($serializer_26).serialize_vgv2b9_k$ = function (encoder, value) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_output = encoder.beginStructure_yljocp_k$(tmp0_desc);
  tmp1_output.encodeStringElement_1n5wu2_k$(tmp0_desc, 0, value.key_1);
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 1) ? true : !(value.name_1 == null)) {
    tmp1_output.encodeNullableSerializableElement_5lquiv_k$(tmp0_desc, 1, StringSerializer_getInstance(), value.name_1);
  }
  tmp1_output.endStructure_1xqz0n_k$(tmp0_desc);
};
protoOf($serializer_26).serialize_5ase3y_k$ = function (encoder, value) {
  return this.serialize_vgv2b9_k$(encoder, value instanceof InjectorMetadata ? value : THROW_CCE());
};
protoOf($serializer_26).deserialize_sy6x50_k$ = function (decoder) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_flag = true;
  var tmp2_index = 0;
  var tmp3_bitMask0 = 0;
  var tmp4_local0 = null;
  var tmp5_local1 = null;
  var tmp6_input = decoder.beginStructure_yljocp_k$(tmp0_desc);
  if (tmp6_input.decodeSequentially_xlblqy_k$()) {
    tmp4_local0 = tmp6_input.decodeStringElement_3oenpg_k$(tmp0_desc, 0);
    tmp3_bitMask0 = tmp3_bitMask0 | 1;
    tmp5_local1 = tmp6_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 1, StringSerializer_getInstance(), tmp5_local1);
    tmp3_bitMask0 = tmp3_bitMask0 | 2;
  } else
    while (tmp1_flag) {
      tmp2_index = tmp6_input.decodeElementIndex_bstkhp_k$(tmp0_desc);
      switch (tmp2_index) {
        case -1:
          tmp1_flag = false;
          break;
        case 0:
          tmp4_local0 = tmp6_input.decodeStringElement_3oenpg_k$(tmp0_desc, 0);
          tmp3_bitMask0 = tmp3_bitMask0 | 1;
          break;
        case 1:
          tmp5_local1 = tmp6_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 1, StringSerializer_getInstance(), tmp5_local1);
          tmp3_bitMask0 = tmp3_bitMask0 | 2;
          break;
        default:
          throw UnknownFieldException_init_$Create$(tmp2_index);
      }
    }
  tmp6_input.endStructure_1xqz0n_k$(tmp0_desc);
  return InjectorMetadata_init_$Create$(tmp3_bitMask0, tmp4_local0, tmp5_local1, null);
};
protoOf($serializer_26).get_descriptor_wjt6a0_k$ = function () {
  return this.descriptor_1;
};
protoOf($serializer_26).childSerializers_5ghqw5_k$ = function () {
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  return [StringSerializer_getInstance(), get_nullable(StringSerializer_getInstance())];
};
var $serializer_instance_26;
function $serializer_getInstance_26() {
  if ($serializer_instance_26 == null)
    new $serializer_26();
  return $serializer_instance_26;
}
function InjectorMetadata_init_$Init$(seen0, key, name, serializationConstructorMarker, $this) {
  if (!(1 === (1 & seen0))) {
    throwMissingFieldException(seen0, 1, $serializer_getInstance_26().descriptor_1);
  }
  $this.key_1 = key;
  if (0 === (seen0 & 2))
    $this.name_1 = null;
  else
    $this.name_1 = name;
  return $this;
}
function InjectorMetadata_init_$Create$(seen0, key, name, serializationConstructorMarker) {
  return InjectorMetadata_init_$Init$(seen0, key, name, serializationConstructorMarker, objectCreate(protoOf(InjectorMetadata)));
}
function InjectorMetadata() {
}
protoOf(InjectorMetadata).toString = function () {
  return 'InjectorMetadata(key=' + this.key_1 + ', name=' + this.name_1 + ')';
};
protoOf(InjectorMetadata).hashCode = function () {
  var result = getStringHashCode(this.key_1);
  result = imul(result, 31) + (this.name_1 == null ? 0 : getStringHashCode(this.name_1)) | 0;
  return result;
};
protoOf(InjectorMetadata).equals = function (other) {
  if (this === other)
    return true;
  if (!(other instanceof InjectorMetadata))
    return false;
  if (!(this.key_1 === other.key_1))
    return false;
  if (!(this.name_1 == other.name_1))
    return false;
  return true;
};
function $serializer_27() {
  $serializer_instance_27 = this;
  var tmp0_serialDesc = new PluginGeneratedSerialDescriptor('dev.zacsweers.metro.gradle.analysis.ExtensionAccessorMetadata', this, 3);
  tmp0_serialDesc.addElement_5pzumi_k$('key', false);
  tmp0_serialDesc.addElement_5pzumi_k$('name', true);
  tmp0_serialDesc.addElement_5pzumi_k$('isProperty', true);
  this.descriptor_1 = tmp0_serialDesc;
}
protoOf($serializer_27).serialize_hw4o49_k$ = function (encoder, value) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_output = encoder.beginStructure_yljocp_k$(tmp0_desc);
  tmp1_output.encodeStringElement_1n5wu2_k$(tmp0_desc, 0, value.key_1);
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 1) ? true : !(value.name_1 == null)) {
    tmp1_output.encodeNullableSerializableElement_5lquiv_k$(tmp0_desc, 1, StringSerializer_getInstance(), value.name_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 2) ? true : !(value.isProperty_1 === false)) {
    tmp1_output.encodeBooleanElement_ydht7q_k$(tmp0_desc, 2, value.isProperty_1);
  }
  tmp1_output.endStructure_1xqz0n_k$(tmp0_desc);
};
protoOf($serializer_27).serialize_5ase3y_k$ = function (encoder, value) {
  return this.serialize_hw4o49_k$(encoder, value instanceof ExtensionAccessorMetadata ? value : THROW_CCE());
};
protoOf($serializer_27).deserialize_sy6x50_k$ = function (decoder) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_flag = true;
  var tmp2_index = 0;
  var tmp3_bitMask0 = 0;
  var tmp4_local0 = null;
  var tmp5_local1 = null;
  var tmp6_local2 = false;
  var tmp7_input = decoder.beginStructure_yljocp_k$(tmp0_desc);
  if (tmp7_input.decodeSequentially_xlblqy_k$()) {
    tmp4_local0 = tmp7_input.decodeStringElement_3oenpg_k$(tmp0_desc, 0);
    tmp3_bitMask0 = tmp3_bitMask0 | 1;
    tmp5_local1 = tmp7_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 1, StringSerializer_getInstance(), tmp5_local1);
    tmp3_bitMask0 = tmp3_bitMask0 | 2;
    tmp6_local2 = tmp7_input.decodeBooleanElement_vuyhtj_k$(tmp0_desc, 2);
    tmp3_bitMask0 = tmp3_bitMask0 | 4;
  } else
    while (tmp1_flag) {
      tmp2_index = tmp7_input.decodeElementIndex_bstkhp_k$(tmp0_desc);
      switch (tmp2_index) {
        case -1:
          tmp1_flag = false;
          break;
        case 0:
          tmp4_local0 = tmp7_input.decodeStringElement_3oenpg_k$(tmp0_desc, 0);
          tmp3_bitMask0 = tmp3_bitMask0 | 1;
          break;
        case 1:
          tmp5_local1 = tmp7_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 1, StringSerializer_getInstance(), tmp5_local1);
          tmp3_bitMask0 = tmp3_bitMask0 | 2;
          break;
        case 2:
          tmp6_local2 = tmp7_input.decodeBooleanElement_vuyhtj_k$(tmp0_desc, 2);
          tmp3_bitMask0 = tmp3_bitMask0 | 4;
          break;
        default:
          throw UnknownFieldException_init_$Create$(tmp2_index);
      }
    }
  tmp7_input.endStructure_1xqz0n_k$(tmp0_desc);
  return ExtensionAccessorMetadata_init_$Create$(tmp3_bitMask0, tmp4_local0, tmp5_local1, tmp6_local2, null);
};
protoOf($serializer_27).get_descriptor_wjt6a0_k$ = function () {
  return this.descriptor_1;
};
protoOf($serializer_27).childSerializers_5ghqw5_k$ = function () {
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  return [StringSerializer_getInstance(), get_nullable(StringSerializer_getInstance()), BooleanSerializer_getInstance()];
};
var $serializer_instance_27;
function $serializer_getInstance_27() {
  if ($serializer_instance_27 == null)
    new $serializer_27();
  return $serializer_instance_27;
}
function ExtensionAccessorMetadata_init_$Init$(seen0, key, name, isProperty, serializationConstructorMarker, $this) {
  if (!(1 === (1 & seen0))) {
    throwMissingFieldException(seen0, 1, $serializer_getInstance_27().descriptor_1);
  }
  $this.key_1 = key;
  if (0 === (seen0 & 2))
    $this.name_1 = null;
  else
    $this.name_1 = name;
  if (0 === (seen0 & 4))
    $this.isProperty_1 = false;
  else
    $this.isProperty_1 = isProperty;
  return $this;
}
function ExtensionAccessorMetadata_init_$Create$(seen0, key, name, isProperty, serializationConstructorMarker) {
  return ExtensionAccessorMetadata_init_$Init$(seen0, key, name, isProperty, serializationConstructorMarker, objectCreate(protoOf(ExtensionAccessorMetadata)));
}
function ExtensionAccessorMetadata() {
}
protoOf(ExtensionAccessorMetadata).toString = function () {
  return 'ExtensionAccessorMetadata(key=' + this.key_1 + ', name=' + this.name_1 + ', isProperty=' + this.isProperty_1 + ')';
};
protoOf(ExtensionAccessorMetadata).hashCode = function () {
  var result = getStringHashCode(this.key_1);
  result = imul(result, 31) + (this.name_1 == null ? 0 : getStringHashCode(this.name_1)) | 0;
  result = imul(result, 31) + getBooleanHashCode(this.isProperty_1) | 0;
  return result;
};
protoOf(ExtensionAccessorMetadata).equals = function (other) {
  if (this === other)
    return true;
  if (!(other instanceof ExtensionAccessorMetadata))
    return false;
  if (!(this.key_1 === other.key_1))
    return false;
  if (!(this.name_1 == other.name_1))
    return false;
  if (!(this.isProperty_1 === other.isProperty_1))
    return false;
  return true;
};
function ExtensionsMetadata$Companion$$childSerializers$_anonymous__zi7u87() {
  return new ArrayListSerializer($serializer_getInstance_27());
}
function ExtensionsMetadata$Companion$$childSerializers$_anonymous__zi7u87_0() {
  return new ArrayListSerializer($serializer_getInstance_29());
}
function ExtensionsMetadata$Companion$$childSerializers$_anonymous__zi7u87_1() {
  return new ArrayListSerializer(StringSerializer_getInstance());
}
function Companion_25() {
  Companion_instance_26 = this;
  var tmp = this;
  var tmp_0 = LazyThreadSafetyMode_PUBLICATION_getInstance();
  var tmp_1 = lazy(tmp_0, ExtensionsMetadata$Companion$$childSerializers$_anonymous__zi7u87);
  var tmp_2 = LazyThreadSafetyMode_PUBLICATION_getInstance();
  var tmp_3 = lazy(tmp_2, ExtensionsMetadata$Companion$$childSerializers$_anonymous__zi7u87_0);
  var tmp_4 = LazyThreadSafetyMode_PUBLICATION_getInstance();
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  tmp.$childSerializers_1 = [tmp_1, tmp_3, lazy(tmp_4, ExtensionsMetadata$Companion$$childSerializers$_anonymous__zi7u87_1)];
}
var Companion_instance_26;
function Companion_getInstance_25() {
  if (Companion_instance_26 == null)
    new Companion_25();
  return Companion_instance_26;
}
function $serializer_28() {
  $serializer_instance_28 = this;
  var tmp0_serialDesc = new PluginGeneratedSerialDescriptor('dev.zacsweers.metro.gradle.analysis.ExtensionsMetadata', this, 3);
  tmp0_serialDesc.addElement_5pzumi_k$('accessors', true);
  tmp0_serialDesc.addElement_5pzumi_k$('factoryAccessors', true);
  tmp0_serialDesc.addElement_5pzumi_k$('factoriesImplemented', true);
  this.descriptor_1 = tmp0_serialDesc;
}
protoOf($serializer_28).serialize_euf82z_k$ = function (encoder, value) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_output = encoder.beginStructure_yljocp_k$(tmp0_desc);
  var tmp2_cached = Companion_getInstance_25().$childSerializers_1;
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 0) ? true : !equals(value.accessors_1, emptyList())) {
    tmp1_output.encodeSerializableElement_isqxcl_k$(tmp0_desc, 0, tmp2_cached[0].get_value_j01efc_k$(), value.accessors_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 1) ? true : !equals(value.factoryAccessors_1, emptyList())) {
    tmp1_output.encodeSerializableElement_isqxcl_k$(tmp0_desc, 1, tmp2_cached[1].get_value_j01efc_k$(), value.factoryAccessors_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 2) ? true : !equals(value.factoriesImplemented_1, emptyList())) {
    tmp1_output.encodeSerializableElement_isqxcl_k$(tmp0_desc, 2, tmp2_cached[2].get_value_j01efc_k$(), value.factoriesImplemented_1);
  }
  tmp1_output.endStructure_1xqz0n_k$(tmp0_desc);
};
protoOf($serializer_28).serialize_5ase3y_k$ = function (encoder, value) {
  return this.serialize_euf82z_k$(encoder, value instanceof ExtensionsMetadata ? value : THROW_CCE());
};
protoOf($serializer_28).deserialize_sy6x50_k$ = function (decoder) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_flag = true;
  var tmp2_index = 0;
  var tmp3_bitMask0 = 0;
  var tmp4_local0 = null;
  var tmp5_local1 = null;
  var tmp6_local2 = null;
  var tmp7_input = decoder.beginStructure_yljocp_k$(tmp0_desc);
  var tmp8_cached = Companion_getInstance_25().$childSerializers_1;
  if (tmp7_input.decodeSequentially_xlblqy_k$()) {
    tmp4_local0 = tmp7_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 0, tmp8_cached[0].get_value_j01efc_k$(), tmp4_local0);
    tmp3_bitMask0 = tmp3_bitMask0 | 1;
    tmp5_local1 = tmp7_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 1, tmp8_cached[1].get_value_j01efc_k$(), tmp5_local1);
    tmp3_bitMask0 = tmp3_bitMask0 | 2;
    tmp6_local2 = tmp7_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 2, tmp8_cached[2].get_value_j01efc_k$(), tmp6_local2);
    tmp3_bitMask0 = tmp3_bitMask0 | 4;
  } else
    while (tmp1_flag) {
      tmp2_index = tmp7_input.decodeElementIndex_bstkhp_k$(tmp0_desc);
      switch (tmp2_index) {
        case -1:
          tmp1_flag = false;
          break;
        case 0:
          tmp4_local0 = tmp7_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 0, tmp8_cached[0].get_value_j01efc_k$(), tmp4_local0);
          tmp3_bitMask0 = tmp3_bitMask0 | 1;
          break;
        case 1:
          tmp5_local1 = tmp7_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 1, tmp8_cached[1].get_value_j01efc_k$(), tmp5_local1);
          tmp3_bitMask0 = tmp3_bitMask0 | 2;
          break;
        case 2:
          tmp6_local2 = tmp7_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 2, tmp8_cached[2].get_value_j01efc_k$(), tmp6_local2);
          tmp3_bitMask0 = tmp3_bitMask0 | 4;
          break;
        default:
          throw UnknownFieldException_init_$Create$(tmp2_index);
      }
    }
  tmp7_input.endStructure_1xqz0n_k$(tmp0_desc);
  return ExtensionsMetadata_init_$Create$(tmp3_bitMask0, tmp4_local0, tmp5_local1, tmp6_local2, null);
};
protoOf($serializer_28).get_descriptor_wjt6a0_k$ = function () {
  return this.descriptor_1;
};
protoOf($serializer_28).childSerializers_5ghqw5_k$ = function () {
  var tmp0_cached = Companion_getInstance_25().$childSerializers_1;
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  return [tmp0_cached[0].get_value_j01efc_k$(), tmp0_cached[1].get_value_j01efc_k$(), tmp0_cached[2].get_value_j01efc_k$()];
};
var $serializer_instance_28;
function $serializer_getInstance_28() {
  if ($serializer_instance_28 == null)
    new $serializer_28();
  return $serializer_instance_28;
}
function ExtensionsMetadata_init_$Init$(seen0, accessors, factoryAccessors, factoriesImplemented, serializationConstructorMarker, $this) {
  if (!(0 === (0 & seen0))) {
    throwMissingFieldException(seen0, 0, $serializer_getInstance_28().descriptor_1);
  }
  if (0 === (seen0 & 1))
    $this.accessors_1 = emptyList();
  else
    $this.accessors_1 = accessors;
  if (0 === (seen0 & 2))
    $this.factoryAccessors_1 = emptyList();
  else
    $this.factoryAccessors_1 = factoryAccessors;
  if (0 === (seen0 & 4))
    $this.factoriesImplemented_1 = emptyList();
  else
    $this.factoriesImplemented_1 = factoriesImplemented;
  return $this;
}
function ExtensionsMetadata_init_$Create$(seen0, accessors, factoryAccessors, factoriesImplemented, serializationConstructorMarker) {
  return ExtensionsMetadata_init_$Init$(seen0, accessors, factoryAccessors, factoriesImplemented, serializationConstructorMarker, objectCreate(protoOf(ExtensionsMetadata)));
}
function ExtensionsMetadata(accessors, factoryAccessors, factoriesImplemented) {
  Companion_getInstance_25();
  accessors = accessors === VOID ? emptyList() : accessors;
  factoryAccessors = factoryAccessors === VOID ? emptyList() : factoryAccessors;
  factoriesImplemented = factoriesImplemented === VOID ? emptyList() : factoriesImplemented;
  this.accessors_1 = accessors;
  this.factoryAccessors_1 = factoryAccessors;
  this.factoriesImplemented_1 = factoriesImplemented;
}
protoOf(ExtensionsMetadata).toString = function () {
  return 'ExtensionsMetadata(accessors=' + toString(this.accessors_1) + ', factoryAccessors=' + toString(this.factoryAccessors_1) + ', factoriesImplemented=' + toString(this.factoriesImplemented_1) + ')';
};
protoOf(ExtensionsMetadata).hashCode = function () {
  var result = hashCode(this.accessors_1);
  result = imul(result, 31) + hashCode(this.factoryAccessors_1) | 0;
  result = imul(result, 31) + hashCode(this.factoriesImplemented_1) | 0;
  return result;
};
protoOf(ExtensionsMetadata).equals = function (other) {
  if (this === other)
    return true;
  if (!(other instanceof ExtensionsMetadata))
    return false;
  if (!equals(this.accessors_1, other.accessors_1))
    return false;
  if (!equals(this.factoryAccessors_1, other.factoryAccessors_1))
    return false;
  if (!equals(this.factoriesImplemented_1, other.factoriesImplemented_1))
    return false;
  return true;
};
function $serializer_29() {
  $serializer_instance_29 = this;
  var tmp0_serialDesc = new PluginGeneratedSerialDescriptor('dev.zacsweers.metro.gradle.analysis.ExtensionFactoryAccessorMetadata', this, 4);
  tmp0_serialDesc.addElement_5pzumi_k$('key', false);
  tmp0_serialDesc.addElement_5pzumi_k$('isSAM', true);
  tmp0_serialDesc.addElement_5pzumi_k$('name', true);
  tmp0_serialDesc.addElement_5pzumi_k$('isProperty', true);
  this.descriptor_1 = tmp0_serialDesc;
}
protoOf($serializer_29).serialize_lf0e5z_k$ = function (encoder, value) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_output = encoder.beginStructure_yljocp_k$(tmp0_desc);
  tmp1_output.encodeStringElement_1n5wu2_k$(tmp0_desc, 0, value.key_1);
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 1) ? true : !(value.isSAM_1 === false)) {
    tmp1_output.encodeBooleanElement_ydht7q_k$(tmp0_desc, 1, value.isSAM_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 2) ? true : !(value.name_1 == null)) {
    tmp1_output.encodeNullableSerializableElement_5lquiv_k$(tmp0_desc, 2, StringSerializer_getInstance(), value.name_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 3) ? true : !(value.isProperty_1 === false)) {
    tmp1_output.encodeBooleanElement_ydht7q_k$(tmp0_desc, 3, value.isProperty_1);
  }
  tmp1_output.endStructure_1xqz0n_k$(tmp0_desc);
};
protoOf($serializer_29).serialize_5ase3y_k$ = function (encoder, value) {
  return this.serialize_lf0e5z_k$(encoder, value instanceof ExtensionFactoryAccessorMetadata ? value : THROW_CCE());
};
protoOf($serializer_29).deserialize_sy6x50_k$ = function (decoder) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_flag = true;
  var tmp2_index = 0;
  var tmp3_bitMask0 = 0;
  var tmp4_local0 = null;
  var tmp5_local1 = false;
  var tmp6_local2 = null;
  var tmp7_local3 = false;
  var tmp8_input = decoder.beginStructure_yljocp_k$(tmp0_desc);
  if (tmp8_input.decodeSequentially_xlblqy_k$()) {
    tmp4_local0 = tmp8_input.decodeStringElement_3oenpg_k$(tmp0_desc, 0);
    tmp3_bitMask0 = tmp3_bitMask0 | 1;
    tmp5_local1 = tmp8_input.decodeBooleanElement_vuyhtj_k$(tmp0_desc, 1);
    tmp3_bitMask0 = tmp3_bitMask0 | 2;
    tmp6_local2 = tmp8_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 2, StringSerializer_getInstance(), tmp6_local2);
    tmp3_bitMask0 = tmp3_bitMask0 | 4;
    tmp7_local3 = tmp8_input.decodeBooleanElement_vuyhtj_k$(tmp0_desc, 3);
    tmp3_bitMask0 = tmp3_bitMask0 | 8;
  } else
    while (tmp1_flag) {
      tmp2_index = tmp8_input.decodeElementIndex_bstkhp_k$(tmp0_desc);
      switch (tmp2_index) {
        case -1:
          tmp1_flag = false;
          break;
        case 0:
          tmp4_local0 = tmp8_input.decodeStringElement_3oenpg_k$(tmp0_desc, 0);
          tmp3_bitMask0 = tmp3_bitMask0 | 1;
          break;
        case 1:
          tmp5_local1 = tmp8_input.decodeBooleanElement_vuyhtj_k$(tmp0_desc, 1);
          tmp3_bitMask0 = tmp3_bitMask0 | 2;
          break;
        case 2:
          tmp6_local2 = tmp8_input.decodeNullableSerializableElement_k2y6ab_k$(tmp0_desc, 2, StringSerializer_getInstance(), tmp6_local2);
          tmp3_bitMask0 = tmp3_bitMask0 | 4;
          break;
        case 3:
          tmp7_local3 = tmp8_input.decodeBooleanElement_vuyhtj_k$(tmp0_desc, 3);
          tmp3_bitMask0 = tmp3_bitMask0 | 8;
          break;
        default:
          throw UnknownFieldException_init_$Create$(tmp2_index);
      }
    }
  tmp8_input.endStructure_1xqz0n_k$(tmp0_desc);
  return ExtensionFactoryAccessorMetadata_init_$Create$(tmp3_bitMask0, tmp4_local0, tmp5_local1, tmp6_local2, tmp7_local3, null);
};
protoOf($serializer_29).get_descriptor_wjt6a0_k$ = function () {
  return this.descriptor_1;
};
protoOf($serializer_29).childSerializers_5ghqw5_k$ = function () {
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  return [StringSerializer_getInstance(), BooleanSerializer_getInstance(), get_nullable(StringSerializer_getInstance()), BooleanSerializer_getInstance()];
};
var $serializer_instance_29;
function $serializer_getInstance_29() {
  if ($serializer_instance_29 == null)
    new $serializer_29();
  return $serializer_instance_29;
}
function ExtensionFactoryAccessorMetadata_init_$Init$(seen0, key, isSAM, name, isProperty, serializationConstructorMarker, $this) {
  if (!(1 === (1 & seen0))) {
    throwMissingFieldException(seen0, 1, $serializer_getInstance_29().descriptor_1);
  }
  $this.key_1 = key;
  if (0 === (seen0 & 2))
    $this.isSAM_1 = false;
  else
    $this.isSAM_1 = isSAM;
  if (0 === (seen0 & 4))
    $this.name_1 = null;
  else
    $this.name_1 = name;
  if (0 === (seen0 & 8))
    $this.isProperty_1 = false;
  else
    $this.isProperty_1 = isProperty;
  return $this;
}
function ExtensionFactoryAccessorMetadata_init_$Create$(seen0, key, isSAM, name, isProperty, serializationConstructorMarker) {
  return ExtensionFactoryAccessorMetadata_init_$Init$(seen0, key, isSAM, name, isProperty, serializationConstructorMarker, objectCreate(protoOf(ExtensionFactoryAccessorMetadata)));
}
function ExtensionFactoryAccessorMetadata() {
}
protoOf(ExtensionFactoryAccessorMetadata).toString = function () {
  return 'ExtensionFactoryAccessorMetadata(key=' + this.key_1 + ', isSAM=' + this.isSAM_1 + ', name=' + this.name_1 + ', isProperty=' + this.isProperty_1 + ')';
};
protoOf(ExtensionFactoryAccessorMetadata).hashCode = function () {
  var result = getStringHashCode(this.key_1);
  result = imul(result, 31) + getBooleanHashCode(this.isSAM_1) | 0;
  result = imul(result, 31) + (this.name_1 == null ? 0 : getStringHashCode(this.name_1)) | 0;
  result = imul(result, 31) + getBooleanHashCode(this.isProperty_1) | 0;
  return result;
};
protoOf(ExtensionFactoryAccessorMetadata).equals = function (other) {
  if (this === other)
    return true;
  if (!(other instanceof ExtensionFactoryAccessorMetadata))
    return false;
  if (!(this.key_1 === other.key_1))
    return false;
  if (!(this.isSAM_1 === other.isSAM_1))
    return false;
  if (!(this.name_1 == other.name_1))
    return false;
  if (!(this.isProperty_1 === other.isProperty_1))
    return false;
  return true;
};
function $serializer_30() {
  $serializer_instance_30 = this;
  var tmp0_serialDesc = new PluginGeneratedSerialDescriptor('dev.zacsweers.metro.gradle.analysis.AssistedParameterMetadata', this, 2);
  tmp0_serialDesc.addElement_5pzumi_k$('key', false);
  tmp0_serialDesc.addElement_5pzumi_k$('name', false);
  this.descriptor_1 = tmp0_serialDesc;
}
protoOf($serializer_30).serialize_e238bm_k$ = function (encoder, value) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_output = encoder.beginStructure_yljocp_k$(tmp0_desc);
  tmp1_output.encodeStringElement_1n5wu2_k$(tmp0_desc, 0, value.key_1);
  tmp1_output.encodeStringElement_1n5wu2_k$(tmp0_desc, 1, value.name_1);
  tmp1_output.endStructure_1xqz0n_k$(tmp0_desc);
};
protoOf($serializer_30).serialize_5ase3y_k$ = function (encoder, value) {
  return this.serialize_e238bm_k$(encoder, value instanceof AssistedParameterMetadata ? value : THROW_CCE());
};
protoOf($serializer_30).deserialize_sy6x50_k$ = function (decoder) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_flag = true;
  var tmp2_index = 0;
  var tmp3_bitMask0 = 0;
  var tmp4_local0 = null;
  var tmp5_local1 = null;
  var tmp6_input = decoder.beginStructure_yljocp_k$(tmp0_desc);
  if (tmp6_input.decodeSequentially_xlblqy_k$()) {
    tmp4_local0 = tmp6_input.decodeStringElement_3oenpg_k$(tmp0_desc, 0);
    tmp3_bitMask0 = tmp3_bitMask0 | 1;
    tmp5_local1 = tmp6_input.decodeStringElement_3oenpg_k$(tmp0_desc, 1);
    tmp3_bitMask0 = tmp3_bitMask0 | 2;
  } else
    while (tmp1_flag) {
      tmp2_index = tmp6_input.decodeElementIndex_bstkhp_k$(tmp0_desc);
      switch (tmp2_index) {
        case -1:
          tmp1_flag = false;
          break;
        case 0:
          tmp4_local0 = tmp6_input.decodeStringElement_3oenpg_k$(tmp0_desc, 0);
          tmp3_bitMask0 = tmp3_bitMask0 | 1;
          break;
        case 1:
          tmp5_local1 = tmp6_input.decodeStringElement_3oenpg_k$(tmp0_desc, 1);
          tmp3_bitMask0 = tmp3_bitMask0 | 2;
          break;
        default:
          throw UnknownFieldException_init_$Create$(tmp2_index);
      }
    }
  tmp6_input.endStructure_1xqz0n_k$(tmp0_desc);
  return AssistedParameterMetadata_init_$Create$(tmp3_bitMask0, tmp4_local0, tmp5_local1, null);
};
protoOf($serializer_30).get_descriptor_wjt6a0_k$ = function () {
  return this.descriptor_1;
};
protoOf($serializer_30).childSerializers_5ghqw5_k$ = function () {
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  return [StringSerializer_getInstance(), StringSerializer_getInstance()];
};
var $serializer_instance_30;
function $serializer_getInstance_30() {
  if ($serializer_instance_30 == null)
    new $serializer_30();
  return $serializer_instance_30;
}
function AssistedParameterMetadata_init_$Init$(seen0, key, name, serializationConstructorMarker, $this) {
  if (!(3 === (3 & seen0))) {
    throwMissingFieldException(seen0, 3, $serializer_getInstance_30().descriptor_1);
  }
  $this.key_1 = key;
  $this.name_1 = name;
  return $this;
}
function AssistedParameterMetadata_init_$Create$(seen0, key, name, serializationConstructorMarker) {
  return AssistedParameterMetadata_init_$Init$(seen0, key, name, serializationConstructorMarker, objectCreate(protoOf(AssistedParameterMetadata)));
}
function AssistedParameterMetadata() {
}
protoOf(AssistedParameterMetadata).toString = function () {
  return 'AssistedParameterMetadata(key=' + this.key_1 + ', name=' + this.name_1 + ')';
};
protoOf(AssistedParameterMetadata).hashCode = function () {
  var result = getStringHashCode(this.key_1);
  result = imul(result, 31) + getStringHashCode(this.name_1) | 0;
  return result;
};
protoOf(AssistedParameterMetadata).equals = function (other) {
  if (this === other)
    return true;
  if (!(other instanceof AssistedParameterMetadata))
    return false;
  if (!(this.key_1 === other.key_1))
    return false;
  if (!(this.name_1 === other.name_1))
    return false;
  return true;
};
function GraphOptimizationStatsMetadata$Companion$$childSerializers$_anonymous__gt51nt() {
  return new LinkedHashMapSerializer(StringSerializer_getInstance(), IntSerializer_getInstance());
}
function Companion_26() {
  Companion_instance_27 = this;
  var tmp = this;
  var tmp_0 = LazyThreadSafetyMode_PUBLICATION_getInstance();
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  tmp.$childSerializers_1 = [null, null, null, null, null, null, null, null, null, lazy(tmp_0, GraphOptimizationStatsMetadata$Companion$$childSerializers$_anonymous__gt51nt)];
}
var Companion_instance_27;
function Companion_getInstance_26() {
  if (Companion_instance_27 == null)
    new Companion_26();
  return Companion_instance_27;
}
function $serializer_31() {
  $serializer_instance_31 = this;
  var tmp0_serialDesc = new PluginGeneratedSerialDescriptor('dev.zacsweers.metro.gradle.analysis.GraphOptimizationStatsMetadata', this, 10);
  tmp0_serialDesc.addElement_5pzumi_k$('bindingsPrunedByShrinking', true);
  tmp0_serialDesc.addElement_5pzumi_k$('classConstructorDirectInvocations', true);
  tmp0_serialDesc.addElement_5pzumi_k$('classConstructorNewInstanceCalls', true);
  tmp0_serialDesc.addElement_5pzumi_k$('providerDirectInvocations', true);
  tmp0_serialDesc.addElement_5pzumi_k$('providerNewInstanceCalls', true);
  tmp0_serialDesc.addElement_5pzumi_k$('shardsGenerated', true);
  tmp0_serialDesc.addElement_5pzumi_k$('shardedSupertypes', true);
  tmp0_serialDesc.addElement_5pzumi_k$('shardedInitFunctions', true);
  tmp0_serialDesc.addElement_5pzumi_k$('providerInlines', true);
  tmp0_serialDesc.addElement_5pzumi_k$('providerInlineFallbacks', true);
  this.descriptor_1 = tmp0_serialDesc;
}
protoOf($serializer_31).serialize_6kw49n_k$ = function (encoder, value) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_output = encoder.beginStructure_yljocp_k$(tmp0_desc);
  var tmp2_cached = Companion_getInstance_26().$childSerializers_1;
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 0) ? true : !(value.bindingsPrunedByShrinking_1 === 0)) {
    tmp1_output.encodeIntElement_krhhce_k$(tmp0_desc, 0, value.bindingsPrunedByShrinking_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 1) ? true : !(value.classConstructorDirectInvocations_1 === 0)) {
    tmp1_output.encodeIntElement_krhhce_k$(tmp0_desc, 1, value.classConstructorDirectInvocations_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 2) ? true : !(value.classConstructorNewInstanceCalls_1 === 0)) {
    tmp1_output.encodeIntElement_krhhce_k$(tmp0_desc, 2, value.classConstructorNewInstanceCalls_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 3) ? true : !(value.providerDirectInvocations_1 === 0)) {
    tmp1_output.encodeIntElement_krhhce_k$(tmp0_desc, 3, value.providerDirectInvocations_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 4) ? true : !(value.providerNewInstanceCalls_1 === 0)) {
    tmp1_output.encodeIntElement_krhhce_k$(tmp0_desc, 4, value.providerNewInstanceCalls_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 5) ? true : !(value.shardsGenerated_1 === 0)) {
    tmp1_output.encodeIntElement_krhhce_k$(tmp0_desc, 5, value.shardsGenerated_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 6) ? true : !(value.shardedSupertypes_1 === 0)) {
    tmp1_output.encodeIntElement_krhhce_k$(tmp0_desc, 6, value.shardedSupertypes_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 7) ? true : !(value.shardedInitFunctions_1 === 0)) {
    tmp1_output.encodeIntElement_krhhce_k$(tmp0_desc, 7, value.shardedInitFunctions_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 8) ? true : !(value.providerInlines_1 === 0)) {
    tmp1_output.encodeIntElement_krhhce_k$(tmp0_desc, 8, value.providerInlines_1);
  }
  if (tmp1_output.shouldEncodeElementDefault_x8eyid_k$(tmp0_desc, 9) ? true : !equals(value.providerInlineFallbacks_1, emptyMap())) {
    tmp1_output.encodeSerializableElement_isqxcl_k$(tmp0_desc, 9, tmp2_cached[9].get_value_j01efc_k$(), value.providerInlineFallbacks_1);
  }
  tmp1_output.endStructure_1xqz0n_k$(tmp0_desc);
};
protoOf($serializer_31).serialize_5ase3y_k$ = function (encoder, value) {
  return this.serialize_6kw49n_k$(encoder, value instanceof GraphOptimizationStatsMetadata ? value : THROW_CCE());
};
protoOf($serializer_31).deserialize_sy6x50_k$ = function (decoder) {
  var tmp0_desc = this.descriptor_1;
  var tmp1_flag = true;
  var tmp2_index = 0;
  var tmp3_bitMask0 = 0;
  var tmp4_local0 = 0;
  var tmp5_local1 = 0;
  var tmp6_local2 = 0;
  var tmp7_local3 = 0;
  var tmp8_local4 = 0;
  var tmp9_local5 = 0;
  var tmp10_local6 = 0;
  var tmp11_local7 = 0;
  var tmp12_local8 = 0;
  var tmp13_local9 = null;
  var tmp14_input = decoder.beginStructure_yljocp_k$(tmp0_desc);
  var tmp15_cached = Companion_getInstance_26().$childSerializers_1;
  if (tmp14_input.decodeSequentially_xlblqy_k$()) {
    tmp4_local0 = tmp14_input.decodeIntElement_941u6a_k$(tmp0_desc, 0);
    tmp3_bitMask0 = tmp3_bitMask0 | 1;
    tmp5_local1 = tmp14_input.decodeIntElement_941u6a_k$(tmp0_desc, 1);
    tmp3_bitMask0 = tmp3_bitMask0 | 2;
    tmp6_local2 = tmp14_input.decodeIntElement_941u6a_k$(tmp0_desc, 2);
    tmp3_bitMask0 = tmp3_bitMask0 | 4;
    tmp7_local3 = tmp14_input.decodeIntElement_941u6a_k$(tmp0_desc, 3);
    tmp3_bitMask0 = tmp3_bitMask0 | 8;
    tmp8_local4 = tmp14_input.decodeIntElement_941u6a_k$(tmp0_desc, 4);
    tmp3_bitMask0 = tmp3_bitMask0 | 16;
    tmp9_local5 = tmp14_input.decodeIntElement_941u6a_k$(tmp0_desc, 5);
    tmp3_bitMask0 = tmp3_bitMask0 | 32;
    tmp10_local6 = tmp14_input.decodeIntElement_941u6a_k$(tmp0_desc, 6);
    tmp3_bitMask0 = tmp3_bitMask0 | 64;
    tmp11_local7 = tmp14_input.decodeIntElement_941u6a_k$(tmp0_desc, 7);
    tmp3_bitMask0 = tmp3_bitMask0 | 128;
    tmp12_local8 = tmp14_input.decodeIntElement_941u6a_k$(tmp0_desc, 8);
    tmp3_bitMask0 = tmp3_bitMask0 | 256;
    tmp13_local9 = tmp14_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 9, tmp15_cached[9].get_value_j01efc_k$(), tmp13_local9);
    tmp3_bitMask0 = tmp3_bitMask0 | 512;
  } else
    while (tmp1_flag) {
      tmp2_index = tmp14_input.decodeElementIndex_bstkhp_k$(tmp0_desc);
      switch (tmp2_index) {
        case -1:
          tmp1_flag = false;
          break;
        case 0:
          tmp4_local0 = tmp14_input.decodeIntElement_941u6a_k$(tmp0_desc, 0);
          tmp3_bitMask0 = tmp3_bitMask0 | 1;
          break;
        case 1:
          tmp5_local1 = tmp14_input.decodeIntElement_941u6a_k$(tmp0_desc, 1);
          tmp3_bitMask0 = tmp3_bitMask0 | 2;
          break;
        case 2:
          tmp6_local2 = tmp14_input.decodeIntElement_941u6a_k$(tmp0_desc, 2);
          tmp3_bitMask0 = tmp3_bitMask0 | 4;
          break;
        case 3:
          tmp7_local3 = tmp14_input.decodeIntElement_941u6a_k$(tmp0_desc, 3);
          tmp3_bitMask0 = tmp3_bitMask0 | 8;
          break;
        case 4:
          tmp8_local4 = tmp14_input.decodeIntElement_941u6a_k$(tmp0_desc, 4);
          tmp3_bitMask0 = tmp3_bitMask0 | 16;
          break;
        case 5:
          tmp9_local5 = tmp14_input.decodeIntElement_941u6a_k$(tmp0_desc, 5);
          tmp3_bitMask0 = tmp3_bitMask0 | 32;
          break;
        case 6:
          tmp10_local6 = tmp14_input.decodeIntElement_941u6a_k$(tmp0_desc, 6);
          tmp3_bitMask0 = tmp3_bitMask0 | 64;
          break;
        case 7:
          tmp11_local7 = tmp14_input.decodeIntElement_941u6a_k$(tmp0_desc, 7);
          tmp3_bitMask0 = tmp3_bitMask0 | 128;
          break;
        case 8:
          tmp12_local8 = tmp14_input.decodeIntElement_941u6a_k$(tmp0_desc, 8);
          tmp3_bitMask0 = tmp3_bitMask0 | 256;
          break;
        case 9:
          tmp13_local9 = tmp14_input.decodeSerializableElement_uahnnv_k$(tmp0_desc, 9, tmp15_cached[9].get_value_j01efc_k$(), tmp13_local9);
          tmp3_bitMask0 = tmp3_bitMask0 | 512;
          break;
        default:
          throw UnknownFieldException_init_$Create$(tmp2_index);
      }
    }
  tmp14_input.endStructure_1xqz0n_k$(tmp0_desc);
  return GraphOptimizationStatsMetadata_init_$Create$(tmp3_bitMask0, tmp4_local0, tmp5_local1, tmp6_local2, tmp7_local3, tmp8_local4, tmp9_local5, tmp10_local6, tmp11_local7, tmp12_local8, tmp13_local9, null);
};
protoOf($serializer_31).get_descriptor_wjt6a0_k$ = function () {
  return this.descriptor_1;
};
protoOf($serializer_31).childSerializers_5ghqw5_k$ = function () {
  var tmp0_cached = Companion_getInstance_26().$childSerializers_1;
  // Inline function 'kotlin.arrayOf' call
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  return [IntSerializer_getInstance(), IntSerializer_getInstance(), IntSerializer_getInstance(), IntSerializer_getInstance(), IntSerializer_getInstance(), IntSerializer_getInstance(), IntSerializer_getInstance(), IntSerializer_getInstance(), IntSerializer_getInstance(), tmp0_cached[9].get_value_j01efc_k$()];
};
var $serializer_instance_31;
function $serializer_getInstance_31() {
  if ($serializer_instance_31 == null)
    new $serializer_31();
  return $serializer_instance_31;
}
function GraphOptimizationStatsMetadata_init_$Init$(seen0, bindingsPrunedByShrinking, classConstructorDirectInvocations, classConstructorNewInstanceCalls, providerDirectInvocations, providerNewInstanceCalls, shardsGenerated, shardedSupertypes, shardedInitFunctions, providerInlines, providerInlineFallbacks, serializationConstructorMarker, $this) {
  if (!(0 === (0 & seen0))) {
    throwMissingFieldException(seen0, 0, $serializer_getInstance_31().descriptor_1);
  }
  if (0 === (seen0 & 1))
    $this.bindingsPrunedByShrinking_1 = 0;
  else
    $this.bindingsPrunedByShrinking_1 = bindingsPrunedByShrinking;
  if (0 === (seen0 & 2))
    $this.classConstructorDirectInvocations_1 = 0;
  else
    $this.classConstructorDirectInvocations_1 = classConstructorDirectInvocations;
  if (0 === (seen0 & 4))
    $this.classConstructorNewInstanceCalls_1 = 0;
  else
    $this.classConstructorNewInstanceCalls_1 = classConstructorNewInstanceCalls;
  if (0 === (seen0 & 8))
    $this.providerDirectInvocations_1 = 0;
  else
    $this.providerDirectInvocations_1 = providerDirectInvocations;
  if (0 === (seen0 & 16))
    $this.providerNewInstanceCalls_1 = 0;
  else
    $this.providerNewInstanceCalls_1 = providerNewInstanceCalls;
  if (0 === (seen0 & 32))
    $this.shardsGenerated_1 = 0;
  else
    $this.shardsGenerated_1 = shardsGenerated;
  if (0 === (seen0 & 64))
    $this.shardedSupertypes_1 = 0;
  else
    $this.shardedSupertypes_1 = shardedSupertypes;
  if (0 === (seen0 & 128))
    $this.shardedInitFunctions_1 = 0;
  else
    $this.shardedInitFunctions_1 = shardedInitFunctions;
  if (0 === (seen0 & 256))
    $this.providerInlines_1 = 0;
  else
    $this.providerInlines_1 = providerInlines;
  if (0 === (seen0 & 512))
    $this.providerInlineFallbacks_1 = emptyMap();
  else
    $this.providerInlineFallbacks_1 = providerInlineFallbacks;
  return $this;
}
function GraphOptimizationStatsMetadata_init_$Create$(seen0, bindingsPrunedByShrinking, classConstructorDirectInvocations, classConstructorNewInstanceCalls, providerDirectInvocations, providerNewInstanceCalls, shardsGenerated, shardedSupertypes, shardedInitFunctions, providerInlines, providerInlineFallbacks, serializationConstructorMarker) {
  return GraphOptimizationStatsMetadata_init_$Init$(seen0, bindingsPrunedByShrinking, classConstructorDirectInvocations, classConstructorNewInstanceCalls, providerDirectInvocations, providerNewInstanceCalls, shardsGenerated, shardedSupertypes, shardedInitFunctions, providerInlines, providerInlineFallbacks, serializationConstructorMarker, objectCreate(protoOf(GraphOptimizationStatsMetadata)));
}
function GraphOptimizationStatsMetadata(bindingsPrunedByShrinking, classConstructorDirectInvocations, classConstructorNewInstanceCalls, providerDirectInvocations, providerNewInstanceCalls, shardsGenerated, shardedSupertypes, shardedInitFunctions, providerInlines, providerInlineFallbacks) {
  Companion_getInstance_26();
  bindingsPrunedByShrinking = bindingsPrunedByShrinking === VOID ? 0 : bindingsPrunedByShrinking;
  classConstructorDirectInvocations = classConstructorDirectInvocations === VOID ? 0 : classConstructorDirectInvocations;
  classConstructorNewInstanceCalls = classConstructorNewInstanceCalls === VOID ? 0 : classConstructorNewInstanceCalls;
  providerDirectInvocations = providerDirectInvocations === VOID ? 0 : providerDirectInvocations;
  providerNewInstanceCalls = providerNewInstanceCalls === VOID ? 0 : providerNewInstanceCalls;
  shardsGenerated = shardsGenerated === VOID ? 0 : shardsGenerated;
  shardedSupertypes = shardedSupertypes === VOID ? 0 : shardedSupertypes;
  shardedInitFunctions = shardedInitFunctions === VOID ? 0 : shardedInitFunctions;
  providerInlines = providerInlines === VOID ? 0 : providerInlines;
  providerInlineFallbacks = providerInlineFallbacks === VOID ? emptyMap() : providerInlineFallbacks;
  this.bindingsPrunedByShrinking_1 = bindingsPrunedByShrinking;
  this.classConstructorDirectInvocations_1 = classConstructorDirectInvocations;
  this.classConstructorNewInstanceCalls_1 = classConstructorNewInstanceCalls;
  this.providerDirectInvocations_1 = providerDirectInvocations;
  this.providerNewInstanceCalls_1 = providerNewInstanceCalls;
  this.shardsGenerated_1 = shardsGenerated;
  this.shardedSupertypes_1 = shardedSupertypes;
  this.shardedInitFunctions_1 = shardedInitFunctions;
  this.providerInlines_1 = providerInlines;
  this.providerInlineFallbacks_1 = providerInlineFallbacks;
}
protoOf(GraphOptimizationStatsMetadata).toString = function () {
  return 'GraphOptimizationStatsMetadata(bindingsPrunedByShrinking=' + this.bindingsPrunedByShrinking_1 + ', classConstructorDirectInvocations=' + this.classConstructorDirectInvocations_1 + ', classConstructorNewInstanceCalls=' + this.classConstructorNewInstanceCalls_1 + ', providerDirectInvocations=' + this.providerDirectInvocations_1 + ', providerNewInstanceCalls=' + this.providerNewInstanceCalls_1 + ', shardsGenerated=' + this.shardsGenerated_1 + ', shardedSupertypes=' + this.shardedSupertypes_1 + ', shardedInitFunctions=' + this.shardedInitFunctions_1 + ', providerInlines=' + this.providerInlines_1 + ', providerInlineFallbacks=' + toString(this.providerInlineFallbacks_1) + ')';
};
protoOf(GraphOptimizationStatsMetadata).hashCode = function () {
  var result = this.bindingsPrunedByShrinking_1;
  result = imul(result, 31) + this.classConstructorDirectInvocations_1 | 0;
  result = imul(result, 31) + this.classConstructorNewInstanceCalls_1 | 0;
  result = imul(result, 31) + this.providerDirectInvocations_1 | 0;
  result = imul(result, 31) + this.providerNewInstanceCalls_1 | 0;
  result = imul(result, 31) + this.shardsGenerated_1 | 0;
  result = imul(result, 31) + this.shardedSupertypes_1 | 0;
  result = imul(result, 31) + this.shardedInitFunctions_1 | 0;
  result = imul(result, 31) + this.providerInlines_1 | 0;
  result = imul(result, 31) + hashCode(this.providerInlineFallbacks_1) | 0;
  return result;
};
protoOf(GraphOptimizationStatsMetadata).equals = function (other) {
  if (this === other)
    return true;
  if (!(other instanceof GraphOptimizationStatsMetadata))
    return false;
  if (!(this.bindingsPrunedByShrinking_1 === other.bindingsPrunedByShrinking_1))
    return false;
  if (!(this.classConstructorDirectInvocations_1 === other.classConstructorDirectInvocations_1))
    return false;
  if (!(this.classConstructorNewInstanceCalls_1 === other.classConstructorNewInstanceCalls_1))
    return false;
  if (!(this.providerDirectInvocations_1 === other.providerDirectInvocations_1))
    return false;
  if (!(this.providerNewInstanceCalls_1 === other.providerNewInstanceCalls_1))
    return false;
  if (!(this.shardsGenerated_1 === other.shardsGenerated_1))
    return false;
  if (!(this.shardedSupertypes_1 === other.shardedSupertypes_1))
    return false;
  if (!(this.shardedInitFunctions_1 === other.shardedInitFunctions_1))
    return false;
  if (!(this.providerInlines_1 === other.providerInlines_1))
    return false;
  if (!equals(this.providerInlineFallbacks_1, other.providerInlineFallbacks_1))
    return false;
  return true;
};
function unwrapTypeKey(key) {
  var wrapperPrefixes = listOf(['Provider<', 'Lazy<', 'dev.zacsweers.metro.Provider<', 'kotlin.Lazy<', 'javax.inject.Provider<', 'jakarta.inject.Provider<']);
  var _iterator__ex2g4s = wrapperPrefixes.iterator_jk1svi_k$();
  while (_iterator__ex2g4s.hasNext_bitz1p_k$()) {
    var prefix = _iterator__ex2g4s.next_20eer_k$();
    if (startsWith(key, prefix) && endsWith(key, '>')) {
      return removeSuffix(removePrefix(key, prefix), '>');
    }
  }
  return key;
}
//region block: post-declaration
protoOf($serializer).typeParametersSerializers_fr94fx_k$ = typeParametersSerializers;
protoOf($serializer_0).typeParametersSerializers_fr94fx_k$ = typeParametersSerializers;
protoOf($serializer_1).typeParametersSerializers_fr94fx_k$ = typeParametersSerializers;
protoOf($serializer_2).typeParametersSerializers_fr94fx_k$ = typeParametersSerializers;
protoOf($serializer_3).typeParametersSerializers_fr94fx_k$ = typeParametersSerializers;
protoOf($serializer_4).typeParametersSerializers_fr94fx_k$ = typeParametersSerializers;
protoOf($serializer_5).typeParametersSerializers_fr94fx_k$ = typeParametersSerializers;
protoOf($serializer_6).typeParametersSerializers_fr94fx_k$ = typeParametersSerializers;
protoOf($serializer_7).typeParametersSerializers_fr94fx_k$ = typeParametersSerializers;
protoOf($serializer_8).typeParametersSerializers_fr94fx_k$ = typeParametersSerializers;
protoOf($serializer_9).typeParametersSerializers_fr94fx_k$ = typeParametersSerializers;
protoOf($serializer_10).typeParametersSerializers_fr94fx_k$ = typeParametersSerializers;
protoOf($serializer_11).typeParametersSerializers_fr94fx_k$ = typeParametersSerializers;
protoOf($serializer_12).typeParametersSerializers_fr94fx_k$ = typeParametersSerializers;
protoOf($serializer_13).typeParametersSerializers_fr94fx_k$ = typeParametersSerializers;
protoOf($serializer_14).typeParametersSerializers_fr94fx_k$ = typeParametersSerializers;
protoOf($serializer_15).typeParametersSerializers_fr94fx_k$ = typeParametersSerializers;
protoOf($serializer_16).typeParametersSerializers_fr94fx_k$ = typeParametersSerializers;
protoOf($serializer_17).typeParametersSerializers_fr94fx_k$ = typeParametersSerializers;
protoOf($serializer_18).typeParametersSerializers_fr94fx_k$ = typeParametersSerializers;
protoOf($serializer_19).typeParametersSerializers_fr94fx_k$ = typeParametersSerializers;
protoOf($serializer_20).typeParametersSerializers_fr94fx_k$ = typeParametersSerializers;
protoOf($serializer_21).typeParametersSerializers_fr94fx_k$ = typeParametersSerializers;
protoOf($serializer_22).typeParametersSerializers_fr94fx_k$ = typeParametersSerializers;
protoOf($serializer_23).typeParametersSerializers_fr94fx_k$ = typeParametersSerializers;
protoOf($serializer_24).typeParametersSerializers_fr94fx_k$ = typeParametersSerializers;
protoOf($serializer_25).typeParametersSerializers_fr94fx_k$ = typeParametersSerializers;
protoOf($serializer_26).typeParametersSerializers_fr94fx_k$ = typeParametersSerializers;
protoOf($serializer_27).typeParametersSerializers_fr94fx_k$ = typeParametersSerializers;
protoOf($serializer_28).typeParametersSerializers_fr94fx_k$ = typeParametersSerializers;
protoOf($serializer_29).typeParametersSerializers_fr94fx_k$ = typeParametersSerializers;
protoOf($serializer_30).typeParametersSerializers_fr94fx_k$ = typeParametersSerializers;
protoOf($serializer_31).typeParametersSerializers_fr94fx_k$ = typeParametersSerializers;
//endregion
//region block: init
Companion_instance_17 = new Companion_16();
Companion_instance_19 = new Companion_18();
Companion_instance_20 = new Companion_19();
Companion_instance_21 = new Companion_20();
Companion_instance_24 = new Companion_23();
//endregion
//region block: exports
export {
  BindingExplanation as BindingExplanationtrjyxewvnqvh,
  AccessorMetadata as AccessorMetadatabrzra2dayqyz,
  BindingMetadata as BindingMetadata2vqn7lk5kttug,
  DependencyMetadata as DependencyMetadata8rtjsv2z9kiq,
  FullAnalysisReport as FullAnalysisReport2xfl94zu4035r,
  GraphAnalysis as GraphAnalysis3tvx3nafb3xyc,
  GraphDependencyMetadata as GraphDependencyMetadata10z3tse19c59g,
  GraphMetadata as GraphMetadata374r61jzvfi7l,
  GraphStatsMetadata as GraphStatsMetadata2mb61swu85h8w,
  MultibindingMetadata as MultibindingMetadata29e72hjz84wjv,
  OptionalWrapperMetadata as OptionalWrapperMetadata325xthvr1c6hr,
  analysisEdges as analysisEdges21jont9fxzxmq,
  unwrapTypeKey as unwrapTypeKey3m568br941v1y,
  BindingCandidateStatus_SELECTED_getInstance as BindingCandidateStatus_SELECTED_getInstance2cfyov6j2a6z8,
  BindingExplanationOutcome_SELECTED_getInstance as BindingExplanationOutcome_SELECTED_getInstance2tfy6rsok1hmq,
};
//endregion

//# sourceMappingURL=metro-graphs.mjs.map
