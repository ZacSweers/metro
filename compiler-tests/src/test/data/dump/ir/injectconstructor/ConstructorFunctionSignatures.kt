// Golden image to validate that we generate the correct signature for
// constructorFunction() IR breadcrumbs

@Inject class SimpleClass<T>
@Inject class ClassWithBounds<T : Any>
@Inject class ClassWithNullable<T, E : T?>
@Inject class ClassWithMultiple<T, V, E>
@Inject class ClassWithBackRefs<T, V, E : V>
@Inject class ClassWithWheres<T, V, E> where E : V
@Inject class ComplexMonster<T, V, E, LongName, NullableLongName : LongName?> where E : V, V : T, T : Any