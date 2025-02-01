# Validation & Error Reporting

Common programmer/usage errors are implemented in FIR. This should allow errors to appear directly in the IDE, offering the best and fastest feedback loop for developers writing their code.

TODO IDE screenshot example

Dependency graph validation is performed at the per-graph level. Metro seeks to report binding validation errors at least on par with Dagger, if not better.

```
ExampleGraph.kt:6:1 [Metro/DependencyCycle] Found a dependency cycle:
	kotlin.Int is injected at
    	[test.ExampleGraph] test.ExampleGraph.provideString(..., int)
	kotlin.String is injected at
    	[test.ExampleGraph] test.ExampleGraph.provideDouble(..., string)
	kotlin.Double is injected at
    	[test.ExampleGraph] test.ExampleGraph.provideInt(..., double)
	kotlin.Int is injected at
    	[test.ExampleGraph] test.ExampleGraph.provideString(..., int)
```

Binding errors take learnings from Dagger and report fully qualified references that IDEs like IntelliJ can usually autolink.

```
ExampleGraph.kt:6:1 [Metro/GraphDependencyCycle] Dependency graph dependency cycle detected! The below graph depends on itself.
	test.CharSequenceGraph is requested at
    	[test.CharSequenceGraph] test.CharSequenceGraph.Factory.create()
```

Note that binding graph resolution currently only happens in the compiler IR backend, but maybe someday we can move this to FIR to get errors in the IDE.
