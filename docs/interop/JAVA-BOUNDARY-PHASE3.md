# Typed JVM boundary: experimental Phase 3

Phase 3 implements Java-owned interfaces with checked Flix boundary wrappers. Java compiles
its interface and consumers first. Flix reads their classfiles and emits an implementation;
`java-api-stubs` is unnecessary for this build order and rejects interface contracts.
The overall ADR remains Proposed for later capabilities.

## Declare an implementation

```java
package example;
public interface Service {
    java.util.List<Integer> values();
    int sum(java.util.List<Integer> values);
    default int answer() { return 42; }
}
```

```text
export instance example.Service = mod Impl as "example.FlixService" {
    def values: () -> java.util.List[java.lang.Integer];
    def sum: (java.util.List[java.lang.Integer]) -> int;
}
```

```flix
pub mod Impl {
    pub def values(): List[Int32] = 1 :: 2 :: Nil
    pub def sum(values: List[Int32]): Int32 = List.sum(values)
}
```

The interface name identifies existing Java metadata. `as` explicitly selects the generated
implementation class name; the compiler does not infer a public name from module layout.
Each member maps to a public monomorphic Flix definition, with optional `= target` aliases.
Aliases are also useful for Java names that are Flix keywords, such as `run = execute`.
Overloads use separate member lines and distinct parameter descriptors; their Flix targets can
have different names. Return types alone cannot distinguish overloads.

Both the recorded contract and Java interface must agree with the ABI independently derived
from checked `JavaArgument` and `JavaResult` instances. Generic signatures are checked, not
just erased descriptors. Existing conversion, effect/default-handler, null-path and transitive
region rules apply. A Java interface does not select new conversions or erase unsupported types.

The generated class is public and final, implements the interface, and has a public no-argument
constructor. Its instance methods forward through ordinary checked Flix wrappers. It has no
per-instance Flix state; each invocation allocates a fresh wrapper. Java default methods remain
inherited unless explicitly overridden. Public `Object` methods satisfy matching interface
redeclarations, including `equals`. Inherited generic methods specialized by a concrete
subinterface receive synthetic erased-descriptor bridges. Each implementation emits every
required inherited descriptor, including multiple bridges with covariant returns, so calls
through each parent interface dispatch correctly. Generic return arguments remain invariant.
Shared defaults and more-specific overrides remain inherited. Conflicting maximally specific
defaults require an explicit contract member, including conflicts introduced by binary evolution.
This follows [JLS 9.4.1.3](https://docs.oracle.com/javase/specs/jls/se21/html/jls-9.html#jls-9.4.1.3)
and [JVMS 5.4.3.4](https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-5.html#jvms-5.4.3.4).

## Compile Java first

```console
javac -d build/java src/java/example/Service.java src/java/example/Client.java
jar --create --file build/java.jar -C build/java .
java -jar flix.jar java-api src/Service.flix-api --lib build/java.jar --out build/flix src/Impl.flix
```

Java consumers accept `Service` through normal dependency injection. A small launcher that
constructs `new example.FlixService()` can compile after Flix, against `build/java.jar` and
`build/flix`. Alternatively, an existing application factory can discover the explicitly named
implementation at runtime. The tests use reflection to construct it while compiling the entire
Java caller before Flix.

Run with `build/flix` and the Java application classes/jar. Neither the compiler jar nor API
stubs belongs on the runtime classpath. Output never copies the Java interface into the Flix
artifact. Flix can import the already compiled Java consumer/helper classes during checking.
Java code that directly names the generated implementation must compile after it exists;
the Java-first portion depends on the interface. Types used by that interface must also exist
when Java compiles. If it references Flix-owned generated models, those models still need their
Phase 2 bootstrap path; this feature removes the dependency on the implementation class.

The existing `java-api` command and editor `.flix-api` validation support this form. Missing
methods, incompatible signatures and invalid interfaces produce contract-located diagnostics.
Implementation names must not already exist on the Java classpath or collide with other
generated classes. Output-directory cleanup remains the build tool's responsibility.

## Supported scope

The interface must be public, non-sealed, non-annotation and non-generic. A concrete
non-generic interface may inherit a fully specialized generic parent. Methods must have
concrete boundary types: primitives, native references and supported parameterized references.
Method type variables, wildcards and arrays are rejected. Existing monomorphic conversion
restrictions remain, including explicit adapters for supported structural products and nominal
representations. All abstract methods must be implemented, apart from those fulfilled by
`Object`; inherited defaults may be omitted. Interface static methods cannot be implemented. Marker interfaces may use an empty member block;
the generated constructor is sufficient when no abstract methods are required.
Checked `throws` declarations do not constrain this boundary: they are accepted, and Flix
code may throw any exception. Calls allocate a fresh wrapper, as described above. JPMS package
exports are not validated; deployment must make the interface and its types accessible.
There is no generated handler constructor, service registration or automatic callback mapping.

## Inheritance review follow-up

The isolated `fix/java-boundary-interface-inheritance` branch preserves the original Phase 3
worktree. On `90467ff52`, the narrower-return `Supplier<String>` redeclaration and the original
`A<String>`/`B` example both passed. Stronger regressions reproduced two failures: a covariant
parent needed a second bridge and threw `AbstractMethodError`; binary evolution introduced two
conflicting defaults that checking incorrectly accepted. Both tests failed before their fixes.
Additional positive tests cover shared and more-specific defaults and covariant parameterized
returns. Interface validation now runs in the interop frontend before JVM emission; raw shape
plans cannot emit unvalidated implementations. Shared Java metadata requires `isSealed` to be
passed explicitly rather than relying on a default parameter.

## Validation

`TestJavaBoundaryInterfaces` covers Java-first standalone execution, primitive slot widths,
void methods, conversions/null paths, inherited/default methods, overloads, erased generic
bridges, `Object` inheritance, metadata/name rejection, no-stub behavior, CLI output and editor
error correction. The parser and runtime regressions failed before implementation; additional
bridge, inheritance and class-ownership regressions failed before their fixes.

The combined regression gate on `287b3d289` passes **176 tests in 13 suites, zero failures,
zero aborted suites**, with no ignored, canceled or pending tests:

```console
./mill --no-server flix.test.testOnly 'ca.uwaterloo.flix.language.phase.interop.*' \
  ca.uwaterloo.flix.language.phase.TestInstances \
  ca.uwaterloo.flix.language.jvm.TestJavaMetadata \
  ca.uwaterloo.flix.language.jvm.TestByteBuddyJavaTypeProvider -oC
```

It includes all 11 interface tests, both Phase 4 feasibility probes, the existing eight boundary
suites, core instance validation and Java metadata/provider checks. Java callers compile before
Flix and execute in isolated JVMs with only generated runtime output and the Java dependency
jar. CLI and editor tests also pass. The subsequent milestone commit changes documentation only.

These results describe the original `feat/java-boundary-phase3` milestone before the inheritance
review fixes. Phase 2's earlier full-suite run predates this implementation. Native-image
execution remains outside this validation. The combined regression gate with all review fixes passes **183 tests in 13 suites, zero failures
and zero aborted suites**, including all 17 interface tests and the unvalidated-plan emission
regression. There are no ignored, canceled or pending tests. The full compiler-suite gate is
still required before merging this fixed tip.
