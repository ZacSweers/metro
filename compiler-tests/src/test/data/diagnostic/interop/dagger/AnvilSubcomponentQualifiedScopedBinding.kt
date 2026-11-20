// WITH_ANVIL
// RUN_PIPELINE_TILL: BACKEND

// https://github.com/ZacSweers/metro/issues/388
class Parent
class Child

@SingleIn(Parent::class)
@DependencyGraph(scope = Parent::class)
interface ParentComponent {
    @DependencyGraph.Factory
    interface Factory {
        fun create(): ParentComponent
    }
}

@com.squareup.anvil.annotations.ContributesSubcomponent(Child::class, parentScope = Parent::class)
interface ChildComponent {
    @GraphExtension.Factory @ContributesTo(Parent::class)
    interface Factory {
        fun create(): ChildComponent
    }
}

interface ActivityNavigator

@Inject
@ForScope(Child::class)
@SingleIn(Child::class)
@com.squareup.anvil.annotations.ContributesBinding(Child::class, boundType = ActivityNavigator::class)
class BottomSheetNavigator : ActivityNavigator
