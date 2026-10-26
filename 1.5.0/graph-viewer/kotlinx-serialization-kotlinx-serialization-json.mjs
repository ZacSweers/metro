import {
  EmptySerializersModule991ju6pz9b79 as EmptySerializersModule,
  Decoder23nde051s631g as Decoder,
  CompositeDecoder2tzm7wpwkr0og as CompositeDecoder,
  SerializerFactory1qv9hivitncuv as SerializerFactory,
  serializer1x79l67jvwntn as serializer,
  InlinePrimitiveDescriptor3i6ccn1a4fw94 as InlinePrimitiveDescriptor,
  SerializableWithd2dap36updxd as SerializableWith,
  SEALED_getInstance19i1cdv1u9v1e as SEALED_getInstance,
  buildSerialDescriptor2873qmkp8r2ib as buildSerialDescriptor,
  KSerializerzf77vz1967fq as KSerializer,
  MapSerializer11kmegt3g5c1g as MapSerializer,
  SerialDescriptor2pelqekb5ic3a as SerialDescriptor,
  STRING_getInstance7xungxdjbzv as STRING_getInstance,
  ListSerializer1hxuk9dx5n9du as ListSerializer,
  ENUM_getInstance25239ht7oag6o as ENUM_getInstance,
  PrimitiveSerialDescriptor3egfp53lutxj2 as PrimitiveSerialDescriptor,
  serializer2lw83vwvpnyms as serializer_0,
  get_isNullable36pbikm8xb7bz as get_isNullable,
  get_isInline5x26qrhi9qs6 as get_isInline,
  get_annotationshjxdbdcl8kmv as get_annotations,
  Encoderqvmrpqtq8hnu as Encoder,
  CompositeEncoderknecpkexzn3v as CompositeEncoder,
  SerializationExceptioneqrdve3ts2n9 as SerializationException,
  SerializationException_init_$Init$k4j4knbm05t9 as SerializationException_init_$Init$,
  ElementMarker33ojvsajwmzts as ElementMarker,
  SerializationException_init_$Create$1oc2fm69kd4pj as SerializationException_init_$Create$,
  CLASS_getInstance3jy3ydw5832xx as CLASS_getInstance,
  jsonCachedSerialNameslxufy2gu43jt as jsonCachedSerialNames,
  LIST_getInstance2vniy0f7mmb29 as LIST_getInstance,
  CONTEXTUAL_getInstance20l1ap7hne5we as CONTEXTUAL_getInstance,
  PolymorphicKindla9gurooefwb as PolymorphicKind,
  PrimitiveKindndgbuh6is7ze as PrimitiveKind,
  MAP_getInstance3e78m6vf98i66 as MAP_getInstance,
  ENUMlmq49cvwy4ow as ENUM,
  contextual3hpp1gupsu4al as contextual,
  SerializersModuleCollector3dddz14wd7brg as SerializersModuleCollector,
  AbstractDecoder35guh02ubh2hm as AbstractDecoder,
  AbstractPolymorphicSerializer1ccxwp48nfy58 as AbstractPolymorphicSerializer,
  DeserializationStrategy1z3z5pj9f7zc8 as DeserializationStrategy,
  findPolymorphicSerializer1nm87hvemahcj as findPolymorphicSerializer,
  missingFieldExceptionWithNewMessage2gqddjx5jxge9 as missingFieldExceptionWithNewMessage,
  MissingFieldException24tqif29emcmi as MissingFieldException,
  AbstractEncoder2gxtu3xmy3f8j as AbstractEncoder,
  OBJECT_getInstancerq52wx6ymebx as OBJECT_getInstance,
  findPolymorphicSerializerk638ixyjovk5 as findPolymorphicSerializer_0,
  SerializationStrategyh6ouydnm6hci as SerializationStrategy,
  serializer3ikrxnm8b29d6 as serializer_1,
  serializer36584sjyg5661 as serializer_2,
  serializer1q7c5q67ysppr as serializer_3,
  NamedValueDecoderzk26ztf92xbq as NamedValueDecoder,
  NamedValueEncoder1lca9edk1lf89 as NamedValueEncoder,
  getContextualDescriptor2n1gf3b895yb8 as getContextualDescriptor,
} from './kotlinx-serialization-kotlinx-serialization-core.mjs';
import {
  protoOf180f3jzyo7rfj as protoOf,
  initMetadataForObject1cxne3s9w65el as initMetadataForObject,
  VOID3gxj6tk5isa35 as VOID,
  Unit_instance3kz1zqli6gr1r as Unit_instance,
  initMetadataForClassbxx6q50dy2s7 as initMetadataForClass,
  toString1pkumu07cwy4m as toString,
  IllegalArgumentException_init_$Create$1gox8sdqs91jy as IllegalArgumentException_init_$Create$,
  charSequenceLength3278n89t01tmv as charSequenceLength,
  charSequenceGet1vxk1y5n17t1z as charSequenceGet,
  _Char___init__impl__6a9atxrzocepzq3zgi as _Char___init__impl__6a9atx,
  equals2au1ep9vhcato as equals,
  toString30pk9tzaqopn as toString_0,
  Enum3alwj03lh1n41 as Enum,
  initMetadataForInterface1egvbzx539z91 as initMetadataForInterface,
  initMetadataForCompanion1wyw17z38v6ac as initMetadataForCompanion,
  StringBuilder_init_$Create$b2348mky3co as StringBuilder_init_$Create$,
  hashCodeq5arwsb9dgti as hashCode,
  joinToString1cxrrlmo0chqs as joinToString,
  THROW_CCE2g6jy02ryeudk as THROW_CCE,
  KtMap140uvy3s5zad8 as KtMap,
  KtList3hktaavzmj137 as KtList,
  numberRangeToNumber25vse2rgp6rs8 as numberRangeToNumber,
  ClosedRangehokgr73im9z3 as ClosedRange,
  isInterface3d6p8outrmvmk as isInterface,
  contains2c50nlxg7en7o as contains,
  convertToIntofdoxh9bstof as convertToInt,
  getKClassFromExpression348iqjl4fnx2f as getKClassFromExpression,
  getBooleanHashCode1bbj3u6b3v0a7 as getBooleanHashCode,
  getStringHashCode26igk1bx568vk as getStringHashCode,
  toDouble1kn912gjoizjp as toDouble,
  StringCompanionObject_instance2dx16b5pp3v3z as StringCompanionObject_instance,
  LinkedHashMap_init_$Create$s27yccuovnls as LinkedHashMap_init_$Create$,
  ArrayList_init_$Create$2xrifdqz5xirg as ArrayList_init_$Create$,
  noWhenBranchMatchedException2a6r7ubxgky5j as noWhenBranchMatchedException,
  toLongOrNullutqivezb0wx1 as toLongOrNull,
  toULongOrNullojoyxi0i9tgj as toULongOrNull,
  ULong3f9k7s38t3rfp as ULong,
  Companion_getInstance11ies3wlxhmd7 as Companion_getInstance,
  _ULong___get_data__impl__fggpzbbo8lz7f8fnfy as _ULong___get_data__impl__fggpzb,
  toDoubleOrNullkxwozihadygj as toDoubleOrNull,
  toBooleanStrictOrNull2j0md398tkvbj as toBooleanStrictOrNull,
  IllegalStateException_init_$Create$38ax6c90susjc as IllegalStateException_init_$Create$,
  KProperty1ca4yb4wlo496 as KProperty1,
  getPropertyCallableRef3hckxc0xueiaj as getPropertyCallableRef,
  constructCallableReference23y65rf941mch as constructCallableReference,
  lazy2hsh8ze7j6ikd as lazy,
  captureStack1fzi4aczwc4hg as captureStack,
  defineProp3ur6h3slcvq4x as defineProp,
  fromInt1lka3ktyu79a4 as fromInt,
  _UInt___init__impl__l7qpdl5ip6p2ldulrs as _UInt___init__impl__l7qpdl,
  UInt__toString_impl_dbgl212c25g5qzedjl1 as UInt__toString_impl_dbgl21,
  _ULong___init__impl__c78o9k1vasxel72m2l4 as _ULong___init__impl__c78o9k,
  ULong__toString_impl_f9au7k36l427nkiqphd as ULong__toString_impl_f9au7k,
  _UByte___init__impl__g9hnc49zztd2brjj2m as _UByte___init__impl__g9hnc4,
  UByte__toString_impl_v72jg3k7aad61xjb4o as UByte__toString_impl_v72jg,
  _UShort___init__impl__jigrne1e3nphhrejdd8 as _UShort___init__impl__jigrne,
  UShort__toString_impl_edaoee2vx1is7vlchpz as UShort__toString_impl_edaoee,
  charSequenceSubSequence1iwpdba8s3jc7 as charSequenceSubSequence,
  coerceAtLeast2bkz8m9ik7hep as coerceAtLeast,
  coerceAtMost322komnqp70ag as coerceAtMost,
  isBlank1dvkhjjvox3p0 as isBlank,
  Collection1k04j3hzsbod0 as Collection,
  toSet1glep2u1u9tcb as toSet,
  singleOrNullrknfaxokm1sl as singleOrNull,
  emptyMapr06gerzljqtm as emptyMap,
  LinkedHashSet_init_$Create$vv7nkva6wxy5 as LinkedHashSet_init_$Create$,
  getValue48kllevslyh6 as getValue,
  copyOf2ng0t8oizk6it as copyOf,
  arrayCopytctsywo3h7gj as arrayCopy,
  DeepRecursiveFunction3r49v8igsve1g as DeepRecursiveFunction,
  invoke246lvi6tzooz1 as invoke,
  CoroutineImpl2sn3kjnwmfr10 as CoroutineImpl,
  DeepRecursiveScope1pqaydvh4vdcu as DeepRecursiveScope,
  Unitkvevlwgzwiuc as Unit,
  get_COROUTINE_SUSPENDED3ujt3p13qm4iy as get_COROUTINE_SUSPENDED,
  initMetadataForLambda3af3he42mmnh as initMetadataForLambda,
  initMetadataForCoroutine1i7lbatuf5bnt as initMetadataForCoroutine,
  getKClass3t8tygqu4lcxf as getKClass,
  ensureNotNull1e947j3ixpazm as ensureNotNull,
  substringBefore3n7kj60w69hju as substringBefore,
  removeSuffix3d61x5lsuvuho as removeSuffix,
  substringAfter1hku067gwr5ve as substringAfter,
  contains3ue2qo8xhmpf1 as contains_0,
  plus17rl43at52ays as plus,
  convertToByte1epqhkuyxuz5a as convertToByte,
  equalsLong28bsrfhwvd686 as equalsLong,
  convertToShortvtefcftm709c as convertToShort,
  IllegalArgumentException2asla15b5jaob as IllegalArgumentException,
  isFinite1tx0gn65nl9tj as isFinite,
  isFinite2t9l5a275mxm6 as isFinite_0,
  charCodeAt1yspne1d8erbm as charCodeAt,
  toUInt21lx0mz8wkp7c as toUInt,
  _UInt___get_data__impl__f0vqqw30ojw38w2y6td as _UInt___get_data__impl__f0vqqw,
  toULong266mnyksbttkw as toULong,
  toUByteh6p4wmqswkrs as toUByte,
  _UByte___get_data__impl__jof9qr100nrznmlnqj as _UByte___get_data__impl__jof9qr,
  toUShort7yqspfnhrot4 as toUShort,
  _UShort___get_data__impl__g02451raj6q6epxg1b as _UShort___get_data__impl__g0245,
  objectCreate1ve4bgxiu4x98 as objectCreate,
  toString1n9qg6fb0b5i8 as toString_1,
  Companion_getInstance1crempiiqys0g as Companion_getInstance_0,
  Companion_getInstanceb1xi7131i8df as Companion_getInstance_1,
  Companion_getInstance1foc7f9q9rk1s as Companion_getInstance_2,
  setOf45ia9pnfhe90 as setOf,
  Char__toInt_impl_vasixd28v1p2u6jau5g as Char__toInt_impl_vasixd,
  numberToChar93r9buh19yek as numberToChar,
  equals2v6cggk171b6e as equals_0,
  toByte4i43936u611k as toByte,
  startsWith26w8qjqapeeq6 as startsWith,
  single29ec4rh52687r as single,
  Char19o2r8palgjof as Char,
  emptySetcxexqki71qfa as emptySet,
  plus1ogy4liedzq5j as plus_0,
  toInt2q8uldh7sc951 as toInt,
  toList3jhuyej2anx2q as toList,
  throwUninitializedPropertyAccessException14fok093f3k3t as throwUninitializedPropertyAccessException,
  enumEntries20mr21zbe3az4 as enumEntries,
  toNumberlmbpvqo27r53 as toNumber,
  last1vo29oleiqj36 as last,
  removeLast3759euu1xvfa3 as removeLast,
  lastIndexOf2d52xhix5ymjr as lastIndexOf,
  Long2qws0ah9gnpki as Long,
  Char__minus_impl_a2frrhw2puetaduxyd as Char__minus_impl_a2frrh,
  multiply18i3gv3wlmcjg as multiply,
  add85si75olwt6n as add,
  subtract16cg4lfi29fq9 as subtract,
  compare2uud5j30pw5xc as compare,
  numberToLong345n6tb1n1i71 as numberToLong,
  negate12tprdg5pyd5t as negate,
  charArray2ujmm1qusno00 as charArray,
  indexOfwa4w6635jewi as indexOf,
  indexOf1xbs558u7wr52 as indexOf_0,
  substringiqarkczpya5m as substring,
  StringBuilder_init_$Create$yggnhbmai7wr as StringBuilder_init_$Create$_0,
  HashMap_init_$Create$m2z141lj3xfu as HashMap_init_$Create$,
} from './kotlin-kotlin-stdlib.mjs';
//region block: imports
var imul = Math.imul;
//endregion
//region block: pre-declaration
initMetadataForClass(Json, 'Json');
initMetadataForObject(Default, 'Default', VOID, Json);
initMetadataForClass(JsonBuilder, 'JsonBuilder');
initMetadataForClass(JsonImpl, 'JsonImpl', VOID, Json);
initMetadataForClass(JsonClassDiscriminator, 'JsonClassDiscriminator');
initMetadataForClass(JsonIgnoreUnknownKeys, 'JsonIgnoreUnknownKeys');
initMetadataForClass(JsonNames, 'JsonNames');
initMetadataForClass(JsonConfiguration, 'JsonConfiguration');
initMetadataForClass(ClassDiscriminatorMode, 'ClassDiscriminatorMode', VOID, Enum);
initMetadataForInterface(JsonDecoder, 'JsonDecoder', VOID, VOID, [Decoder, CompositeDecoder]);
initMetadataForCompanion(Companion);
initMetadataForClass(JsonElement, 'JsonElement', VOID, VOID, VOID, VOID, VOID, {0: JsonElementSerializer_getInstance});
initMetadataForClass(JsonObject, 'JsonObject', VOID, JsonElement, [KtMap], VOID, VOID, {0: JsonObjectSerializer_getInstance});
initMetadataForCompanion(Companion_0);
initMetadataForCompanion(Companion_1);
initMetadataForClass(JsonPrimitive, 'JsonPrimitive', VOID, JsonElement, VOID, VOID, VOID, {0: JsonPrimitiveSerializer_getInstance});
initMetadataForCompanion(Companion_2);
initMetadataForClass(JsonArray, 'JsonArray', VOID, JsonElement, [KtList], VOID, VOID, {0: JsonArraySerializer_getInstance});
initMetadataForObject(JsonNull, 'JsonNull', VOID, JsonPrimitive, [SerializerFactory], VOID, VOID, {0: JsonNullSerializer_getInstance});
initMetadataForClass(JsonLiteral, 'JsonLiteral', VOID, JsonPrimitive);
initMetadataForClass(JsonObjectBuilder, 'JsonObjectBuilder');
initMetadataForClass(JsonArrayBuilder, 'JsonArrayBuilder');
initMetadataForObject(JsonElementSerializer, 'JsonElementSerializer', VOID, VOID, [KSerializer]);
initMetadataForObject(JsonObjectDescriptor, 'JsonObjectDescriptor', VOID, VOID, [SerialDescriptor]);
initMetadataForObject(JsonObjectSerializer, 'JsonObjectSerializer', VOID, VOID, [KSerializer]);
initMetadataForObject(JsonPrimitiveSerializer, 'JsonPrimitiveSerializer', VOID, VOID, [KSerializer]);
initMetadataForObject(JsonArrayDescriptor, 'JsonArrayDescriptor', VOID, VOID, [SerialDescriptor]);
initMetadataForObject(JsonArraySerializer, 'JsonArraySerializer', VOID, VOID, [KSerializer]);
initMetadataForObject(JsonNullSerializer, 'JsonNullSerializer', VOID, VOID, [KSerializer]);
initMetadataForObject(JsonLiteralSerializer, 'JsonLiteralSerializer', VOID, VOID, [KSerializer]);
initMetadataForClass(defer$1, VOID, VOID, VOID, [SerialDescriptor]);
initMetadataForInterface(JsonEncoder, 'JsonEncoder', VOID, VOID, [Encoder, CompositeEncoder]);
initMetadataForClass(JsonException, 'JsonException', VOID, SerializationException);
initMetadataForClass(JsonDecodingException, 'JsonDecodingException', VOID, JsonException);
initMetadataForClass(JsonEncodingException, 'JsonEncodingException', VOID, JsonException);
initMetadataForClass(Composer, 'Composer');
initMetadataForClass(ComposerForUnsignedNumbers, 'ComposerForUnsignedNumbers', VOID, Composer);
initMetadataForClass(ComposerForUnquotedLiterals, 'ComposerForUnquotedLiterals', VOID, Composer);
initMetadataForClass(ComposerWithPrettyPrint, 'ComposerWithPrettyPrint', VOID, Composer);
initMetadataForClass(JsonElementMarker, 'JsonElementMarker');
initMetadataForObject(Tombstone, 'Tombstone');
initMetadataForObject(RedactedKey, 'RedactedKey');
initMetadataForClass(JsonPath, 'JsonPath');
initMetadataForClass(JsonSerializersModuleValidator, 'JsonSerializersModuleValidator', VOID, VOID, [SerializersModuleCollector]);
initMetadataForLambda(JsonTreeReader$readDeepRecursive$slambda, CoroutineImpl, VOID, [2]);
initMetadataForCoroutine($readObjectCOROUTINE$, CoroutineImpl);
initMetadataForClass(JsonTreeReader, 'JsonTreeReader', VOID, VOID, VOID, [1]);
initMetadataForClass(Key, 'Key', Key);
initMetadataForClass(DescriptorSchemaCache, 'DescriptorSchemaCache', DescriptorSchemaCache);
initMetadataForClass(DiscriminatorHolder, 'DiscriminatorHolder');
initMetadataForClass(StreamingJsonDecoder, 'StreamingJsonDecoder', VOID, AbstractDecoder, [JsonDecoder]);
initMetadataForClass(JsonDecoderForUnsignedTypes, 'JsonDecoderForUnsignedTypes', VOID, AbstractDecoder);
initMetadataForClass(StreamingJsonEncoder, 'StreamingJsonEncoder', VOID, AbstractEncoder, [JsonEncoder]);
initMetadataForClass(AbstractJsonTreeDecoder, 'AbstractJsonTreeDecoder', VOID, NamedValueDecoder, [JsonDecoder]);
initMetadataForClass(JsonTreeDecoder, 'JsonTreeDecoder', VOID, AbstractJsonTreeDecoder);
initMetadataForClass(JsonTreeListDecoder, 'JsonTreeListDecoder', VOID, AbstractJsonTreeDecoder);
initMetadataForClass(JsonPrimitiveDecoder, 'JsonPrimitiveDecoder', VOID, AbstractJsonTreeDecoder);
initMetadataForClass(JsonTreeMapDecoder, 'JsonTreeMapDecoder', VOID, JsonTreeDecoder);
initMetadataForClass(AbstractJsonTreeEncoder, 'AbstractJsonTreeEncoder', VOID, NamedValueEncoder, [JsonEncoder]);
initMetadataForClass(JsonTreeEncoder, 'JsonTreeEncoder', VOID, AbstractJsonTreeEncoder);
initMetadataForClass(AbstractJsonTreeEncoder$inlineUnsignedNumberEncoder$1, VOID, VOID, AbstractEncoder);
initMetadataForClass(AbstractJsonTreeEncoder$inlineUnquotedLiteralEncoder$1, VOID, VOID, AbstractEncoder);
initMetadataForClass(JsonPrimitiveEncoder, 'JsonPrimitiveEncoder', VOID, AbstractJsonTreeEncoder);
initMetadataForClass(JsonTreeListEncoder, 'JsonTreeListEncoder', VOID, AbstractJsonTreeEncoder);
initMetadataForClass(JsonTreeMapEncoder, 'JsonTreeMapEncoder', VOID, JsonTreeEncoder);
initMetadataForClass(WriteMode, 'WriteMode', VOID, Enum);
initMetadataForClass(AbstractJsonLexer, 'AbstractJsonLexer');
initMetadataForObject(CharMappings, 'CharMappings');
initMetadataForClass(StringJsonLexer, 'StringJsonLexer', VOID, AbstractJsonLexer);
initMetadataForClass(StringJsonLexerWithComments, 'StringJsonLexerWithComments', VOID, StringJsonLexer);
initMetadataForClass(JsonToStringWriter, 'JsonToStringWriter', JsonToStringWriter);
//endregion
function Default() {
  Default_instance = this;
  Json.call(this, new JsonConfiguration(), EmptySerializersModule());
}
var Default_instance;
function Default_getInstance() {
  if (Default_instance == null)
    new Default();
  return Default_instance;
}
function Json(configuration, serializersModule) {
  Default_getInstance();
  this.configuration_1 = configuration;
  this.serializersModule_1 = serializersModule;
  this._schemaCache_1 = new DescriptorSchemaCache();
}
protoOf(Json).get_serializersModule_piitvg_k$ = function () {
  return this.serializersModule_1;
};
protoOf(Json).encodeToString_k0apqx_k$ = function (serializer, value) {
  var result = new JsonToStringWriter();
  try {
    encodeByWriter(this, result, serializer, value);
    return result.toString();
  }finally {
    result.release_wu5yyf_k$();
  }
};
protoOf(Json).decodeFromString_jwu9sq_k$ = function (deserializer, string) {
  var lexer = StringJsonLexer_0(this, string);
  var input = new StreamingJsonDecoder(this, WriteMode_OBJ_getInstance(), lexer, deserializer.get_descriptor_wjt6a0_k$(), null);
  var result = input.decodeSerializableValue_xpnpad_k$(deserializer);
  lexer.expectEof_2xwqoj_k$();
  return result;
};
protoOf(Json).encodeToJsonElement_w5lo4o_k$ = function (serializer, value) {
  return writeJson(this, value, serializer);
};
protoOf(Json).decodeFromJsonElement_tsogwj_k$ = function (deserializer, element) {
  return readJson(this, element, deserializer);
};
protoOf(Json).parseToJsonElement_rqvr2k_k$ = function (string) {
  return this.decodeFromString_jwu9sq_k$(JsonElementSerializer_getInstance(), string);
};
function Json_0(from, builderAction) {
  from = from === VOID ? Default_getInstance() : from;
  var builder = new JsonBuilder(from);
  builderAction(builder);
  var conf = builder.build_bkh6qh_k$();
  return new JsonImpl(conf, builder.serializersModule_1);
}
function JsonBuilder(json) {
  this.encodeDefaults_1 = json.configuration_1.encodeDefaults_1;
  this.explicitNulls_1 = json.configuration_1.explicitNulls_1;
  this.ignoreUnknownKeys_1 = json.configuration_1.ignoreUnknownKeys_1;
  this.isLenient_1 = json.configuration_1.isLenient_1;
  this.prettyPrint_1 = json.configuration_1.prettyPrint_1;
  this.prettyPrintIndent_1 = json.configuration_1.prettyPrintIndent_1;
  this.coerceInputValues_1 = json.configuration_1.coerceInputValues_1;
  this.classDiscriminator_1 = json.configuration_1.classDiscriminator_1;
  this.classDiscriminatorMode_1 = json.configuration_1.classDiscriminatorMode_1;
  this.useAlternativeNames_1 = json.configuration_1.useAlternativeNames_1;
  this.namingStrategy_1 = json.configuration_1.namingStrategy_1;
  this.decodeEnumsCaseInsensitive_1 = json.configuration_1.decodeEnumsCaseInsensitive_1;
  this.allowTrailingComma_1 = json.configuration_1.allowTrailingComma_1;
  this.allowComments_1 = json.configuration_1.allowComments_1;
  this.allowSpecialFloatingPointValues_1 = json.configuration_1.allowSpecialFloatingPointValues_1;
  this.allowStructuredMapKeys_1 = json.configuration_1.allowStructuredMapKeys_1;
  this.useArrayPolymorphism_1 = json.configuration_1.useArrayPolymorphism_1;
  this.serializersModule_1 = json.get_serializersModule_piitvg_k$();
  this.exceptionsWithDebugInfo_1 = json.configuration_1.exceptionsWithDebugInfo_1;
}
protoOf(JsonBuilder).build_bkh6qh_k$ = function () {
  if (this.useArrayPolymorphism_1) {
    // Inline function 'kotlin.require' call
    if (!(this.classDiscriminator_1 === 'type')) {
      var message = 'Class discriminator should not be specified when array polymorphism is specified';
      throw IllegalArgumentException_init_$Create$(toString(message));
    }
    // Inline function 'kotlin.require' call
    if (!this.classDiscriminatorMode_1.equals(ClassDiscriminatorMode_POLYMORPHIC_getInstance())) {
      var message_0 = 'useArrayPolymorphism option can only be used if classDiscriminatorMode in a default POLYMORPHIC state.';
      throw IllegalArgumentException_init_$Create$(toString(message_0));
    }
  }
  if (!this.prettyPrint_1) {
    // Inline function 'kotlin.require' call
    if (!(this.prettyPrintIndent_1 === '    ')) {
      var message_1 = 'Indent should not be specified when default printing mode is used';
      throw IllegalArgumentException_init_$Create$(toString(message_1));
    }
  } else if (!(this.prettyPrintIndent_1 === '    ')) {
    var tmp0 = this.prettyPrintIndent_1;
    var tmp$ret$6;
    $l$block: {
      // Inline function 'kotlin.text.all' call
      var inductionVariable = 0;
      while (inductionVariable < charSequenceLength(tmp0)) {
        var element = charSequenceGet(tmp0, inductionVariable);
        inductionVariable = inductionVariable + 1 | 0;
        if (!(element === _Char___init__impl__6a9atx(32) || element === _Char___init__impl__6a9atx(9) || element === _Char___init__impl__6a9atx(13) || element === _Char___init__impl__6a9atx(10))) {
          tmp$ret$6 = false;
          break $l$block;
        }
      }
      tmp$ret$6 = true;
    }
    var allWhitespaces = tmp$ret$6;
    // Inline function 'kotlin.require' call
    if (!allWhitespaces) {
      var message_2 = 'Only whitespace, tab, newline and carriage return are allowed as pretty print symbols. Had ' + this.prettyPrintIndent_1;
      throw IllegalArgumentException_init_$Create$(toString(message_2));
    }
  }
  return new JsonConfiguration(this.encodeDefaults_1, this.ignoreUnknownKeys_1, this.isLenient_1, this.allowStructuredMapKeys_1, this.prettyPrint_1, this.explicitNulls_1, this.prettyPrintIndent_1, this.coerceInputValues_1, this.useArrayPolymorphism_1, this.classDiscriminator_1, this.allowSpecialFloatingPointValues_1, this.useAlternativeNames_1, this.namingStrategy_1, this.decodeEnumsCaseInsensitive_1, this.allowTrailingComma_1, this.allowComments_1, this.classDiscriminatorMode_1, this.exceptionsWithDebugInfo_1);
};
function validateConfiguration($this) {
  if (equals($this.get_serializersModule_piitvg_k$(), EmptySerializersModule()))
    return Unit_instance;
  var collector = new JsonSerializersModuleValidator($this.configuration_1);
  $this.get_serializersModule_piitvg_k$().dumpTo_vt5sm4_k$(collector);
}
function JsonImpl(configuration, module_0) {
  Json.call(this, configuration, module_0);
  validateConfiguration(this);
}
function JsonClassDiscriminator() {
}
function JsonIgnoreUnknownKeys() {
}
function JsonNames() {
}
function JsonConfiguration(encodeDefaults, ignoreUnknownKeys, isLenient, allowStructuredMapKeys, prettyPrint, explicitNulls, prettyPrintIndent, coerceInputValues, useArrayPolymorphism, classDiscriminator, allowSpecialFloatingPointValues, useAlternativeNames, namingStrategy, decodeEnumsCaseInsensitive, allowTrailingComma, allowComments, classDiscriminatorMode, exceptionsWithDebugInfo) {
  encodeDefaults = encodeDefaults === VOID ? false : encodeDefaults;
  ignoreUnknownKeys = ignoreUnknownKeys === VOID ? false : ignoreUnknownKeys;
  isLenient = isLenient === VOID ? false : isLenient;
  allowStructuredMapKeys = allowStructuredMapKeys === VOID ? false : allowStructuredMapKeys;
  prettyPrint = prettyPrint === VOID ? false : prettyPrint;
  explicitNulls = explicitNulls === VOID ? true : explicitNulls;
  prettyPrintIndent = prettyPrintIndent === VOID ? '    ' : prettyPrintIndent;
  coerceInputValues = coerceInputValues === VOID ? false : coerceInputValues;
  useArrayPolymorphism = useArrayPolymorphism === VOID ? false : useArrayPolymorphism;
  classDiscriminator = classDiscriminator === VOID ? 'type' : classDiscriminator;
  allowSpecialFloatingPointValues = allowSpecialFloatingPointValues === VOID ? false : allowSpecialFloatingPointValues;
  useAlternativeNames = useAlternativeNames === VOID ? true : useAlternativeNames;
  namingStrategy = namingStrategy === VOID ? null : namingStrategy;
  decodeEnumsCaseInsensitive = decodeEnumsCaseInsensitive === VOID ? false : decodeEnumsCaseInsensitive;
  allowTrailingComma = allowTrailingComma === VOID ? false : allowTrailingComma;
  allowComments = allowComments === VOID ? false : allowComments;
  classDiscriminatorMode = classDiscriminatorMode === VOID ? ClassDiscriminatorMode_POLYMORPHIC_getInstance() : classDiscriminatorMode;
  exceptionsWithDebugInfo = exceptionsWithDebugInfo === VOID ? true : exceptionsWithDebugInfo;
  this.encodeDefaults_1 = encodeDefaults;
  this.ignoreUnknownKeys_1 = ignoreUnknownKeys;
  this.isLenient_1 = isLenient;
  this.allowStructuredMapKeys_1 = allowStructuredMapKeys;
  this.prettyPrint_1 = prettyPrint;
  this.explicitNulls_1 = explicitNulls;
  this.prettyPrintIndent_1 = prettyPrintIndent;
  this.coerceInputValues_1 = coerceInputValues;
  this.useArrayPolymorphism_1 = useArrayPolymorphism;
  this.classDiscriminator_1 = classDiscriminator;
  this.allowSpecialFloatingPointValues_1 = allowSpecialFloatingPointValues;
  this.useAlternativeNames_1 = useAlternativeNames;
  this.namingStrategy_1 = namingStrategy;
  this.decodeEnumsCaseInsensitive_1 = decodeEnumsCaseInsensitive;
  this.allowTrailingComma_1 = allowTrailingComma;
  this.allowComments_1 = allowComments;
  this.classDiscriminatorMode_1 = classDiscriminatorMode;
  this.exceptionsWithDebugInfo_1 = exceptionsWithDebugInfo;
}
protoOf(JsonConfiguration).toString = function () {
  return 'JsonConfiguration(encodeDefaults=' + this.encodeDefaults_1 + ', ignoreUnknownKeys=' + this.ignoreUnknownKeys_1 + ', isLenient=' + this.isLenient_1 + ', ' + ('allowStructuredMapKeys=' + this.allowStructuredMapKeys_1 + ', prettyPrint=' + this.prettyPrint_1 + ', explicitNulls=' + this.explicitNulls_1 + ', ') + ("prettyPrintIndent='" + this.prettyPrintIndent_1 + "', coerceInputValues=" + this.coerceInputValues_1 + ', useArrayPolymorphism=' + this.useArrayPolymorphism_1 + ', ') + ("classDiscriminator='" + this.classDiscriminator_1 + "', allowSpecialFloatingPointValues=" + this.allowSpecialFloatingPointValues_1 + ', ') + ('useAlternativeNames=' + this.useAlternativeNames_1 + ', namingStrategy=' + toString_0(this.namingStrategy_1) + ', decodeEnumsCaseInsensitive=' + this.decodeEnumsCaseInsensitive_1 + ', ') + ('allowTrailingComma=' + this.allowTrailingComma_1 + ', allowComments=' + this.allowComments_1 + ', classDiscriminatorMode=' + this.classDiscriminatorMode_1.toString() + ', exceptionsWithDebugInfo=' + this.exceptionsWithDebugInfo_1 + ')');
};
var static_init_called;
function static_init() {
  if (static_init_called)
    return Unit_instance;
  static_init_called = true;
  ClassDiscriminatorMode_NONE_instance = new ClassDiscriminatorMode('NONE', 0);
  ClassDiscriminatorMode_ALL_JSON_OBJECTS_instance = new ClassDiscriminatorMode('ALL_JSON_OBJECTS', 1);
  ClassDiscriminatorMode_POLYMORPHIC_instance = new ClassDiscriminatorMode('POLYMORPHIC', 2);
}
var ClassDiscriminatorMode_NONE_instance;
var ClassDiscriminatorMode_ALL_JSON_OBJECTS_instance;
var ClassDiscriminatorMode_POLYMORPHIC_instance;
function ClassDiscriminatorMode(name, ordinal) {
  Enum.call(this, name, ordinal);
}
function ClassDiscriminatorMode_NONE_getInstance() {
  static_init();
  return ClassDiscriminatorMode_NONE_instance;
}
function ClassDiscriminatorMode_ALL_JSON_OBJECTS_getInstance() {
  static_init();
  return ClassDiscriminatorMode_ALL_JSON_OBJECTS_instance;
}
function ClassDiscriminatorMode_POLYMORPHIC_getInstance() {
  static_init();
  return ClassDiscriminatorMode_POLYMORPHIC_instance;
}
function JsonDecoder() {
}
function get_jsonUnquotedLiteralDescriptor() {
  _init_properties_JsonElement_kt__7cbdc2();
  return jsonUnquotedLiteralDescriptor;
}
var jsonUnquotedLiteralDescriptor;
function Companion() {
}
var Companion_instance;
function Companion_getInstance_3() {
  return Companion_instance;
}
function JsonObject$toString$lambda(_destruct__k2r9zo) {
  // Inline function 'kotlin.collections.component1' call
  var k = _destruct__k2r9zo.get_key_18j28a_k$();
  // Inline function 'kotlin.collections.component2' call
  var v = _destruct__k2r9zo.get_value_j01efc_k$();
  // Inline function 'kotlin.text.buildString' call
  // Inline function 'kotlin.apply' call
  var this_0 = StringBuilder_init_$Create$();
  printQuoted(this_0, k);
  this_0.append_58al37_k$(_Char___init__impl__6a9atx(58));
  this_0.append_t8pm91_k$(v);
  return this_0.toString();
}
function JsonObject(content) {
  JsonElement.call(this);
  this.content_1 = content;
}
protoOf(JsonObject).equals = function (other) {
  return equals(this.content_1, other);
};
protoOf(JsonObject).hashCode = function () {
  return hashCode(this.content_1);
};
protoOf(JsonObject).toString = function () {
  var tmp = this.content_1.get_entries_p20ztl_k$();
  return joinToString(tmp, ',', '{', '}', VOID, VOID, JsonObject$toString$lambda);
};
protoOf(JsonObject).isEmpty_y1axqb_k$ = function () {
  return this.content_1.isEmpty_y1axqb_k$();
};
protoOf(JsonObject).containsKey_w445h6_k$ = function (key) {
  return this.content_1.containsKey_aw81wo_k$(key);
};
protoOf(JsonObject).containsKey_aw81wo_k$ = function (key) {
  if (!(!(key == null) ? typeof key === 'string' : false))
    return false;
  return this.containsKey_w445h6_k$((!(key == null) ? typeof key === 'string' : false) ? key : THROW_CCE());
};
protoOf(JsonObject).get_6bo4tg_k$ = function (key) {
  return this.content_1.get_wei43m_k$(key);
};
protoOf(JsonObject).get_wei43m_k$ = function (key) {
  if (!(!(key == null) ? typeof key === 'string' : false))
    return null;
  return this.get_6bo4tg_k$((!(key == null) ? typeof key === 'string' : false) ? key : THROW_CCE());
};
protoOf(JsonObject).get_size_woubt6_k$ = function () {
  return this.content_1.get_size_woubt6_k$();
};
protoOf(JsonObject).get_keys_wop4xp_k$ = function () {
  return this.content_1.get_keys_wop4xp_k$();
};
protoOf(JsonObject).get_values_ksazhn_k$ = function () {
  return this.content_1.get_values_ksazhn_k$();
};
protoOf(JsonObject).get_entries_p20ztl_k$ = function () {
  return this.content_1.get_entries_p20ztl_k$();
};
function get_jsonObject(_this__u8e3s4) {
  _init_properties_JsonElement_kt__7cbdc2();
  var tmp0_elvis_lhs = _this__u8e3s4 instanceof JsonObject ? _this__u8e3s4 : null;
  var tmp;
  if (tmp0_elvis_lhs == null) {
    error(_this__u8e3s4, 'JsonObject');
  } else {
    tmp = tmp0_elvis_lhs;
  }
  return tmp;
}
function Companion_0() {
}
var Companion_instance_0;
function Companion_getInstance_4() {
  return Companion_instance_0;
}
function JsonElement() {
}
function Companion_1() {
}
var Companion_instance_1;
function Companion_getInstance_5() {
  return Companion_instance_1;
}
function JsonPrimitive() {
  JsonElement.call(this);
}
protoOf(JsonPrimitive).toString = function () {
  return this.get_content_h02jrk_k$();
};
function get_jsonPrimitive(_this__u8e3s4) {
  _init_properties_JsonElement_kt__7cbdc2();
  var tmp0_elvis_lhs = _this__u8e3s4 instanceof JsonPrimitive ? _this__u8e3s4 : null;
  var tmp;
  if (tmp0_elvis_lhs == null) {
    error(_this__u8e3s4, 'JsonPrimitive');
  } else {
    tmp = tmp0_elvis_lhs;
  }
  return tmp;
}
function JsonPrimitive_0(value) {
  _init_properties_JsonElement_kt__7cbdc2();
  if (value == null)
    return JsonNull_getInstance();
  return new JsonLiteral(value, true);
}
function JsonPrimitive_1(value) {
  _init_properties_JsonElement_kt__7cbdc2();
  if (value == null)
    return JsonNull_getInstance();
  return new JsonLiteral(value, false);
}
function Companion_2() {
}
var Companion_instance_2;
function Companion_getInstance_6() {
  return Companion_instance_2;
}
function JsonArray(content) {
  JsonElement.call(this);
  this.content_1 = content;
}
protoOf(JsonArray).equals = function (other) {
  return equals(this.content_1, other);
};
protoOf(JsonArray).hashCode = function () {
  return hashCode(this.content_1);
};
protoOf(JsonArray).toString = function () {
  return joinToString(this.content_1, ',', '[', ']');
};
protoOf(JsonArray).isEmpty_y1axqb_k$ = function () {
  return this.content_1.isEmpty_y1axqb_k$();
};
protoOf(JsonArray).contains_ba8w01_k$ = function (element) {
  return this.content_1.contains_aljjnj_k$(element);
};
protoOf(JsonArray).contains_aljjnj_k$ = function (element) {
  if (!(element instanceof JsonElement))
    return false;
  return this.contains_ba8w01_k$(element instanceof JsonElement ? element : THROW_CCE());
};
protoOf(JsonArray).iterator_jk1svi_k$ = function () {
  return this.content_1.iterator_jk1svi_k$();
};
protoOf(JsonArray).get_c1px32_k$ = function (index) {
  return this.content_1.get_c1px32_k$(index);
};
protoOf(JsonArray).get_size_woubt6_k$ = function () {
  return this.content_1.get_size_woubt6_k$();
};
function get_jsonArray(_this__u8e3s4) {
  _init_properties_JsonElement_kt__7cbdc2();
  var tmp0_elvis_lhs = _this__u8e3s4 instanceof JsonArray ? _this__u8e3s4 : null;
  var tmp;
  if (tmp0_elvis_lhs == null) {
    error(_this__u8e3s4, 'JsonArray');
  } else {
    tmp = tmp0_elvis_lhs;
  }
  return tmp;
}
function get_booleanOrNull(_this__u8e3s4) {
  _init_properties_JsonElement_kt__7cbdc2();
  return toBooleanStrictOrNull_0(_this__u8e3s4.get_content_h02jrk_k$());
}
function JsonPrimitive_2(value) {
  _init_properties_JsonElement_kt__7cbdc2();
  if (value == null)
    return JsonNull_getInstance();
  return new JsonLiteral(value, false);
}
function get_intOrNull(_this__u8e3s4) {
  _init_properties_JsonElement_kt__7cbdc2();
  // Inline function 'kotlinx.serialization.json.exceptionToNull' call
  var tmp;
  try {
    tmp = parseLongImpl(_this__u8e3s4);
  } catch ($p) {
    var tmp_0;
    if ($p instanceof JsonDecodingException) {
      var e = $p;
      tmp_0 = null;
    } else {
      throw $p;
    }
    tmp = tmp_0;
  }
  var tmp0_elvis_lhs = tmp;
  var tmp_1;
  if (tmp0_elvis_lhs == null) {
    return null;
  } else {
    tmp_1 = tmp0_elvis_lhs;
  }
  var result = tmp_1;
  // Inline function 'kotlin.ranges.contains' call
  var this_0 = numberRangeToNumber(-2147483648, 2147483647);
  if (!contains(isInterface(this_0, ClosedRange) ? this_0 : THROW_CCE(), result))
    return null;
  return convertToInt(result);
}
function error(_this__u8e3s4, element) {
  _init_properties_JsonElement_kt__7cbdc2();
  throw IllegalArgumentException_init_$Create$('Element ' + toString(getKClassFromExpression(_this__u8e3s4)) + ' is not a ' + element);
}
function JsonNull() {
  JsonNull_instance = this;
  JsonPrimitive.call(this);
  this.content_1 = 'null';
}
protoOf(JsonNull).get_isString_zep7bw_k$ = function () {
  return false;
};
protoOf(JsonNull).get_content_h02jrk_k$ = function () {
  return this.content_1;
};
protoOf(JsonNull).serializer_9w0wvi_k$ = function () {
  return JsonNullSerializer_getInstance();
};
protoOf(JsonNull).serializer_nv39qc_k$ = function (typeParamsSerializers) {
  return this.serializer_9w0wvi_k$();
};
var JsonNull_instance;
function JsonNull_getInstance() {
  if (JsonNull_instance == null)
    new JsonNull();
  return JsonNull_instance;
}
function JsonLiteral(body, isString, coerceToInlineType) {
  coerceToInlineType = coerceToInlineType === VOID ? null : coerceToInlineType;
  JsonPrimitive.call(this);
  this.isString_1 = isString;
  this.coerceToInlineType_1 = coerceToInlineType;
  this.content_1 = toString(body);
  if (!(this.coerceToInlineType_1 == null)) {
    // Inline function 'kotlin.require' call
    // Inline function 'kotlin.require' call
    if (!this.coerceToInlineType_1.get_isInline_usk17w_k$()) {
      var message = 'Failed requirement.';
      throw IllegalArgumentException_init_$Create$(toString(message));
    }
  }
}
protoOf(JsonLiteral).get_isString_zep7bw_k$ = function () {
  return this.isString_1;
};
protoOf(JsonLiteral).get_content_h02jrk_k$ = function () {
  return this.content_1;
};
protoOf(JsonLiteral).toString = function () {
  var tmp;
  if (this.isString_1) {
    // Inline function 'kotlin.text.buildString' call
    // Inline function 'kotlin.apply' call
    var this_0 = StringBuilder_init_$Create$();
    printQuoted(this_0, this.content_1);
    tmp = this_0.toString();
  } else {
    tmp = this.content_1;
  }
  return tmp;
};
protoOf(JsonLiteral).equals = function (other) {
  if (this === other)
    return true;
  if (other == null || !getKClassFromExpression(this).equals(getKClassFromExpression(other)))
    return false;
  if (!(other instanceof JsonLiteral))
    THROW_CCE();
  if (!(this.isString_1 === other.isString_1))
    return false;
  if (!(this.content_1 === other.content_1))
    return false;
  return true;
};
protoOf(JsonLiteral).hashCode = function () {
  var result = getBooleanHashCode(this.isString_1);
  result = imul(31, result) + getStringHashCode(this.content_1) | 0;
  return result;
};
function parseLongImpl(_this__u8e3s4) {
  _init_properties_JsonElement_kt__7cbdc2();
  return StringJsonLexer_0(Default_getInstance(), _this__u8e3s4.get_content_h02jrk_k$()).consumeNumericLiteralFully_dgqq24_k$();
}
function get_contentOrNull(_this__u8e3s4) {
  _init_properties_JsonElement_kt__7cbdc2();
  var tmp;
  if (_this__u8e3s4 instanceof JsonNull) {
    tmp = null;
  } else {
    tmp = _this__u8e3s4.get_content_h02jrk_k$();
  }
  return tmp;
}
function get_float(_this__u8e3s4) {
  _init_properties_JsonElement_kt__7cbdc2();
  // Inline function 'kotlin.text.toFloat' call
  var this_0 = _this__u8e3s4.get_content_h02jrk_k$();
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  return toDouble(this_0);
}
function get_double(_this__u8e3s4) {
  _init_properties_JsonElement_kt__7cbdc2();
  return toDouble(_this__u8e3s4.get_content_h02jrk_k$());
}
var properties_initialized_JsonElement_kt_abxy8s;
function _init_properties_JsonElement_kt__7cbdc2() {
  if (!properties_initialized_JsonElement_kt_abxy8s) {
    properties_initialized_JsonElement_kt_abxy8s = true;
    jsonUnquotedLiteralDescriptor = InlinePrimitiveDescriptor('kotlinx.serialization.json.JsonUnquotedLiteral', serializer(StringCompanionObject_instance));
  }
}
function JsonObjectBuilder() {
  var tmp = this;
  // Inline function 'kotlin.collections.linkedMapOf' call
  tmp.content_1 = LinkedHashMap_init_$Create$();
}
protoOf(JsonObjectBuilder).put_mrde5n_k$ = function (key, element) {
  return this.content_1.put_4fpzoq_k$(key, element);
};
protoOf(JsonObjectBuilder).build_bkh6qh_k$ = function () {
  return new JsonObject(this.content_1);
};
function JsonArrayBuilder() {
  var tmp = this;
  // Inline function 'kotlin.collections.mutableListOf' call
  tmp.content_1 = ArrayList_init_$Create$();
}
protoOf(JsonArrayBuilder).add_i0x14v_k$ = function (element) {
  // Inline function 'kotlin.collections.plusAssign' call
  this.content_1.add_utx5q5_k$(element);
  return true;
};
protoOf(JsonArrayBuilder).addAll_s6d7z9_k$ = function (elements) {
  return this.content_1.addAll_h3ej1q_k$(elements);
};
protoOf(JsonArrayBuilder).build_bkh6qh_k$ = function () {
  return new JsonArray(this.content_1);
};
function JsonElementSerializer$descriptor$lambda($this$buildSerialDescriptor) {
  $this$buildSerialDescriptor.element$default_ey7ac9_k$('JsonPrimitive', defer(JsonElementSerializer$descriptor$lambda$lambda));
  $this$buildSerialDescriptor.element$default_ey7ac9_k$('JsonNull', defer(JsonElementSerializer$descriptor$lambda$lambda_0));
  $this$buildSerialDescriptor.element$default_ey7ac9_k$('JsonLiteral', defer(JsonElementSerializer$descriptor$lambda$lambda_1));
  $this$buildSerialDescriptor.element$default_ey7ac9_k$('JsonObject', defer(JsonElementSerializer$descriptor$lambda$lambda_2));
  $this$buildSerialDescriptor.element$default_ey7ac9_k$('JsonArray', defer(JsonElementSerializer$descriptor$lambda$lambda_3));
  return Unit_instance;
}
function JsonElementSerializer$descriptor$lambda$lambda() {
  return JsonPrimitiveSerializer_getInstance().descriptor_1;
}
function JsonElementSerializer$descriptor$lambda$lambda_0() {
  return JsonNullSerializer_getInstance().descriptor_1;
}
function JsonElementSerializer$descriptor$lambda$lambda_1() {
  return JsonLiteralSerializer_getInstance().descriptor_1;
}
function JsonElementSerializer$descriptor$lambda$lambda_2() {
  return JsonObjectSerializer_getInstance().descriptor_1;
}
function JsonElementSerializer$descriptor$lambda$lambda_3() {
  return JsonArraySerializer_getInstance().descriptor_1;
}
function JsonElementSerializer() {
  JsonElementSerializer_instance = this;
  var tmp = this;
  var tmp_0 = SEALED_getInstance();
  tmp.descriptor_1 = buildSerialDescriptor('kotlinx.serialization.json.JsonElement', tmp_0, [], JsonElementSerializer$descriptor$lambda);
}
protoOf(JsonElementSerializer).get_descriptor_wjt6a0_k$ = function () {
  return this.descriptor_1;
};
protoOf(JsonElementSerializer).serialize_pk22vx_k$ = function (encoder, value) {
  verify(encoder);
  if (value instanceof JsonPrimitive) {
    encoder.encodeSerializableValue_3uuzip_k$(JsonPrimitiveSerializer_getInstance(), value);
  } else {
    if (value instanceof JsonObject) {
      encoder.encodeSerializableValue_3uuzip_k$(JsonObjectSerializer_getInstance(), value);
    } else {
      if (value instanceof JsonArray) {
        encoder.encodeSerializableValue_3uuzip_k$(JsonArraySerializer_getInstance(), value);
      } else {
        noWhenBranchMatchedException();
      }
    }
  }
};
protoOf(JsonElementSerializer).serialize_5ase3y_k$ = function (encoder, value) {
  return this.serialize_pk22vx_k$(encoder, value instanceof JsonElement ? value : THROW_CCE());
};
protoOf(JsonElementSerializer).deserialize_sy6x50_k$ = function (decoder) {
  var input = asJsonDecoder(decoder);
  return input.decodeJsonElement_6lz9ye_k$();
};
var JsonElementSerializer_instance;
function JsonElementSerializer_getInstance() {
  if (JsonElementSerializer_instance == null)
    new JsonElementSerializer();
  return JsonElementSerializer_instance;
}
function JsonObjectDescriptor() {
  JsonObjectDescriptor_instance = this;
  this.$$delegate_0__1 = MapSerializer(serializer(StringCompanionObject_instance), JsonElementSerializer_getInstance()).get_descriptor_wjt6a0_k$();
  this.serialName_1 = 'kotlinx.serialization.json.JsonObject';
}
protoOf(JsonObjectDescriptor).get_serialName_u2rqhk_k$ = function () {
  return this.serialName_1;
};
protoOf(JsonObjectDescriptor).getElementName_u4sqmf_k$ = function (index) {
  return this.$$delegate_0__1.getElementName_u4sqmf_k$(index);
};
protoOf(JsonObjectDescriptor).getElementIndex_utfbym_k$ = function (name) {
  return this.$$delegate_0__1.getElementIndex_utfbym_k$(name);
};
protoOf(JsonObjectDescriptor).getElementAnnotations_omrjs6_k$ = function (index) {
  return this.$$delegate_0__1.getElementAnnotations_omrjs6_k$(index);
};
protoOf(JsonObjectDescriptor).getElementDescriptor_ncda77_k$ = function (index) {
  return this.$$delegate_0__1.getElementDescriptor_ncda77_k$(index);
};
protoOf(JsonObjectDescriptor).isElementOptional_heqq56_k$ = function (index) {
  return this.$$delegate_0__1.isElementOptional_heqq56_k$(index);
};
protoOf(JsonObjectDescriptor).get_kind_wop7ml_k$ = function () {
  return this.$$delegate_0__1.get_kind_wop7ml_k$();
};
protoOf(JsonObjectDescriptor).get_isNullable_67sy7o_k$ = function () {
  return this.$$delegate_0__1.get_isNullable_67sy7o_k$();
};
protoOf(JsonObjectDescriptor).get_isInline_usk17w_k$ = function () {
  return this.$$delegate_0__1.get_isInline_usk17w_k$();
};
protoOf(JsonObjectDescriptor).get_elementsCount_288r0x_k$ = function () {
  return this.$$delegate_0__1.get_elementsCount_288r0x_k$();
};
protoOf(JsonObjectDescriptor).get_annotations_20dirp_k$ = function () {
  return this.$$delegate_0__1.get_annotations_20dirp_k$();
};
var JsonObjectDescriptor_instance;
function JsonObjectDescriptor_getInstance() {
  if (JsonObjectDescriptor_instance == null)
    new JsonObjectDescriptor();
  return JsonObjectDescriptor_instance;
}
function JsonObjectSerializer() {
  JsonObjectSerializer_instance = this;
  this.descriptor_1 = JsonObjectDescriptor_getInstance();
}
protoOf(JsonObjectSerializer).get_descriptor_wjt6a0_k$ = function () {
  return this.descriptor_1;
};
protoOf(JsonObjectSerializer).serialize_5nm41s_k$ = function (encoder, value) {
  verify(encoder);
  MapSerializer(serializer(StringCompanionObject_instance), JsonElementSerializer_getInstance()).serialize_5ase3y_k$(encoder, value);
};
protoOf(JsonObjectSerializer).serialize_5ase3y_k$ = function (encoder, value) {
  return this.serialize_5nm41s_k$(encoder, value instanceof JsonObject ? value : THROW_CCE());
};
protoOf(JsonObjectSerializer).deserialize_sy6x50_k$ = function (decoder) {
  verify_0(decoder);
  return new JsonObject(MapSerializer(serializer(StringCompanionObject_instance), JsonElementSerializer_getInstance()).deserialize_sy6x50_k$(decoder));
};
var JsonObjectSerializer_instance;
function JsonObjectSerializer_getInstance() {
  if (JsonObjectSerializer_instance == null)
    new JsonObjectSerializer();
  return JsonObjectSerializer_instance;
}
function JsonPrimitiveSerializer() {
  JsonPrimitiveSerializer_instance = this;
  this.descriptor_1 = buildSerialDescriptor('kotlinx.serialization.json.JsonPrimitive', STRING_getInstance(), []);
}
protoOf(JsonPrimitiveSerializer).get_descriptor_wjt6a0_k$ = function () {
  return this.descriptor_1;
};
protoOf(JsonPrimitiveSerializer).serialize_p6n2zb_k$ = function (encoder, value) {
  verify(encoder);
  var tmp;
  if (value instanceof JsonNull) {
    encoder.encodeSerializableValue_3uuzip_k$(JsonNullSerializer_getInstance(), JsonNull_getInstance());
    tmp = Unit_instance;
  } else {
    var tmp_0 = JsonLiteralSerializer_getInstance();
    encoder.encodeSerializableValue_3uuzip_k$(tmp_0, value instanceof JsonLiteral ? value : THROW_CCE());
    tmp = Unit_instance;
  }
  return tmp;
};
protoOf(JsonPrimitiveSerializer).serialize_5ase3y_k$ = function (encoder, value) {
  return this.serialize_p6n2zb_k$(encoder, value instanceof JsonPrimitive ? value : THROW_CCE());
};
protoOf(JsonPrimitiveSerializer).deserialize_sy6x50_k$ = function (decoder) {
  var jsonDecoder = asJsonDecoder(decoder);
  var result = jsonDecoder.decodeJsonElement_6lz9ye_k$();
  if (!(result instanceof JsonPrimitive)) {
    // Inline function 'kotlinx.serialization.json.internal.decodingExceptionOf' call
    var shortMessage = 'Unexpected JSON element, expected JsonPrimitive, had ' + toString(getKClassFromExpression(result));
    var tmp;
    if (jsonDecoder.get_json_woos35_k$().configuration_1.exceptionsWithDebugInfo_1) {
      var tmp$ret$3 = toString(result);
      tmp = toString(minify(tmp$ret$3));
    } else {
      tmp = null;
    }
    var inputValue = tmp;
    throw new JsonDecodingException(access$formatDecodingException$tJsonExceptionsKt(-1, shortMessage, null, null, inputValue), shortMessage, -1, null, inputValue, null);
  }
  return result;
};
var JsonPrimitiveSerializer_instance;
function JsonPrimitiveSerializer_getInstance() {
  if (JsonPrimitiveSerializer_instance == null)
    new JsonPrimitiveSerializer();
  return JsonPrimitiveSerializer_instance;
}
function JsonArrayDescriptor() {
  JsonArrayDescriptor_instance = this;
  this.$$delegate_0__1 = ListSerializer(JsonElementSerializer_getInstance()).get_descriptor_wjt6a0_k$();
  this.serialName_1 = 'kotlinx.serialization.json.JsonArray';
}
protoOf(JsonArrayDescriptor).get_serialName_u2rqhk_k$ = function () {
  return this.serialName_1;
};
protoOf(JsonArrayDescriptor).getElementName_u4sqmf_k$ = function (index) {
  return this.$$delegate_0__1.getElementName_u4sqmf_k$(index);
};
protoOf(JsonArrayDescriptor).getElementIndex_utfbym_k$ = function (name) {
  return this.$$delegate_0__1.getElementIndex_utfbym_k$(name);
};
protoOf(JsonArrayDescriptor).getElementAnnotations_omrjs6_k$ = function (index) {
  return this.$$delegate_0__1.getElementAnnotations_omrjs6_k$(index);
};
protoOf(JsonArrayDescriptor).getElementDescriptor_ncda77_k$ = function (index) {
  return this.$$delegate_0__1.getElementDescriptor_ncda77_k$(index);
};
protoOf(JsonArrayDescriptor).isElementOptional_heqq56_k$ = function (index) {
  return this.$$delegate_0__1.isElementOptional_heqq56_k$(index);
};
protoOf(JsonArrayDescriptor).get_kind_wop7ml_k$ = function () {
  return this.$$delegate_0__1.get_kind_wop7ml_k$();
};
protoOf(JsonArrayDescriptor).get_isNullable_67sy7o_k$ = function () {
  return this.$$delegate_0__1.get_isNullable_67sy7o_k$();
};
protoOf(JsonArrayDescriptor).get_isInline_usk17w_k$ = function () {
  return this.$$delegate_0__1.get_isInline_usk17w_k$();
};
protoOf(JsonArrayDescriptor).get_elementsCount_288r0x_k$ = function () {
  return this.$$delegate_0__1.get_elementsCount_288r0x_k$();
};
protoOf(JsonArrayDescriptor).get_annotations_20dirp_k$ = function () {
  return this.$$delegate_0__1.get_annotations_20dirp_k$();
};
var JsonArrayDescriptor_instance;
function JsonArrayDescriptor_getInstance() {
  if (JsonArrayDescriptor_instance == null)
    new JsonArrayDescriptor();
  return JsonArrayDescriptor_instance;
}
function JsonArraySerializer() {
  JsonArraySerializer_instance = this;
  this.descriptor_1 = JsonArrayDescriptor_getInstance();
}
protoOf(JsonArraySerializer).get_descriptor_wjt6a0_k$ = function () {
  return this.descriptor_1;
};
protoOf(JsonArraySerializer).serialize_1tf28e_k$ = function (encoder, value) {
  verify(encoder);
  ListSerializer(JsonElementSerializer_getInstance()).serialize_5ase3y_k$(encoder, value);
};
protoOf(JsonArraySerializer).serialize_5ase3y_k$ = function (encoder, value) {
  return this.serialize_1tf28e_k$(encoder, value instanceof JsonArray ? value : THROW_CCE());
};
protoOf(JsonArraySerializer).deserialize_sy6x50_k$ = function (decoder) {
  verify_0(decoder);
  return new JsonArray(ListSerializer(JsonElementSerializer_getInstance()).deserialize_sy6x50_k$(decoder));
};
var JsonArraySerializer_instance;
function JsonArraySerializer_getInstance() {
  if (JsonArraySerializer_instance == null)
    new JsonArraySerializer();
  return JsonArraySerializer_instance;
}
function JsonNullSerializer() {
  JsonNullSerializer_instance = this;
  this.descriptor_1 = buildSerialDescriptor('kotlinx.serialization.json.JsonNull', ENUM_getInstance(), []);
}
protoOf(JsonNullSerializer).get_descriptor_wjt6a0_k$ = function () {
  return this.descriptor_1;
};
protoOf(JsonNullSerializer).serialize_52d5kl_k$ = function (encoder, value) {
  verify(encoder);
  encoder.encodeNull_ejiosz_k$();
};
protoOf(JsonNullSerializer).serialize_5ase3y_k$ = function (encoder, value) {
  return this.serialize_52d5kl_k$(encoder, value instanceof JsonNull ? value : THROW_CCE());
};
protoOf(JsonNullSerializer).deserialize_sy6x50_k$ = function (decoder) {
  verify_0(decoder);
  if (decoder.decodeNotNullMark_us4ba1_k$()) {
    throw decodingExceptionOf("Expected 'null' literal");
  }
  decoder.decodeNull_jzrmuj_k$();
  return JsonNull_getInstance();
};
var JsonNullSerializer_instance;
function JsonNullSerializer_getInstance() {
  if (JsonNullSerializer_instance == null)
    new JsonNullSerializer();
  return JsonNullSerializer_instance;
}
function defer(deferred) {
  return new defer$1(deferred);
}
function JsonLiteralSerializer() {
  JsonLiteralSerializer_instance = this;
  this.descriptor_1 = PrimitiveSerialDescriptor('kotlinx.serialization.json.JsonLiteral', STRING_getInstance());
}
protoOf(JsonLiteralSerializer).get_descriptor_wjt6a0_k$ = function () {
  return this.descriptor_1;
};
protoOf(JsonLiteralSerializer).serialize_1mrbye_k$ = function (encoder, value) {
  verify(encoder);
  if (value.isString_1) {
    return encoder.encodeString_424b5v_k$(value.content_1);
  }
  if (!(value.coerceToInlineType_1 == null)) {
    return encoder.encodeInline_wxp5pu_k$(value.coerceToInlineType_1).encodeString_424b5v_k$(value.content_1);
  }
  var tmp0_safe_receiver = toLongOrNull(value.content_1);
  if (tmp0_safe_receiver == null)
    null;
  else {
    // Inline function 'kotlin.let' call
    return encoder.encodeLong_vlw3uq_k$(tmp0_safe_receiver);
  }
  var tmp1_safe_receiver = toULongOrNull(value.content_1);
  var tmp = tmp1_safe_receiver;
  if ((tmp == null ? null : new ULong(tmp)) == null)
    null;
  else {
    var tmp_0 = tmp1_safe_receiver;
    // Inline function 'kotlin.let' call
    var it = (tmp_0 == null ? null : new ULong(tmp_0)).data_1;
    var tmp_1 = encoder.encodeInline_wxp5pu_k$(serializer_0(Companion_getInstance()).get_descriptor_wjt6a0_k$());
    // Inline function 'kotlin.ULong.toLong' call
    var tmp$ret$4 = _ULong___get_data__impl__fggpzb(it);
    tmp_1.encodeLong_vlw3uq_k$(tmp$ret$4);
    return Unit_instance;
  }
  var tmp2_safe_receiver = toDoubleOrNull(value.content_1);
  if (tmp2_safe_receiver == null)
    null;
  else {
    // Inline function 'kotlin.let' call
    return encoder.encodeDouble_n270q9_k$(tmp2_safe_receiver);
  }
  var tmp3_safe_receiver = toBooleanStrictOrNull(value.content_1);
  if (tmp3_safe_receiver == null)
    null;
  else {
    // Inline function 'kotlin.let' call
    return encoder.encodeBoolean_tu2e59_k$(tmp3_safe_receiver);
  }
  encoder.encodeString_424b5v_k$(value.content_1);
};
protoOf(JsonLiteralSerializer).serialize_5ase3y_k$ = function (encoder, value) {
  return this.serialize_1mrbye_k$(encoder, value instanceof JsonLiteral ? value : THROW_CCE());
};
protoOf(JsonLiteralSerializer).deserialize_sy6x50_k$ = function (decoder) {
  var jsonDecoder = asJsonDecoder(decoder);
  var result = jsonDecoder.decodeJsonElement_6lz9ye_k$();
  if (!(result instanceof JsonLiteral)) {
    // Inline function 'kotlinx.serialization.json.internal.decodingExceptionOf' call
    var shortMessage = 'Unexpected JSON element, expected JsonLiteral, had ' + toString(getKClassFromExpression(result));
    var tmp;
    if (jsonDecoder.get_json_woos35_k$().configuration_1.exceptionsWithDebugInfo_1) {
      var tmp$ret$3 = toString(result);
      tmp = toString(minify(tmp$ret$3));
    } else {
      tmp = null;
    }
    var inputValue = tmp;
    throw new JsonDecodingException(access$formatDecodingException$tJsonExceptionsKt(-1, shortMessage, null, null, inputValue), shortMessage, -1, null, inputValue, null);
  }
  return result;
};
var JsonLiteralSerializer_instance;
function JsonLiteralSerializer_getInstance() {
  if (JsonLiteralSerializer_instance == null)
    new JsonLiteralSerializer();
  return JsonLiteralSerializer_instance;
}
function verify(encoder) {
  asJsonEncoder(encoder);
}
function asJsonDecoder(_this__u8e3s4) {
  var tmp0_elvis_lhs = isInterface(_this__u8e3s4, JsonDecoder) ? _this__u8e3s4 : null;
  var tmp;
  if (tmp0_elvis_lhs == null) {
    throw IllegalStateException_init_$Create$('This serializer can be used only with Json format.' + ('Expected Decoder to be JsonDecoder, got ' + toString(getKClassFromExpression(_this__u8e3s4))));
  } else {
    tmp = tmp0_elvis_lhs;
  }
  return tmp;
}
function verify_0(decoder) {
  asJsonDecoder(decoder);
}
function asJsonEncoder(_this__u8e3s4) {
  var tmp0_elvis_lhs = isInterface(_this__u8e3s4, JsonEncoder) ? _this__u8e3s4 : null;
  var tmp;
  if (tmp0_elvis_lhs == null) {
    throw IllegalStateException_init_$Create$('This serializer can be used only with Json format.' + ('Expected Encoder to be JsonEncoder, got ' + toString(getKClassFromExpression(_this__u8e3s4))));
  } else {
    tmp = tmp0_elvis_lhs;
  }
  return tmp;
}
function _get_original__l7ku1m($this) {
  var tmp0 = $this.original$delegate_1;
  var tmp = KProperty1;
  // Inline function 'kotlin.getValue' call
  getPropertyCallableRef('original', 1, tmp, defer$o$_get_original_$ref_3cje7k(), null);
  return tmp0.get_value_j01efc_k$();
}
function defer$o$_get_original_$ref_3cje7k() {
  return constructCallableReference(function (p0) {
    return _get_original__l7ku1m(p0);
  }, 1, 0, 8);
}
function defer$1($deferred) {
  this.original$delegate_1 = lazy($deferred);
}
protoOf(defer$1).get_serialName_u2rqhk_k$ = function () {
  return _get_original__l7ku1m(this).get_serialName_u2rqhk_k$();
};
protoOf(defer$1).get_kind_wop7ml_k$ = function () {
  return _get_original__l7ku1m(this).get_kind_wop7ml_k$();
};
protoOf(defer$1).get_elementsCount_288r0x_k$ = function () {
  return _get_original__l7ku1m(this).get_elementsCount_288r0x_k$();
};
protoOf(defer$1).getElementName_u4sqmf_k$ = function (index) {
  return _get_original__l7ku1m(this).getElementName_u4sqmf_k$(index);
};
protoOf(defer$1).getElementIndex_utfbym_k$ = function (name) {
  return _get_original__l7ku1m(this).getElementIndex_utfbym_k$(name);
};
protoOf(defer$1).getElementAnnotations_omrjs6_k$ = function (index) {
  return _get_original__l7ku1m(this).getElementAnnotations_omrjs6_k$(index);
};
protoOf(defer$1).getElementDescriptor_ncda77_k$ = function (index) {
  return _get_original__l7ku1m(this).getElementDescriptor_ncda77_k$(index);
};
protoOf(defer$1).isElementOptional_heqq56_k$ = function (index) {
  return _get_original__l7ku1m(this).isElementOptional_heqq56_k$(index);
};
function JsonEncoder() {
}
function JsonDecodingException(fullMessage, shortMessage, offset, path, input, hint) {
  JsonException.call(this, fullMessage);
  captureStack(this, JsonDecodingException);
  this.shortMessage_1 = shortMessage;
  this.offset_1 = offset;
  this.path_1 = path;
  this.input_1 = input;
  this.hint_1 = hint;
}
function JsonEncodingException(shortMessage, classSerialName, hint) {
  classSerialName = classSerialName === VOID ? null : classSerialName;
  hint = hint === VOID ? null : hint;
  JsonException.call(this, formatEncodingException(shortMessage, hint));
  captureStack(this, JsonEncodingException);
  this.shortMessage_1 = shortMessage;
  this.classSerialName_1 = classSerialName;
  this.hint_1 = hint;
}
function JsonException(message) {
  SerializationException_init_$Init$(message, this);
  captureStack(this, JsonException);
  this.message_1 = message;
  delete this.message;
}
protoOf(JsonException).get_message_h23axq_k$ = function () {
  return this.message_1;
};
function Composer(writer) {
  this.writer_1 = writer;
  this.writingFirst_1 = true;
}
protoOf(Composer).indent_cuntic_k$ = function () {
  this.writingFirst_1 = true;
};
protoOf(Composer).unIndent_45q4lx_k$ = function () {
  return Unit_instance;
};
protoOf(Composer).nextItem_40n9p2_k$ = function () {
  this.writingFirst_1 = false;
};
protoOf(Composer).nextItemIfNotFirst_9wb040_k$ = function () {
  this.writingFirst_1 = false;
};
protoOf(Composer).space_po67ue_k$ = function () {
  return Unit_instance;
};
protoOf(Composer).print_49c4ow_k$ = function (v) {
  return this.writer_1.writeChar_9yrrvs_k$(v);
};
protoOf(Composer).print_wtfns3_k$ = function (v) {
  return this.writer_1.write_mozxwr_k$(v);
};
protoOf(Composer).print_81xt5n_k$ = function (v) {
  return this.writer_1.write_mozxwr_k$(v.toString());
};
protoOf(Composer).print_3xddxz_k$ = function (v) {
  return this.writer_1.write_mozxwr_k$(v.toString());
};
protoOf(Composer).print_p65m4b_k$ = function (v) {
  return this.writer_1.writeLong_vk8q1z_k$(fromInt(v));
};
protoOf(Composer).print_l5t6fv_k$ = function (v) {
  return this.writer_1.writeLong_vk8q1z_k$(fromInt(v));
};
protoOf(Composer).print_ay1yo5_k$ = function (v) {
  return this.writer_1.writeLong_vk8q1z_k$(fromInt(v));
};
protoOf(Composer).print_u23kfb_k$ = function (v) {
  return this.writer_1.writeLong_vk8q1z_k$(v);
};
protoOf(Composer).print_u0bpvs_k$ = function (v) {
  return this.writer_1.write_mozxwr_k$(v.toString());
};
protoOf(Composer).printQuoted_gtxn2t_k$ = function (value) {
  return this.writer_1.writeQuoted_k770v7_k$(value);
};
function Composer_0(sb, json) {
  return json.configuration_1.prettyPrint_1 ? new ComposerWithPrettyPrint(sb, json) : new Composer(sb);
}
function ComposerForUnsignedNumbers(writer, forceQuoting) {
  Composer.call(this, writer);
  this.forceQuoting_1 = forceQuoting;
}
protoOf(ComposerForUnsignedNumbers).print_ay1yo5_k$ = function (v) {
  if (this.forceQuoting_1) {
    // Inline function 'kotlin.toUInt' call
    var tmp$ret$0 = _UInt___init__impl__l7qpdl(v);
    this.printQuoted_gtxn2t_k$(UInt__toString_impl_dbgl21(tmp$ret$0));
  } else {
    // Inline function 'kotlin.toUInt' call
    var tmp$ret$1 = _UInt___init__impl__l7qpdl(v);
    this.print_wtfns3_k$(UInt__toString_impl_dbgl21(tmp$ret$1));
  }
};
protoOf(ComposerForUnsignedNumbers).print_u23kfb_k$ = function (v) {
  if (this.forceQuoting_1) {
    // Inline function 'kotlin.toULong' call
    var tmp$ret$0 = _ULong___init__impl__c78o9k(v);
    this.printQuoted_gtxn2t_k$(ULong__toString_impl_f9au7k(tmp$ret$0));
  } else {
    // Inline function 'kotlin.toULong' call
    var tmp$ret$1 = _ULong___init__impl__c78o9k(v);
    this.print_wtfns3_k$(ULong__toString_impl_f9au7k(tmp$ret$1));
  }
};
protoOf(ComposerForUnsignedNumbers).print_p65m4b_k$ = function (v) {
  if (this.forceQuoting_1) {
    // Inline function 'kotlin.toUByte' call
    var tmp$ret$0 = _UByte___init__impl__g9hnc4(v);
    this.printQuoted_gtxn2t_k$(UByte__toString_impl_v72jg(tmp$ret$0));
  } else {
    // Inline function 'kotlin.toUByte' call
    var tmp$ret$1 = _UByte___init__impl__g9hnc4(v);
    this.print_wtfns3_k$(UByte__toString_impl_v72jg(tmp$ret$1));
  }
};
protoOf(ComposerForUnsignedNumbers).print_l5t6fv_k$ = function (v) {
  if (this.forceQuoting_1) {
    // Inline function 'kotlin.toUShort' call
    var tmp$ret$0 = _UShort___init__impl__jigrne(v);
    this.printQuoted_gtxn2t_k$(UShort__toString_impl_edaoee(tmp$ret$0));
  } else {
    // Inline function 'kotlin.toUShort' call
    var tmp$ret$1 = _UShort___init__impl__jigrne(v);
    this.print_wtfns3_k$(UShort__toString_impl_edaoee(tmp$ret$1));
  }
};
function ComposerForUnquotedLiterals(writer, forceQuoting) {
  Composer.call(this, writer);
  this.forceQuoting_1 = forceQuoting;
}
protoOf(ComposerForUnquotedLiterals).printQuoted_gtxn2t_k$ = function (value) {
  if (this.forceQuoting_1) {
    protoOf(Composer).printQuoted_gtxn2t_k$.call(this, value);
  } else {
    protoOf(Composer).print_wtfns3_k$.call(this, value);
  }
};
function ComposerWithPrettyPrint(writer, json) {
  Composer.call(this, writer);
  this.json_1 = json;
  this.level_1 = 0;
}
protoOf(ComposerWithPrettyPrint).indent_cuntic_k$ = function () {
  this.writingFirst_1 = true;
  this.level_1 = this.level_1 + 1 | 0;
};
protoOf(ComposerWithPrettyPrint).unIndent_45q4lx_k$ = function () {
  this.level_1 = this.level_1 - 1 | 0;
};
protoOf(ComposerWithPrettyPrint).nextItem_40n9p2_k$ = function () {
  this.writingFirst_1 = false;
  this.print_wtfns3_k$('\n');
  // Inline function 'kotlin.repeat' call
  var times = this.level_1;
  var inductionVariable = 0;
  if (inductionVariable < times)
    do {
      var index = inductionVariable;
      inductionVariable = inductionVariable + 1 | 0;
      this.print_wtfns3_k$(this.json_1.configuration_1.prettyPrintIndent_1);
    }
     while (inductionVariable < times);
};
protoOf(ComposerWithPrettyPrint).nextItemIfNotFirst_9wb040_k$ = function () {
  if (this.writingFirst_1)
    this.writingFirst_1 = false;
  else {
    this.nextItem_40n9p2_k$();
  }
};
protoOf(ComposerWithPrettyPrint).space_po67ue_k$ = function () {
  this.print_49c4ow_k$(_Char___init__impl__6a9atx(32));
};
function readIfAbsent($this, descriptor, index) {
  $this.isUnmarkedNull_1 = (!descriptor.isElementOptional_heqq56_k$(index) && descriptor.getElementDescriptor_ncda77_k$(index).get_isNullable_67sy7o_k$());
  return $this.isUnmarkedNull_1;
}
function JsonElementMarker$readIfAbsent$ref(p0) {
  return constructCallableReference(function (p0_0, p1) {
    var tmp0 = p0;
    return readIfAbsent(tmp0, p0_0, p1);
  }, 2, 0, 9, 'readIfAbsent', [p0]);
}
function JsonElementMarker(descriptor) {
  var tmp = this;
  tmp.origin_1 = new ElementMarker(descriptor, JsonElementMarker$readIfAbsent$ref(this));
  this.isUnmarkedNull_1 = false;
}
protoOf(JsonElementMarker).mark_lz09aa_k$ = function (index) {
  this.origin_1.mark_qmjjl1_k$(index);
};
protoOf(JsonElementMarker).nextUnmarkedIndex_feexpp_k$ = function () {
  return this.origin_1.nextUnmarkedIndex_u6mxd2_k$();
};
function minify(_this__u8e3s4, offset) {
  offset = offset === VOID ? -1 : offset;
  if (charSequenceLength(_this__u8e3s4) < 200)
    return _this__u8e3s4;
  if (offset === -1) {
    var start = charSequenceLength(_this__u8e3s4) - 60 | 0;
    if (start <= 0)
      return _this__u8e3s4;
    // Inline function 'kotlin.text.substring' call
    var endIndex = charSequenceLength(_this__u8e3s4);
    return '.....' + toString(charSequenceSubSequence(_this__u8e3s4, start, endIndex));
  }
  var start_0 = offset - 30 | 0;
  var end = offset + 30 | 0;
  var prefix = start_0 <= 0 ? '' : '.....';
  var suffix = end >= charSequenceLength(_this__u8e3s4) ? '' : '.....';
  var tmp2 = coerceAtLeast(start_0, 0);
  // Inline function 'kotlin.text.substring' call
  var endIndex_0 = coerceAtMost(end, charSequenceLength(_this__u8e3s4));
  return prefix + toString(charSequenceSubSequence(_this__u8e3s4, tmp2, endIndex_0)) + suffix;
}
function access$formatDecodingException$tJsonExceptionsKt(offset, shortMessage, path, hint, input) {
  return formatDecodingException(offset, shortMessage, path, hint, input);
}
function invalidTrailingComma(_this__u8e3s4, entity) {
  entity = entity === VOID ? 'object' : entity;
  _this__u8e3s4.fail_5m5zd7_k$('Trailing comma before the end of JSON ' + entity, _this__u8e3s4.currentPosition_1 - 1 | 0, "Trailing commas are non-complaint JSON and not allowed by default. Use 'allowTrailingComma = true' in 'Json {}' builder to support them.");
}
function throwInvalidFloatingPointDecoded(_this__u8e3s4, result) {
  _this__u8e3s4.fail$default_ly4hfp_k$(nonFiniteFpMessage(result, null), VOID, "It is possible to deserialize them using 'JsonBuilder.allowSpecialFloatingPointValues = true'");
}
function InvalidKeyKindException(keyDescriptor) {
  return new JsonEncodingException("Value of type '" + keyDescriptor.get_serialName_u2rqhk_k$() + "' can't be used in JSON as a key in the map. " + ("It should have either primitive or enum kind, but its kind is '" + keyDescriptor.get_kind_wop7ml_k$().toString() + "'"), keyDescriptor.get_serialName_u2rqhk_k$(), "Use 'allowStructuredMapKeys = true' in 'Json {}' builder to convert such maps to [key1, value1, key2, value2,...] arrays.");
}
function InvalidFloatingPointEncoded(value, key) {
  key = key === VOID ? null : key;
  return new JsonEncodingException(nonFiniteFpMessage(value, key), VOID, "It is possible to deserialize them using 'JsonBuilder.allowSpecialFloatingPointValues = true'");
}
function access$nonFiniteFpMessage$tJsonExceptionsKt(value, key) {
  return nonFiniteFpMessage(value, key);
}
function decodingExceptionOf(shortMessage) {
  return new JsonDecodingException(formatDecodingException(-1, shortMessage, null, null, null), shortMessage, -1, null, null, null);
}
function formatEncodingException(shortMessage, hint) {
  var tmp;
  // Inline function 'kotlin.text.isNullOrBlank' call
  if (hint == null || isBlank(hint)) {
    tmp = '';
  } else {
    tmp = '\n' + hint;
  }
  return shortMessage + tmp;
}
function decodingExceptionOf_0(_this__u8e3s4, shortMessage, offset, path, hint, input) {
  // Inline function 'kotlinx.serialization.json.internal.ifDebugInput' call
  var tmp;
  if (_this__u8e3s4.configuration_1.exceptionsWithDebugInfo_1) {
    tmp = toString(minify(input, offset));
  } else {
    tmp = null;
  }
  var inputValue = tmp;
  return new JsonDecodingException(formatDecodingException(offset, shortMessage, path, hint, inputValue), shortMessage, offset, path, inputValue, hint);
}
function formatDecodingException(offset, shortMessage, path, hint, input) {
  // Inline function 'kotlin.text.buildString' call
  // Inline function 'kotlin.apply' call
  var this_0 = StringBuilder_init_$Create$();
  if (offset >= 0) {
    this_0.append_22ad7x_k$('Unexpected JSON token at offset ' + offset + ': ');
  }
  this_0.append_22ad7x_k$(shortMessage);
  // Inline function 'kotlin.text.isNullOrBlank' call
  if (!(path == null || isBlank(path))) {
    this_0.append_22ad7x_k$(' at path: ');
    this_0.append_22ad7x_k$(path);
  }
  // Inline function 'kotlin.text.isNullOrBlank' call
  if (!(hint == null || isBlank(hint))) {
    this_0.append_22ad7x_k$('\n' + hint);
  }
  if (!(input == null)) {
    this_0.append_22ad7x_k$('\nJSON input: ');
    this_0.append_22ad7x_k$(input);
  }
  return this_0.toString();
}
function nonFiniteFpMessage(value, key) {
  return 'Unexpected special floating-point value ' + toString(value) + (!(key == null) ? ' with key ' + key + '. ' : '. ') + 'By default, ' + 'non-finite floating point values are prohibited because they do not conform JSON specification.';
}
function get_JsonDeserializationNamesKey() {
  _init_properties_JsonNamesMap_kt__cbbp0k();
  return JsonDeserializationNamesKey;
}
var JsonDeserializationNamesKey;
function get_JsonSerializationNamesKey() {
  _init_properties_JsonNamesMap_kt__cbbp0k();
  return JsonSerializationNamesKey;
}
var JsonSerializationNamesKey;
function ignoreUnknownKeys(_this__u8e3s4, json) {
  _init_properties_JsonNamesMap_kt__cbbp0k();
  var tmp;
  if (json.configuration_1.ignoreUnknownKeys_1) {
    tmp = true;
  } else {
    var tmp0 = _this__u8e3s4.get_annotations_20dirp_k$();
    var tmp$ret$0;
    $l$block_0: {
      // Inline function 'kotlin.collections.any' call
      var tmp_0;
      if (isInterface(tmp0, Collection)) {
        tmp_0 = tmp0.isEmpty_y1axqb_k$();
      } else {
        tmp_0 = false;
      }
      if (tmp_0) {
        tmp$ret$0 = false;
        break $l$block_0;
      }
      var _iterator__ex2g4s = tmp0.iterator_jk1svi_k$();
      while (_iterator__ex2g4s.hasNext_bitz1p_k$()) {
        var element = _iterator__ex2g4s.next_20eer_k$();
        if (element instanceof JsonIgnoreUnknownKeys) {
          tmp$ret$0 = true;
          break $l$block_0;
        }
      }
      tmp$ret$0 = false;
    }
    tmp = tmp$ret$0;
  }
  return tmp;
}
function getJsonNameIndex(_this__u8e3s4, json, name) {
  _init_properties_JsonNamesMap_kt__cbbp0k();
  if (decodeCaseInsensitive(json, _this__u8e3s4)) {
    // Inline function 'kotlin.text.lowercase' call
    // Inline function 'kotlin.js.asDynamic' call
    var tmp$ret$0 = name.toLowerCase();
    return getJsonNameIndexSlowPath(_this__u8e3s4, json, tmp$ret$0);
  }
  var strategy = namingStrategy(_this__u8e3s4, json);
  if (!(strategy == null))
    return getJsonNameIndexSlowPath(_this__u8e3s4, json, name);
  var index = _this__u8e3s4.getElementIndex_utfbym_k$(name);
  if (!(index === -3))
    return index;
  if (!json.configuration_1.useAlternativeNames_1)
    return index;
  return getJsonNameIndexSlowPath(_this__u8e3s4, json, name);
}
function getJsonNameIndexOrThrow(_this__u8e3s4, json, name, suffix) {
  suffix = suffix === VOID ? '' : suffix;
  _init_properties_JsonNamesMap_kt__cbbp0k();
  var index = getJsonNameIndex(_this__u8e3s4, json, name);
  if (index === -3)
    throw SerializationException_init_$Create$(_this__u8e3s4.get_serialName_u2rqhk_k$() + " does not contain element with name '" + name + "'" + suffix);
  return index;
}
function getJsonElementName(_this__u8e3s4, json, index) {
  _init_properties_JsonNamesMap_kt__cbbp0k();
  var strategy = namingStrategy(_this__u8e3s4, json);
  return strategy == null ? _this__u8e3s4.getElementName_u4sqmf_k$(index) : serializationNamesIndices(_this__u8e3s4, json, strategy)[index];
}
function namingStrategy(_this__u8e3s4, json) {
  _init_properties_JsonNamesMap_kt__cbbp0k();
  return equals(_this__u8e3s4.get_kind_wop7ml_k$(), CLASS_getInstance()) ? json.configuration_1.namingStrategy_1 : null;
}
function deserializationNamesMap(_this__u8e3s4, descriptor) {
  _init_properties_JsonNamesMap_kt__cbbp0k();
  var tmp = get_schemaCache(_this__u8e3s4);
  var tmp_0 = get_JsonDeserializationNamesKey();
  return tmp.getOrPut_g280x5_k$(descriptor, tmp_0, deserializationNamesMap$lambda(descriptor, _this__u8e3s4));
}
function getJsonEncodedNames(_this__u8e3s4, json) {
  _init_properties_JsonNamesMap_kt__cbbp0k();
  var strategy = namingStrategy(_this__u8e3s4, json);
  return strategy == null ? jsonCachedSerialNames(_this__u8e3s4) : toSet(serializationNamesIndices(_this__u8e3s4, json, strategy));
}
function decodeCaseInsensitive(_this__u8e3s4, descriptor) {
  _init_properties_JsonNamesMap_kt__cbbp0k();
  return _this__u8e3s4.configuration_1.decodeEnumsCaseInsensitive_1 && equals(descriptor.get_kind_wop7ml_k$(), ENUM_getInstance());
}
function getJsonNameIndexSlowPath(_this__u8e3s4, json, name) {
  _init_properties_JsonNamesMap_kt__cbbp0k();
  var tmp0_elvis_lhs = deserializationNamesMap(json, _this__u8e3s4).get_wei43m_k$(name);
  return tmp0_elvis_lhs == null ? -3 : tmp0_elvis_lhs;
}
function serializationNamesIndices(_this__u8e3s4, json, strategy) {
  _init_properties_JsonNamesMap_kt__cbbp0k();
  var tmp = get_schemaCache(json);
  var tmp_0 = get_JsonSerializationNamesKey();
  return tmp.getOrPut_g280x5_k$(_this__u8e3s4, tmp_0, serializationNamesIndices$lambda(_this__u8e3s4, strategy));
}
function buildDeserializationNamesMap(_this__u8e3s4, json) {
  _init_properties_JsonNamesMap_kt__cbbp0k();
  // Inline function 'kotlin.collections.mutableMapOf' call
  var builder = LinkedHashMap_init_$Create$();
  var useLowercaseEnums = decodeCaseInsensitive(json, _this__u8e3s4);
  var strategyForClasses = namingStrategy(_this__u8e3s4, json);
  var inductionVariable = 0;
  var last = _this__u8e3s4.get_elementsCount_288r0x_k$();
  if (inductionVariable < last)
    do {
      var i = inductionVariable;
      inductionVariable = inductionVariable + 1 | 0;
      // Inline function 'kotlin.collections.filterIsInstance' call
      var tmp0 = _this__u8e3s4.getElementAnnotations_omrjs6_k$(i);
      // Inline function 'kotlin.collections.filterIsInstanceTo' call
      var destination = ArrayList_init_$Create$();
      var _iterator__ex2g4s = tmp0.iterator_jk1svi_k$();
      while (_iterator__ex2g4s.hasNext_bitz1p_k$()) {
        var element = _iterator__ex2g4s.next_20eer_k$();
        if (element instanceof JsonNames) {
          destination.add_utx5q5_k$(element);
        }
      }
      var tmp0_safe_receiver = singleOrNull(destination);
      var tmp1_safe_receiver = tmp0_safe_receiver == null ? null : tmp0_safe_receiver.names_1;
      if (tmp1_safe_receiver == null)
        null;
      else {
        // Inline function 'kotlin.collections.forEach' call
        var inductionVariable_0 = 0;
        var last_0 = tmp1_safe_receiver.length;
        while (inductionVariable_0 < last_0) {
          var element_0 = tmp1_safe_receiver[inductionVariable_0];
          inductionVariable_0 = inductionVariable_0 + 1 | 0;
          var tmp;
          if (useLowercaseEnums) {
            // Inline function 'kotlin.text.lowercase' call
            // Inline function 'kotlin.js.asDynamic' call
            tmp = element_0.toLowerCase();
          } else {
            tmp = element_0;
          }
          buildDeserializationNamesMap$putOrThrow(builder, _this__u8e3s4, tmp, i);
        }
      }
      var tmp_0;
      if (useLowercaseEnums) {
        // Inline function 'kotlin.text.lowercase' call
        // Inline function 'kotlin.js.asDynamic' call
        tmp_0 = _this__u8e3s4.getElementName_u4sqmf_k$(i).toLowerCase();
      } else if (!(strategyForClasses == null)) {
        tmp_0 = strategyForClasses.serialNameForJson_tclx3n_k$(_this__u8e3s4, i, _this__u8e3s4.getElementName_u4sqmf_k$(i));
      } else {
        tmp_0 = null;
      }
      var nameToPut = tmp_0;
      if (nameToPut == null)
        null;
      else {
        // Inline function 'kotlin.let' call
        buildDeserializationNamesMap$putOrThrow(builder, _this__u8e3s4, nameToPut, i);
      }
    }
     while (inductionVariable < last);
  // Inline function 'kotlin.collections.ifEmpty' call
  var tmp_1;
  if (builder.isEmpty_y1axqb_k$()) {
    tmp_1 = emptyMap();
  } else {
    tmp_1 = builder;
  }
  return tmp_1;
}
function deserializationNamesMap$lambda($descriptor, $this_deserializationNamesMap) {
  return function () {
    return buildDeserializationNamesMap($descriptor, $this_deserializationNamesMap);
  };
}
function serializationNamesIndices$lambda($this_serializationNamesIndices, $strategy) {
  return function () {
    // Inline function 'kotlin.collections.mutableSetOf' call
    var trackingSet = LinkedHashSet_init_$Create$();
    var tmp = 0;
    var tmp_0 = $this_serializationNamesIndices.get_elementsCount_288r0x_k$();
    // Inline function 'kotlin.arrayOfNulls' call
    var tmp_1 = Array(tmp_0);
    while (tmp < tmp_0) {
      var tmp_2 = tmp;
      var baseName = $this_serializationNamesIndices.getElementName_u4sqmf_k$(tmp_2);
      var name = $strategy.serialNameForJson_tclx3n_k$($this_serializationNamesIndices, tmp_2, baseName);
      if (!trackingSet.add_utx5q5_k$(name))
        throw new JsonEncodingException("The transformed name '" + name + "' for property " + baseName + ' already exists ' + ('in ' + toString($this_serializationNamesIndices)), $this_serializationNamesIndices.get_serialName_u2rqhk_k$());
      tmp_1[tmp_2] = name;
      tmp = tmp + 1 | 0;
    }
    return tmp_1;
  };
}
function buildDeserializationNamesMap$putOrThrow(_this__u8e3s4, $this_buildDeserializationNamesMap, name, index) {
  var entity = equals($this_buildDeserializationNamesMap.get_kind_wop7ml_k$(), ENUM_getInstance()) ? 'enum value' : 'property';
  // Inline function 'kotlin.collections.contains' call
  // Inline function 'kotlin.collections.containsKey' call
  if ((isInterface(_this__u8e3s4, KtMap) ? _this__u8e3s4 : THROW_CCE()).containsKey_aw81wo_k$(name)) {
    throw decodingExceptionOf("The suggested name '" + name + "' for " + entity + ' ' + $this_buildDeserializationNamesMap.getElementName_u4sqmf_k$(index) + ' is already one of the names for ' + entity + ' ' + ($this_buildDeserializationNamesMap.getElementName_u4sqmf_k$(getValue(_this__u8e3s4, name)) + ' in ' + toString($this_buildDeserializationNamesMap)));
  }
  // Inline function 'kotlin.collections.set' call
  _this__u8e3s4.put_4fpzoq_k$(name, index);
}
var properties_initialized_JsonNamesMap_kt_ljpf42;
function _init_properties_JsonNamesMap_kt__cbbp0k() {
  if (!properties_initialized_JsonNamesMap_kt_ljpf42) {
    properties_initialized_JsonNamesMap_kt_ljpf42 = true;
    JsonDeserializationNamesKey = new Key();
    JsonSerializationNamesKey = new Key();
  }
}
function Tombstone() {
}
var Tombstone_instance;
function Tombstone_getInstance() {
  return Tombstone_instance;
}
function RedactedKey() {
}
var RedactedKey_instance;
function RedactedKey_getInstance() {
  return RedactedKey_instance;
}
function resize($this) {
  var newSize = imul($this.currentDepth_1, 2);
  $this.currentObjectPath_1 = copyOf($this.currentObjectPath_1, newSize);
  var tmp = 0;
  var tmp_0 = new Int32Array(newSize);
  while (tmp < newSize) {
    tmp_0[tmp] = -1;
    tmp = tmp + 1 | 0;
  }
  var newIndices = tmp_0;
  // Inline function 'kotlin.collections.copyInto' call
  var this_0 = $this.indicies_1;
  var endIndex = this_0.length;
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  var tmp_1 = this_0;
  // Inline function 'kotlin.js.unsafeCast' call
  // Inline function 'kotlin.js.asDynamic' call
  arrayCopy(tmp_1, newIndices, 0, 0, endIndex);
  $this.indicies_1 = newIndices;
}
function JsonPath(configuration) {
  this.configuration_1 = configuration;
  var tmp = this;
  // Inline function 'kotlin.arrayOfNulls' call
  tmp.currentObjectPath_1 = Array(8);
  var tmp_0 = this;
  var tmp_1 = 0;
  var tmp_2 = new Int32Array(8);
  while (tmp_1 < 8) {
    tmp_2[tmp_1] = -1;
    tmp_1 = tmp_1 + 1 | 0;
  }
  tmp_0.indicies_1 = tmp_2;
  this.currentDepth_1 = -1;
}
protoOf(JsonPath).pushDescriptor_ymkfo8_k$ = function (sd) {
  this.currentDepth_1 = this.currentDepth_1 + 1 | 0;
  var depth = this.currentDepth_1;
  if (depth === this.currentObjectPath_1.length) {
    resize(this);
  }
  this.currentObjectPath_1[depth] = sd;
};
protoOf(JsonPath).updateDescriptorIndex_kw65aq_k$ = function (index) {
  this.indicies_1[this.currentDepth_1] = index;
};
protoOf(JsonPath).updateCurrentMapKey_rv46l8_k$ = function (key) {
  var tmp;
  if (!(this.indicies_1[this.currentDepth_1] === -2)) {
    this.currentDepth_1 = this.currentDepth_1 + 1 | 0;
    tmp = this.currentDepth_1 === this.currentObjectPath_1.length;
  } else {
    tmp = false;
  }
  if (tmp) {
    resize(this);
  }
  this.currentObjectPath_1[this.currentDepth_1] = this.configuration_1.exceptionsWithDebugInfo_1 ? key : RedactedKey_instance;
  this.indicies_1[this.currentDepth_1] = -2;
};
protoOf(JsonPath).resetCurrentMapKey_1l0a77_k$ = function () {
  if (this.indicies_1[this.currentDepth_1] === -2) {
    this.currentObjectPath_1[this.currentDepth_1] = Tombstone_instance;
  }
};
protoOf(JsonPath).popDescriptor_wfx7tc_k$ = function () {
  var depth = this.currentDepth_1;
  if (this.indicies_1[depth] === -2) {
    this.indicies_1[depth] = -1;
    this.currentDepth_1 = this.currentDepth_1 - 1 | 0;
  }
  if (!(this.currentDepth_1 === -1)) {
    this.currentDepth_1 = this.currentDepth_1 - 1 | 0;
  }
};
protoOf(JsonPath).getPath_18su3p_k$ = function () {
  // Inline function 'kotlin.text.buildString' call
  // Inline function 'kotlin.apply' call
  var this_0 = StringBuilder_init_$Create$();
  this_0.append_22ad7x_k$('$');
  // Inline function 'kotlin.repeat' call
  var times = this.currentDepth_1 + 1 | 0;
  var inductionVariable = 0;
  if (inductionVariable < times)
    do {
      var index = inductionVariable;
      inductionVariable = inductionVariable + 1 | 0;
      var element = this.currentObjectPath_1[index];
      if (!(element == null) ? isInterface(element, SerialDescriptor) : false) {
        if (equals(element.get_kind_wop7ml_k$(), LIST_getInstance())) {
          if (!(this.indicies_1[index] === -1)) {
            this_0.append_22ad7x_k$('[');
            this_0.append_uppzia_k$(this.indicies_1[index]);
            this_0.append_22ad7x_k$(']');
          }
        } else {
          var idx = this.indicies_1[index];
          if (idx >= 0) {
            this_0.append_22ad7x_k$('.');
            this_0.append_22ad7x_k$(element.getElementName_u4sqmf_k$(idx));
          }
        }
      } else {
        if (element === RedactedKey_instance) {
          this_0.append_22ad7x_k$('[<debug info disabled>]');
        } else {
          if (!(element === Tombstone_instance)) {
            this_0.append_22ad7x_k$('[');
            this_0.append_22ad7x_k$("'");
            this_0.append_t8pm91_k$(element);
            this_0.append_22ad7x_k$("'");
            this_0.append_22ad7x_k$(']');
          }
        }
      }
    }
     while (inductionVariable < times);
  return this_0.toString();
};
protoOf(JsonPath).toString = function () {
  return this.getPath_18su3p_k$();
};
function checkKind($this, descriptor, actualClass) {
  var kind = descriptor.get_kind_wop7ml_k$();
  var tmp;
  if (kind instanceof PolymorphicKind) {
    tmp = true;
  } else {
    tmp = equals(kind, CONTEXTUAL_getInstance());
  }
  if (tmp) {
    throw IllegalArgumentException_init_$Create$('Serializer for ' + actualClass.get_simpleName_r6f8py_k$() + " can't be registered as a subclass for polymorphic serialization " + ('because its kind ' + kind.toString() + ' is not concrete. To work with multiple hierarchies, register it as a base class.'));
  }
  if ($this.useArrayPolymorphism_1)
    return Unit_instance;
  if (!$this.isDiscriminatorRequired_1)
    return Unit_instance;
  var tmp_0;
  var tmp_1;
  if (equals(kind, LIST_getInstance()) || equals(kind, MAP_getInstance())) {
    tmp_1 = true;
  } else {
    tmp_1 = kind instanceof PrimitiveKind;
  }
  if (tmp_1) {
    tmp_0 = true;
  } else {
    tmp_0 = kind instanceof ENUM;
  }
  if (tmp_0) {
    throw IllegalArgumentException_init_$Create$('Serializer for ' + actualClass.get_simpleName_r6f8py_k$() + ' of kind ' + kind.toString() + ' cannot be serialized polymorphically with class discriminator.');
  }
}
function JsonSerializersModuleValidator(configuration) {
  this.useArrayPolymorphism_1 = configuration.useArrayPolymorphism_1;
  this.isDiscriminatorRequired_1 = !configuration.classDiscriminatorMode_1.equals(ClassDiscriminatorMode_NONE_getInstance());
}
protoOf(JsonSerializersModuleValidator).contextual_cy8rhx_k$ = function (kClass, provider) {
};
protoOf(JsonSerializersModuleValidator).polymorphic_q1pzlt_k$ = function (baseClass, actualClass, actualSerializer) {
  var descriptor = actualSerializer.get_descriptor_wjt6a0_k$();
  checkKind(this, descriptor, actualClass);
};
protoOf(JsonSerializersModuleValidator).polymorphicDefaultSerializer_svn9pe_k$ = function (baseClass, defaultSerializerProvider) {
};
protoOf(JsonSerializersModuleValidator).polymorphicDefaultDeserializer_1at2c4_k$ = function (baseClass, defaultDeserializerProvider) {
};
function encodeByWriter(json, writer, serializer, value) {
  var tmp = WriteMode_OBJ_getInstance();
  // Inline function 'kotlin.arrayOfNulls' call
  var size = get_entries().get_size_woubt6_k$();
  var tmp$ret$0 = Array(size);
  var encoder = StreamingJsonEncoder_init_$Create$(writer, json, tmp, tmp$ret$0);
  encoder.encodeSerializableValue_3uuzip_k$(serializer, value);
}
function readObject($this) {
  // Inline function 'kotlinx.serialization.json.internal.JsonTreeReader.readObjectImpl' call
  var lastToken = $this.lexer_1.consumeNextToken_dugwfi_k$(6);
  if ($this.lexer_1.peekNextToken_1gqwr9_k$() === 4) {
    $this.lexer_1.fail$default_ly4hfp_k$('Unexpected leading comma');
  }
  // Inline function 'kotlin.collections.linkedMapOf' call
  var result = LinkedHashMap_init_$Create$();
  $l$loop: while ($this.lexer_1.canConsumeValue_oljqd7_k$()) {
    var key = $this.isLenient_1 ? $this.lexer_1.consumeStringLenient_9oypvu_k$() : $this.lexer_1.consumeString_j3j2z7_k$();
    $this.lexer_1.consumeNextToken_dugwfi_k$(5);
    var element = $this.read_22xsm_k$();
    // Inline function 'kotlin.collections.set' call
    result.put_4fpzoq_k$(key, element);
    lastToken = $this.lexer_1.consumeNextToken_uf1vsa_k$();
    var tmp0_subject = lastToken;
    if (tmp0_subject !== 4)
      if (tmp0_subject === 7)
        break $l$loop;
      else {
        $this.lexer_1.fail$default_ly4hfp_k$('Expected end of the object or comma');
      }
  }
  if (lastToken === 6) {
    $this.lexer_1.consumeNextToken_dugwfi_k$(7);
  } else if (lastToken === 4) {
    if (!$this.trailingCommaAllowed_1) {
      invalidTrailingComma($this.lexer_1);
    }
    $this.lexer_1.consumeNextToken_dugwfi_k$(7);
  }
  return new JsonObject(result);
}
function readObject_0($this, $receiver, $completion) {
  var tmp = new $readObjectCOROUTINE$($this, $receiver, $completion);
  tmp.result_1 = Unit_instance;
  tmp.exception_1 = null;
  return tmp.doResume_5yljmg_k$();
}
function readArray($this) {
  var lastToken = $this.lexer_1.consumeNextToken_uf1vsa_k$();
  if ($this.lexer_1.peekNextToken_1gqwr9_k$() === 4) {
    $this.lexer_1.fail$default_ly4hfp_k$('Unexpected leading comma');
  }
  // Inline function 'kotlin.collections.arrayListOf' call
  var result = ArrayList_init_$Create$();
  while ($this.lexer_1.canConsumeValue_oljqd7_k$()) {
    var element = $this.read_22xsm_k$();
    result.add_utx5q5_k$(element);
    lastToken = $this.lexer_1.consumeNextToken_uf1vsa_k$();
    if (!(lastToken === 4)) {
      var tmp0 = $this.lexer_1;
      // Inline function 'kotlinx.serialization.json.internal.AbstractJsonLexer.require' call
      var condition = lastToken === 9;
      var position = tmp0.currentPosition_1;
      if (!condition) {
        var tmp$ret$2 = 'Expected end of the array or comma';
        tmp0.fail$default_ly4hfp_k$(tmp$ret$2, position);
      }
    }
  }
  if (lastToken === 8) {
    $this.lexer_1.consumeNextToken_dugwfi_k$(9);
  } else if (lastToken === 4) {
    if (!$this.trailingCommaAllowed_1) {
      invalidTrailingComma($this.lexer_1, 'array');
    }
    $this.lexer_1.consumeNextToken_dugwfi_k$(9);
  }
  return new JsonArray(result);
}
function readValue($this, isString) {
  var tmp;
  if ($this.isLenient_1 || !isString) {
    tmp = $this.lexer_1.consumeStringLenient_9oypvu_k$();
  } else {
    tmp = $this.lexer_1.consumeString_j3j2z7_k$();
  }
  var string = tmp;
  if (!isString && string === 'null')
    return JsonNull_getInstance();
  return new JsonLiteral(string, isString);
}
function readDeepRecursive($this) {
  return invoke(new DeepRecursiveFunction(JsonTreeReader$readDeepRecursive$slambda_0($this, null)), Unit_instance);
}
function JsonTreeReader$readDeepRecursive$slambda(this$0, resultContinuation) {
  this.this$0__1 = this$0;
  CoroutineImpl.call(this, resultContinuation);
}
protoOf(JsonTreeReader$readDeepRecursive$slambda).invoke_bq9n4h_k$ = function ($this$DeepRecursiveFunction, it, $completion) {
  var tmp = this.create_z8vk9n_k$($this$DeepRecursiveFunction, it, $completion);
  tmp.result_1 = Unit_instance;
  tmp.exception_1 = null;
  return tmp.doResume_5yljmg_k$();
};
protoOf(JsonTreeReader$readDeepRecursive$slambda).invoke_4tzzq6_k$ = function (p1, p2, $completion) {
  var tmp = p1 instanceof DeepRecursiveScope ? p1 : THROW_CCE();
  return this.invoke_bq9n4h_k$(tmp, p2 instanceof Unit ? p2 : THROW_CCE(), $completion);
};
protoOf(JsonTreeReader$readDeepRecursive$slambda).doResume_5yljmg_k$ = function () {
  var suspendResult = this.result_1;
  $sm: do
    try {
      var tmp = this.state_1;
      switch (tmp) {
        case 0:
          this.exceptionState_1 = 3;
          var tmp0_subject = this.this$0__1.lexer_1.peekNextToken_1gqwr9_k$();
          if (tmp0_subject === 1) {
            this.WHEN_RESULT0__1 = readValue(this.this$0__1, true);
            this.state_1 = 2;
            continue $sm;
          } else {
            if (tmp0_subject === 0) {
              this.WHEN_RESULT0__1 = readValue(this.this$0__1, false);
              this.state_1 = 2;
              continue $sm;
            } else {
              if (tmp0_subject === 6) {
                this.state_1 = 1;
                suspendResult = readObject_0(this.this$0__1, this.$this$DeepRecursiveFunction_1, this);
                if (suspendResult === get_COROUTINE_SUSPENDED()) {
                  return suspendResult;
                }
                continue $sm;
              } else {
                if (tmp0_subject === 8) {
                  this.WHEN_RESULT0__1 = readArray(this.this$0__1);
                  this.state_1 = 2;
                  continue $sm;
                } else {
                  var tmp_0 = this;
                  this.this$0__1.lexer_1.fail$default_ly4hfp_k$("Can't begin reading element, unexpected token");
                }
              }
            }
          }

          break;
        case 1:
          this.WHEN_RESULT0__1 = suspendResult;
          this.state_1 = 2;
          continue $sm;
        case 2:
          return this.WHEN_RESULT0__1;
        case 3:
          throw this.exception_1;
      }
    } catch ($p) {
      var e = $p;
      if (this.exceptionState_1 === 3) {
        throw e;
      } else {
        this.state_1 = this.exceptionState_1;
        this.exception_1 = e;
      }
    }
   while (true);
};
protoOf(JsonTreeReader$readDeepRecursive$slambda).create_z8vk9n_k$ = function ($this$DeepRecursiveFunction, it, completion) {
  var i = new JsonTreeReader$readDeepRecursive$slambda(this.this$0__1, completion);
  i.$this$DeepRecursiveFunction_1 = $this$DeepRecursiveFunction;
  i.it_1 = it;
  return i;
};
function JsonTreeReader$readDeepRecursive$slambda_0(this$0, resultContinuation) {
  var i = new JsonTreeReader$readDeepRecursive$slambda(this$0, resultContinuation);
  return constructCallableReference(function ($this$DeepRecursiveFunction, it, $completion) {
    return i.invoke_bq9n4h_k$($this$DeepRecursiveFunction, it, $completion);
  }, 2);
}
function $readObjectCOROUTINE$(_this__u8e3s4, _this__u8e3s4_0, resultContinuation) {
  CoroutineImpl.call(this, resultContinuation);
  this._this__u8e3s4__1 = _this__u8e3s4;
  this._this__u8e3s4__2 = _this__u8e3s4_0;
}
protoOf($readObjectCOROUTINE$).doResume_5yljmg_k$ = function () {
  var suspendResult = this.result_1;
  $sm: do
    try {
      var tmp = this.state_1;
      switch (tmp) {
        case 0:
          this.exceptionState_1 = 5;
          this.this1__1 = this._this__u8e3s4__1;
          this.lastToken2__1 = this.this1__1.lexer_1.consumeNextToken_dugwfi_k$(6);
          if (this.this1__1.lexer_1.peekNextToken_1gqwr9_k$() === 4) {
            this.this1__1.lexer_1.fail$default_ly4hfp_k$('Unexpected leading comma');
          }

          var tmp_0 = this;
          tmp_0.result0__1 = LinkedHashMap_init_$Create$();
          this.state_1 = 1;
          continue $sm;
        case 1:
          if (!this.this1__1.lexer_1.canConsumeValue_oljqd7_k$()) {
            this.state_1 = 4;
            continue $sm;
          }

          this.key3__1 = this.this1__1.isLenient_1 ? this.this1__1.lexer_1.consumeStringLenient_9oypvu_k$() : this.this1__1.lexer_1.consumeString_j3j2z7_k$();
          this.this1__1.lexer_1.consumeNextToken_dugwfi_k$(5);
          this.state_1 = 2;
          suspendResult = this._this__u8e3s4__2.callRecursive_g04ojy_k$(Unit_instance, this);
          if (suspendResult === get_COROUTINE_SUSPENDED()) {
            return suspendResult;
          }

          continue $sm;
        case 2:
          var element = suspendResult;
          var tmp0 = this.result0__1;
          var key = this.key3__1;
          tmp0.put_4fpzoq_k$(key, element);
          this.lastToken2__1 = this.this1__1.lexer_1.consumeNextToken_uf1vsa_k$();
          var tmp0_subject = this.lastToken2__1;
          if (tmp0_subject === 4) {
            this.state_1 = 3;
            continue $sm;
          } else {
            if (tmp0_subject === 7) {
              this.state_1 = 4;
              continue $sm;
            } else {
              this.this1__1.lexer_1.fail$default_ly4hfp_k$('Expected end of the object or comma');
            }
          }

          break;
        case 3:
          this.state_1 = 1;
          continue $sm;
        case 4:
          if (this.lastToken2__1 === 6) {
            this.this1__1.lexer_1.consumeNextToken_dugwfi_k$(7);
          } else if (this.lastToken2__1 === 4) {
            if (!this.this1__1.trailingCommaAllowed_1) {
              invalidTrailingComma(this.this1__1.lexer_1);
            }
            this.this1__1.lexer_1.consumeNextToken_dugwfi_k$(7);
          }

          return new JsonObject(this.result0__1);
        case 5:
          throw this.exception_1;
      }
    } catch ($p) {
      var e = $p;
      if (this.exceptionState_1 === 5) {
        throw e;
      } else {
        this.state_1 = this.exceptionState_1;
        this.exception_1 = e;
      }
    }
   while (true);
};
function JsonTreeReader(configuration, lexer) {
  this.lexer_1 = lexer;
  this.isLenient_1 = configuration.isLenient_1;
  this.trailingCommaAllowed_1 = configuration.allowTrailingComma_1;
  this.stackDepth_1 = 0;
}
protoOf(JsonTreeReader).read_22xsm_k$ = function () {
  var token = this.lexer_1.peekNextToken_1gqwr9_k$();
  var tmp;
  if (token === 1) {
    tmp = readValue(this, true);
  } else if (token === 0) {
    tmp = readValue(this, false);
  } else if (token === 6) {
    var tmp_0;
    this.stackDepth_1 = this.stackDepth_1 + 1 | 0;
    if (this.stackDepth_1 === 200) {
      tmp_0 = readDeepRecursive(this);
    } else {
      tmp_0 = readObject(this);
    }
    var result = tmp_0;
    this.stackDepth_1 = this.stackDepth_1 - 1 | 0;
    tmp = result;
  } else if (token === 8) {
    tmp = readArray(this);
  } else {
    this.lexer_1.fail$default_ly4hfp_k$('Cannot read Json element because of unexpected ' + tokenDescription(token));
  }
  return tmp;
};
function classDiscriminator(_this__u8e3s4, json) {
  var _iterator__ex2g4s = _this__u8e3s4.get_annotations_20dirp_k$().iterator_jk1svi_k$();
  while (_iterator__ex2g4s.hasNext_bitz1p_k$()) {
    var annotation = _iterator__ex2g4s.next_20eer_k$();
    if (annotation instanceof JsonClassDiscriminator)
      return annotation.discriminator_1;
  }
  return json.configuration_1.classDiscriminator_1;
}
function access$checkEncodingConflicts$tPolymorphicKt($receiver, serializer, actualSerializer, classDiscriminator) {
  return checkEncodingConflicts($receiver, serializer, actualSerializer, classDiscriminator);
}
function checkKind_0(kind) {
  if (kind instanceof ENUM) {
    // Inline function 'kotlin.error' call
    var message = "Enums cannot be serialized polymorphically with 'type' parameter. You can use 'JsonBuilder.useArrayPolymorphism' instead";
    throw IllegalStateException_init_$Create$(toString(message));
  }
  if (kind instanceof PrimitiveKind) {
    // Inline function 'kotlin.error' call
    var message_0 = "Primitives cannot be serialized polymorphically with 'type' parameter. You can use 'JsonBuilder.useArrayPolymorphism' instead";
    throw IllegalStateException_init_$Create$(toString(message_0));
  }
  if (kind instanceof PolymorphicKind) {
    // Inline function 'kotlin.error' call
    var message_1 = 'Actual serializer for polymorphic cannot be polymorphic itself';
    throw IllegalStateException_init_$Create$(toString(message_1));
  }
}
function checkEncodingConflicts(_this__u8e3s4, serializer, actualSerializer, classDiscriminator) {
  if (getJsonEncodedNames(actualSerializer.get_descriptor_wjt6a0_k$(), _this__u8e3s4).contains_aljjnj_k$(classDiscriminator)) {
    var baseName = serializer.get_descriptor_wjt6a0_k$().get_serialName_u2rqhk_k$();
    var actualName = actualSerializer.get_descriptor_wjt6a0_k$().get_serialName_u2rqhk_k$();
    var text = _this__u8e3s4.configuration_1.classDiscriminatorMode_1.equals(ClassDiscriminatorMode_ALL_JSON_OBJECTS_getInstance()) && baseName === actualName ? 'in ALL_JSON_OBJECTS class discriminator mode' : "as base class '" + baseName + "'";
    var tmp0_shortMessage = "Class '" + actualName + "' cannot be serialized " + text + ' because' + (" it has property name that conflicts with JSON class discriminator '" + classDiscriminator + "'.");
    var tmp1_hint = 'You can either change class discriminator in JsonConfiguration, or rename property with @SerialName annotation.';
    throw new JsonEncodingException(tmp0_shortMessage, actualName, tmp1_hint);
  }
}
function Key() {
}
function DescriptorSchemaCache() {
  this.map_1 = createMapForCache(16);
}
protoOf(DescriptorSchemaCache).set_y36rj3_k$ = function (descriptor, key, value) {
  // Inline function 'kotlin.collections.getOrPut' call
  var this_0 = this.map_1;
  var value_0 = this_0.get_wei43m_k$(descriptor);
  var tmp;
  if (value_0 == null) {
    var answer = createMapForCache(2);
    this_0.put_4fpzoq_k$(descriptor, answer);
    tmp = answer;
  } else {
    tmp = value_0;
  }
  var tmp0 = tmp;
  var tmp2 = key instanceof Key ? key : THROW_CCE();
  // Inline function 'kotlin.collections.set' call
  var value_1 = !(value == null) ? value : THROW_CCE();
  tmp0.put_4fpzoq_k$(tmp2, value_1);
};
protoOf(DescriptorSchemaCache).getOrPut_g280x5_k$ = function (descriptor, key, defaultValue) {
  var tmp0_safe_receiver = this.get_xn5txp_k$(descriptor, key);
  if (tmp0_safe_receiver == null)
    null;
  else {
    // Inline function 'kotlin.let' call
    return tmp0_safe_receiver;
  }
  var value = defaultValue();
  this.set_y36rj3_k$(descriptor, key, value);
  return value;
};
protoOf(DescriptorSchemaCache).get_xn5txp_k$ = function (descriptor, key) {
  var tmp0_safe_receiver = this.map_1.get_wei43m_k$(descriptor);
  var tmp;
  if (tmp0_safe_receiver == null) {
    tmp = null;
  } else {
    tmp = tmp0_safe_receiver.get_wei43m_k$(key instanceof Key ? key : THROW_CCE());
  }
  var tmp_0 = tmp;
  return !(tmp_0 == null) ? tmp_0 : null;
};
function DiscriminatorHolder(discriminatorToSkip) {
  this.discriminatorToSkip_1 = discriminatorToSkip;
}
function trySkip($this, $receiver, unknownKey) {
  if ($receiver == null)
    return false;
  if ($receiver.discriminatorToSkip_1 === unknownKey) {
    $receiver.discriminatorToSkip_1 = null;
    return true;
  }
  return false;
}
function skipLeftoverElements($this, descriptor) {
  while (!($this.decodeElementIndex_bstkhp_k$(descriptor) === -1)) {
  }
}
function checkLeadingComma($this) {
  if ($this.lexer_1.peekNextToken_1gqwr9_k$() === 4) {
    $this.lexer_1.fail$default_ly4hfp_k$('Unexpected leading comma');
  }
}
function decodeMapIndex($this) {
  var hasComma = false;
  var decodingKey = !(($this.currentIndex_1 % 2 | 0) === 0);
  if (decodingKey) {
    if (!($this.currentIndex_1 === -1)) {
      hasComma = $this.lexer_1.tryConsumeComma_9n2ve4_k$();
    }
  } else {
    $this.lexer_1.consumeNextToken_kteg6b_k$(_Char___init__impl__6a9atx(58));
  }
  var tmp;
  if ($this.lexer_1.canConsumeValue_oljqd7_k$()) {
    if (decodingKey) {
      if ($this.currentIndex_1 === -1) {
        var tmp0 = $this.lexer_1;
        // Inline function 'kotlinx.serialization.json.internal.AbstractJsonLexer.require' call
        var condition = !hasComma;
        var position = tmp0.currentPosition_1;
        if (!condition) {
          var tmp$ret$1 = 'Unexpected leading comma';
          tmp0.fail$default_ly4hfp_k$(tmp$ret$1, position);
        }
      } else {
        var tmp0_0 = $this.lexer_1;
        // Inline function 'kotlinx.serialization.json.internal.AbstractJsonLexer.require' call
        var condition_0 = hasComma;
        var position_0 = tmp0_0.currentPosition_1;
        if (!condition_0) {
          var tmp$ret$3 = 'Expected comma after the key-value pair';
          tmp0_0.fail$default_ly4hfp_k$(tmp$ret$3, position_0);
        }
      }
    }
    $this.currentIndex_1 = $this.currentIndex_1 + 1 | 0;
    tmp = $this.currentIndex_1;
  } else {
    if (hasComma && !$this.json_1.configuration_1.allowTrailingComma_1) {
      invalidTrailingComma($this.lexer_1);
    }
    tmp = -1;
  }
  return tmp;
}
function coerceInputValue($this, descriptor, index) {
  var tmp0 = $this.json_1;
  var tmp$ret$0;
  $l$block_2: {
    // Inline function 'kotlinx.serialization.json.internal.tryCoerceValue' call
    var isOptional = descriptor.isElementOptional_heqq56_k$(index);
    var elementDescriptor = descriptor.getElementDescriptor_ncda77_k$(index);
    var tmp;
    if (isOptional && !elementDescriptor.get_isNullable_67sy7o_k$()) {
      tmp = $this.lexer_1.tryConsumeNull_2shltp_k$(true);
    } else {
      tmp = false;
    }
    if (tmp) {
      tmp$ret$0 = true;
      break $l$block_2;
    }
    if (equals(elementDescriptor.get_kind_wop7ml_k$(), ENUM_getInstance())) {
      var tmp_0;
      if (elementDescriptor.get_isNullable_67sy7o_k$()) {
        tmp_0 = $this.lexer_1.tryConsumeNull_2shltp_k$(false);
      } else {
        tmp_0 = false;
      }
      if (tmp_0) {
        tmp$ret$0 = false;
        break $l$block_2;
      }
      var tmp0_elvis_lhs = $this.lexer_1.peekString_d4c947_k$($this.configuration_1.isLenient_1);
      var tmp_1;
      if (tmp0_elvis_lhs == null) {
        tmp$ret$0 = false;
        break $l$block_2;
      } else {
        tmp_1 = tmp0_elvis_lhs;
      }
      var enumValue = tmp_1;
      var enumIndex = getJsonNameIndex(elementDescriptor, tmp0, enumValue);
      var coerceToNull = !tmp0.configuration_1.explicitNulls_1 && elementDescriptor.get_isNullable_67sy7o_k$();
      if (enumIndex === -3 && (isOptional || coerceToNull)) {
        $this.lexer_1.consumeString_j3j2z7_k$();
        tmp$ret$0 = true;
        break $l$block_2;
      }
    }
    tmp$ret$0 = false;
  }
  return tmp$ret$0;
}
function decodeObjectIndex($this, descriptor) {
  var hasComma = $this.lexer_1.tryConsumeComma_9n2ve4_k$();
  while ($this.lexer_1.canConsumeValue_oljqd7_k$()) {
    hasComma = false;
    var key = decodeStringKey($this);
    $this.lexer_1.consumeNextToken_kteg6b_k$(_Char___init__impl__6a9atx(58));
    var index = getJsonNameIndex(descriptor, $this.json_1, key);
    var tmp;
    if (!(index === -3)) {
      var tmp_0;
      if ($this.configuration_1.coerceInputValues_1 && coerceInputValue($this, descriptor, index)) {
        hasComma = $this.lexer_1.tryConsumeComma_9n2ve4_k$();
        tmp_0 = false;
      } else {
        var tmp0_safe_receiver = $this.elementMarker_1;
        if (tmp0_safe_receiver == null)
          null;
        else {
          tmp0_safe_receiver.mark_lz09aa_k$(index);
        }
        return index;
      }
      tmp = tmp_0;
    } else {
      tmp = true;
    }
    var isUnknown = tmp;
    if (isUnknown) {
      hasComma = handleUnknown($this, descriptor, key);
    }
  }
  if (hasComma && !$this.json_1.configuration_1.allowTrailingComma_1) {
    invalidTrailingComma($this.lexer_1);
  }
  var tmp1_safe_receiver = $this.elementMarker_1;
  var tmp2_elvis_lhs = tmp1_safe_receiver == null ? null : tmp1_safe_receiver.nextUnmarkedIndex_feexpp_k$();
  return tmp2_elvis_lhs == null ? -1 : tmp2_elvis_lhs;
}
function handleUnknown($this, descriptor, key) {
  if (ignoreUnknownKeys(descriptor, $this.json_1) || trySkip($this, $this.discriminatorHolder_1, key)) {
    $this.lexer_1.skipElement_eq7t4_k$($this.configuration_1.isLenient_1);
  } else {
    $this.lexer_1.path_1.popDescriptor_wfx7tc_k$();
    $this.lexer_1.failOnUnknownKey_g0pqrs_k$(key);
  }
  return $this.lexer_1.tryConsumeComma_9n2ve4_k$();
}
function decodeListIndex($this) {
  var hasComma = $this.lexer_1.tryConsumeComma_9n2ve4_k$();
  var tmp;
  if ($this.lexer_1.canConsumeValue_oljqd7_k$()) {
    if (!($this.currentIndex_1 === -1) && !hasComma) {
      $this.lexer_1.fail$default_ly4hfp_k$('Expected end of the array or comma');
    }
    $this.currentIndex_1 = $this.currentIndex_1 + 1 | 0;
    tmp = $this.currentIndex_1;
  } else {
    if (hasComma && !$this.json_1.configuration_1.allowTrailingComma_1) {
      invalidTrailingComma($this.lexer_1, 'array');
    }
    tmp = -1;
  }
  return tmp;
}
function decodeStringKey($this) {
  var tmp;
  if ($this.configuration_1.isLenient_1) {
    tmp = $this.lexer_1.consumeStringLenientNotNull_m2rgts_k$();
  } else {
    tmp = $this.lexer_1.consumeKeyString_mfa3ws_k$();
  }
  return tmp;
}
function StreamingJsonDecoder(json, mode, lexer, descriptor, discriminatorHolder) {
  AbstractDecoder.call(this);
  this.json_1 = json;
  this.mode_1 = mode;
  this.lexer_1 = lexer;
  this.serializersModule_1 = this.json_1.get_serializersModule_piitvg_k$();
  this.currentIndex_1 = -1;
  this.discriminatorHolder_1 = discriminatorHolder;
  this.configuration_1 = this.json_1.configuration_1;
  this.elementMarker_1 = this.configuration_1.explicitNulls_1 ? null : new JsonElementMarker(descriptor);
}
protoOf(StreamingJsonDecoder).get_json_woos35_k$ = function () {
  return this.json_1;
};
protoOf(StreamingJsonDecoder).get_serializersModule_piitvg_k$ = function () {
  return this.serializersModule_1;
};
protoOf(StreamingJsonDecoder).decodeJsonElement_6lz9ye_k$ = function () {
  return (new JsonTreeReader(this.json_1.configuration_1, this.lexer_1)).read_22xsm_k$();
};
protoOf(StreamingJsonDecoder).decodeSerializableValue_xpnpad_k$ = function (deserializer) {
  try {
    var tmp;
    if (!(deserializer instanceof AbstractPolymorphicSerializer)) {
      tmp = true;
    } else {
      tmp = this.json_1.configuration_1.useArrayPolymorphism_1;
    }
    if (tmp) {
      return deserializer.deserialize_sy6x50_k$(this);
    }
    var discriminator = classDiscriminator(deserializer.get_descriptor_wjt6a0_k$(), this.json_1);
    var tmp0_elvis_lhs = this.lexer_1.peekLeadingMatchingValue_y3am18_k$(discriminator, this.configuration_1.isLenient_1);
    var tmp_0;
    if (tmp0_elvis_lhs == null) {
      var tmp2 = isInterface(deserializer, DeserializationStrategy) ? deserializer : THROW_CCE();
      var tmp$ret$0;
      $l$block: {
        // Inline function 'kotlinx.serialization.json.internal.decodeSerializableValuePolymorphic' call
        var tmp_1;
        if (!(tmp2 instanceof AbstractPolymorphicSerializer)) {
          tmp_1 = true;
        } else {
          tmp_1 = this.get_json_woos35_k$().configuration_1.useArrayPolymorphism_1;
        }
        if (tmp_1) {
          tmp$ret$0 = tmp2.deserialize_sy6x50_k$(this);
          break $l$block;
        }
        var discriminator_0 = classDiscriminator(tmp2.get_descriptor_wjt6a0_k$(), this.get_json_woos35_k$());
        var tmp2_0 = this.decodeJsonElement_6lz9ye_k$();
        // Inline function 'kotlinx.serialization.json.internal.cast' call
        var serialName = tmp2.get_descriptor_wjt6a0_k$().get_serialName_u2rqhk_k$();
        if (!(tmp2_0 instanceof JsonObject)) {
          var tmp2_1 = 'Expected ' + getKClass(JsonObject).get_simpleName_r6f8py_k$() + ', but had ' + getKClassFromExpression(tmp2_0).get_simpleName_r6f8py_k$() + ' as the serialized body of ' + serialName;
          // Inline function 'kotlinx.serialization.json.internal.decodingExceptionOf' call
          var path = this.lexer_1.path_1.getPath_18su3p_k$();
          var tmp_2;
          if (this.get_json_woos35_k$().configuration_1.exceptionsWithDebugInfo_1) {
            var tmp$ret$6 = toString(tmp2_0);
            tmp_2 = toString(minify(tmp$ret$6));
          } else {
            tmp_2 = null;
          }
          var inputValue = tmp_2;
          throw new JsonDecodingException(access$formatDecodingException$tJsonExceptionsKt(-1, tmp2_1, path, null, inputValue), tmp2_1, -1, path, inputValue, null);
        }
        var jsonTree = tmp2_0;
        var tmp0_safe_receiver = jsonTree.get_6bo4tg_k$(discriminator_0);
        var tmp1_safe_receiver = tmp0_safe_receiver == null ? null : get_jsonPrimitive(tmp0_safe_receiver);
        var type = tmp1_safe_receiver == null ? null : get_contentOrNull(tmp1_safe_receiver);
        var tmp_3;
        try {
          tmp_3 = findPolymorphicSerializer(tmp2, this, type);
        } catch ($p) {
          var tmp_4;
          if ($p instanceof SerializationException) {
            var it = $p;
            // Inline function 'kotlinx.serialization.json.internal.decodingExceptionOf' call
            var shortMessage = ensureNotNull(it.message);
            var tmp_5;
            if (this.get_json_woos35_k$().configuration_1.exceptionsWithDebugInfo_1) {
              var tmp$ret$10 = jsonTree.toString();
              tmp_5 = toString(minify(tmp$ret$10));
            } else {
              tmp_5 = null;
            }
            var inputValue_0 = tmp_5;
            throw new JsonDecodingException(access$formatDecodingException$tJsonExceptionsKt(-1, shortMessage, null, null, inputValue_0), shortMessage, -1, null, inputValue_0, null);
          } else {
            throw $p;
          }
        }
        var tmp_6 = tmp_3;
        var actualSerializer = isInterface(tmp_6, DeserializationStrategy) ? tmp_6 : THROW_CCE();
        tmp$ret$0 = readPolymorphicJson(this.get_json_woos35_k$(), discriminator_0, jsonTree, actualSerializer);
      }
      return tmp$ret$0;
    } else {
      tmp_0 = tmp0_elvis_lhs;
    }
    var type_0 = tmp_0;
    var tmp_7;
    try {
      tmp_7 = findPolymorphicSerializer(deserializer, this, type_0);
    } catch ($p_0) {
      var tmp_8;
      if ($p_0 instanceof SerializationException) {
        var it_0 = $p_0;
        var message = removeSuffix(substringBefore(ensureNotNull(it_0.message), _Char___init__impl__6a9atx(10)), '.');
        var hint = substringAfter(ensureNotNull(it_0.message), _Char___init__impl__6a9atx(10), '');
        this.lexer_1.fail$default_ly4hfp_k$(message, VOID, hint);
      } else {
        throw $p_0;
      }
      tmp_7 = tmp_8;
    }
    var tmp_9 = tmp_7;
    var actualSerializer_0 = isInterface(tmp_9, DeserializationStrategy) ? tmp_9 : THROW_CCE();
    this.discriminatorHolder_1 = new DiscriminatorHolder(discriminator);
    return actualSerializer_0.deserialize_sy6x50_k$(this);
  } catch ($p_1) {
    if ($p_1 instanceof MissingFieldException) {
      var e = $p_1;
      if (contains_0(ensureNotNull(e.message), 'at path'))
        throw e;
      throw missingFieldExceptionWithNewMessage(e, plus(e.message, ' at path: ') + this.lexer_1.path_1.getPath_18su3p_k$());
    } else {
      throw $p_1;
    }
  }
};
protoOf(StreamingJsonDecoder).beginStructure_yljocp_k$ = function (descriptor) {
  var newMode = switchMode(this.json_1, descriptor);
  this.lexer_1.path_1.pushDescriptor_ymkfo8_k$(descriptor);
  this.lexer_1.consumeNextToken_kteg6b_k$(newMode.begin_1);
  checkLeadingComma(this);
  var tmp;
  switch (newMode.ordinal_1) {
    case 1:
    case 2:
    case 3:
      tmp = new StreamingJsonDecoder(this.json_1, newMode, this.lexer_1, descriptor, this.discriminatorHolder_1);
      break;
    default:
      var tmp_0;
      if (this.mode_1.equals(newMode) && this.json_1.configuration_1.explicitNulls_1) {
        tmp_0 = this;
      } else {
        tmp_0 = new StreamingJsonDecoder(this.json_1, newMode, this.lexer_1, descriptor, this.discriminatorHolder_1);
      }

      tmp = tmp_0;
      break;
  }
  return tmp;
};
protoOf(StreamingJsonDecoder).endStructure_1xqz0n_k$ = function (descriptor) {
  if (descriptor.get_elementsCount_288r0x_k$() === 0 && ignoreUnknownKeys(descriptor, this.json_1)) {
    skipLeftoverElements(this, descriptor);
  }
  if (this.lexer_1.tryConsumeComma_9n2ve4_k$() && !this.json_1.configuration_1.allowTrailingComma_1) {
    invalidTrailingComma(this.lexer_1, '');
  }
  this.lexer_1.consumeNextToken_kteg6b_k$(this.mode_1.end_1);
  this.lexer_1.path_1.popDescriptor_wfx7tc_k$();
};
protoOf(StreamingJsonDecoder).decodeNotNullMark_us4ba1_k$ = function () {
  var tmp;
  var tmp0_safe_receiver = this.elementMarker_1;
  var tmp1_elvis_lhs = tmp0_safe_receiver == null ? null : tmp0_safe_receiver.isUnmarkedNull_1;
  if (!(tmp1_elvis_lhs == null ? false : tmp1_elvis_lhs)) {
    tmp = !this.lexer_1.tryConsumeNull$default_t5tauc_k$();
  } else {
    tmp = false;
  }
  return tmp;
};
protoOf(StreamingJsonDecoder).decodeNull_jzrmuj_k$ = function () {
  return null;
};
protoOf(StreamingJsonDecoder).decodeSerializableElement_uahnnv_k$ = function (descriptor, index, deserializer, previousValue) {
  var isMapKey = this.mode_1.equals(WriteMode_MAP_getInstance()) && (index & 1) === 0;
  if (isMapKey) {
    this.lexer_1.path_1.resetCurrentMapKey_1l0a77_k$();
  }
  var value = protoOf(AbstractDecoder).decodeSerializableElement_uahnnv_k$.call(this, descriptor, index, deserializer, previousValue);
  if (isMapKey) {
    this.lexer_1.path_1.updateCurrentMapKey_rv46l8_k$(value);
  }
  return value;
};
protoOf(StreamingJsonDecoder).decodeElementIndex_bstkhp_k$ = function (descriptor) {
  var index;
  switch (this.mode_1.ordinal_1) {
    case 0:
      index = decodeObjectIndex(this, descriptor);
      break;
    case 2:
      index = decodeMapIndex(this);
      break;
    default:
      index = decodeListIndex(this);
      break;
  }
  if (!this.mode_1.equals(WriteMode_MAP_getInstance())) {
    this.lexer_1.path_1.updateDescriptorIndex_kw65aq_k$(index);
  }
  return index;
};
protoOf(StreamingJsonDecoder).decodeBoolean_m0aca_k$ = function () {
  return this.lexer_1.consumeBooleanLenient_iqeqb9_k$();
};
protoOf(StreamingJsonDecoder).decodeByte_jzz7je_k$ = function () {
  var value = this.lexer_1.consumeNumericLiteral_rdea66_k$();
  if (!equalsLong(value, fromInt(convertToByte(value)))) {
    this.lexer_1.fail$default_ly4hfp_k$("Failed to parse byte for input '" + value.toString() + "'");
  }
  return convertToByte(value);
};
protoOf(StreamingJsonDecoder).decodeShort_jjqk32_k$ = function () {
  var value = this.lexer_1.consumeNumericLiteral_rdea66_k$();
  if (!equalsLong(value, fromInt(convertToShort(value)))) {
    this.lexer_1.fail$default_ly4hfp_k$("Failed to parse short for input '" + value.toString() + "'");
  }
  return convertToShort(value);
};
protoOf(StreamingJsonDecoder).decodeInt_8iq8f5_k$ = function () {
  var value = this.lexer_1.consumeNumericLiteral_rdea66_k$();
  if (!equalsLong(value, fromInt(convertToInt(value)))) {
    this.lexer_1.fail$default_ly4hfp_k$("Failed to parse int for input '" + value.toString() + "'");
  }
  return convertToInt(value);
};
protoOf(StreamingJsonDecoder).decodeLong_jzt186_k$ = function () {
  return this.lexer_1.consumeNumericLiteral_rdea66_k$();
};
protoOf(StreamingJsonDecoder).decodeFloat_jcnrwu_k$ = function () {
  var tmp0 = this.lexer_1;
  var tmp$ret$0;
  $l$block: {
    // Inline function 'kotlinx.serialization.json.internal.parseString' call
    var input = tmp0.consumeStringLenient_9oypvu_k$();
    try {
      // Inline function 'kotlin.text.toFloat' call
      // Inline function 'kotlin.js.unsafeCast' call
      // Inline function 'kotlin.js.asDynamic' call
      tmp$ret$0 = toDouble(input);
      break $l$block;
    } catch ($p) {
      if ($p instanceof IllegalArgumentException) {
        var e = $p;
        tmp0.fail$default_ly4hfp_k$("Failed to parse type '" + 'float' + "' for input '" + input + "'");
      } else {
        throw $p;
      }
    }
  }
  var result = tmp$ret$0;
  var specialFp = this.json_1.configuration_1.allowSpecialFloatingPointValues_1;
  if (specialFp || isFinite(result))
    return result;
  throwInvalidFloatingPointDecoded(this.lexer_1, result);
};
protoOf(StreamingJsonDecoder).decodeDouble_ur8l0f_k$ = function () {
  var tmp0 = this.lexer_1;
  var tmp$ret$0;
  $l$block: {
    // Inline function 'kotlinx.serialization.json.internal.parseString' call
    var input = tmp0.consumeStringLenient_9oypvu_k$();
    try {
      tmp$ret$0 = toDouble(input);
      break $l$block;
    } catch ($p) {
      if ($p instanceof IllegalArgumentException) {
        var e = $p;
        tmp0.fail$default_ly4hfp_k$("Failed to parse type '" + 'double' + "' for input '" + input + "'");
      } else {
        throw $p;
      }
    }
  }
  var result = tmp$ret$0;
  var specialFp = this.json_1.configuration_1.allowSpecialFloatingPointValues_1;
  if (specialFp || isFinite_0(result))
    return result;
  throwInvalidFloatingPointDecoded(this.lexer_1, result);
};
protoOf(StreamingJsonDecoder).decodeChar_dcmcfa_k$ = function () {
  var string = this.lexer_1.consumeStringLenient_9oypvu_k$();
  if (!(string.length === 1)) {
    this.lexer_1.fail$default_ly4hfp_k$("Expected single char, but got '" + string + "'");
  }
  return charCodeAt(string, 0);
};
protoOf(StreamingJsonDecoder).decodeString_x3hxsx_k$ = function () {
  var tmp;
  if (this.configuration_1.isLenient_1) {
    tmp = this.lexer_1.consumeStringLenientNotNull_m2rgts_k$();
  } else {
    tmp = this.lexer_1.consumeString_j3j2z7_k$();
  }
  return tmp;
};
protoOf(StreamingJsonDecoder).decodeInline_ux3vza_k$ = function (descriptor) {
  return get_isUnsignedNumber(descriptor) ? new JsonDecoderForUnsignedTypes(this.lexer_1, this.json_1) : protoOf(AbstractDecoder).decodeInline_ux3vza_k$.call(this, descriptor);
};
protoOf(StreamingJsonDecoder).decodeEnum_slg6lu_k$ = function (enumDescriptor) {
  return getJsonNameIndexOrThrow(enumDescriptor, this.json_1, this.decodeString_x3hxsx_k$(), ' at path ' + this.lexer_1.path_1.getPath_18su3p_k$());
};
function JsonDecoderForUnsignedTypes(lexer, json) {
  AbstractDecoder.call(this);
  this.lexer_1 = lexer;
  this.serializersModule_1 = json.get_serializersModule_piitvg_k$();
}
protoOf(JsonDecoderForUnsignedTypes).get_serializersModule_piitvg_k$ = function () {
  return this.serializersModule_1;
};
protoOf(JsonDecoderForUnsignedTypes).decodeElementIndex_bstkhp_k$ = function (descriptor) {
  // Inline function 'kotlin.error' call
  var message = 'unsupported';
  throw IllegalStateException_init_$Create$(toString(message));
};
protoOf(JsonDecoderForUnsignedTypes).decodeInt_8iq8f5_k$ = function () {
  var tmp0 = this.lexer_1;
  var tmp$ret$0;
  $l$block: {
    // Inline function 'kotlinx.serialization.json.internal.parseString' call
    var input = tmp0.consumeStringLenient_9oypvu_k$();
    try {
      // Inline function 'kotlin.UInt.toInt' call
      var this_0 = toUInt(input);
      tmp$ret$0 = _UInt___get_data__impl__f0vqqw(this_0);
      break $l$block;
    } catch ($p) {
      if ($p instanceof IllegalArgumentException) {
        var e = $p;
        tmp0.fail$default_ly4hfp_k$("Failed to parse type '" + 'UInt' + "' for input '" + input + "'");
      } else {
        throw $p;
      }
    }
  }
  return tmp$ret$0;
};
protoOf(JsonDecoderForUnsignedTypes).decodeLong_jzt186_k$ = function () {
  var tmp0 = this.lexer_1;
  var tmp$ret$0;
  $l$block: {
    // Inline function 'kotlinx.serialization.json.internal.parseString' call
    var input = tmp0.consumeStringLenient_9oypvu_k$();
    try {
      // Inline function 'kotlin.ULong.toLong' call
      var this_0 = toULong(input);
      tmp$ret$0 = _ULong___get_data__impl__fggpzb(this_0);
      break $l$block;
    } catch ($p) {
      if ($p instanceof IllegalArgumentException) {
        var e = $p;
        tmp0.fail$default_ly4hfp_k$("Failed to parse type '" + 'ULong' + "' for input '" + input + "'");
      } else {
        throw $p;
      }
    }
  }
  return tmp$ret$0;
};
protoOf(JsonDecoderForUnsignedTypes).decodeByte_jzz7je_k$ = function () {
  var tmp0 = this.lexer_1;
  var tmp$ret$0;
  $l$block: {
    // Inline function 'kotlinx.serialization.json.internal.parseString' call
    var input = tmp0.consumeStringLenient_9oypvu_k$();
    try {
      // Inline function 'kotlin.UByte.toByte' call
      var this_0 = toUByte(input);
      tmp$ret$0 = _UByte___get_data__impl__jof9qr(this_0);
      break $l$block;
    } catch ($p) {
      if ($p instanceof IllegalArgumentException) {
        var e = $p;
        tmp0.fail$default_ly4hfp_k$("Failed to parse type '" + 'UByte' + "' for input '" + input + "'");
      } else {
        throw $p;
      }
    }
  }
  return tmp$ret$0;
};
protoOf(JsonDecoderForUnsignedTypes).decodeShort_jjqk32_k$ = function () {
  var tmp0 = this.lexer_1;
  var tmp$ret$0;
  $l$block: {
    // Inline function 'kotlinx.serialization.json.internal.parseString' call
    var input = tmp0.consumeStringLenient_9oypvu_k$();
    try {
      // Inline function 'kotlin.UShort.toShort' call
      var this_0 = toUShort(input);
      tmp$ret$0 = _UShort___get_data__impl__g0245(this_0);
      break $l$block;
    } catch ($p) {
      if ($p instanceof IllegalArgumentException) {
        var e = $p;
        tmp0.fail$default_ly4hfp_k$("Failed to parse type '" + 'UShort' + "' for input '" + input + "'");
      } else {
        throw $p;
      }
    }
  }
  return tmp$ret$0;
};
function get_unsignedNumberDescriptors() {
  _init_properties_StreamingJsonEncoder_kt__pn1bsi();
  return unsignedNumberDescriptors;
}
var unsignedNumberDescriptors;
function StreamingJsonEncoder_init_$Init$(output, json, mode, modeReuseCache, $this) {
  StreamingJsonEncoder.call($this, Composer_0(output, json), json, mode, modeReuseCache);
  return $this;
}
function StreamingJsonEncoder_init_$Create$(output, json, mode, modeReuseCache) {
  return StreamingJsonEncoder_init_$Init$(output, json, mode, modeReuseCache, objectCreate(protoOf(StreamingJsonEncoder)));
}
function encodeTypeInfo($this, discriminator, serialName) {
  $this.composer_1.nextItem_40n9p2_k$();
  $this.encodeString_424b5v_k$(discriminator);
  $this.composer_1.print_49c4ow_k$(_Char___init__impl__6a9atx(58));
  $this.composer_1.space_po67ue_k$();
  $this.encodeString_424b5v_k$(serialName);
}
function StreamingJsonEncoder(composer, json, mode, modeReuseCache) {
  AbstractEncoder.call(this);
  this.composer_1 = composer;
  this.json_1 = json;
  this.mode_1 = mode;
  this.modeReuseCache_1 = modeReuseCache;
  this.serializersModule_1 = this.json_1.get_serializersModule_piitvg_k$();
  this.configuration_1 = this.json_1.configuration_1;
  this.forceQuoting_1 = false;
  this.polymorphicDiscriminator_1 = null;
  this.polymorphicSerialName_1 = null;
  var i = this.mode_1.ordinal_1;
  if (!(this.modeReuseCache_1 == null)) {
    if (!(this.modeReuseCache_1[i] === null) || !(this.modeReuseCache_1[i] === this)) {
      this.modeReuseCache_1[i] = this;
    }
  }
}
protoOf(StreamingJsonEncoder).get_json_woos35_k$ = function () {
  return this.json_1;
};
protoOf(StreamingJsonEncoder).get_serializersModule_piitvg_k$ = function () {
  return this.serializersModule_1;
};
protoOf(StreamingJsonEncoder).shouldEncodeElementDefault_x8eyid_k$ = function (descriptor, index) {
  return this.configuration_1.encodeDefaults_1;
};
protoOf(StreamingJsonEncoder).encodeSerializableValue_3uuzip_k$ = function (serializer, value) {
  $l$block: {
    // Inline function 'kotlinx.serialization.json.internal.encodePolymorphically' call
    if (this.get_json_woos35_k$().configuration_1.useArrayPolymorphism_1) {
      serializer.serialize_5ase3y_k$(this, value);
      break $l$block;
    }
    var isPolymorphicSerializer = serializer instanceof AbstractPolymorphicSerializer;
    var tmp;
    if (isPolymorphicSerializer) {
      tmp = !this.get_json_woos35_k$().configuration_1.classDiscriminatorMode_1.equals(ClassDiscriminatorMode_NONE_getInstance());
    } else {
      var tmp_0;
      switch (this.get_json_woos35_k$().configuration_1.classDiscriminatorMode_1.ordinal_1) {
        case 0:
        case 2:
          tmp_0 = false;
          break;
        case 1:
          // Inline function 'kotlin.let' call

          var it = serializer.get_descriptor_wjt6a0_k$().get_kind_wop7ml_k$();
          tmp_0 = equals(it, CLASS_getInstance()) || equals(it, OBJECT_getInstance());
          break;
        default:
          noWhenBranchMatchedException();
          break;
      }
      tmp = tmp_0;
    }
    var needDiscriminator = tmp;
    var baseClassDiscriminator = needDiscriminator ? classDiscriminator(serializer.get_descriptor_wjt6a0_k$(), this.get_json_woos35_k$()) : null;
    var tmp_1;
    if (isPolymorphicSerializer) {
      var casted = serializer instanceof AbstractPolymorphicSerializer ? serializer : THROW_CCE();
      $l$block_0: {
        // Inline function 'kotlin.requireNotNull' call
        if (value == null) {
          var message = 'Value for serializer ' + toString(serializer.get_descriptor_wjt6a0_k$()) + ' should always be non-null. Please report issue to the kotlinx.serialization tracker.';
          throw IllegalArgumentException_init_$Create$(toString(message));
        } else {
          break $l$block_0;
        }
      }
      var actual = findPolymorphicSerializer_0(casted, this, value);
      tmp_1 = isInterface(actual, SerializationStrategy) ? actual : THROW_CCE();
    } else {
      tmp_1 = serializer;
    }
    var actualSerializer = tmp_1;
    if (!(baseClassDiscriminator == null)) {
      access$checkEncodingConflicts$tPolymorphicKt(this.get_json_woos35_k$(), serializer, actualSerializer, baseClassDiscriminator);
      checkKind_0(actualSerializer.get_descriptor_wjt6a0_k$().get_kind_wop7ml_k$());
      var serialName = actualSerializer.get_descriptor_wjt6a0_k$().get_serialName_u2rqhk_k$();
      this.polymorphicDiscriminator_1 = baseClassDiscriminator;
      this.polymorphicSerialName_1 = serialName;
    }
    actualSerializer.serialize_5ase3y_k$(this, value);
  }
};
protoOf(StreamingJsonEncoder).beginStructure_yljocp_k$ = function (descriptor) {
  var newMode = switchMode(this.json_1, descriptor);
  if (!(newMode.begin_1 === _Char___init__impl__6a9atx(0))) {
    this.composer_1.print_49c4ow_k$(newMode.begin_1);
    this.composer_1.indent_cuntic_k$();
  }
  var discriminator = this.polymorphicDiscriminator_1;
  if (!(discriminator == null)) {
    var tmp0_elvis_lhs = this.polymorphicSerialName_1;
    encodeTypeInfo(this, discriminator, tmp0_elvis_lhs == null ? descriptor.get_serialName_u2rqhk_k$() : tmp0_elvis_lhs);
    this.polymorphicDiscriminator_1 = null;
    this.polymorphicSerialName_1 = null;
  }
  if (this.mode_1.equals(newMode)) {
    return this;
  }
  var tmp1_safe_receiver = this.modeReuseCache_1;
  var tmp2_elvis_lhs = tmp1_safe_receiver == null ? null : tmp1_safe_receiver[newMode.ordinal_1];
  return tmp2_elvis_lhs == null ? new StreamingJsonEncoder(this.composer_1, this.json_1, newMode, this.modeReuseCache_1) : tmp2_elvis_lhs;
};
protoOf(StreamingJsonEncoder).endStructure_1xqz0n_k$ = function (descriptor) {
  if (!(this.mode_1.end_1 === _Char___init__impl__6a9atx(0))) {
    this.composer_1.unIndent_45q4lx_k$();
    this.composer_1.nextItemIfNotFirst_9wb040_k$();
    this.composer_1.print_49c4ow_k$(this.mode_1.end_1);
  }
};
protoOf(StreamingJsonEncoder).encodeElement_5v7eyb_k$ = function (descriptor, index) {
  switch (this.mode_1.ordinal_1) {
    case 1:
      if (!this.composer_1.writingFirst_1) {
        this.composer_1.print_49c4ow_k$(_Char___init__impl__6a9atx(44));
      }

      this.composer_1.nextItem_40n9p2_k$();
      break;
    case 2:
      if (!this.composer_1.writingFirst_1) {
        var tmp = this;
        var tmp_0;
        if ((index % 2 | 0) === 0) {
          this.composer_1.print_49c4ow_k$(_Char___init__impl__6a9atx(44));
          this.composer_1.nextItem_40n9p2_k$();
          tmp_0 = true;
        } else {
          this.composer_1.print_49c4ow_k$(_Char___init__impl__6a9atx(58));
          this.composer_1.space_po67ue_k$();
          tmp_0 = false;
        }
        tmp.forceQuoting_1 = tmp_0;
      } else {
        this.forceQuoting_1 = true;
        this.composer_1.nextItem_40n9p2_k$();
      }

      break;
    case 3:
      if (index === 0)
        this.forceQuoting_1 = true;
      if (index === 1) {
        this.composer_1.print_49c4ow_k$(_Char___init__impl__6a9atx(44));
        this.composer_1.space_po67ue_k$();
        this.forceQuoting_1 = false;
      }

      break;
    default:
      if (!this.composer_1.writingFirst_1) {
        this.composer_1.print_49c4ow_k$(_Char___init__impl__6a9atx(44));
      }

      this.composer_1.nextItem_40n9p2_k$();
      this.encodeString_424b5v_k$(getJsonElementName(descriptor, this.json_1, index));
      this.composer_1.print_49c4ow_k$(_Char___init__impl__6a9atx(58));
      this.composer_1.space_po67ue_k$();
      break;
  }
  return true;
};
protoOf(StreamingJsonEncoder).encodeNullableSerializableElement_5lquiv_k$ = function (descriptor, index, serializer, value) {
  if (!(value == null) || this.configuration_1.explicitNulls_1) {
    protoOf(AbstractEncoder).encodeNullableSerializableElement_5lquiv_k$.call(this, descriptor, index, serializer, value);
  }
};
protoOf(StreamingJsonEncoder).encodeInline_wxp5pu_k$ = function (descriptor) {
  var tmp;
  if (get_isUnsignedNumber(descriptor)) {
    // Inline function 'kotlinx.serialization.json.internal.StreamingJsonEncoder.composerAs' call
    var tmp_0;
    var tmp_1 = this.composer_1;
    if (tmp_1 instanceof ComposerForUnsignedNumbers) {
      tmp_0 = this.composer_1;
    } else {
      var tmp0 = this.composer_1.writer_1;
      var p1 = this.forceQuoting_1;
      tmp_0 = new ComposerForUnsignedNumbers(tmp0, p1);
    }
    var tmp$ret$0 = tmp_0;
    tmp = new StreamingJsonEncoder(tmp$ret$0, this.json_1, this.mode_1, null);
  } else if (get_isUnquotedLiteral(descriptor)) {
    // Inline function 'kotlinx.serialization.json.internal.StreamingJsonEncoder.composerAs' call
    var tmp_2;
    var tmp_3 = this.composer_1;
    if (tmp_3 instanceof ComposerForUnquotedLiterals) {
      tmp_2 = this.composer_1;
    } else {
      var tmp0_0 = this.composer_1.writer_1;
      var p1_0 = this.forceQuoting_1;
      tmp_2 = new ComposerForUnquotedLiterals(tmp0_0, p1_0);
    }
    var tmp$ret$2 = tmp_2;
    tmp = new StreamingJsonEncoder(tmp$ret$2, this.json_1, this.mode_1, null);
  } else if (!(this.polymorphicDiscriminator_1 == null)) {
    // Inline function 'kotlin.apply' call
    this.polymorphicSerialName_1 = descriptor.get_serialName_u2rqhk_k$();
    tmp = this;
  } else {
    tmp = protoOf(AbstractEncoder).encodeInline_wxp5pu_k$.call(this, descriptor);
  }
  return tmp;
};
protoOf(StreamingJsonEncoder).encodeNull_ejiosz_k$ = function () {
  this.composer_1.print_wtfns3_k$('null');
};
protoOf(StreamingJsonEncoder).encodeBoolean_tu2e59_k$ = function (value) {
  if (this.forceQuoting_1) {
    this.encodeString_424b5v_k$(value.toString());
  } else {
    this.composer_1.print_u0bpvs_k$(value);
  }
};
protoOf(StreamingJsonEncoder).encodeByte_6txfee_k$ = function (value) {
  if (this.forceQuoting_1) {
    this.encodeString_424b5v_k$(value.toString());
  } else {
    this.composer_1.print_p65m4b_k$(value);
  }
};
protoOf(StreamingJsonEncoder).encodeShort_gza6si_k$ = function (value) {
  if (this.forceQuoting_1) {
    this.encodeString_424b5v_k$(value.toString());
  } else {
    this.composer_1.print_l5t6fv_k$(value);
  }
};
protoOf(StreamingJsonEncoder).encodeInt_y5zi3z_k$ = function (value) {
  if (this.forceQuoting_1) {
    this.encodeString_424b5v_k$(value.toString());
  } else {
    this.composer_1.print_ay1yo5_k$(value);
  }
};
protoOf(StreamingJsonEncoder).encodeLong_vlw3uq_k$ = function (value) {
  if (this.forceQuoting_1) {
    this.encodeString_424b5v_k$(value.toString());
  } else {
    this.composer_1.print_u23kfb_k$(value);
  }
};
protoOf(StreamingJsonEncoder).encodeFloat_b8b85a_k$ = function (value) {
  if (this.forceQuoting_1) {
    this.encodeString_424b5v_k$(value.toString());
  } else {
    this.composer_1.print_81xt5n_k$(value);
  }
  if (!this.configuration_1.allowSpecialFloatingPointValues_1 && !isFinite(value)) {
    throw InvalidFloatingPointEncoded(value);
  }
};
protoOf(StreamingJsonEncoder).encodeDouble_n270q9_k$ = function (value) {
  if (this.forceQuoting_1) {
    this.encodeString_424b5v_k$(value.toString());
  } else {
    this.composer_1.print_3xddxz_k$(value);
  }
  if (!this.configuration_1.allowSpecialFloatingPointValues_1 && !isFinite_0(value)) {
    throw InvalidFloatingPointEncoded(value);
  }
};
protoOf(StreamingJsonEncoder).encodeChar_id8ngf_k$ = function (value) {
  this.encodeString_424b5v_k$(toString_1(value));
};
protoOf(StreamingJsonEncoder).encodeString_424b5v_k$ = function (value) {
  return this.composer_1.printQuoted_gtxn2t_k$(value);
};
protoOf(StreamingJsonEncoder).encodeEnum_2qin0y_k$ = function (enumDescriptor, index) {
  this.encodeString_424b5v_k$(enumDescriptor.getElementName_u4sqmf_k$(index));
};
function get_isUnsignedNumber(_this__u8e3s4) {
  _init_properties_StreamingJsonEncoder_kt__pn1bsi();
  return _this__u8e3s4.get_isInline_usk17w_k$() && get_unsignedNumberDescriptors().contains_aljjnj_k$(_this__u8e3s4);
}
function get_isUnquotedLiteral(_this__u8e3s4) {
  _init_properties_StreamingJsonEncoder_kt__pn1bsi();
  return _this__u8e3s4.get_isInline_usk17w_k$() && equals(_this__u8e3s4, get_jsonUnquotedLiteralDescriptor());
}
var properties_initialized_StreamingJsonEncoder_kt_6ifwwk;
function _init_properties_StreamingJsonEncoder_kt__pn1bsi() {
  if (!properties_initialized_StreamingJsonEncoder_kt_6ifwwk) {
    properties_initialized_StreamingJsonEncoder_kt_6ifwwk = true;
    unsignedNumberDescriptors = setOf([serializer_1(Companion_getInstance_0()).get_descriptor_wjt6a0_k$(), serializer_0(Companion_getInstance()).get_descriptor_wjt6a0_k$(), serializer_2(Companion_getInstance_1()).get_descriptor_wjt6a0_k$(), serializer_3(Companion_getInstance_2()).get_descriptor_wjt6a0_k$()]);
  }
}
function get_ESCAPE_STRINGS() {
  _init_properties_StringOps_kt__fcy1db();
  return ESCAPE_STRINGS;
}
var ESCAPE_STRINGS;
var ESCAPE_MARKERS;
function toHexChar(i) {
  _init_properties_StringOps_kt__fcy1db();
  var d = i & 15;
  var tmp;
  if (d < 10) {
    // Inline function 'kotlin.code' call
    var this_0 = _Char___init__impl__6a9atx(48);
    var tmp$ret$0 = Char__toInt_impl_vasixd(this_0);
    tmp = numberToChar(d + tmp$ret$0 | 0);
  } else {
    var tmp_0 = d - 10 | 0;
    // Inline function 'kotlin.code' call
    var this_1 = _Char___init__impl__6a9atx(97);
    var tmp$ret$1 = Char__toInt_impl_vasixd(this_1);
    tmp = numberToChar(tmp_0 + tmp$ret$1 | 0);
  }
  return tmp;
}
function printQuoted(_this__u8e3s4, value) {
  _init_properties_StringOps_kt__fcy1db();
  _this__u8e3s4.append_58al37_k$(_Char___init__impl__6a9atx(34));
  var lastPos = 0;
  var inductionVariable = 0;
  var last = charSequenceLength(value) - 1 | 0;
  if (inductionVariable <= last)
    do {
      var i = inductionVariable;
      inductionVariable = inductionVariable + 1 | 0;
      // Inline function 'kotlin.code' call
      var this_0 = charCodeAt(value, i);
      var c = Char__toInt_impl_vasixd(this_0);
      if (c < get_ESCAPE_STRINGS().length && !(get_ESCAPE_STRINGS()[c] == null)) {
        _this__u8e3s4.append_xdc1zw_k$(value, lastPos, i);
        _this__u8e3s4.append_22ad7x_k$(get_ESCAPE_STRINGS()[c]);
        lastPos = i + 1 | 0;
      }
    }
     while (inductionVariable <= last);
  if (!(lastPos === 0))
    _this__u8e3s4.append_xdc1zw_k$(value, lastPos, value.length);
  else
    _this__u8e3s4.append_22ad7x_k$(value);
  _this__u8e3s4.append_58al37_k$(_Char___init__impl__6a9atx(34));
}
function toBooleanStrictOrNull_0(_this__u8e3s4) {
  _init_properties_StringOps_kt__fcy1db();
  return equals_0(_this__u8e3s4, 'true', true) ? true : equals_0(_this__u8e3s4, 'false', true) ? false : null;
}
var properties_initialized_StringOps_kt_wzaea7;
function _init_properties_StringOps_kt__fcy1db() {
  if (!properties_initialized_StringOps_kt_wzaea7) {
    properties_initialized_StringOps_kt_wzaea7 = true;
    // Inline function 'kotlin.arrayOfNulls' call
    // Inline function 'kotlin.apply' call
    var this_0 = Array(93);
    var inductionVariable = 0;
    if (inductionVariable <= 31)
      do {
        var c = inductionVariable;
        inductionVariable = inductionVariable + 1 | 0;
        var c1 = toHexChar(c >> 12);
        var c2 = toHexChar(c >> 8);
        var c3 = toHexChar(c >> 4);
        var c4 = toHexChar(c);
        this_0[c] = '\\u' + toString_1(c1) + toString_1(c2) + toString_1(c3) + toString_1(c4);
      }
       while (inductionVariable <= 31);
    // Inline function 'kotlin.code' call
    var this_1 = _Char___init__impl__6a9atx(34);
    this_0[Char__toInt_impl_vasixd(this_1)] = '\\"';
    // Inline function 'kotlin.code' call
    var this_2 = _Char___init__impl__6a9atx(92);
    this_0[Char__toInt_impl_vasixd(this_2)] = '\\\\';
    // Inline function 'kotlin.code' call
    var this_3 = _Char___init__impl__6a9atx(9);
    this_0[Char__toInt_impl_vasixd(this_3)] = '\\t';
    // Inline function 'kotlin.code' call
    var this_4 = _Char___init__impl__6a9atx(8);
    this_0[Char__toInt_impl_vasixd(this_4)] = '\\b';
    // Inline function 'kotlin.code' call
    var this_5 = _Char___init__impl__6a9atx(10);
    this_0[Char__toInt_impl_vasixd(this_5)] = '\\n';
    // Inline function 'kotlin.code' call
    var this_6 = _Char___init__impl__6a9atx(13);
    this_0[Char__toInt_impl_vasixd(this_6)] = '\\r';
    this_0[12] = '\\f';
    ESCAPE_STRINGS = this_0;
    // Inline function 'kotlin.apply' call
    var this_7 = new Int8Array(93);
    var inductionVariable_0 = 0;
    if (inductionVariable_0 <= 31)
      do {
        var c_0 = inductionVariable_0;
        inductionVariable_0 = inductionVariable_0 + 1 | 0;
        this_7[c_0] = 1;
      }
       while (inductionVariable_0 <= 31);
    // Inline function 'kotlin.code' call
    var this_8 = _Char___init__impl__6a9atx(34);
    var tmp = Char__toInt_impl_vasixd(this_8);
    // Inline function 'kotlin.code' call
    var this_9 = _Char___init__impl__6a9atx(34);
    var tmp$ret$3 = Char__toInt_impl_vasixd(this_9);
    this_7[tmp] = toByte(tmp$ret$3);
    // Inline function 'kotlin.code' call
    var this_10 = _Char___init__impl__6a9atx(92);
    var tmp_0 = Char__toInt_impl_vasixd(this_10);
    // Inline function 'kotlin.code' call
    var this_11 = _Char___init__impl__6a9atx(92);
    var tmp$ret$5 = Char__toInt_impl_vasixd(this_11);
    this_7[tmp_0] = toByte(tmp$ret$5);
    // Inline function 'kotlin.code' call
    var this_12 = _Char___init__impl__6a9atx(9);
    var tmp_1 = Char__toInt_impl_vasixd(this_12);
    // Inline function 'kotlin.code' call
    var this_13 = _Char___init__impl__6a9atx(116);
    var tmp$ret$7 = Char__toInt_impl_vasixd(this_13);
    this_7[tmp_1] = toByte(tmp$ret$7);
    // Inline function 'kotlin.code' call
    var this_14 = _Char___init__impl__6a9atx(8);
    var tmp_2 = Char__toInt_impl_vasixd(this_14);
    // Inline function 'kotlin.code' call
    var this_15 = _Char___init__impl__6a9atx(98);
    var tmp$ret$9 = Char__toInt_impl_vasixd(this_15);
    this_7[tmp_2] = toByte(tmp$ret$9);
    // Inline function 'kotlin.code' call
    var this_16 = _Char___init__impl__6a9atx(10);
    var tmp_3 = Char__toInt_impl_vasixd(this_16);
    // Inline function 'kotlin.code' call
    var this_17 = _Char___init__impl__6a9atx(110);
    var tmp$ret$11 = Char__toInt_impl_vasixd(this_17);
    this_7[tmp_3] = toByte(tmp$ret$11);
    // Inline function 'kotlin.code' call
    var this_18 = _Char___init__impl__6a9atx(13);
    var tmp_4 = Char__toInt_impl_vasixd(this_18);
    // Inline function 'kotlin.code' call
    var this_19 = _Char___init__impl__6a9atx(114);
    var tmp$ret$13 = Char__toInt_impl_vasixd(this_19);
    this_7[tmp_4] = toByte(tmp$ret$13);
    // Inline function 'kotlin.code' call
    var this_20 = _Char___init__impl__6a9atx(102);
    var tmp$ret$14 = Char__toInt_impl_vasixd(this_20);
    this_7[12] = toByte(tmp$ret$14);
    ESCAPE_MARKERS = this_7;
  }
}
function readJson(json, element, deserializer) {
  var tmp;
  if (element instanceof JsonObject) {
    tmp = new JsonTreeDecoder(json, element);
  } else {
    if (element instanceof JsonArray) {
      tmp = new JsonTreeListDecoder(json, element);
    } else {
      var tmp_0;
      if (element instanceof JsonLiteral) {
        tmp_0 = true;
      } else {
        tmp_0 = equals(element, JsonNull_getInstance());
      }
      if (tmp_0) {
        tmp = new JsonPrimitiveDecoder(json, element instanceof JsonPrimitive ? element : THROW_CCE());
      } else {
        noWhenBranchMatchedException();
      }
    }
  }
  var input = tmp;
  return input.decodeSerializableValue_xpnpad_k$(deserializer);
}
function readPolymorphicJson(_this__u8e3s4, discriminator, element, deserializer) {
  return (new JsonTreeDecoder(_this__u8e3s4, element, discriminator, deserializer.get_descriptor_wjt6a0_k$())).decodeSerializableValue_xpnpad_k$(deserializer);
}
function unparsedPrimitive($this, literal, primitive, tag) {
  var type = startsWith(primitive, 'i') ? 'an ' + primitive : 'a ' + primitive;
  var tmp2 = "Failed to parse literal '" + literal.toString() + "' as " + type + ' value';
  // Inline function 'kotlinx.serialization.json.internal.decodingExceptionOf' call
  var path = $this.renderTagStack_ixxubu_k$(tag);
  var tmp;
  if ($this.get_json_woos35_k$().configuration_1.exceptionsWithDebugInfo_1) {
    var tmp$ret$3 = toString($this.currentObject_yxe2oo_k$());
    tmp = toString(minify(tmp$ret$3));
  } else {
    tmp = null;
  }
  var inputValue = tmp;
  throw new JsonDecodingException(access$formatDecodingException$tJsonExceptionsKt(-1, tmp2, path, null, inputValue), tmp2, -1, path, inputValue, null);
}
function AbstractJsonTreeDecoder(json, value, polymorphicDiscriminator) {
  polymorphicDiscriminator = polymorphicDiscriminator === VOID ? null : polymorphicDiscriminator;
  NamedValueDecoder.call(this);
  this.json_1 = json;
  this.value_1 = value;
  this.polymorphicDiscriminator_1 = polymorphicDiscriminator;
  this.configuration_1 = this.get_json_woos35_k$().configuration_1;
}
protoOf(AbstractJsonTreeDecoder).get_json_woos35_k$ = function () {
  return this.json_1;
};
protoOf(AbstractJsonTreeDecoder).get_value_j01efc_k$ = function () {
  return this.value_1;
};
protoOf(AbstractJsonTreeDecoder).get_serializersModule_piitvg_k$ = function () {
  return this.get_json_woos35_k$().get_serializersModule_piitvg_k$();
};
protoOf(AbstractJsonTreeDecoder).currentObject_yxe2oo_k$ = function () {
  var tmp0_safe_receiver = this.get_currentTagOrNull_yhyzw_k$();
  var tmp;
  if (tmp0_safe_receiver == null) {
    tmp = null;
  } else {
    // Inline function 'kotlin.let' call
    tmp = this.currentElement_4dg47r_k$(tmp0_safe_receiver);
  }
  var tmp1_elvis_lhs = tmp;
  return tmp1_elvis_lhs == null ? this.get_value_j01efc_k$() : tmp1_elvis_lhs;
};
protoOf(AbstractJsonTreeDecoder).renderTagStack_ixxubu_k$ = function (currentTag) {
  return this.renderTagStack_rja478_k$() + ('.' + currentTag);
};
protoOf(AbstractJsonTreeDecoder).decodeJsonElement_6lz9ye_k$ = function () {
  return this.currentObject_yxe2oo_k$();
};
protoOf(AbstractJsonTreeDecoder).decodeSerializableValue_xpnpad_k$ = function (deserializer) {
  var tmp$ret$0;
  $l$block: {
    // Inline function 'kotlinx.serialization.json.internal.decodeSerializableValuePolymorphic' call
    var tmp;
    if (!(deserializer instanceof AbstractPolymorphicSerializer)) {
      tmp = true;
    } else {
      tmp = this.get_json_woos35_k$().configuration_1.useArrayPolymorphism_1;
    }
    if (tmp) {
      tmp$ret$0 = deserializer.deserialize_sy6x50_k$(this);
      break $l$block;
    }
    var discriminator = classDiscriminator(deserializer.get_descriptor_wjt6a0_k$(), this.get_json_woos35_k$());
    var tmp2 = this.decodeJsonElement_6lz9ye_k$();
    // Inline function 'kotlinx.serialization.json.internal.cast' call
    var serialName = deserializer.get_descriptor_wjt6a0_k$().get_serialName_u2rqhk_k$();
    if (!(tmp2 instanceof JsonObject)) {
      var tmp2_0 = 'Expected ' + getKClass(JsonObject).get_simpleName_r6f8py_k$() + ', but had ' + getKClassFromExpression(tmp2).get_simpleName_r6f8py_k$() + ' as the serialized body of ' + serialName;
      // Inline function 'kotlinx.serialization.json.internal.decodingExceptionOf' call
      var path = this.renderTagStack_rja478_k$();
      var tmp_0;
      if (this.get_json_woos35_k$().configuration_1.exceptionsWithDebugInfo_1) {
        var tmp$ret$6 = toString(tmp2);
        tmp_0 = toString(minify(tmp$ret$6));
      } else {
        tmp_0 = null;
      }
      var inputValue = tmp_0;
      throw new JsonDecodingException(access$formatDecodingException$tJsonExceptionsKt(-1, tmp2_0, path, null, inputValue), tmp2_0, -1, path, inputValue, null);
    }
    var jsonTree = tmp2;
    var tmp0_safe_receiver = jsonTree.get_6bo4tg_k$(discriminator);
    var tmp1_safe_receiver = tmp0_safe_receiver == null ? null : get_jsonPrimitive(tmp0_safe_receiver);
    var type = tmp1_safe_receiver == null ? null : get_contentOrNull(tmp1_safe_receiver);
    var tmp_1;
    try {
      tmp_1 = findPolymorphicSerializer(deserializer, this, type);
    } catch ($p) {
      var tmp_2;
      if ($p instanceof SerializationException) {
        var it = $p;
        // Inline function 'kotlinx.serialization.json.internal.decodingExceptionOf' call
        var shortMessage = ensureNotNull(it.message);
        var tmp_3;
        if (this.get_json_woos35_k$().configuration_1.exceptionsWithDebugInfo_1) {
          var tmp$ret$10 = jsonTree.toString();
          tmp_3 = toString(minify(tmp$ret$10));
        } else {
          tmp_3 = null;
        }
        var inputValue_0 = tmp_3;
        throw new JsonDecodingException(access$formatDecodingException$tJsonExceptionsKt(-1, shortMessage, null, null, inputValue_0), shortMessage, -1, null, inputValue_0, null);
      } else {
        throw $p;
      }
    }
    var tmp_4 = tmp_1;
    var actualSerializer = isInterface(tmp_4, DeserializationStrategy) ? tmp_4 : THROW_CCE();
    tmp$ret$0 = readPolymorphicJson(this.get_json_woos35_k$(), discriminator, jsonTree, actualSerializer);
  }
  return tmp$ret$0;
};
protoOf(AbstractJsonTreeDecoder).composeName_8y2y4d_k$ = function (parentName, childName) {
  return childName;
};
protoOf(AbstractJsonTreeDecoder).beginStructure_yljocp_k$ = function (descriptor) {
  var currentObject = this.currentObject_yxe2oo_k$();
  var tmp0_subject = descriptor.get_kind_wop7ml_k$();
  var tmp;
  var tmp_0;
  if (equals(tmp0_subject, LIST_getInstance())) {
    tmp_0 = true;
  } else {
    tmp_0 = tmp0_subject instanceof PolymorphicKind;
  }
  if (tmp_0) {
    var tmp_1 = this.get_json_woos35_k$();
    // Inline function 'kotlinx.serialization.json.internal.AbstractJsonTreeDecoder.cast' call
    // Inline function 'kotlinx.serialization.json.internal.cast' call
    var serialName = descriptor.get_serialName_u2rqhk_k$();
    if (!(currentObject instanceof JsonArray)) {
      var tmp2 = 'Expected ' + getKClass(JsonArray).get_simpleName_r6f8py_k$() + ', but had ' + getKClassFromExpression(currentObject).get_simpleName_r6f8py_k$() + ' as the serialized body of ' + serialName;
      // Inline function 'kotlinx.serialization.json.internal.decodingExceptionOf' call
      var path = this.renderTagStack_rja478_k$();
      var tmp_2;
      if (this.get_json_woos35_k$().configuration_1.exceptionsWithDebugInfo_1) {
        var tmp$ret$6 = toString(currentObject);
        tmp_2 = toString(minify(tmp$ret$6));
      } else {
        tmp_2 = null;
      }
      var inputValue = tmp_2;
      throw new JsonDecodingException(access$formatDecodingException$tJsonExceptionsKt(-1, tmp2, path, null, inputValue), tmp2, -1, path, inputValue, null);
    }
    tmp = new JsonTreeListDecoder(tmp_1, currentObject);
  } else {
    if (equals(tmp0_subject, MAP_getInstance())) {
      // Inline function 'kotlinx.serialization.json.internal.selectMapMode' call
      var this_0 = this.get_json_woos35_k$();
      var keyDescriptor = carrierDescriptor(descriptor.getElementDescriptor_ncda77_k$(0), this_0.get_serializersModule_piitvg_k$());
      var keyKind = keyDescriptor.get_kind_wop7ml_k$();
      var tmp_3;
      var tmp_4;
      if (keyKind instanceof PrimitiveKind) {
        tmp_4 = true;
      } else {
        tmp_4 = equals(keyKind, ENUM_getInstance());
      }
      if (tmp_4) {
        var tmp_5 = this.get_json_woos35_k$();
        // Inline function 'kotlinx.serialization.json.internal.AbstractJsonTreeDecoder.cast' call
        // Inline function 'kotlinx.serialization.json.internal.cast' call
        var serialName_0 = descriptor.get_serialName_u2rqhk_k$();
        if (!(currentObject instanceof JsonObject)) {
          var tmp2_0 = 'Expected ' + getKClass(JsonObject).get_simpleName_r6f8py_k$() + ', but had ' + getKClassFromExpression(currentObject).get_simpleName_r6f8py_k$() + ' as the serialized body of ' + serialName_0;
          // Inline function 'kotlinx.serialization.json.internal.decodingExceptionOf' call
          var path_0 = this.renderTagStack_rja478_k$();
          var tmp_6;
          if (this.get_json_woos35_k$().configuration_1.exceptionsWithDebugInfo_1) {
            var tmp$ret$15 = toString(currentObject);
            tmp_6 = toString(minify(tmp$ret$15));
          } else {
            tmp_6 = null;
          }
          var inputValue_0 = tmp_6;
          throw new JsonDecodingException(access$formatDecodingException$tJsonExceptionsKt(-1, tmp2_0, path_0, null, inputValue_0), tmp2_0, -1, path_0, inputValue_0, null);
        }
        tmp_3 = new JsonTreeMapDecoder(tmp_5, currentObject);
      } else {
        if (this_0.configuration_1.allowStructuredMapKeys_1) {
          var tmp_7 = this.get_json_woos35_k$();
          // Inline function 'kotlinx.serialization.json.internal.AbstractJsonTreeDecoder.cast' call
          // Inline function 'kotlinx.serialization.json.internal.cast' call
          var serialName_1 = descriptor.get_serialName_u2rqhk_k$();
          if (!(currentObject instanceof JsonArray)) {
            var tmp2_1 = 'Expected ' + getKClass(JsonArray).get_simpleName_r6f8py_k$() + ', but had ' + getKClassFromExpression(currentObject).get_simpleName_r6f8py_k$() + ' as the serialized body of ' + serialName_1;
            // Inline function 'kotlinx.serialization.json.internal.decodingExceptionOf' call
            var path_1 = this.renderTagStack_rja478_k$();
            var tmp_8;
            if (this.get_json_woos35_k$().configuration_1.exceptionsWithDebugInfo_1) {
              var tmp$ret$23 = toString(currentObject);
              tmp_8 = toString(minify(tmp$ret$23));
            } else {
              tmp_8 = null;
            }
            var inputValue_1 = tmp_8;
            throw new JsonDecodingException(access$formatDecodingException$tJsonExceptionsKt(-1, tmp2_1, path_1, null, inputValue_1), tmp2_1, -1, path_1, inputValue_1, null);
          }
          tmp_3 = new JsonTreeListDecoder(tmp_7, currentObject);
        } else {
          throw InvalidKeyKindException(keyDescriptor);
        }
      }
      tmp = tmp_3;
    } else {
      var tmp_9 = this.get_json_woos35_k$();
      // Inline function 'kotlinx.serialization.json.internal.AbstractJsonTreeDecoder.cast' call
      // Inline function 'kotlinx.serialization.json.internal.cast' call
      var serialName_2 = descriptor.get_serialName_u2rqhk_k$();
      if (!(currentObject instanceof JsonObject)) {
        var tmp2_2 = 'Expected ' + getKClass(JsonObject).get_simpleName_r6f8py_k$() + ', but had ' + getKClassFromExpression(currentObject).get_simpleName_r6f8py_k$() + ' as the serialized body of ' + serialName_2;
        // Inline function 'kotlinx.serialization.json.internal.decodingExceptionOf' call
        var path_2 = this.renderTagStack_rja478_k$();
        var tmp_10;
        if (this.get_json_woos35_k$().configuration_1.exceptionsWithDebugInfo_1) {
          var tmp$ret$30 = toString(currentObject);
          tmp_10 = toString(minify(tmp$ret$30));
        } else {
          tmp_10 = null;
        }
        var inputValue_2 = tmp_10;
        throw new JsonDecodingException(access$formatDecodingException$tJsonExceptionsKt(-1, tmp2_2, path_2, null, inputValue_2), tmp2_2, -1, path_2, inputValue_2, null);
      }
      tmp = new JsonTreeDecoder(tmp_9, currentObject, this.polymorphicDiscriminator_1);
    }
  }
  return tmp;
};
protoOf(AbstractJsonTreeDecoder).endStructure_1xqz0n_k$ = function (descriptor) {
};
protoOf(AbstractJsonTreeDecoder).decodeNotNullMark_us4ba1_k$ = function () {
  var tmp = this.currentObject_yxe2oo_k$();
  return !(tmp instanceof JsonNull);
};
protoOf(AbstractJsonTreeDecoder).decodeTaggedEnum_lbnta6_k$ = function (tag, enumDescriptor) {
  var tmp = this.get_json_woos35_k$();
  // Inline function 'kotlinx.serialization.json.internal.AbstractJsonTreeDecoder.getPrimitiveValue' call
  var tmp2 = this.currentElement_4dg47r_k$(tag);
  // Inline function 'kotlinx.serialization.json.internal.AbstractJsonTreeDecoder.cast' call
  // Inline function 'kotlinx.serialization.json.internal.cast' call
  var serialName = enumDescriptor.get_serialName_u2rqhk_k$();
  if (!(tmp2 instanceof JsonPrimitive)) {
    var tmp2_0 = 'Expected ' + getKClass(JsonPrimitive).get_simpleName_r6f8py_k$() + ', but had ' + getKClassFromExpression(tmp2).get_simpleName_r6f8py_k$() + ' as the serialized body of ' + serialName;
    // Inline function 'kotlinx.serialization.json.internal.decodingExceptionOf' call
    var path = this.renderTagStack_ixxubu_k$(tag);
    var tmp_0;
    if (this.get_json_woos35_k$().configuration_1.exceptionsWithDebugInfo_1) {
      var tmp$ret$7 = toString(tmp2);
      tmp_0 = toString(minify(tmp$ret$7));
    } else {
      tmp_0 = null;
    }
    var inputValue = tmp_0;
    throw new JsonDecodingException(access$formatDecodingException$tJsonExceptionsKt(-1, tmp2_0, path, null, inputValue), tmp2_0, -1, path, inputValue, null);
  }
  return getJsonNameIndexOrThrow(enumDescriptor, tmp, tmp2.get_content_h02jrk_k$());
};
protoOf(AbstractJsonTreeDecoder).decodeTaggedEnum_iouca9_k$ = function (tag, enumDescriptor) {
  return this.decodeTaggedEnum_lbnta6_k$((!(tag == null) ? typeof tag === 'string' : false) ? tag : THROW_CCE(), enumDescriptor);
};
protoOf(AbstractJsonTreeDecoder).decodeTaggedNotNullMark_t788xf_k$ = function (tag) {
  return !(this.currentElement_4dg47r_k$(tag) === JsonNull_getInstance());
};
protoOf(AbstractJsonTreeDecoder).decodeTaggedNotNullMark_opj0f8_k$ = function (tag) {
  return this.decodeTaggedNotNullMark_t788xf_k$((!(tag == null) ? typeof tag === 'string' : false) ? tag : THROW_CCE());
};
protoOf(AbstractJsonTreeDecoder).decodeTaggedBoolean_8s5b84_k$ = function (tag) {
  var tmp$ret$0;
  $l$block: {
    // Inline function 'kotlinx.serialization.json.internal.AbstractJsonTreeDecoder.getPrimitiveValue' call
    // Inline function 'kotlinx.serialization.json.internal.AbstractJsonTreeDecoder.cast' call
    // Inline function 'kotlinx.serialization.json.internal.cast' call
    var value = this.currentElement_4dg47r_k$(tag);
    if (!(value instanceof JsonPrimitive)) {
      var tmp2 = 'Expected ' + getKClass(JsonPrimitive).get_simpleName_r6f8py_k$() + ', but had ' + getKClassFromExpression(value).get_simpleName_r6f8py_k$() + ' as the serialized body of ' + 'boolean';
      // Inline function 'kotlinx.serialization.json.internal.decodingExceptionOf' call
      var path = this.renderTagStack_ixxubu_k$(tag);
      var tmp;
      if (this.get_json_woos35_k$().configuration_1.exceptionsWithDebugInfo_1) {
        var tmp$ret$7 = toString(value);
        tmp = toString(minify(tmp$ret$7));
      } else {
        tmp = null;
      }
      var inputValue = tmp;
      throw new JsonDecodingException(access$formatDecodingException$tJsonExceptionsKt(-1, tmp2, path, null, inputValue), tmp2, -1, path, inputValue, null);
    }
    var literal = value;
    try {
      var tmp0_elvis_lhs = get_booleanOrNull(literal);
      var tmp_0;
      if (tmp0_elvis_lhs == null) {
        unparsedPrimitive(this, literal, 'boolean', tag);
      } else {
        tmp_0 = tmp0_elvis_lhs;
      }
      tmp$ret$0 = tmp_0;
      break $l$block;
    } catch ($p) {
      if ($p instanceof IllegalArgumentException) {
        var e = $p;
        unparsedPrimitive(this, literal, 'boolean', tag);
      } else {
        throw $p;
      }
    }
  }
  return tmp$ret$0;
};
protoOf(AbstractJsonTreeDecoder).decodeTaggedBoolean_172rbv_k$ = function (tag) {
  return this.decodeTaggedBoolean_8s5b84_k$((!(tag == null) ? typeof tag === 'string' : false) ? tag : THROW_CCE());
};
protoOf(AbstractJsonTreeDecoder).decodeTaggedByte_oje7fc_k$ = function (tag) {
  var tmp$ret$0;
  $l$block: {
    // Inline function 'kotlinx.serialization.json.internal.AbstractJsonTreeDecoder.getPrimitiveValue' call
    // Inline function 'kotlinx.serialization.json.internal.AbstractJsonTreeDecoder.cast' call
    // Inline function 'kotlinx.serialization.json.internal.cast' call
    var value = this.currentElement_4dg47r_k$(tag);
    if (!(value instanceof JsonPrimitive)) {
      var tmp2 = 'Expected ' + getKClass(JsonPrimitive).get_simpleName_r6f8py_k$() + ', but had ' + getKClassFromExpression(value).get_simpleName_r6f8py_k$() + ' as the serialized body of ' + 'byte';
      // Inline function 'kotlinx.serialization.json.internal.decodingExceptionOf' call
      var path = this.renderTagStack_ixxubu_k$(tag);
      var tmp;
      if (this.get_json_woos35_k$().configuration_1.exceptionsWithDebugInfo_1) {
        var tmp$ret$7 = toString(value);
        tmp = toString(minify(tmp$ret$7));
      } else {
        tmp = null;
      }
      var inputValue = tmp;
      throw new JsonDecodingException(access$formatDecodingException$tJsonExceptionsKt(-1, tmp2, path, null, inputValue), tmp2, -1, path, inputValue, null);
    }
    var literal = value;
    try {
      var result = parseLongImpl(literal);
      var tmp_0;
      // Inline function 'kotlin.ranges.contains' call
      var this_0 = numberRangeToNumber(-128, 127);
      if (contains(isInterface(this_0, ClosedRange) ? this_0 : THROW_CCE(), result)) {
        tmp_0 = convertToByte(result);
      } else {
        tmp_0 = null;
      }
      var tmp0_elvis_lhs = tmp_0;
      var tmp_1;
      if (tmp0_elvis_lhs == null) {
        unparsedPrimitive(this, literal, 'byte', tag);
      } else {
        tmp_1 = tmp0_elvis_lhs;
      }
      tmp$ret$0 = tmp_1;
      break $l$block;
    } catch ($p) {
      if ($p instanceof IllegalArgumentException) {
        var e = $p;
        unparsedPrimitive(this, literal, 'byte', tag);
      } else {
        throw $p;
      }
    }
  }
  return tmp$ret$0;
};
protoOf(AbstractJsonTreeDecoder).decodeTaggedByte_y3phtl_k$ = function (tag) {
  return this.decodeTaggedByte_oje7fc_k$((!(tag == null) ? typeof tag === 'string' : false) ? tag : THROW_CCE());
};
protoOf(AbstractJsonTreeDecoder).decodeTaggedShort_b0y92g_k$ = function (tag) {
  var tmp$ret$0;
  $l$block: {
    // Inline function 'kotlinx.serialization.json.internal.AbstractJsonTreeDecoder.getPrimitiveValue' call
    // Inline function 'kotlinx.serialization.json.internal.AbstractJsonTreeDecoder.cast' call
    // Inline function 'kotlinx.serialization.json.internal.cast' call
    var value = this.currentElement_4dg47r_k$(tag);
    if (!(value instanceof JsonPrimitive)) {
      var tmp2 = 'Expected ' + getKClass(JsonPrimitive).get_simpleName_r6f8py_k$() + ', but had ' + getKClassFromExpression(value).get_simpleName_r6f8py_k$() + ' as the serialized body of ' + 'short';
      // Inline function 'kotlinx.serialization.json.internal.decodingExceptionOf' call
      var path = this.renderTagStack_ixxubu_k$(tag);
      var tmp;
      if (this.get_json_woos35_k$().configuration_1.exceptionsWithDebugInfo_1) {
        var tmp$ret$7 = toString(value);
        tmp = toString(minify(tmp$ret$7));
      } else {
        tmp = null;
      }
      var inputValue = tmp;
      throw new JsonDecodingException(access$formatDecodingException$tJsonExceptionsKt(-1, tmp2, path, null, inputValue), tmp2, -1, path, inputValue, null);
    }
    var literal = value;
    try {
      var result = parseLongImpl(literal);
      var tmp_0;
      // Inline function 'kotlin.ranges.contains' call
      var this_0 = numberRangeToNumber(-32768, 32767);
      if (contains(isInterface(this_0, ClosedRange) ? this_0 : THROW_CCE(), result)) {
        tmp_0 = convertToShort(result);
      } else {
        tmp_0 = null;
      }
      var tmp0_elvis_lhs = tmp_0;
      var tmp_1;
      if (tmp0_elvis_lhs == null) {
        unparsedPrimitive(this, literal, 'short', tag);
      } else {
        tmp_1 = tmp0_elvis_lhs;
      }
      tmp$ret$0 = tmp_1;
      break $l$block;
    } catch ($p) {
      if ($p instanceof IllegalArgumentException) {
        var e = $p;
        unparsedPrimitive(this, literal, 'short', tag);
      } else {
        throw $p;
      }
    }
  }
  return tmp$ret$0;
};
protoOf(AbstractJsonTreeDecoder).decodeTaggedShort_dapzw9_k$ = function (tag) {
  return this.decodeTaggedShort_b0y92g_k$((!(tag == null) ? typeof tag === 'string' : false) ? tag : THROW_CCE());
};
protoOf(AbstractJsonTreeDecoder).decodeTaggedInt_9hzwhn_k$ = function (tag) {
  var tmp$ret$0;
  $l$block: {
    // Inline function 'kotlinx.serialization.json.internal.AbstractJsonTreeDecoder.getPrimitiveValue' call
    // Inline function 'kotlinx.serialization.json.internal.AbstractJsonTreeDecoder.cast' call
    // Inline function 'kotlinx.serialization.json.internal.cast' call
    var value = this.currentElement_4dg47r_k$(tag);
    if (!(value instanceof JsonPrimitive)) {
      var tmp2 = 'Expected ' + getKClass(JsonPrimitive).get_simpleName_r6f8py_k$() + ', but had ' + getKClassFromExpression(value).get_simpleName_r6f8py_k$() + ' as the serialized body of ' + 'int';
      // Inline function 'kotlinx.serialization.json.internal.decodingExceptionOf' call
      var path = this.renderTagStack_ixxubu_k$(tag);
      var tmp;
      if (this.get_json_woos35_k$().configuration_1.exceptionsWithDebugInfo_1) {
        var tmp$ret$7 = toString(value);
        tmp = toString(minify(tmp$ret$7));
      } else {
        tmp = null;
      }
      var inputValue = tmp;
      throw new JsonDecodingException(access$formatDecodingException$tJsonExceptionsKt(-1, tmp2, path, null, inputValue), tmp2, -1, path, inputValue, null);
    }
    var literal = value;
    try {
      var result = parseLongImpl(literal);
      var tmp_0;
      // Inline function 'kotlin.ranges.contains' call
      var this_0 = numberRangeToNumber(-2147483648, 2147483647);
      if (contains(isInterface(this_0, ClosedRange) ? this_0 : THROW_CCE(), result)) {
        tmp_0 = convertToInt(result);
      } else {
        tmp_0 = null;
      }
      var tmp0_elvis_lhs = tmp_0;
      var tmp_1;
      if (tmp0_elvis_lhs == null) {
        unparsedPrimitive(this, literal, 'int', tag);
      } else {
        tmp_1 = tmp0_elvis_lhs;
      }
      tmp$ret$0 = tmp_1;
      break $l$block;
    } catch ($p) {
      if ($p instanceof IllegalArgumentException) {
        var e = $p;
        unparsedPrimitive(this, literal, 'int', tag);
      } else {
        throw $p;
      }
    }
  }
  return tmp$ret$0;
};
protoOf(AbstractJsonTreeDecoder).decodeTaggedInt_mhypkc_k$ = function (tag) {
  return this.decodeTaggedInt_9hzwhn_k$((!(tag == null) ? typeof tag === 'string' : false) ? tag : THROW_CCE());
};
protoOf(AbstractJsonTreeDecoder).decodeTaggedLong_uzg5b0_k$ = function (tag) {
  var tmp$ret$0;
  $l$block: {
    // Inline function 'kotlinx.serialization.json.internal.AbstractJsonTreeDecoder.getPrimitiveValue' call
    // Inline function 'kotlinx.serialization.json.internal.AbstractJsonTreeDecoder.cast' call
    // Inline function 'kotlinx.serialization.json.internal.cast' call
    var value = this.currentElement_4dg47r_k$(tag);
    if (!(value instanceof JsonPrimitive)) {
      var tmp2 = 'Expected ' + getKClass(JsonPrimitive).get_simpleName_r6f8py_k$() + ', but had ' + getKClassFromExpression(value).get_simpleName_r6f8py_k$() + ' as the serialized body of ' + 'long';
      // Inline function 'kotlinx.serialization.json.internal.decodingExceptionOf' call
      var path = this.renderTagStack_ixxubu_k$(tag);
      var tmp;
      if (this.get_json_woos35_k$().configuration_1.exceptionsWithDebugInfo_1) {
        var tmp$ret$7 = toString(value);
        tmp = toString(minify(tmp$ret$7));
      } else {
        tmp = null;
      }
      var inputValue = tmp;
      throw new JsonDecodingException(access$formatDecodingException$tJsonExceptionsKt(-1, tmp2, path, null, inputValue), tmp2, -1, path, inputValue, null);
    }
    var literal = value;
    try {
      var tmp0_elvis_lhs = parseLongImpl(literal);
      var tmp_0;
      if (tmp0_elvis_lhs == null) {
        unparsedPrimitive(this, literal, 'long', tag);
      } else {
        tmp_0 = tmp0_elvis_lhs;
      }
      tmp$ret$0 = tmp_0;
      break $l$block;
    } catch ($p) {
      if ($p instanceof IllegalArgumentException) {
        var e = $p;
        unparsedPrimitive(this, literal, 'long', tag);
      } else {
        throw $p;
      }
    }
  }
  return tmp$ret$0;
};
protoOf(AbstractJsonTreeDecoder).decodeTaggedLong_y9rqqb_k$ = function (tag) {
  return this.decodeTaggedLong_uzg5b0_k$((!(tag == null) ? typeof tag === 'string' : false) ? tag : THROW_CCE());
};
protoOf(AbstractJsonTreeDecoder).decodeTaggedFloat_hjyf60_k$ = function (tag) {
  var tmp$ret$0;
  $l$block: {
    // Inline function 'kotlinx.serialization.json.internal.AbstractJsonTreeDecoder.getPrimitiveValue' call
    // Inline function 'kotlinx.serialization.json.internal.AbstractJsonTreeDecoder.cast' call
    // Inline function 'kotlinx.serialization.json.internal.cast' call
    var value = this.currentElement_4dg47r_k$(tag);
    if (!(value instanceof JsonPrimitive)) {
      var tmp2 = 'Expected ' + getKClass(JsonPrimitive).get_simpleName_r6f8py_k$() + ', but had ' + getKClassFromExpression(value).get_simpleName_r6f8py_k$() + ' as the serialized body of ' + 'float';
      // Inline function 'kotlinx.serialization.json.internal.decodingExceptionOf' call
      var path = this.renderTagStack_ixxubu_k$(tag);
      var tmp;
      if (this.get_json_woos35_k$().configuration_1.exceptionsWithDebugInfo_1) {
        var tmp$ret$7 = toString(value);
        tmp = toString(minify(tmp$ret$7));
      } else {
        tmp = null;
      }
      var inputValue = tmp;
      throw new JsonDecodingException(access$formatDecodingException$tJsonExceptionsKt(-1, tmp2, path, null, inputValue), tmp2, -1, path, inputValue, null);
    }
    var literal = value;
    try {
      var tmp0_elvis_lhs = get_float(literal);
      var tmp_0;
      if (tmp0_elvis_lhs == null) {
        unparsedPrimitive(this, literal, 'float', tag);
      } else {
        tmp_0 = tmp0_elvis_lhs;
      }
      tmp$ret$0 = tmp_0;
      break $l$block;
    } catch ($p) {
      if ($p instanceof IllegalArgumentException) {
        var e = $p;
        unparsedPrimitive(this, literal, 'float', tag);
      } else {
        throw $p;
      }
    }
  }
  var result = tmp$ret$0;
  var specialFp = this.get_json_woos35_k$().configuration_1.allowSpecialFloatingPointValues_1;
  if (specialFp || isFinite(result))
    return result;
  // Inline function 'kotlinx.serialization.json.internal.InvalidFloatingPointDecoded' call
  var tmp2_0 = access$nonFiniteFpMessage$tJsonExceptionsKt(result, tag);
  // Inline function 'kotlinx.serialization.json.internal.decodingExceptionOf' call
  var hint = "It is possible to deserialize them using 'JsonBuilder.allowSpecialFloatingPointValues = true'";
  var tmp_1;
  if (this.get_json_woos35_k$().configuration_1.exceptionsWithDebugInfo_1) {
    var tmp$ret$13 = toString(this.currentObject_yxe2oo_k$());
    tmp_1 = toString(minify(tmp$ret$13));
  } else {
    tmp_1 = null;
  }
  var inputValue_0 = tmp_1;
  throw new JsonDecodingException(access$formatDecodingException$tJsonExceptionsKt(-1, tmp2_0, null, hint, inputValue_0), tmp2_0, -1, null, inputValue_0, hint);
};
protoOf(AbstractJsonTreeDecoder).decodeTaggedFloat_81o7o9_k$ = function (tag) {
  return this.decodeTaggedFloat_hjyf60_k$((!(tag == null) ? typeof tag === 'string' : false) ? tag : THROW_CCE());
};
protoOf(AbstractJsonTreeDecoder).decodeTaggedDouble_u6dgwh_k$ = function (tag) {
  var tmp$ret$0;
  $l$block: {
    // Inline function 'kotlinx.serialization.json.internal.AbstractJsonTreeDecoder.getPrimitiveValue' call
    // Inline function 'kotlinx.serialization.json.internal.AbstractJsonTreeDecoder.cast' call
    // Inline function 'kotlinx.serialization.json.internal.cast' call
    var value = this.currentElement_4dg47r_k$(tag);
    if (!(value instanceof JsonPrimitive)) {
      var tmp2 = 'Expected ' + getKClass(JsonPrimitive).get_simpleName_r6f8py_k$() + ', but had ' + getKClassFromExpression(value).get_simpleName_r6f8py_k$() + ' as the serialized body of ' + 'double';
      // Inline function 'kotlinx.serialization.json.internal.decodingExceptionOf' call
      var path = this.renderTagStack_ixxubu_k$(tag);
      var tmp;
      if (this.get_json_woos35_k$().configuration_1.exceptionsWithDebugInfo_1) {
        var tmp$ret$7 = toString(value);
        tmp = toString(minify(tmp$ret$7));
      } else {
        tmp = null;
      }
      var inputValue = tmp;
      throw new JsonDecodingException(access$formatDecodingException$tJsonExceptionsKt(-1, tmp2, path, null, inputValue), tmp2, -1, path, inputValue, null);
    }
    var literal = value;
    try {
      var tmp0_elvis_lhs = get_double(literal);
      var tmp_0;
      if (tmp0_elvis_lhs == null) {
        unparsedPrimitive(this, literal, 'double', tag);
      } else {
        tmp_0 = tmp0_elvis_lhs;
      }
      tmp$ret$0 = tmp_0;
      break $l$block;
    } catch ($p) {
      if ($p instanceof IllegalArgumentException) {
        var e = $p;
        unparsedPrimitive(this, literal, 'double', tag);
      } else {
        throw $p;
      }
    }
  }
  var result = tmp$ret$0;
  var specialFp = this.get_json_woos35_k$().configuration_1.allowSpecialFloatingPointValues_1;
  if (specialFp || isFinite_0(result))
    return result;
  // Inline function 'kotlinx.serialization.json.internal.InvalidFloatingPointDecoded' call
  var tmp2_0 = access$nonFiniteFpMessage$tJsonExceptionsKt(result, tag);
  // Inline function 'kotlinx.serialization.json.internal.decodingExceptionOf' call
  var hint = "It is possible to deserialize them using 'JsonBuilder.allowSpecialFloatingPointValues = true'";
  var tmp_1;
  if (this.get_json_woos35_k$().configuration_1.exceptionsWithDebugInfo_1) {
    var tmp$ret$13 = toString(this.currentObject_yxe2oo_k$());
    tmp_1 = toString(minify(tmp$ret$13));
  } else {
    tmp_1 = null;
  }
  var inputValue_0 = tmp_1;
  throw new JsonDecodingException(access$formatDecodingException$tJsonExceptionsKt(-1, tmp2_0, null, hint, inputValue_0), tmp2_0, -1, null, inputValue_0, hint);
};
protoOf(AbstractJsonTreeDecoder).decodeTaggedDouble_aa801q_k$ = function (tag) {
  return this.decodeTaggedDouble_u6dgwh_k$((!(tag == null) ? typeof tag === 'string' : false) ? tag : THROW_CCE());
};
protoOf(AbstractJsonTreeDecoder).decodeTaggedChar_ha7850_k$ = function (tag) {
  var tmp$ret$0;
  $l$block: {
    // Inline function 'kotlinx.serialization.json.internal.AbstractJsonTreeDecoder.getPrimitiveValue' call
    // Inline function 'kotlinx.serialization.json.internal.AbstractJsonTreeDecoder.cast' call
    // Inline function 'kotlinx.serialization.json.internal.cast' call
    var value = this.currentElement_4dg47r_k$(tag);
    if (!(value instanceof JsonPrimitive)) {
      var tmp2 = 'Expected ' + getKClass(JsonPrimitive).get_simpleName_r6f8py_k$() + ', but had ' + getKClassFromExpression(value).get_simpleName_r6f8py_k$() + ' as the serialized body of ' + 'char';
      // Inline function 'kotlinx.serialization.json.internal.decodingExceptionOf' call
      var path = this.renderTagStack_ixxubu_k$(tag);
      var tmp;
      if (this.get_json_woos35_k$().configuration_1.exceptionsWithDebugInfo_1) {
        var tmp$ret$7 = toString(value);
        tmp = toString(minify(tmp$ret$7));
      } else {
        tmp = null;
      }
      var inputValue = tmp;
      throw new JsonDecodingException(access$formatDecodingException$tJsonExceptionsKt(-1, tmp2, path, null, inputValue), tmp2, -1, path, inputValue, null);
    }
    var literal = value;
    try {
      var tmp0_elvis_lhs = new Char(single(literal.get_content_h02jrk_k$()));
      var tmp_0;
      if (tmp0_elvis_lhs == null) {
        unparsedPrimitive(this, literal, 'char', tag);
      } else {
        tmp_0 = tmp0_elvis_lhs;
      }
      tmp$ret$0 = tmp_0.value_1;
      break $l$block;
    } catch ($p) {
      if ($p instanceof IllegalArgumentException) {
        var e = $p;
        unparsedPrimitive(this, literal, 'char', tag);
      } else {
        throw $p;
      }
    }
  }
  return tmp$ret$0;
};
protoOf(AbstractJsonTreeDecoder).decodeTaggedChar_w7yrsn_k$ = function (tag) {
  return this.decodeTaggedChar_ha7850_k$((!(tag == null) ? typeof tag === 'string' : false) ? tag : THROW_CCE());
};
protoOf(AbstractJsonTreeDecoder).decodeTaggedString_fe7ocx_k$ = function (tag) {
  // Inline function 'kotlinx.serialization.json.internal.AbstractJsonTreeDecoder.cast' call
  // Inline function 'kotlinx.serialization.json.internal.cast' call
  var value = this.currentElement_4dg47r_k$(tag);
  if (!(value instanceof JsonPrimitive)) {
    var tmp2 = 'Expected ' + getKClass(JsonPrimitive).get_simpleName_r6f8py_k$() + ', but had ' + getKClassFromExpression(value).get_simpleName_r6f8py_k$() + ' as the serialized body of ' + 'string';
    // Inline function 'kotlinx.serialization.json.internal.decodingExceptionOf' call
    var path = this.renderTagStack_ixxubu_k$(tag);
    var tmp;
    if (this.get_json_woos35_k$().configuration_1.exceptionsWithDebugInfo_1) {
      var tmp$ret$6 = toString(value);
      tmp = toString(minify(tmp$ret$6));
    } else {
      tmp = null;
    }
    var inputValue = tmp;
    throw new JsonDecodingException(access$formatDecodingException$tJsonExceptionsKt(-1, tmp2, path, null, inputValue), tmp2, -1, path, inputValue, null);
  }
  var value_0 = value;
  if (!(value_0 instanceof JsonLiteral)) {
    var tmp2_0 = "Expected string value for a non-null key '" + tag + "', got null literal instead";
    var tmp4 = this.renderTagStack_ixxubu_k$(tag);
    // Inline function 'kotlinx.serialization.json.internal.decodingExceptionOf' call
    var hint = "Use 'coerceInputValues = true' in 'Json {}' builder to coerce nulls if property has a default value.";
    var tmp_0;
    if (this.get_json_woos35_k$().configuration_1.exceptionsWithDebugInfo_1) {
      var tmp$ret$10 = toString(this.currentObject_yxe2oo_k$());
      tmp_0 = toString(minify(tmp$ret$10));
    } else {
      tmp_0 = null;
    }
    var inputValue_0 = tmp_0;
    throw new JsonDecodingException(access$formatDecodingException$tJsonExceptionsKt(-1, tmp2_0, tmp4, hint, inputValue_0), tmp2_0, -1, tmp4, inputValue_0, hint);
  }
  if (!value_0.isString_1 && !this.get_json_woos35_k$().configuration_1.isLenient_1) {
    var tmp2_1 = "String literal for value of key '" + tag + "' should be quoted";
    var tmp4_0 = this.renderTagStack_ixxubu_k$(tag);
    // Inline function 'kotlinx.serialization.json.internal.decodingExceptionOf' call
    var hint_0 = "Use 'isLenient = true' in 'Json {}' builder to accept non-compliant JSON.";
    var tmp_1;
    if (this.get_json_woos35_k$().configuration_1.exceptionsWithDebugInfo_1) {
      var tmp$ret$14 = toString(this.currentObject_yxe2oo_k$());
      tmp_1 = toString(minify(tmp$ret$14));
    } else {
      tmp_1 = null;
    }
    var inputValue_1 = tmp_1;
    throw new JsonDecodingException(access$formatDecodingException$tJsonExceptionsKt(-1, tmp2_1, tmp4_0, hint_0, inputValue_1), tmp2_1, -1, tmp4_0, inputValue_1, hint_0);
  }
  return value_0.content_1;
};
protoOf(AbstractJsonTreeDecoder).decodeTaggedString_45pp1e_k$ = function (tag) {
  return this.decodeTaggedString_fe7ocx_k$((!(tag == null) ? typeof tag === 'string' : false) ? tag : THROW_CCE());
};
protoOf(AbstractJsonTreeDecoder).decodeTaggedInline_tuyvom_k$ = function (tag, inlineDescriptor) {
  var tmp;
  if (get_isUnsignedNumber(inlineDescriptor)) {
    var tmp_0 = this.get_json_woos35_k$();
    // Inline function 'kotlinx.serialization.json.internal.AbstractJsonTreeDecoder.getPrimitiveValue' call
    var tmp2 = this.currentElement_4dg47r_k$(tag);
    // Inline function 'kotlinx.serialization.json.internal.AbstractJsonTreeDecoder.cast' call
    // Inline function 'kotlinx.serialization.json.internal.cast' call
    var serialName = inlineDescriptor.get_serialName_u2rqhk_k$();
    if (!(tmp2 instanceof JsonPrimitive)) {
      var tmp2_0 = 'Expected ' + getKClass(JsonPrimitive).get_simpleName_r6f8py_k$() + ', but had ' + getKClassFromExpression(tmp2).get_simpleName_r6f8py_k$() + ' as the serialized body of ' + serialName;
      // Inline function 'kotlinx.serialization.json.internal.decodingExceptionOf' call
      var path = this.renderTagStack_ixxubu_k$(tag);
      var tmp_1;
      if (this.get_json_woos35_k$().configuration_1.exceptionsWithDebugInfo_1) {
        var tmp$ret$7 = toString(tmp2);
        tmp_1 = toString(minify(tmp$ret$7));
      } else {
        tmp_1 = null;
      }
      var inputValue = tmp_1;
      throw new JsonDecodingException(access$formatDecodingException$tJsonExceptionsKt(-1, tmp2_0, path, null, inputValue), tmp2_0, -1, path, inputValue, null);
    }
    var lexer = StringJsonLexer_0(tmp_0, tmp2.get_content_h02jrk_k$());
    tmp = new JsonDecoderForUnsignedTypes(lexer, this.get_json_woos35_k$());
  } else {
    tmp = protoOf(NamedValueDecoder).decodeTaggedInline_u4chc9_k$.call(this, tag, inlineDescriptor);
  }
  return tmp;
};
protoOf(AbstractJsonTreeDecoder).decodeTaggedInline_u4chc9_k$ = function (tag, inlineDescriptor) {
  return this.decodeTaggedInline_tuyvom_k$((!(tag == null) ? typeof tag === 'string' : false) ? tag : THROW_CCE(), inlineDescriptor);
};
protoOf(AbstractJsonTreeDecoder).decodeInline_ux3vza_k$ = function (descriptor) {
  return !(this.get_currentTagOrNull_yhyzw_k$() == null) ? protoOf(NamedValueDecoder).decodeInline_ux3vza_k$.call(this, descriptor) : (new JsonPrimitiveDecoder(this.get_json_woos35_k$(), this.get_value_j01efc_k$(), this.polymorphicDiscriminator_1)).decodeInline_ux3vza_k$(descriptor);
};
function setForceNull($this, descriptor, index) {
  $this.forceNull_1 = (!$this.get_json_woos35_k$().configuration_1.explicitNulls_1 && !descriptor.isElementOptional_heqq56_k$(index) && descriptor.getElementDescriptor_ncda77_k$(index).get_isNullable_67sy7o_k$());
  return $this.forceNull_1;
}
function JsonTreeDecoder(json, value, polymorphicDiscriminator, polyDescriptor) {
  polymorphicDiscriminator = polymorphicDiscriminator === VOID ? null : polymorphicDiscriminator;
  polyDescriptor = polyDescriptor === VOID ? null : polyDescriptor;
  AbstractJsonTreeDecoder.call(this, json, value, polymorphicDiscriminator);
  this.value_2 = value;
  this.polyDescriptor_1 = polyDescriptor;
  this.position_1 = 0;
  this.forceNull_1 = false;
}
protoOf(JsonTreeDecoder).get_value_j01efc_k$ = function () {
  return this.value_2;
};
protoOf(JsonTreeDecoder).decodeElementIndex_bstkhp_k$ = function (descriptor) {
  $l$loop: while (this.position_1 < descriptor.get_elementsCount_288r0x_k$()) {
    var _unary__edvuaz = this.position_1;
    this.position_1 = _unary__edvuaz + 1 | 0;
    var name = this.getTag_u4dqfb_k$(descriptor, _unary__edvuaz);
    var index = this.position_1 - 1 | 0;
    this.forceNull_1 = false;
    var tmp;
    // Inline function 'kotlin.collections.contains' call
    // Inline function 'kotlin.collections.containsKey' call
    var this_0 = this.get_value_j01efc_k$();
    if ((isInterface(this_0, KtMap) ? this_0 : THROW_CCE()).containsKey_aw81wo_k$(name)) {
      tmp = true;
    } else {
      tmp = setForceNull(this, descriptor, index);
    }
    if (tmp) {
      if (!this.configuration_1.coerceInputValues_1)
        return index;
      var tmp0 = this.get_json_woos35_k$();
      var tmp$ret$2;
      $l$block_2: {
        // Inline function 'kotlinx.serialization.json.internal.tryCoerceValue' call
        var isOptional = descriptor.isElementOptional_heqq56_k$(index);
        var elementDescriptor = descriptor.getElementDescriptor_ncda77_k$(index);
        var tmp_0;
        if (isOptional && !elementDescriptor.get_isNullable_67sy7o_k$()) {
          var tmp_1 = this.currentElementOrNull_jb4enh_k$(name);
          tmp_0 = tmp_1 instanceof JsonNull;
        } else {
          tmp_0 = false;
        }
        if (tmp_0) {
          tmp$ret$2 = true;
          break $l$block_2;
        }
        if (equals(elementDescriptor.get_kind_wop7ml_k$(), ENUM_getInstance())) {
          var tmp_2;
          if (elementDescriptor.get_isNullable_67sy7o_k$()) {
            var tmp_3 = this.currentElementOrNull_jb4enh_k$(name);
            tmp_2 = tmp_3 instanceof JsonNull;
          } else {
            tmp_2 = false;
          }
          if (tmp_2) {
            tmp$ret$2 = false;
            break $l$block_2;
          }
          var tmp_4 = this.currentElementOrNull_jb4enh_k$(name);
          var tmp0_safe_receiver = tmp_4 instanceof JsonPrimitive ? tmp_4 : null;
          var tmp0_elvis_lhs = tmp0_safe_receiver == null ? null : get_contentOrNull(tmp0_safe_receiver);
          var tmp_5;
          if (tmp0_elvis_lhs == null) {
            tmp$ret$2 = false;
            break $l$block_2;
          } else {
            tmp_5 = tmp0_elvis_lhs;
          }
          var enumValue = tmp_5;
          var enumIndex = getJsonNameIndex(elementDescriptor, tmp0, enumValue);
          var coerceToNull = !tmp0.configuration_1.explicitNulls_1 && elementDescriptor.get_isNullable_67sy7o_k$();
          if (enumIndex === -3 && (isOptional || coerceToNull)) {
            if (setForceNull(this, descriptor, index))
              return index;
            tmp$ret$2 = true;
            break $l$block_2;
          }
        }
        tmp$ret$2 = false;
      }
      if (tmp$ret$2)
        continue $l$loop;
      return index;
    }
  }
  return -1;
};
protoOf(JsonTreeDecoder).decodeNotNullMark_us4ba1_k$ = function () {
  return !this.forceNull_1 && protoOf(AbstractJsonTreeDecoder).decodeNotNullMark_us4ba1_k$.call(this);
};
protoOf(JsonTreeDecoder).elementName_p66hrm_k$ = function (descriptor, index) {
  var strategy = namingStrategy(descriptor, this.get_json_woos35_k$());
  var baseName = descriptor.getElementName_u4sqmf_k$(index);
  if (strategy == null) {
    if (!this.configuration_1.useAlternativeNames_1)
      return baseName;
    if (this.get_value_j01efc_k$().get_keys_wop4xp_k$().contains_aljjnj_k$(baseName))
      return baseName;
  }
  var deserializationNamesMap_0 = deserializationNamesMap(this.get_json_woos35_k$(), descriptor);
  // Inline function 'kotlin.collections.find' call
  var tmp0 = this.get_value_j01efc_k$().get_keys_wop4xp_k$();
  var tmp$ret$1;
  $l$block: {
    // Inline function 'kotlin.collections.firstOrNull' call
    var _iterator__ex2g4s = tmp0.iterator_jk1svi_k$();
    while (_iterator__ex2g4s.hasNext_bitz1p_k$()) {
      var element = _iterator__ex2g4s.next_20eer_k$();
      if (deserializationNamesMap_0.get_wei43m_k$(element) === index) {
        tmp$ret$1 = element;
        break $l$block;
      }
    }
    tmp$ret$1 = null;
  }
  var tmp0_safe_receiver = tmp$ret$1;
  if (tmp0_safe_receiver == null)
    null;
  else {
    // Inline function 'kotlin.let' call
    return tmp0_safe_receiver;
  }
  var fallbackName = strategy == null ? null : strategy.serialNameForJson_tclx3n_k$(descriptor, index, baseName);
  return fallbackName == null ? baseName : fallbackName;
};
protoOf(JsonTreeDecoder).currentElement_4dg47r_k$ = function (tag) {
  return getValue(this.get_value_j01efc_k$(), tag);
};
protoOf(JsonTreeDecoder).currentElementOrNull_jb4enh_k$ = function (tag) {
  return this.get_value_j01efc_k$().get_6bo4tg_k$(tag);
};
protoOf(JsonTreeDecoder).beginStructure_yljocp_k$ = function (descriptor) {
  if (descriptor === this.polyDescriptor_1) {
    var tmp = this.get_json_woos35_k$();
    var tmp2 = this.currentObject_yxe2oo_k$();
    // Inline function 'kotlinx.serialization.json.internal.AbstractJsonTreeDecoder.cast' call
    // Inline function 'kotlinx.serialization.json.internal.cast' call
    var serialName = this.polyDescriptor_1.get_serialName_u2rqhk_k$();
    if (!(tmp2 instanceof JsonObject)) {
      var tmp2_0 = 'Expected ' + getKClass(JsonObject).get_simpleName_r6f8py_k$() + ', but had ' + getKClassFromExpression(tmp2).get_simpleName_r6f8py_k$() + ' as the serialized body of ' + serialName;
      // Inline function 'kotlinx.serialization.json.internal.decodingExceptionOf' call
      var path = this.renderTagStack_rja478_k$();
      var tmp_0;
      if (this.get_json_woos35_k$().configuration_1.exceptionsWithDebugInfo_1) {
        var tmp$ret$6 = toString(tmp2);
        tmp_0 = toString(minify(tmp$ret$6));
      } else {
        tmp_0 = null;
      }
      var inputValue = tmp_0;
      throw new JsonDecodingException(access$formatDecodingException$tJsonExceptionsKt(-1, tmp2_0, path, null, inputValue), tmp2_0, -1, path, inputValue, null);
    }
    return new JsonTreeDecoder(tmp, tmp2, this.polymorphicDiscriminator_1, this.polyDescriptor_1);
  }
  return protoOf(AbstractJsonTreeDecoder).beginStructure_yljocp_k$.call(this, descriptor);
};
protoOf(JsonTreeDecoder).endStructure_1xqz0n_k$ = function (descriptor) {
  var tmp;
  if (ignoreUnknownKeys(descriptor, this.get_json_woos35_k$())) {
    tmp = true;
  } else {
    var tmp_0 = descriptor.get_kind_wop7ml_k$();
    tmp = tmp_0 instanceof PolymorphicKind;
  }
  if (tmp)
    return Unit_instance;
  var strategy = namingStrategy(descriptor, this.get_json_woos35_k$());
  var tmp_1;
  if (strategy == null && !this.configuration_1.useAlternativeNames_1) {
    tmp_1 = jsonCachedSerialNames(descriptor);
  } else if (!(strategy == null)) {
    tmp_1 = deserializationNamesMap(this.get_json_woos35_k$(), descriptor).get_keys_wop4xp_k$();
  } else {
    var tmp_2 = jsonCachedSerialNames(descriptor);
    var tmp0_safe_receiver = get_schemaCache(this.get_json_woos35_k$()).get_xn5txp_k$(descriptor, get_JsonDeserializationNamesKey());
    // Inline function 'kotlin.collections.orEmpty' call
    var tmp0_elvis_lhs = tmp0_safe_receiver == null ? null : tmp0_safe_receiver.get_keys_wop4xp_k$();
    var tmp$ret$0 = tmp0_elvis_lhs == null ? emptySet() : tmp0_elvis_lhs;
    tmp_1 = plus_0(tmp_2, tmp$ret$0);
  }
  var names = tmp_1;
  var _iterator__ex2g4s = this.get_value_j01efc_k$().get_keys_wop4xp_k$().iterator_jk1svi_k$();
  while (_iterator__ex2g4s.hasNext_bitz1p_k$()) {
    var key = _iterator__ex2g4s.next_20eer_k$();
    if (!names.contains_aljjnj_k$(key) && !(key === this.polymorphicDiscriminator_1)) {
      var tmp2 = "Encountered an unknown key '" + key + "'";
      var tmp4 = this.renderTagStack_rja478_k$();
      // Inline function 'kotlinx.serialization.json.internal.decodingExceptionOf' call
      var hint = "Use 'ignoreUnknownKeys = true' in 'Json {}' builder or '@JsonIgnoreUnknownKeys' annotation to ignore unknown keys.";
      var tmp_3;
      if (this.get_json_woos35_k$().configuration_1.exceptionsWithDebugInfo_1) {
        var tmp$ret$4 = this.get_value_j01efc_k$().toString();
        tmp_3 = toString(minify(tmp$ret$4));
      } else {
        tmp_3 = null;
      }
      var inputValue = tmp_3;
      throw new JsonDecodingException(access$formatDecodingException$tJsonExceptionsKt(-1, tmp2, tmp4, hint, inputValue), tmp2, -1, tmp4, inputValue, hint);
    }
  }
};
function JsonTreeListDecoder(json, value) {
  AbstractJsonTreeDecoder.call(this, json, value);
  this.value_2 = value;
  this.size_1 = this.value_2.get_size_woubt6_k$();
  this.currentIndex_1 = -1;
}
protoOf(JsonTreeListDecoder).get_value_j01efc_k$ = function () {
  return this.value_2;
};
protoOf(JsonTreeListDecoder).elementName_p66hrm_k$ = function (descriptor, index) {
  return index.toString();
};
protoOf(JsonTreeListDecoder).currentElement_4dg47r_k$ = function (tag) {
  return this.value_2.get_c1px32_k$(toInt(tag));
};
protoOf(JsonTreeListDecoder).decodeElementIndex_bstkhp_k$ = function (descriptor) {
  while (this.currentIndex_1 < (this.size_1 - 1 | 0)) {
    this.currentIndex_1 = this.currentIndex_1 + 1 | 0;
    return this.currentIndex_1;
  }
  return -1;
};
function JsonPrimitiveDecoder(json, value, polymorphicDiscriminator) {
  polymorphicDiscriminator = polymorphicDiscriminator === VOID ? null : polymorphicDiscriminator;
  AbstractJsonTreeDecoder.call(this, json, value, polymorphicDiscriminator);
  this.value_2 = value;
  this.pushTag_bibnaf_k$('primitive');
}
protoOf(JsonPrimitiveDecoder).get_value_j01efc_k$ = function () {
  return this.value_2;
};
protoOf(JsonPrimitiveDecoder).decodeElementIndex_bstkhp_k$ = function (descriptor) {
  return 0;
};
protoOf(JsonPrimitiveDecoder).currentElement_4dg47r_k$ = function (tag) {
  // Inline function 'kotlin.require' call
  if (!(tag === 'primitive')) {
    var message = "This input can only handle primitives with 'primitive' tag";
    throw IllegalArgumentException_init_$Create$(toString(message));
  }
  return this.value_2;
};
function JsonTreeMapDecoder(json, value) {
  JsonTreeDecoder.call(this, json, value);
  this.value_3 = value;
  this.keys_1 = toList(this.value_3.get_keys_wop4xp_k$());
  this.size_1 = imul(this.keys_1.get_size_woubt6_k$(), 2);
  this.position_2 = -1;
}
protoOf(JsonTreeMapDecoder).get_value_j01efc_k$ = function () {
  return this.value_3;
};
protoOf(JsonTreeMapDecoder).elementName_p66hrm_k$ = function (descriptor, index) {
  var i = index / 2 | 0;
  return this.keys_1.get_c1px32_k$(i);
};
protoOf(JsonTreeMapDecoder).decodeElementIndex_bstkhp_k$ = function (descriptor) {
  while (this.position_2 < (this.size_1 - 1 | 0)) {
    this.position_2 = this.position_2 + 1 | 0;
    return this.position_2;
  }
  return -1;
};
protoOf(JsonTreeMapDecoder).currentElement_4dg47r_k$ = function (tag) {
  return (this.position_2 % 2 | 0) === 0 ? JsonPrimitive_0(tag) : getValue(this.value_3, tag);
};
protoOf(JsonTreeMapDecoder).endStructure_1xqz0n_k$ = function (descriptor) {
};
function writeJson(json, value, serializer) {
  var result = {_v: null};
  var encoder = new JsonTreeEncoder(json, writeJson$lambda(result));
  encoder.encodeSerializableValue_3uuzip_k$(serializer, value);
  var tmp;
  if (result._v == null) {
    throwUninitializedPropertyAccessException('result');
  } else {
    tmp = result._v;
  }
  return tmp;
}
function JsonTreeEncoder(json, nodeConsumer) {
  AbstractJsonTreeEncoder.call(this, json, nodeConsumer);
  var tmp = this;
  // Inline function 'kotlin.collections.linkedMapOf' call
  tmp.content_1 = LinkedHashMap_init_$Create$();
}
protoOf(JsonTreeEncoder).putElement_rnqfb1_k$ = function (key, element) {
  // Inline function 'kotlin.collections.set' call
  this.content_1.put_4fpzoq_k$(key, element);
};
protoOf(JsonTreeEncoder).encodeNullableSerializableElement_5lquiv_k$ = function (descriptor, index, serializer, value) {
  if (!(value == null) || this.configuration_1.explicitNulls_1) {
    protoOf(AbstractJsonTreeEncoder).encodeNullableSerializableElement_5lquiv_k$.call(this, descriptor, index, serializer, value);
  }
};
protoOf(JsonTreeEncoder).getCurrent_z8uawt_k$ = function () {
  return new JsonObject(this.content_1);
};
function inlineUnsignedNumberEncoder($this, tag) {
  return new AbstractJsonTreeEncoder$inlineUnsignedNumberEncoder$1($this, tag);
}
function inlineUnquotedLiteralEncoder($this, tag, inlineDescriptor) {
  return new AbstractJsonTreeEncoder$inlineUnquotedLiteralEncoder$1($this, tag, inlineDescriptor);
}
function AbstractJsonTreeEncoder$inlineUnsignedNumberEncoder$1(this$0, $tag) {
  this.this$0__1 = this$0;
  this.$tag_1 = $tag;
  AbstractEncoder.call(this);
  this.serializersModule_1 = this$0.json_1.get_serializersModule_piitvg_k$();
}
protoOf(AbstractJsonTreeEncoder$inlineUnsignedNumberEncoder$1).get_serializersModule_piitvg_k$ = function () {
  return this.serializersModule_1;
};
protoOf(AbstractJsonTreeEncoder$inlineUnsignedNumberEncoder$1).putUnquotedString_jy6tm1_k$ = function (s) {
  return this.this$0__1.putElement_rnqfb1_k$(this.$tag_1, new JsonLiteral(s, false));
};
protoOf(AbstractJsonTreeEncoder$inlineUnsignedNumberEncoder$1).encodeInt_y5zi3z_k$ = function (value) {
  // Inline function 'kotlin.toUInt' call
  var tmp$ret$0 = _UInt___init__impl__l7qpdl(value);
  return this.putUnquotedString_jy6tm1_k$(UInt__toString_impl_dbgl21(tmp$ret$0));
};
protoOf(AbstractJsonTreeEncoder$inlineUnsignedNumberEncoder$1).encodeLong_vlw3uq_k$ = function (value) {
  // Inline function 'kotlin.toULong' call
  var tmp$ret$0 = _ULong___init__impl__c78o9k(value);
  return this.putUnquotedString_jy6tm1_k$(ULong__toString_impl_f9au7k(tmp$ret$0));
};
protoOf(AbstractJsonTreeEncoder$inlineUnsignedNumberEncoder$1).encodeByte_6txfee_k$ = function (value) {
  // Inline function 'kotlin.toUByte' call
  var tmp$ret$0 = _UByte___init__impl__g9hnc4(value);
  return this.putUnquotedString_jy6tm1_k$(UByte__toString_impl_v72jg(tmp$ret$0));
};
protoOf(AbstractJsonTreeEncoder$inlineUnsignedNumberEncoder$1).encodeShort_gza6si_k$ = function (value) {
  // Inline function 'kotlin.toUShort' call
  var tmp$ret$0 = _UShort___init__impl__jigrne(value);
  return this.putUnquotedString_jy6tm1_k$(UShort__toString_impl_edaoee(tmp$ret$0));
};
function AbstractJsonTreeEncoder$inlineUnquotedLiteralEncoder$1(this$0, $tag, $inlineDescriptor) {
  this.this$0__1 = this$0;
  this.$tag_1 = $tag;
  this.$inlineDescriptor_1 = $inlineDescriptor;
  AbstractEncoder.call(this);
}
protoOf(AbstractJsonTreeEncoder$inlineUnquotedLiteralEncoder$1).get_serializersModule_piitvg_k$ = function () {
  return this.this$0__1.json_1.get_serializersModule_piitvg_k$();
};
protoOf(AbstractJsonTreeEncoder$inlineUnquotedLiteralEncoder$1).encodeString_424b5v_k$ = function (value) {
  return this.this$0__1.putElement_rnqfb1_k$(this.$tag_1, new JsonLiteral(value, false, this.$inlineDescriptor_1));
};
function AbstractJsonTreeEncoder$beginStructure$lambda(this$0) {
  return function (node) {
    this$0.putElement_rnqfb1_k$(this$0.get_currentTag_wui9re_k$(), node);
    return Unit_instance;
  };
}
function AbstractJsonTreeEncoder(json, nodeConsumer) {
  NamedValueEncoder.call(this);
  this.json_1 = json;
  this.nodeConsumer_1 = nodeConsumer;
  this.configuration_1 = this.json_1.configuration_1;
  this.polymorphicDiscriminator_1 = null;
  this.polymorphicSerialName_1 = null;
}
protoOf(AbstractJsonTreeEncoder).get_json_woos35_k$ = function () {
  return this.json_1;
};
protoOf(AbstractJsonTreeEncoder).get_serializersModule_piitvg_k$ = function () {
  return this.json_1.get_serializersModule_piitvg_k$();
};
protoOf(AbstractJsonTreeEncoder).elementName_p66hrm_k$ = function (descriptor, index) {
  return getJsonElementName(descriptor, this.json_1, index);
};
protoOf(AbstractJsonTreeEncoder).shouldEncodeElementDefault_x8eyid_k$ = function (descriptor, index) {
  return this.configuration_1.encodeDefaults_1;
};
protoOf(AbstractJsonTreeEncoder).composeName_8y2y4d_k$ = function (parentName, childName) {
  return childName;
};
protoOf(AbstractJsonTreeEncoder).encodeNotNullMark_415a1t_k$ = function () {
};
protoOf(AbstractJsonTreeEncoder).encodeNull_ejiosz_k$ = function () {
  var tmp0_elvis_lhs = this.get_currentTagOrNull_yhyzw_k$();
  var tmp;
  if (tmp0_elvis_lhs == null) {
    return this.nodeConsumer_1(JsonNull_getInstance());
  } else {
    tmp = tmp0_elvis_lhs;
  }
  var tag = tmp;
  this.encodeTaggedNull_qzw0n5_k$(tag);
};
protoOf(AbstractJsonTreeEncoder).encodeTaggedNull_qzw0n5_k$ = function (tag) {
  return this.putElement_rnqfb1_k$(tag, JsonNull_getInstance());
};
protoOf(AbstractJsonTreeEncoder).encodeTaggedNull_ef6rw0_k$ = function (tag) {
  return this.encodeTaggedNull_qzw0n5_k$((!(tag == null) ? typeof tag === 'string' : false) ? tag : THROW_CCE());
};
protoOf(AbstractJsonTreeEncoder).encodeTaggedInt_tkpbln_k$ = function (tag, value) {
  return this.putElement_rnqfb1_k$(tag, JsonPrimitive_2(value));
};
protoOf(AbstractJsonTreeEncoder).encodeTaggedInt_sojdj8_k$ = function (tag, value) {
  return this.encodeTaggedInt_tkpbln_k$((!(tag == null) ? typeof tag === 'string' : false) ? tag : THROW_CCE(), value);
};
protoOf(AbstractJsonTreeEncoder).encodeTaggedByte_5409y0_k$ = function (tag, value) {
  return this.putElement_rnqfb1_k$(tag, JsonPrimitive_2(value));
};
protoOf(AbstractJsonTreeEncoder).encodeTaggedByte_zfi7rb_k$ = function (tag, value) {
  return this.encodeTaggedByte_5409y0_k$((!(tag == null) ? typeof tag === 'string' : false) ? tag : THROW_CCE(), value);
};
protoOf(AbstractJsonTreeEncoder).encodeTaggedShort_wf2i94_k$ = function (tag, value) {
  return this.putElement_rnqfb1_k$(tag, JsonPrimitive_2(value));
};
protoOf(AbstractJsonTreeEncoder).encodeTaggedShort_18vuif_k$ = function (tag, value) {
  return this.encodeTaggedShort_wf2i94_k$((!(tag == null) ? typeof tag === 'string' : false) ? tag : THROW_CCE(), value);
};
protoOf(AbstractJsonTreeEncoder).encodeTaggedLong_ifi8eg_k$ = function (tag, value) {
  return this.putElement_rnqfb1_k$(tag, JsonPrimitive_2(value));
};
protoOf(AbstractJsonTreeEncoder).encodeTaggedLong_j9j45b_k$ = function (tag, value) {
  return this.encodeTaggedLong_ifi8eg_k$((!(tag == null) ? typeof tag === 'string' : false) ? tag : THROW_CCE(), value);
};
protoOf(AbstractJsonTreeEncoder).encodeTaggedFloat_4is9mw_k$ = function (tag, value) {
  this.putElement_rnqfb1_k$(tag, JsonPrimitive_2(value));
  if (!this.configuration_1.allowSpecialFloatingPointValues_1 && !isFinite(value)) {
    throw InvalidFloatingPointEncoded(value, tag);
  }
};
protoOf(AbstractJsonTreeEncoder).encodeTaggedFloat_dhn4x5_k$ = function (tag, value) {
  return this.encodeTaggedFloat_4is9mw_k$((!(tag == null) ? typeof tag === 'string' : false) ? tag : THROW_CCE(), value);
};
protoOf(AbstractJsonTreeEncoder).encodeSerializableValue_3uuzip_k$ = function (serializer, value) {
  if (!(this.get_currentTagOrNull_yhyzw_k$() == null) || !get_requiresTopLevelTag(carrierDescriptor(serializer.get_descriptor_wjt6a0_k$(), this.get_serializersModule_piitvg_k$()))) {
    $l$block: {
      // Inline function 'kotlinx.serialization.json.internal.encodePolymorphically' call
      if (this.get_json_woos35_k$().configuration_1.useArrayPolymorphism_1) {
        serializer.serialize_5ase3y_k$(this, value);
        break $l$block;
      }
      var isPolymorphicSerializer = serializer instanceof AbstractPolymorphicSerializer;
      var tmp;
      if (isPolymorphicSerializer) {
        tmp = !this.get_json_woos35_k$().configuration_1.classDiscriminatorMode_1.equals(ClassDiscriminatorMode_NONE_getInstance());
      } else {
        var tmp_0;
        switch (this.get_json_woos35_k$().configuration_1.classDiscriminatorMode_1.ordinal_1) {
          case 0:
          case 2:
            tmp_0 = false;
            break;
          case 1:
            // Inline function 'kotlin.let' call

            var it = serializer.get_descriptor_wjt6a0_k$().get_kind_wop7ml_k$();
            tmp_0 = equals(it, CLASS_getInstance()) || equals(it, OBJECT_getInstance());
            break;
          default:
            noWhenBranchMatchedException();
            break;
        }
        tmp = tmp_0;
      }
      var needDiscriminator = tmp;
      var baseClassDiscriminator = needDiscriminator ? classDiscriminator(serializer.get_descriptor_wjt6a0_k$(), this.get_json_woos35_k$()) : null;
      var tmp_1;
      if (isPolymorphicSerializer) {
        var casted = serializer instanceof AbstractPolymorphicSerializer ? serializer : THROW_CCE();
        $l$block_0: {
          // Inline function 'kotlin.requireNotNull' call
          if (value == null) {
            var message = 'Value for serializer ' + toString(serializer.get_descriptor_wjt6a0_k$()) + ' should always be non-null. Please report issue to the kotlinx.serialization tracker.';
            throw IllegalArgumentException_init_$Create$(toString(message));
          } else {
            break $l$block_0;
          }
        }
        var actual = findPolymorphicSerializer_0(casted, this, value);
        tmp_1 = isInterface(actual, SerializationStrategy) ? actual : THROW_CCE();
      } else {
        tmp_1 = serializer;
      }
      var actualSerializer = tmp_1;
      if (!(baseClassDiscriminator == null)) {
        access$checkEncodingConflicts$tPolymorphicKt(this.get_json_woos35_k$(), serializer, actualSerializer, baseClassDiscriminator);
        checkKind_0(actualSerializer.get_descriptor_wjt6a0_k$().get_kind_wop7ml_k$());
        var serialName = actualSerializer.get_descriptor_wjt6a0_k$().get_serialName_u2rqhk_k$();
        this.polymorphicDiscriminator_1 = baseClassDiscriminator;
        this.polymorphicSerialName_1 = serialName;
      }
      actualSerializer.serialize_5ase3y_k$(this, value);
    }
  } else {
    // Inline function 'kotlin.run' call
    (new JsonPrimitiveEncoder(this.json_1, this.nodeConsumer_1)).encodeSerializableValue_3uuzip_k$(serializer, value);
  }
};
protoOf(AbstractJsonTreeEncoder).encodeTaggedDouble_wgtjw1_k$ = function (tag, value) {
  this.putElement_rnqfb1_k$(tag, JsonPrimitive_2(value));
  if (!this.configuration_1.allowSpecialFloatingPointValues_1 && !isFinite_0(value)) {
    throw InvalidFloatingPointEncoded(value, tag);
  }
};
protoOf(AbstractJsonTreeEncoder).encodeTaggedDouble_wxqx2s_k$ = function (tag, value) {
  return this.encodeTaggedDouble_wgtjw1_k$((!(tag == null) ? typeof tag === 'string' : false) ? tag : THROW_CCE(), value);
};
protoOf(AbstractJsonTreeEncoder).encodeTaggedBoolean_hv2cld_k$ = function (tag, value) {
  return this.putElement_rnqfb1_k$(tag, JsonPrimitive_1(value));
};
protoOf(AbstractJsonTreeEncoder).encodeTaggedBoolean_96ly0o_k$ = function (tag, value) {
  return this.encodeTaggedBoolean_hv2cld_k$((!(tag == null) ? typeof tag === 'string' : false) ? tag : THROW_CCE(), value);
};
protoOf(AbstractJsonTreeEncoder).encodeTaggedChar_owp4mj_k$ = function (tag, value) {
  return this.putElement_rnqfb1_k$(tag, JsonPrimitive_0(toString_1(value)));
};
protoOf(AbstractJsonTreeEncoder).encodeTaggedChar_o52n5u_k$ = function (tag, value) {
  return this.encodeTaggedChar_owp4mj_k$((!(tag == null) ? typeof tag === 'string' : false) ? tag : THROW_CCE(), value);
};
protoOf(AbstractJsonTreeEncoder).encodeTaggedString_wkr2bh_k$ = function (tag, value) {
  return this.putElement_rnqfb1_k$(tag, JsonPrimitive_0(value));
};
protoOf(AbstractJsonTreeEncoder).encodeTaggedString_w0iev4_k$ = function (tag, value) {
  return this.encodeTaggedString_wkr2bh_k$((!(tag == null) ? typeof tag === 'string' : false) ? tag : THROW_CCE(), value);
};
protoOf(AbstractJsonTreeEncoder).encodeTaggedEnum_56ln9q_k$ = function (tag, enumDescriptor, ordinal) {
  return this.putElement_rnqfb1_k$(tag, JsonPrimitive_0(enumDescriptor.getElementName_u4sqmf_k$(ordinal)));
};
protoOf(AbstractJsonTreeEncoder).encodeTaggedEnum_vfihkh_k$ = function (tag, enumDescriptor, ordinal) {
  return this.encodeTaggedEnum_56ln9q_k$((!(tag == null) ? typeof tag === 'string' : false) ? tag : THROW_CCE(), enumDescriptor, ordinal);
};
protoOf(AbstractJsonTreeEncoder).encodeTaggedValue_sfcjfh_k$ = function (tag, value) {
  this.putElement_rnqfb1_k$(tag, JsonPrimitive_0(toString(value)));
};
protoOf(AbstractJsonTreeEncoder).encodeTaggedValue_uwjsrc_k$ = function (tag, value) {
  return this.encodeTaggedValue_sfcjfh_k$((!(tag == null) ? typeof tag === 'string' : false) ? tag : THROW_CCE(), value);
};
protoOf(AbstractJsonTreeEncoder).encodeTaggedInline_idwvcu_k$ = function (tag, inlineDescriptor) {
  return get_isUnsignedNumber(inlineDescriptor) ? inlineUnsignedNumberEncoder(this, tag) : get_isUnquotedLiteral(inlineDescriptor) ? inlineUnquotedLiteralEncoder(this, tag, inlineDescriptor) : protoOf(NamedValueEncoder).encodeTaggedInline_k3uebz_k$.call(this, tag, inlineDescriptor);
};
protoOf(AbstractJsonTreeEncoder).encodeTaggedInline_k3uebz_k$ = function (tag, inlineDescriptor) {
  return this.encodeTaggedInline_idwvcu_k$((!(tag == null) ? typeof tag === 'string' : false) ? tag : THROW_CCE(), inlineDescriptor);
};
protoOf(AbstractJsonTreeEncoder).encodeInline_wxp5pu_k$ = function (descriptor) {
  var tmp;
  if (!(this.get_currentTagOrNull_yhyzw_k$() == null)) {
    if (!(this.polymorphicDiscriminator_1 == null))
      this.polymorphicSerialName_1 = descriptor.get_serialName_u2rqhk_k$();
    tmp = protoOf(NamedValueEncoder).encodeInline_wxp5pu_k$.call(this, descriptor);
  } else {
    tmp = (new JsonPrimitiveEncoder(this.json_1, this.nodeConsumer_1)).encodeInline_wxp5pu_k$(descriptor);
  }
  return tmp;
};
protoOf(AbstractJsonTreeEncoder).beginStructure_yljocp_k$ = function (descriptor) {
  var tmp;
  if (this.get_currentTagOrNull_yhyzw_k$() == null) {
    tmp = this.nodeConsumer_1;
  } else {
    tmp = AbstractJsonTreeEncoder$beginStructure$lambda(this);
  }
  var consumer = tmp;
  var tmp0_subject = descriptor.get_kind_wop7ml_k$();
  var tmp_0;
  var tmp_1;
  if (equals(tmp0_subject, LIST_getInstance())) {
    tmp_1 = true;
  } else {
    tmp_1 = tmp0_subject instanceof PolymorphicKind;
  }
  if (tmp_1) {
    tmp_0 = new JsonTreeListEncoder(this.json_1, consumer);
  } else {
    if (equals(tmp0_subject, MAP_getInstance())) {
      // Inline function 'kotlinx.serialization.json.internal.selectMapMode' call
      var this_0 = this.json_1;
      var keyDescriptor = carrierDescriptor(descriptor.getElementDescriptor_ncda77_k$(0), this_0.get_serializersModule_piitvg_k$());
      var keyKind = keyDescriptor.get_kind_wop7ml_k$();
      var tmp_2;
      var tmp_3;
      if (keyKind instanceof PrimitiveKind) {
        tmp_3 = true;
      } else {
        tmp_3 = equals(keyKind, ENUM_getInstance());
      }
      if (tmp_3) {
        tmp_2 = new JsonTreeMapEncoder(this.json_1, consumer);
      } else {
        if (this_0.configuration_1.allowStructuredMapKeys_1) {
          tmp_2 = new JsonTreeListEncoder(this.json_1, consumer);
        } else {
          throw InvalidKeyKindException(keyDescriptor);
        }
      }
      tmp_0 = tmp_2;
    } else {
      tmp_0 = new JsonTreeEncoder(this.json_1, consumer);
    }
  }
  var encoder = tmp_0;
  var discriminator = this.polymorphicDiscriminator_1;
  if (!(discriminator == null)) {
    if (encoder instanceof JsonTreeMapEncoder) {
      encoder.putElement_rnqfb1_k$('key', JsonPrimitive_0(discriminator));
      var tmp1_elvis_lhs = this.polymorphicSerialName_1;
      encoder.putElement_rnqfb1_k$('value', JsonPrimitive_0(tmp1_elvis_lhs == null ? descriptor.get_serialName_u2rqhk_k$() : tmp1_elvis_lhs));
    } else {
      var tmp2_elvis_lhs = this.polymorphicSerialName_1;
      encoder.putElement_rnqfb1_k$(discriminator, JsonPrimitive_0(tmp2_elvis_lhs == null ? descriptor.get_serialName_u2rqhk_k$() : tmp2_elvis_lhs));
    }
    this.polymorphicDiscriminator_1 = null;
    this.polymorphicSerialName_1 = null;
  }
  return encoder;
};
protoOf(AbstractJsonTreeEncoder).endEncode_mdsrgg_k$ = function (descriptor) {
  this.nodeConsumer_1(this.getCurrent_z8uawt_k$());
};
function get_requiresTopLevelTag(_this__u8e3s4) {
  var tmp;
  var tmp_0 = _this__u8e3s4.get_kind_wop7ml_k$();
  if (tmp_0 instanceof PrimitiveKind) {
    tmp = true;
  } else {
    tmp = _this__u8e3s4.get_kind_wop7ml_k$() === ENUM_getInstance();
  }
  return tmp;
}
function JsonPrimitiveEncoder(json, nodeConsumer) {
  AbstractJsonTreeEncoder.call(this, json, nodeConsumer);
  this.content_1 = null;
  this.pushTag_bibnaf_k$('primitive');
}
protoOf(JsonPrimitiveEncoder).putElement_rnqfb1_k$ = function (key, element) {
  // Inline function 'kotlin.require' call
  if (!(key === 'primitive')) {
    var message = "This output can only consume primitives with 'primitive' tag";
    throw IllegalArgumentException_init_$Create$(toString(message));
  }
  // Inline function 'kotlin.require' call
  if (!(this.content_1 == null)) {
    var message_0 = 'Primitive element was already recorded. Does call to .encodeXxx happen more than once?';
    throw IllegalArgumentException_init_$Create$(toString(message_0));
  }
  this.content_1 = element;
  this.nodeConsumer_1(element);
};
protoOf(JsonPrimitiveEncoder).getCurrent_z8uawt_k$ = function () {
  var tmp0 = this.content_1;
  var tmp$ret$0;
  $l$block: {
    // Inline function 'kotlin.requireNotNull' call
    if (tmp0 == null) {
      var message = 'Primitive element has not been recorded. Is call to .encodeXxx is missing in serializer?';
      throw IllegalArgumentException_init_$Create$(toString(message));
    } else {
      tmp$ret$0 = tmp0;
      break $l$block;
    }
  }
  return tmp$ret$0;
};
function JsonTreeListEncoder(json, nodeConsumer) {
  AbstractJsonTreeEncoder.call(this, json, nodeConsumer);
  var tmp = this;
  // Inline function 'kotlin.collections.arrayListOf' call
  tmp.array_1 = ArrayList_init_$Create$();
}
protoOf(JsonTreeListEncoder).elementName_p66hrm_k$ = function (descriptor, index) {
  return index.toString();
};
protoOf(JsonTreeListEncoder).putElement_rnqfb1_k$ = function (key, element) {
  var idx = toInt(key);
  this.array_1.add_dl6gt3_k$(idx, element);
};
protoOf(JsonTreeListEncoder).getCurrent_z8uawt_k$ = function () {
  return new JsonArray(this.array_1);
};
function _get_tag__e6h4qf($this) {
  var tmp = $this.tag_1;
  if (!(tmp == null))
    return tmp;
  else {
    throwUninitializedPropertyAccessException('tag');
  }
}
function JsonTreeMapEncoder(json, nodeConsumer) {
  JsonTreeEncoder.call(this, json, nodeConsumer);
  this.isKey_1 = true;
}
protoOf(JsonTreeMapEncoder).putElement_rnqfb1_k$ = function (key, element) {
  if (this.isKey_1) {
    var tmp = this;
    var tmp_0;
    if (element instanceof JsonPrimitive) {
      tmp_0 = element.get_content_h02jrk_k$();
    } else {
      if (element instanceof JsonObject) {
        throw InvalidKeyKindException(JsonObjectSerializer_getInstance().descriptor_1);
      } else {
        if (element instanceof JsonArray) {
          throw InvalidKeyKindException(JsonArraySerializer_getInstance().descriptor_1);
        } else {
          noWhenBranchMatchedException();
        }
      }
    }
    tmp.tag_1 = tmp_0;
    this.isKey_1 = false;
  } else {
    var tmp0 = this.content_1;
    // Inline function 'kotlin.collections.set' call
    var key_0 = _get_tag__e6h4qf(this);
    tmp0.put_4fpzoq_k$(key_0, element);
    this.isKey_1 = true;
  }
};
protoOf(JsonTreeMapEncoder).getCurrent_z8uawt_k$ = function () {
  return new JsonObject(this.content_1);
};
function writeJson$lambda($result) {
  return function (it) {
    $result._v = it;
    return Unit_instance;
  };
}
var static_init_called_0;
function static_init_0() {
  if (static_init_called_0)
    return Unit_instance;
  static_init_called_0 = true;
  WriteMode_OBJ_instance = new WriteMode('OBJ', 0, _Char___init__impl__6a9atx(123), _Char___init__impl__6a9atx(125));
  WriteMode_LIST_instance = new WriteMode('LIST', 1, _Char___init__impl__6a9atx(91), _Char___init__impl__6a9atx(93));
  WriteMode_MAP_instance = new WriteMode('MAP', 2, _Char___init__impl__6a9atx(123), _Char___init__impl__6a9atx(125));
  WriteMode_POLY_OBJ_instance = new WriteMode('POLY_OBJ', 3, _Char___init__impl__6a9atx(91), _Char___init__impl__6a9atx(93));
}
var WriteMode_OBJ_instance;
var WriteMode_LIST_instance;
var WriteMode_MAP_instance;
var WriteMode_POLY_OBJ_instance;
function values() {
  static_init_0();
  return [WriteMode_OBJ_getInstance(), WriteMode_LIST_getInstance(), WriteMode_MAP_getInstance(), WriteMode_POLY_OBJ_getInstance()];
}
function get_entries() {
  static_init_0();
  if ($ENTRIES == null)
    $ENTRIES = enumEntries(values());
  return $ENTRIES;
}
var $ENTRIES;
function WriteMode(name, ordinal, begin, end) {
  Enum.call(this, name, ordinal);
  this.begin_1 = begin;
  this.end_1 = end;
}
function switchMode(_this__u8e3s4, desc) {
  var tmp0_subject = desc.get_kind_wop7ml_k$();
  var tmp;
  if (tmp0_subject instanceof PolymorphicKind) {
    tmp = WriteMode_POLY_OBJ_getInstance();
  } else {
    if (equals(tmp0_subject, LIST_getInstance())) {
      tmp = WriteMode_LIST_getInstance();
    } else {
      if (equals(tmp0_subject, MAP_getInstance())) {
        // Inline function 'kotlinx.serialization.json.internal.selectMapMode' call
        var keyDescriptor = carrierDescriptor(desc.getElementDescriptor_ncda77_k$(0), _this__u8e3s4.get_serializersModule_piitvg_k$());
        var keyKind = keyDescriptor.get_kind_wop7ml_k$();
        var tmp_0;
        var tmp_1;
        if (keyKind instanceof PrimitiveKind) {
          tmp_1 = true;
        } else {
          tmp_1 = equals(keyKind, ENUM_getInstance());
        }
        if (tmp_1) {
          tmp_0 = WriteMode_MAP_getInstance();
        } else {
          if (_this__u8e3s4.configuration_1.allowStructuredMapKeys_1) {
            tmp_0 = WriteMode_LIST_getInstance();
          } else {
            throw InvalidKeyKindException(keyDescriptor);
          }
        }
        tmp = tmp_0;
      } else {
        tmp = WriteMode_OBJ_getInstance();
      }
    }
  }
  return tmp;
}
function carrierDescriptor(_this__u8e3s4, module_0) {
  var tmp;
  if (equals(_this__u8e3s4.get_kind_wop7ml_k$(), CONTEXTUAL_getInstance())) {
    var tmp0_safe_receiver = getContextualDescriptor(module_0, _this__u8e3s4);
    var tmp1_elvis_lhs = tmp0_safe_receiver == null ? null : carrierDescriptor(tmp0_safe_receiver, module_0);
    tmp = tmp1_elvis_lhs == null ? _this__u8e3s4 : tmp1_elvis_lhs;
  } else if (_this__u8e3s4.get_isInline_usk17w_k$()) {
    tmp = carrierDescriptor(_this__u8e3s4.getElementDescriptor_ncda77_k$(0), module_0);
  } else {
    tmp = _this__u8e3s4;
  }
  return tmp;
}
function WriteMode_OBJ_getInstance() {
  static_init_0();
  return WriteMode_OBJ_instance;
}
function WriteMode_LIST_getInstance() {
  static_init_0();
  return WriteMode_LIST_instance;
}
function WriteMode_MAP_getInstance() {
  static_init_0();
  return WriteMode_MAP_instance;
}
function WriteMode_POLY_OBJ_getInstance() {
  static_init_0();
  return WriteMode_POLY_OBJ_instance;
}
function appendEscape($this, lastPosition, current) {
  $this.appendRange_e8o1xp_k$(lastPosition, current);
  return appendEsc($this, current + 1 | 0);
}
function decodedString($this, lastPosition, currentPosition) {
  $this.appendRange_e8o1xp_k$(lastPosition, currentPosition);
  var result = $this.escapedString_1.toString();
  $this.escapedString_1.setLength_oy0ork_k$(0);
  return result;
}
function takePeeked($this) {
  // Inline function 'kotlin.also' call
  var this_0 = ensureNotNull($this.peekedString_1);
  $this.peekedString_1 = null;
  return this_0;
}
function wasUnquotedString($this) {
  return !(charSequenceGet($this.get_source_jl0x7o_k$(), $this.currentPosition_1 - 1 | 0) === _Char___init__impl__6a9atx(34));
}
function appendEsc($this, startPosition) {
  var currentPosition = startPosition;
  currentPosition = $this.prefetchOrEof_k52kdy_k$(currentPosition);
  if (currentPosition === -1) {
    $this.fail$default_ly4hfp_k$('Expected escape sequence to continue, got EOF');
  }
  var tmp = $this.get_source_jl0x7o_k$();
  var _unary__edvuaz = currentPosition;
  currentPosition = _unary__edvuaz + 1 | 0;
  var currentChar = charSequenceGet(tmp, _unary__edvuaz);
  if (currentChar === _Char___init__impl__6a9atx(117)) {
    return appendHex($this, $this.get_source_jl0x7o_k$(), currentPosition);
  }
  // Inline function 'kotlin.code' call
  var tmp$ret$0 = Char__toInt_impl_vasixd(currentChar);
  var c = escapeToChar(tmp$ret$0);
  if (c === _Char___init__impl__6a9atx(0)) {
    $this.fail$default_ly4hfp_k$("Invalid escaped char '" + toString_1(currentChar) + "'");
  }
  $this.escapedString_1.append_58al37_k$(c);
  return currentPosition;
}
function appendHex($this, source, startPos) {
  if ((startPos + 4 | 0) >= charSequenceLength(source)) {
    $this.currentPosition_1 = startPos;
    $this.ensureHaveChars_2p1sdj_k$();
    if (($this.currentPosition_1 + 4 | 0) >= charSequenceLength(source)) {
      $this.fail$default_ly4hfp_k$('Unexpected EOF during unicode escape');
    }
    return appendHex($this, source, $this.currentPosition_1);
  }
  $this.escapedString_1.append_58al37_k$(numberToChar((((fromHexChar($this, source, startPos) << 12) + (fromHexChar($this, source, startPos + 1 | 0) << 8) | 0) + (fromHexChar($this, source, startPos + 2 | 0) << 4) | 0) + fromHexChar($this, source, startPos + 3 | 0) | 0));
  return startPos + 4 | 0;
}
function fromHexChar($this, source, currentPosition) {
  var character = charSequenceGet(source, currentPosition);
  var tmp;
  if (_Char___init__impl__6a9atx(48) <= character ? character <= _Char___init__impl__6a9atx(57) : false) {
    // Inline function 'kotlin.code' call
    var tmp_0 = Char__toInt_impl_vasixd(character);
    // Inline function 'kotlin.code' call
    var this_0 = _Char___init__impl__6a9atx(48);
    tmp = tmp_0 - Char__toInt_impl_vasixd(this_0) | 0;
  } else if (_Char___init__impl__6a9atx(97) <= character ? character <= _Char___init__impl__6a9atx(102) : false) {
    // Inline function 'kotlin.code' call
    var tmp_1 = Char__toInt_impl_vasixd(character);
    // Inline function 'kotlin.code' call
    var this_1 = _Char___init__impl__6a9atx(97);
    tmp = (tmp_1 - Char__toInt_impl_vasixd(this_1) | 0) + 10 | 0;
  } else if (_Char___init__impl__6a9atx(65) <= character ? character <= _Char___init__impl__6a9atx(70) : false) {
    // Inline function 'kotlin.code' call
    var tmp_2 = Char__toInt_impl_vasixd(character);
    // Inline function 'kotlin.code' call
    var this_2 = _Char___init__impl__6a9atx(65);
    tmp = (tmp_2 - Char__toInt_impl_vasixd(this_2) | 0) + 10 | 0;
  } else {
    $this.fail$default_ly4hfp_k$("Invalid toHexChar char '" + toString_1(character) + "' in unicode escape");
  }
  return tmp;
}
function consumeBoolean2($this, start) {
  var current = $this.prefetchOrEof_k52kdy_k$(start);
  if (current >= charSequenceLength($this.get_source_jl0x7o_k$()) || current === -1) {
    $this.fail$default_ly4hfp_k$('EOF');
  }
  var tmp = $this.get_source_jl0x7o_k$();
  var _unary__edvuaz = current;
  current = _unary__edvuaz + 1 | 0;
  // Inline function 'kotlin.code' call
  var this_0 = charSequenceGet(tmp, _unary__edvuaz);
  var tmp0_subject = Char__toInt_impl_vasixd(this_0) | 32;
  var tmp_0;
  // Inline function 'kotlin.code' call
  var this_1 = _Char___init__impl__6a9atx(116);
  if (tmp0_subject === Char__toInt_impl_vasixd(this_1)) {
    consumeBooleanLiteral($this, 'rue', current);
    tmp_0 = true;
  } else {
    // Inline function 'kotlin.code' call
    var this_2 = _Char___init__impl__6a9atx(102);
    if (tmp0_subject === Char__toInt_impl_vasixd(this_2)) {
      consumeBooleanLiteral($this, 'alse', current);
      tmp_0 = false;
    } else {
      $this.fail$default_ly4hfp_k$("Expected valid boolean literal prefix, but had '" + $this.consumeStringLenient_9oypvu_k$() + "'");
    }
  }
  return tmp_0;
}
function consumeBooleanLiteral($this, literalSuffix, current) {
  if ((charSequenceLength($this.get_source_jl0x7o_k$()) - current | 0) < literalSuffix.length) {
    $this.fail$default_ly4hfp_k$('Unexpected end of boolean literal');
  }
  var inductionVariable = 0;
  var last = charSequenceLength(literalSuffix) - 1 | 0;
  if (inductionVariable <= last)
    do {
      var i = inductionVariable;
      inductionVariable = inductionVariable + 1 | 0;
      var expected = charCodeAt(literalSuffix, i);
      var actual = charSequenceGet($this.get_source_jl0x7o_k$(), current + i | 0);
      // Inline function 'kotlin.code' call
      var tmp = Char__toInt_impl_vasixd(expected);
      // Inline function 'kotlin.code' call
      if (!(tmp === (Char__toInt_impl_vasixd(actual) | 32))) {
        $this.fail$default_ly4hfp_k$("Expected valid boolean literal prefix, but had '" + $this.consumeStringLenient_9oypvu_k$() + "'");
      }
    }
     while (inductionVariable <= last);
  $this.currentPosition_1 = current + literalSuffix.length | 0;
}
function consumeNumericLiteral$calculateExponent(exponentAccumulator, isExponentPositive) {
  var tmp;
  switch (isExponentPositive) {
    case false:
      // Inline function 'kotlin.math.pow' call

      var x = -toNumber(exponentAccumulator);
      tmp = Math.pow(10.0, x);
      break;
    case true:
      // Inline function 'kotlin.math.pow' call

      var x_0 = toNumber(exponentAccumulator);
      tmp = Math.pow(10.0, x_0);
      break;
    default:
      noWhenBranchMatchedException();
      break;
  }
  return tmp;
}
function AbstractJsonLexer(configuration) {
  this.configuration_1 = configuration;
  this.currentPosition_1 = 0;
  this.path_1 = new JsonPath(this.configuration_1);
  this.peekedString_1 = null;
  this.escapedString_1 = StringBuilder_init_$Create$();
}
protoOf(AbstractJsonLexer).ensureHaveChars_2p1sdj_k$ = function () {
};
protoOf(AbstractJsonLexer).tryConsumeComma_9n2ve4_k$ = function () {
  var current = this.skipWhitespaces_ox013r_k$();
  var source = this.get_source_jl0x7o_k$();
  if (current >= charSequenceLength(source) || current === -1)
    return false;
  if (charSequenceGet(source, current) === _Char___init__impl__6a9atx(44)) {
    this.currentPosition_1 = this.currentPosition_1 + 1 | 0;
    return true;
  }
  return false;
};
protoOf(AbstractJsonLexer).isValidValueStart_kdgv46_k$ = function (c) {
  return c === _Char___init__impl__6a9atx(125) || c === _Char___init__impl__6a9atx(93) || (c === _Char___init__impl__6a9atx(58) || c === _Char___init__impl__6a9atx(44)) ? false : true;
};
protoOf(AbstractJsonLexer).expectEof_2xwqoj_k$ = function () {
  var nextToken = this.consumeNextToken_uf1vsa_k$();
  if (!(nextToken === 10)) {
    this.fail$default_ly4hfp_k$('Expected EOF after parsing, but had ' + toString_1(charSequenceGet(this.get_source_jl0x7o_k$(), this.currentPosition_1 - 1 | 0)) + ' instead');
  }
};
protoOf(AbstractJsonLexer).consumeNextToken_dugwfi_k$ = function (expected) {
  var token = this.consumeNextToken_uf1vsa_k$();
  if (!(token === expected)) {
    // Inline function 'kotlinx.serialization.json.internal.AbstractJsonLexer.fail' call
    var expected_0 = tokenDescription(expected);
    var position = true && this.currentPosition_1 > 0 ? this.currentPosition_1 - 1 | 0 : this.currentPosition_1;
    var s = this.currentPosition_1 === charSequenceLength(this.get_source_jl0x7o_k$()) || position < 0 ? 'EOF' : toString_1(charSequenceGet(this.get_source_jl0x7o_k$(), position));
    var tmp$ret$1 = 'Expected ' + expected_0 + ", but had '" + s + "' instead";
    this.fail$default_ly4hfp_k$(tmp$ret$1, position);
  }
  return token;
};
protoOf(AbstractJsonLexer).unexpectedToken_u9lezp_k$ = function (expected) {
  if (this.currentPosition_1 > 0 && expected === _Char___init__impl__6a9atx(34)) {
    var tmp$ret$0;
    $l$block: {
      // Inline function 'kotlinx.serialization.json.internal.AbstractJsonLexer.withPositionRollback' call
      var snapshot = this.currentPosition_1;
      try {
        this.currentPosition_1 = this.currentPosition_1 - 1 | 0;
        tmp$ret$0 = this.consumeStringLenient_9oypvu_k$();
        break $l$block;
      }finally {
        this.currentPosition_1 = snapshot;
      }
    }
    var inputLiteral = tmp$ret$0;
    if (inputLiteral === 'null') {
      this.fail_5m5zd7_k$("Expected string literal but 'null' literal was found", this.currentPosition_1 - 1 | 0, "Use 'coerceInputValues = true' in 'Json {}' builder to coerce nulls if property has a default value.");
    }
  }
  // Inline function 'kotlinx.serialization.json.internal.AbstractJsonLexer.fail' call
  var expectedToken = charToTokenClass(expected);
  var expected_0 = tokenDescription(expectedToken);
  var position = true && this.currentPosition_1 > 0 ? this.currentPosition_1 - 1 | 0 : this.currentPosition_1;
  var s = this.currentPosition_1 === charSequenceLength(this.get_source_jl0x7o_k$()) || position < 0 ? 'EOF' : toString_1(charSequenceGet(this.get_source_jl0x7o_k$(), position));
  var tmp$ret$3 = 'Expected ' + expected_0 + ", but had '" + s + "' instead";
  this.fail$default_ly4hfp_k$(tmp$ret$3, position);
};
protoOf(AbstractJsonLexer).peekNextToken_1gqwr9_k$ = function () {
  var source = this.get_source_jl0x7o_k$();
  var cpos = this.currentPosition_1;
  $l$loop_0: while (true) {
    cpos = this.prefetchOrEof_k52kdy_k$(cpos);
    if (cpos === -1)
      break $l$loop_0;
    var ch = charSequenceGet(source, cpos);
    if (ch === _Char___init__impl__6a9atx(32) || ch === _Char___init__impl__6a9atx(10) || ch === _Char___init__impl__6a9atx(13) || ch === _Char___init__impl__6a9atx(9)) {
      cpos = cpos + 1 | 0;
      continue $l$loop_0;
    }
    this.currentPosition_1 = cpos;
    return charToTokenClass(ch);
  }
  this.currentPosition_1 = cpos;
  return 10;
};
protoOf(AbstractJsonLexer).tryConsumeNull_2shltp_k$ = function (doConsume) {
  var current = this.skipWhitespaces_ox013r_k$();
  current = this.prefetchOrEof_k52kdy_k$(current);
  var len = charSequenceLength(this.get_source_jl0x7o_k$()) - current | 0;
  if (len < 4 || current === -1)
    return false;
  var inductionVariable = 0;
  if (inductionVariable <= 3)
    do {
      var i = inductionVariable;
      inductionVariable = inductionVariable + 1 | 0;
      if (!(charCodeAt('null', i) === charSequenceGet(this.get_source_jl0x7o_k$(), current + i | 0)))
        return false;
    }
     while (inductionVariable <= 3);
  if (len > 4 && charToTokenClass(charSequenceGet(this.get_source_jl0x7o_k$(), current + 4 | 0)) === 0)
    return false;
  if (doConsume) {
    this.currentPosition_1 = current + 4 | 0;
  }
  return true;
};
protoOf(AbstractJsonLexer).tryConsumeNull$default_t5tauc_k$ = function (doConsume, $super) {
  doConsume = doConsume === VOID ? true : doConsume;
  return $super === VOID ? this.tryConsumeNull_2shltp_k$(doConsume) : $super.tryConsumeNull_2shltp_k$.call(this, doConsume);
};
protoOf(AbstractJsonLexer).peekString_d4c947_k$ = function (isLenient) {
  var token = this.peekNextToken_1gqwr9_k$();
  var tmp;
  if (isLenient) {
    if (!(token === 1) && !(token === 0))
      return null;
    tmp = this.consumeStringLenient_9oypvu_k$();
  } else {
    if (!(token === 1))
      return null;
    tmp = this.consumeString_j3j2z7_k$();
  }
  var string = tmp;
  this.peekedString_1 = string;
  return string;
};
protoOf(AbstractJsonLexer).discardPeeked_n23g48_k$ = function () {
  this.peekedString_1 = null;
};
protoOf(AbstractJsonLexer).substring_d7lab3_k$ = function (startPos, endPos) {
  // Inline function 'kotlin.text.substring' call
  var this_0 = this.get_source_jl0x7o_k$();
  return toString(charSequenceSubSequence(this_0, startPos, endPos));
};
protoOf(AbstractJsonLexer).consumeString_j3j2z7_k$ = function () {
  if (!(this.peekedString_1 == null)) {
    return takePeeked(this);
  }
  return this.consumeKeyString_mfa3ws_k$();
};
protoOf(AbstractJsonLexer).consumeString2 = function (source, startPosition, current) {
  var currentPosition = current;
  var lastPosition = startPosition;
  var char = charSequenceGet(source, currentPosition);
  var usedAppend = false;
  while (!(char === _Char___init__impl__6a9atx(34))) {
    if (char === _Char___init__impl__6a9atx(92)) {
      usedAppend = true;
      currentPosition = this.prefetchOrEof_k52kdy_k$(appendEscape(this, lastPosition, currentPosition));
      if (currentPosition === -1) {
        this.fail$default_ly4hfp_k$('Unexpected EOF', currentPosition);
      }
      lastPosition = currentPosition;
    } else {
      currentPosition = currentPosition + 1 | 0;
      if (currentPosition >= charSequenceLength(source)) {
        usedAppend = true;
        this.appendRange_e8o1xp_k$(lastPosition, currentPosition);
        currentPosition = this.prefetchOrEof_k52kdy_k$(currentPosition);
        if (currentPosition === -1) {
          this.fail$default_ly4hfp_k$('Unexpected EOF', currentPosition);
        }
        lastPosition = currentPosition;
      }
    }
    char = charSequenceGet(source, currentPosition);
  }
  var tmp;
  if (!usedAppend) {
    tmp = this.substring_d7lab3_k$(lastPosition, currentPosition);
  } else {
    tmp = decodedString(this, lastPosition, currentPosition);
  }
  var string = tmp;
  this.currentPosition_1 = currentPosition + 1 | 0;
  return string;
};
protoOf(AbstractJsonLexer).consumeStringLenientNotNull_m2rgts_k$ = function () {
  var result = this.consumeStringLenient_9oypvu_k$();
  if (result === 'null' && wasUnquotedString(this)) {
    this.fail$default_ly4hfp_k$("Unexpected 'null' value instead of string literal");
  }
  return result;
};
protoOf(AbstractJsonLexer).consumeStringLenient_9oypvu_k$ = function () {
  if (!(this.peekedString_1 == null)) {
    return takePeeked(this);
  }
  var current = this.skipWhitespaces_ox013r_k$();
  if (current >= charSequenceLength(this.get_source_jl0x7o_k$()) || current === -1) {
    this.fail$default_ly4hfp_k$('EOF', current);
  }
  var token = charToTokenClass(charSequenceGet(this.get_source_jl0x7o_k$(), current));
  if (token === 1) {
    return this.consumeString_j3j2z7_k$();
  }
  if (!(token === 0)) {
    this.fail$default_ly4hfp_k$('Expected beginning of the string, but got ' + toString_1(charSequenceGet(this.get_source_jl0x7o_k$(), current)));
  }
  var usedAppend = false;
  while (charToTokenClass(charSequenceGet(this.get_source_jl0x7o_k$(), current)) === 0) {
    current = current + 1 | 0;
    if (current >= charSequenceLength(this.get_source_jl0x7o_k$())) {
      usedAppend = true;
      this.appendRange_e8o1xp_k$(this.currentPosition_1, current);
      var eof = this.prefetchOrEof_k52kdy_k$(current);
      if (eof === -1) {
        this.currentPosition_1 = current;
        return decodedString(this, 0, 0);
      } else {
        current = eof;
      }
    }
  }
  var tmp;
  if (!usedAppend) {
    tmp = this.substring_d7lab3_k$(this.currentPosition_1, current);
  } else {
    tmp = decodedString(this, this.currentPosition_1, current);
  }
  var result = tmp;
  this.currentPosition_1 = current;
  return result;
};
protoOf(AbstractJsonLexer).appendRange_e8o1xp_k$ = function (fromIndex, toIndex) {
  this.escapedString_1.append_xdc1zw_k$(this.get_source_jl0x7o_k$(), fromIndex, toIndex);
};
protoOf(AbstractJsonLexer).skipElement_eq7t4_k$ = function (allowLenientStrings) {
  // Inline function 'kotlin.collections.mutableListOf' call
  var tokenStack = ArrayList_init_$Create$();
  var lastToken = this.peekNextToken_1gqwr9_k$();
  if (!(lastToken === 8) && !(lastToken === 6)) {
    this.consumeStringLenient_9oypvu_k$();
    return Unit_instance;
  }
  $l$loop: while (true) {
    lastToken = this.peekNextToken_1gqwr9_k$();
    if (lastToken === 1) {
      if (allowLenientStrings)
        this.consumeStringLenient_9oypvu_k$();
      else
        this.consumeKeyString_mfa3ws_k$();
      continue $l$loop;
    }
    var tmp0_subject = lastToken;
    if (tmp0_subject === 8 || tmp0_subject === 6) {
      tokenStack.add_utx5q5_k$(lastToken);
    } else if (tmp0_subject === 9) {
      if (!(last(tokenStack) === 8)) {
        this.fail$default_ly4hfp_k$('found ] instead of }');
      }
      removeLast(tokenStack);
    } else if (tmp0_subject === 7) {
      if (!(last(tokenStack) === 6)) {
        this.fail$default_ly4hfp_k$('found } instead of ]');
      }
      removeLast(tokenStack);
    } else if (tmp0_subject === 10) {
      this.fail$default_ly4hfp_k$('Unexpected end of input due to malformed JSON during ignoring unknown keys');
    }
    this.consumeNextToken_uf1vsa_k$();
    if (tokenStack.get_size_woubt6_k$() === 0)
      return Unit_instance;
  }
};
protoOf(AbstractJsonLexer).toString = function () {
  return "JsonReader(source='" + toString(this.get_source_jl0x7o_k$()) + "', currentPosition=" + this.currentPosition_1 + ')';
};
protoOf(AbstractJsonLexer).failOnUnknownKey_g0pqrs_k$ = function (key) {
  var processed = this.substring_d7lab3_k$(0, this.currentPosition_1);
  var lastIndexOf_0 = lastIndexOf(processed, key);
  this.fail_5m5zd7_k$("Encountered an unknown key '" + key + "'", lastIndexOf_0, "Use 'ignoreUnknownKeys = true' in 'Json {}' builder or '@JsonIgnoreUnknownKeys' annotation to ignore unknown keys.");
};
protoOf(AbstractJsonLexer).fail_5m5zd7_k$ = function (message, position, hint) {
  throw decodingExceptionOf_0(this, message, position, this.path_1.getPath_18su3p_k$(), hint, this.get_source_jl0x7o_k$());
};
protoOf(AbstractJsonLexer).fail$default_ly4hfp_k$ = function (message, position, hint, $super) {
  position = position === VOID ? this.currentPosition_1 : position;
  hint = hint === VOID ? null : hint;
  return $super === VOID ? this.fail_5m5zd7_k$(message, position, hint) : $super.fail_5m5zd7_k$.call(this, message, position, hint);
};
protoOf(AbstractJsonLexer).consumeNumericLiteral_rdea66_k$ = function () {
  var current = this.skipWhitespaces_ox013r_k$();
  current = this.prefetchOrEof_k52kdy_k$(current);
  if (current >= charSequenceLength(this.get_source_jl0x7o_k$()) || current === -1) {
    this.fail$default_ly4hfp_k$('EOF');
  }
  var tmp;
  if (charSequenceGet(this.get_source_jl0x7o_k$(), current) === _Char___init__impl__6a9atx(34)) {
    current = current + 1 | 0;
    if (current === charSequenceLength(this.get_source_jl0x7o_k$())) {
      this.fail$default_ly4hfp_k$('EOF');
    }
    tmp = true;
  } else {
    tmp = false;
  }
  var hasQuotation = tmp;
  var accumulator = new Long(0, 0);
  var exponentAccumulator = new Long(0, 0);
  var isNegative = false;
  var isExponentPositive = false;
  var hasExponent = false;
  var start = current;
  $l$loop_4: while (!(current === charSequenceLength(this.get_source_jl0x7o_k$()))) {
    var ch = charSequenceGet(this.get_source_jl0x7o_k$(), current);
    if ((ch === _Char___init__impl__6a9atx(101) || ch === _Char___init__impl__6a9atx(69)) && !hasExponent) {
      if (current === start) {
        this.fail$default_ly4hfp_k$("Unexpected symbol '" + toString_1(ch) + "' in numeric literal", current);
      }
      isExponentPositive = true;
      hasExponent = true;
      current = current + 1 | 0;
      continue $l$loop_4;
    }
    if (ch === _Char___init__impl__6a9atx(45) && hasExponent) {
      if (current === start) {
        this.fail$default_ly4hfp_k$("Unexpected symbol '-' in numeric literal", current);
      }
      isExponentPositive = false;
      current = current + 1 | 0;
      continue $l$loop_4;
    }
    if (ch === _Char___init__impl__6a9atx(43) && hasExponent) {
      if (current === start) {
        this.fail$default_ly4hfp_k$("Unexpected symbol '+' in numeric literal", current);
      }
      isExponentPositive = true;
      current = current + 1 | 0;
      continue $l$loop_4;
    }
    if (ch === _Char___init__impl__6a9atx(45)) {
      if (!(current === start)) {
        this.fail$default_ly4hfp_k$("Unexpected symbol '-' in numeric literal", current);
      }
      isNegative = true;
      current = current + 1 | 0;
      continue $l$loop_4;
    }
    var token = charToTokenClass(ch);
    if (!(token === 0))
      break $l$loop_4;
    current = current + 1 | 0;
    var digit = Char__minus_impl_a2frrh(ch, _Char___init__impl__6a9atx(48));
    if (!(0 <= digit ? digit <= 9 : false)) {
      this.fail$default_ly4hfp_k$("Unexpected symbol '" + toString_1(ch) + "' in numeric literal", current - 1 | 0);
    }
    if (hasExponent) {
      // Inline function 'kotlin.Long.times' call
      var this_0 = exponentAccumulator;
      // Inline function 'kotlin.Long.plus' call
      var this_1 = multiply(this_0, fromInt(10));
      exponentAccumulator = add(this_1, fromInt(digit));
      continue $l$loop_4;
    }
    // Inline function 'kotlin.Long.times' call
    var this_2 = accumulator;
    // Inline function 'kotlin.Long.minus' call
    var this_3 = multiply(this_2, fromInt(10));
    accumulator = subtract(this_3, fromInt(digit));
    if (compare(accumulator, new Long(0, 0)) > 0) {
      this.fail$default_ly4hfp_k$('Numeric value overflow');
    }
  }
  var hasChars = !(current === start);
  if (start === current || (isNegative && start === (current - 1 | 0))) {
    this.fail$default_ly4hfp_k$('Expected numeric literal', current);
  }
  if (hasQuotation) {
    if (!hasChars) {
      this.fail$default_ly4hfp_k$('EOF');
    }
    if (!(charSequenceGet(this.get_source_jl0x7o_k$(), current) === _Char___init__impl__6a9atx(34))) {
      this.fail$default_ly4hfp_k$('Expected closing quotation mark', current);
    }
    current = current + 1 | 0;
  }
  this.currentPosition_1 = current;
  if (hasExponent) {
    var doubleAccumulator = toNumber(accumulator) * consumeNumericLiteral$calculateExponent(exponentAccumulator, isExponentPositive);
    if (doubleAccumulator > toNumber(new Long(-1, 2147483647)) || doubleAccumulator < toNumber(new Long(0, -2147483648))) {
      this.fail$default_ly4hfp_k$('Numeric value overflow');
    }
    // Inline function 'kotlin.math.floor' call
    if (!(Math.floor(doubleAccumulator) === doubleAccumulator)) {
      this.fail$default_ly4hfp_k$("Can't convert " + doubleAccumulator + ' to Long');
    }
    accumulator = numberToLong(doubleAccumulator);
  }
  var tmp_0;
  if (isNegative) {
    tmp_0 = accumulator;
  } else if (!equalsLong(accumulator, new Long(0, -2147483648))) {
    tmp_0 = negate(accumulator);
  } else {
    this.fail$default_ly4hfp_k$('Numeric value overflow');
  }
  return tmp_0;
};
protoOf(AbstractJsonLexer).consumeNumericLiteralFully_dgqq24_k$ = function () {
  var result = this.consumeNumericLiteral_rdea66_k$();
  var next = this.consumeNextToken_uf1vsa_k$();
  if (!(next === 10)) {
    // Inline function 'kotlinx.serialization.json.internal.AbstractJsonLexer.fail' call
    var expected = tokenDescription(10);
    var position = true && this.currentPosition_1 > 0 ? this.currentPosition_1 - 1 | 0 : this.currentPosition_1;
    var s = this.currentPosition_1 === charSequenceLength(this.get_source_jl0x7o_k$()) || position < 0 ? 'EOF' : toString_1(charSequenceGet(this.get_source_jl0x7o_k$(), position));
    var tmp$ret$1 = "Expected input to contain a single valid number, but got '" + s + "' after it";
    this.fail$default_ly4hfp_k$(tmp$ret$1, position);
  }
  return result;
};
protoOf(AbstractJsonLexer).consumeBooleanLenient_iqeqb9_k$ = function () {
  var current = this.skipWhitespaces_ox013r_k$();
  if (current === charSequenceLength(this.get_source_jl0x7o_k$())) {
    this.fail$default_ly4hfp_k$('EOF');
  }
  var tmp;
  if (charSequenceGet(this.get_source_jl0x7o_k$(), current) === _Char___init__impl__6a9atx(34)) {
    current = current + 1 | 0;
    tmp = true;
  } else {
    tmp = false;
  }
  var hasQuotation = tmp;
  var result = consumeBoolean2(this, current);
  if (hasQuotation) {
    if (this.currentPosition_1 === charSequenceLength(this.get_source_jl0x7o_k$())) {
      this.fail$default_ly4hfp_k$('EOF');
    }
    if (!(charSequenceGet(this.get_source_jl0x7o_k$(), this.currentPosition_1) === _Char___init__impl__6a9atx(34))) {
      this.fail$default_ly4hfp_k$('Expected closing quotation mark');
    }
    this.currentPosition_1 = this.currentPosition_1 + 1 | 0;
  }
  return result;
};
function charToTokenClass(c) {
  var tmp;
  // Inline function 'kotlin.code' call
  if (Char__toInt_impl_vasixd(c) < 126) {
    var tmp_0 = CharMappings_getInstance().CHAR_TO_TOKEN_1;
    // Inline function 'kotlin.code' call
    tmp = tmp_0[Char__toInt_impl_vasixd(c)];
  } else {
    tmp = 0;
  }
  return tmp;
}
function tokenDescription(token) {
  return token === 1 ? "quotation mark '\"'" : token === 2 ? "string escape sequence '\\'" : token === 4 ? "comma ','" : token === 5 ? "colon ':'" : token === 6 ? "start of the object '{'" : token === 7 ? "end of the object '}'" : token === 8 ? "start of the array '['" : token === 9 ? "end of the array ']'" : token === 10 ? 'end of the input' : token === 127 ? 'invalid token' : 'valid token';
}
function escapeToChar(c) {
  return c < 117 ? CharMappings_getInstance().ESCAPE_2_CHAR_1[c] : _Char___init__impl__6a9atx(0);
}
function initEscape($this) {
  var inductionVariable = 0;
  if (inductionVariable <= 31)
    do {
      var i = inductionVariable;
      inductionVariable = inductionVariable + 1 | 0;
      initC2ESC($this, i, _Char___init__impl__6a9atx(117));
    }
     while (inductionVariable <= 31);
  initC2ESC($this, 8, _Char___init__impl__6a9atx(98));
  initC2ESC($this, 9, _Char___init__impl__6a9atx(116));
  initC2ESC($this, 10, _Char___init__impl__6a9atx(110));
  initC2ESC($this, 12, _Char___init__impl__6a9atx(102));
  initC2ESC($this, 13, _Char___init__impl__6a9atx(114));
  initC2ESC_0($this, _Char___init__impl__6a9atx(47), _Char___init__impl__6a9atx(47));
  initC2ESC_0($this, _Char___init__impl__6a9atx(34), _Char___init__impl__6a9atx(34));
  initC2ESC_0($this, _Char___init__impl__6a9atx(92), _Char___init__impl__6a9atx(92));
}
function initCharToToken($this) {
  var inductionVariable = 0;
  if (inductionVariable <= 32)
    do {
      var i = inductionVariable;
      inductionVariable = inductionVariable + 1 | 0;
      initC2TC($this, i, 127);
    }
     while (inductionVariable <= 32);
  initC2TC($this, 9, 3);
  initC2TC($this, 10, 3);
  initC2TC($this, 13, 3);
  initC2TC($this, 32, 3);
  initC2TC_0($this, _Char___init__impl__6a9atx(44), 4);
  initC2TC_0($this, _Char___init__impl__6a9atx(58), 5);
  initC2TC_0($this, _Char___init__impl__6a9atx(123), 6);
  initC2TC_0($this, _Char___init__impl__6a9atx(125), 7);
  initC2TC_0($this, _Char___init__impl__6a9atx(91), 8);
  initC2TC_0($this, _Char___init__impl__6a9atx(93), 9);
  initC2TC_0($this, _Char___init__impl__6a9atx(34), 1);
  initC2TC_0($this, _Char___init__impl__6a9atx(92), 2);
}
function initC2ESC($this, c, esc) {
  if (!(esc === _Char___init__impl__6a9atx(117))) {
    // Inline function 'kotlin.code' call
    var tmp$ret$0 = Char__toInt_impl_vasixd(esc);
    $this.ESCAPE_2_CHAR_1[tmp$ret$0] = numberToChar(c);
  }
}
function initC2ESC_0($this, c, esc) {
  // Inline function 'kotlin.code' call
  var tmp$ret$0 = Char__toInt_impl_vasixd(c);
  return initC2ESC($this, tmp$ret$0, esc);
}
function initC2TC($this, c, cl) {
  $this.CHAR_TO_TOKEN_1[c] = cl;
}
function initC2TC_0($this, c, cl) {
  // Inline function 'kotlin.code' call
  var tmp$ret$0 = Char__toInt_impl_vasixd(c);
  return initC2TC($this, tmp$ret$0, cl);
}
function CharMappings() {
  CharMappings_instance = this;
  this.ESCAPE_2_CHAR_1 = charArray(117);
  this.CHAR_TO_TOKEN_1 = new Int8Array(126);
  initEscape(this);
  initCharToToken(this);
}
var CharMappings_instance;
function CharMappings_getInstance() {
  if (CharMappings_instance == null)
    new CharMappings();
  return CharMappings_instance;
}
function StringJsonLexerWithComments(source, configuration) {
  StringJsonLexer.call(this, source, configuration);
}
protoOf(StringJsonLexerWithComments).consumeNextToken_uf1vsa_k$ = function () {
  var source = this.get_source_jl0x7o_k$();
  var cpos = this.skipWhitespaces_ox013r_k$();
  if (cpos >= source.length || cpos === -1)
    return 10;
  this.currentPosition_1 = cpos + 1 | 0;
  return charToTokenClass(charCodeAt(source, cpos));
};
protoOf(StringJsonLexerWithComments).canConsumeValue_oljqd7_k$ = function () {
  var current = this.skipWhitespaces_ox013r_k$();
  if (current >= this.get_source_jl0x7o_k$().length || current === -1)
    return false;
  return this.isValidValueStart_kdgv46_k$(charCodeAt(this.get_source_jl0x7o_k$(), current));
};
protoOf(StringJsonLexerWithComments).consumeNextToken_kteg6b_k$ = function (expected) {
  var source = this.get_source_jl0x7o_k$();
  var current = this.skipWhitespaces_ox013r_k$();
  if (current >= source.length || current === -1) {
    this.currentPosition_1 = -1;
    this.unexpectedToken_u9lezp_k$(expected);
  }
  var c = charCodeAt(source, current);
  this.currentPosition_1 = current + 1 | 0;
  if (c === expected)
    return Unit_instance;
  else {
    this.unexpectedToken_u9lezp_k$(expected);
  }
};
protoOf(StringJsonLexerWithComments).peekNextToken_1gqwr9_k$ = function () {
  var source = this.get_source_jl0x7o_k$();
  var cpos = this.skipWhitespaces_ox013r_k$();
  if (cpos >= source.length || cpos === -1)
    return 10;
  this.currentPosition_1 = cpos;
  return charToTokenClass(charCodeAt(source, cpos));
};
protoOf(StringJsonLexerWithComments).skipWhitespaces_ox013r_k$ = function () {
  var current = this.currentPosition_1;
  if (current === -1)
    return current;
  var source = this.get_source_jl0x7o_k$();
  $l$loop_1: while (current < source.length) {
    var c = charCodeAt(source, current);
    // Inline function 'kotlinx.serialization.json.internal.AbstractJsonLexer.isWs' call
    if (c === _Char___init__impl__6a9atx(32) || c === _Char___init__impl__6a9atx(10) || c === _Char___init__impl__6a9atx(13) || c === _Char___init__impl__6a9atx(9)) {
      current = current + 1 | 0;
      continue $l$loop_1;
    }
    if (c === _Char___init__impl__6a9atx(47) && (current + 1 | 0) < source.length) {
      var tmp0_subject = charCodeAt(source, current + 1 | 0);
      if (tmp0_subject === _Char___init__impl__6a9atx(47)) {
        current = indexOf_0(source, _Char___init__impl__6a9atx(10), current + 2 | 0);
        if (current === -1) {
          current = source.length;
        } else {
          current = current + 1 | 0;
        }
        continue $l$loop_1;
      } else if (tmp0_subject === _Char___init__impl__6a9atx(42)) {
        current = indexOf(source, '*/', current + 2 | 0);
        if (current === -1) {
          this.currentPosition_1 = source.length;
          this.fail$default_ly4hfp_k$('Expected end of the block comment: "*/", but had EOF instead');
        } else {
          current = current + 2 | 0;
        }
        continue $l$loop_1;
      }
    }
    break $l$loop_1;
  }
  this.currentPosition_1 = current;
  return current;
};
function StringJsonLexer(source, configuration) {
  AbstractJsonLexer.call(this, configuration);
  this.source_1 = source;
}
protoOf(StringJsonLexer).get_source_jl0x7o_k$ = function () {
  return this.source_1;
};
protoOf(StringJsonLexer).prefetchOrEof_k52kdy_k$ = function (position) {
  return position < this.get_source_jl0x7o_k$().length ? position : -1;
};
protoOf(StringJsonLexer).consumeNextToken_uf1vsa_k$ = function () {
  var source = this.get_source_jl0x7o_k$();
  var cpos = this.currentPosition_1;
  $l$loop: while (!(cpos === -1) && cpos < source.length) {
    var _unary__edvuaz = cpos;
    cpos = _unary__edvuaz + 1 | 0;
    var c = charCodeAt(source, _unary__edvuaz);
    // Inline function 'kotlinx.serialization.json.internal.AbstractJsonLexer.isWs' call
    if (c === _Char___init__impl__6a9atx(32) || c === _Char___init__impl__6a9atx(10) || c === _Char___init__impl__6a9atx(13) || c === _Char___init__impl__6a9atx(9))
      continue $l$loop;
    this.currentPosition_1 = cpos;
    return charToTokenClass(c);
  }
  this.currentPosition_1 = source.length;
  return 10;
};
protoOf(StringJsonLexer).canConsumeValue_oljqd7_k$ = function () {
  var current = this.currentPosition_1;
  if (current === -1)
    return false;
  var source = this.get_source_jl0x7o_k$();
  $l$loop: while (current < source.length) {
    var c = charCodeAt(source, current);
    // Inline function 'kotlinx.serialization.json.internal.AbstractJsonLexer.isWs' call
    if (c === _Char___init__impl__6a9atx(32) || c === _Char___init__impl__6a9atx(10) || c === _Char___init__impl__6a9atx(13) || c === _Char___init__impl__6a9atx(9)) {
      current = current + 1 | 0;
      continue $l$loop;
    }
    this.currentPosition_1 = current;
    return this.isValidValueStart_kdgv46_k$(c);
  }
  this.currentPosition_1 = current;
  return false;
};
protoOf(StringJsonLexer).skipWhitespaces_ox013r_k$ = function () {
  var current = this.currentPosition_1;
  if (current === -1)
    return current;
  var source = this.get_source_jl0x7o_k$();
  $l$loop: while (current < source.length) {
    var c = charCodeAt(source, current);
    // Inline function 'kotlinx.serialization.json.internal.AbstractJsonLexer.isWs' call
    if (c === _Char___init__impl__6a9atx(32) || c === _Char___init__impl__6a9atx(10) || c === _Char___init__impl__6a9atx(13) || c === _Char___init__impl__6a9atx(9)) {
      current = current + 1 | 0;
    } else {
      break $l$loop;
    }
  }
  this.currentPosition_1 = current;
  return current;
};
protoOf(StringJsonLexer).consumeNextToken_kteg6b_k$ = function (expected) {
  if (this.currentPosition_1 === -1) {
    this.unexpectedToken_u9lezp_k$(expected);
  }
  var source = this.get_source_jl0x7o_k$();
  var cpos = this.currentPosition_1;
  $l$loop: while (cpos < source.length) {
    var _unary__edvuaz = cpos;
    cpos = _unary__edvuaz + 1 | 0;
    var c = charCodeAt(source, _unary__edvuaz);
    // Inline function 'kotlinx.serialization.json.internal.AbstractJsonLexer.isWs' call
    if (c === _Char___init__impl__6a9atx(32) || c === _Char___init__impl__6a9atx(10) || c === _Char___init__impl__6a9atx(13) || c === _Char___init__impl__6a9atx(9))
      continue $l$loop;
    this.currentPosition_1 = cpos;
    if (c === expected)
      return Unit_instance;
    this.unexpectedToken_u9lezp_k$(expected);
  }
  this.currentPosition_1 = -1;
  this.unexpectedToken_u9lezp_k$(expected);
};
protoOf(StringJsonLexer).consumeKeyString_mfa3ws_k$ = function () {
  this.consumeNextToken_kteg6b_k$(_Char___init__impl__6a9atx(34));
  var current = this.currentPosition_1;
  var closingQuote = indexOf_0(this.get_source_jl0x7o_k$(), _Char___init__impl__6a9atx(34), current);
  if (closingQuote === -1) {
    this.consumeStringLenient_9oypvu_k$();
    // Inline function 'kotlinx.serialization.json.internal.AbstractJsonLexer.fail' call
    var expected = tokenDescription(1);
    var position = false && this.currentPosition_1 > 0 ? this.currentPosition_1 - 1 | 0 : this.currentPosition_1;
    var s = this.currentPosition_1 === charSequenceLength(this.get_source_jl0x7o_k$()) || position < 0 ? 'EOF' : toString_1(charSequenceGet(this.get_source_jl0x7o_k$(), position));
    var tmp$ret$1 = 'Expected ' + expected + ", but had '" + s + "' instead";
    this.fail$default_ly4hfp_k$(tmp$ret$1, position);
  }
  var inductionVariable = current;
  if (inductionVariable < closingQuote)
    do {
      var i = inductionVariable;
      inductionVariable = inductionVariable + 1 | 0;
      if (charCodeAt(this.get_source_jl0x7o_k$(), i) === _Char___init__impl__6a9atx(92)) {
        return this.consumeString2(this.get_source_jl0x7o_k$(), this.currentPosition_1, i);
      }
    }
     while (inductionVariable < closingQuote);
  this.currentPosition_1 = closingQuote + 1 | 0;
  return substring(this.get_source_jl0x7o_k$(), current, closingQuote);
};
protoOf(StringJsonLexer).peekLeadingMatchingValue_y3am18_k$ = function (keyToMatch, isLenient) {
  var positionSnapshot = this.currentPosition_1;
  try {
    if (!(this.consumeNextToken_uf1vsa_k$() === 6))
      return null;
    var firstKey = this.peekString_d4c947_k$(isLenient);
    if (!(firstKey === keyToMatch))
      return null;
    this.discardPeeked_n23g48_k$();
    if (!(this.consumeNextToken_uf1vsa_k$() === 5))
      return null;
    return this.peekString_d4c947_k$(isLenient);
  }finally {
    this.currentPosition_1 = positionSnapshot;
    this.discardPeeked_n23g48_k$();
  }
};
function StringJsonLexer_0(json, source) {
  return !json.configuration_1.allowComments_1 ? new StringJsonLexer(source, json.configuration_1) : new StringJsonLexerWithComments(source, json.configuration_1);
}
function get_schemaCache(_this__u8e3s4) {
  return _this__u8e3s4._schemaCache_1;
}
function JsonToStringWriter() {
  this.sb_1 = StringBuilder_init_$Create$_0(128);
}
protoOf(JsonToStringWriter).writeLong_vk8q1z_k$ = function (value) {
  this.sb_1.append_cu3spi_k$(value);
};
protoOf(JsonToStringWriter).writeChar_9yrrvs_k$ = function (char) {
  this.sb_1.append_58al37_k$(char);
};
protoOf(JsonToStringWriter).write_mozxwr_k$ = function (text) {
  this.sb_1.append_22ad7x_k$(text);
};
protoOf(JsonToStringWriter).writeQuoted_k770v7_k$ = function (text) {
  printQuoted(this.sb_1, text);
};
protoOf(JsonToStringWriter).release_wu5yyf_k$ = function () {
  this.sb_1.clear_1keqml_k$();
};
protoOf(JsonToStringWriter).toString = function () {
  return this.sb_1.toString();
};
function createMapForCache(initialCapacity) {
  return HashMap_init_$Create$(initialCapacity);
}
//region block: post-declaration
protoOf(defer$1).get_isNullable_67sy7o_k$ = get_isNullable;
protoOf(defer$1).get_isInline_usk17w_k$ = get_isInline;
protoOf(defer$1).get_annotations_20dirp_k$ = get_annotations;
defineProp(protoOf(JsonException), 'message', function () {
  return this.get_message_h23axq_k$();
});
protoOf(JsonSerializersModuleValidator).contextual_phl8co_k$ = contextual;
//endregion
//region block: init
Companion_instance = new Companion();
Companion_instance_0 = new Companion_0();
Companion_instance_1 = new Companion_1();
Companion_instance_2 = new Companion_2();
Tombstone_instance = new Tombstone();
RedactedKey_instance = new RedactedKey();
//endregion
//region block: exports
export {
  Default_getInstance as Default_getInstancevb13yk0g3h3e,
  JsonObjectSerializer_getInstance as JsonObjectSerializer_getInstance15ehqyi55oo4q,
  JsonArrayBuilder as JsonArrayBuilderu8edol6ui3pj,
  JsonArray as JsonArray2urf8ey7u44sd,
  JsonObjectBuilder as JsonObjectBuilder2nl6rv6vdayuk,
  JsonObject as JsonObjectee06ihoeeiqj,
  JsonPrimitive_0 as JsonPrimitiveolttw629wj53,
  JsonPrimitive_2 as JsonPrimitive2fp8648nd60dn,
  JsonPrimitive_1 as JsonPrimitive1xkjzc5d7ihuv,
  JsonPrimitive as JsonPrimitive3ttzjh2ft5dnx,
  Json_0 as Jsonsmkyu9xjl7fv,
  get_booleanOrNull as get_booleanOrNull376axlcpdhkmo,
  get_intOrNull as get_intOrNulld29i64b3udf,
  get_jsonArray as get_jsonArray18sglwhl4pclz,
  get_jsonObject as get_jsonObject2u4z2ch1uuca9,
  get_jsonPrimitive as get_jsonPrimitivez17tyd5rw1ql,
};
//endregion

//# sourceMappingURL=kotlinx-serialization-kotlinx-serialization-json.mjs.map
