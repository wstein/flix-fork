# Typed JVM boundary: Phase 4 investigation

Two executable feasibility probes pass using ordinary Flix source and the Phase 3 interface
boundary. They establish a narrow path for synchronous Java operation objects and lazy native
collection views. They do not introduce an automatic effect adapter or replace the default
boundary collection instances.

## Java operation objects

A Java interface supplies an operation:

```java
public interface Handler { int answer(int value); }
```

Flix accepts this native object and installs a lexical handler:

```flix
pub eff Ask { def ask(value: Int32): Int32 }

pub def invoke(h: Handler, x: Int32): Int32 \ IO =
    run { Ask.ask(x) } with handler Ask {
        def ask(value, resume) = resume(h.answer(value))
    }
```

The ordinary Java interop call contributes IO. The handler removes `Ask` before the checked
wrapper forwards to Java; current boundary validation still rejects unhandled non-primitive
effects. The probe compiles a Java interface/client before Flix and runs without compiler or
stub classes. It checks operation values, repeated calls, nested/reentrant boundary calls and
unchanged Java exception identity. Handler selection is lexical and passed explicitly, with
no ambient global or thread-local handler registry.

Java receives operation arguments and returns a value. Flix owns and resumes the continuation
exactly once in this example. This does not prove that continuations can safely escape to Java,
resume on another thread, resume multiple times or outlive regions. Those cases need explicit
lifetime, thread and cancellation semantics before an API exposes a resumption object.
A Java object whose method returns normally also cannot express an arbitrary aborting or
multi-shot algebraic handler through this operation-only shape.

A future declarative adapter should list each handled effect and Java operation method,
validate both directions' conversion instances and effects, and generate ordinary checked
handler source around conversions and the target call. Explicit Java handlers need defined
precedence over default handlers. Missing operations, unresolved effect rows and regional
operation values should be diagnostics. Start with synchronous operations and compiler-owned
resumption; asynchronous and first-class resumption APIs remain separate work. The working
manual pattern above is available now without extending the contract syntax.

## Read-only collection views

Current `JavaResult[List]`, `Vector`, `Chain`, `Set` and `Map` instances eagerly convert and
return detached unmodifiable collections. The probe instead returns an explicitly native
`List<Integer>` backed by an anonymous Flix implementation of `AbstractList<Integer>`, then
wraps it with `Collections.unmodifiableList`. It captures an immutable Flix list and boxes
one element in `get`; it does not use a default boundary List conversion.

An atomic counter records element conversions. Construction and `size()` leave it at zero;
a read increments it once, and a repeated read converts again. Runtime checks cover values,
negative/past-end indices and rejected add/set/clear/iterator-remove operations. The test
fixture has three elements and is a feasibility probe, not a timing benchmark or a measured
constant-time claim for arbitrary collections.

The adapter construction itself can avoid an element traversal when it captures an already
existing immutable backing value. Producing that value is separate work. A linked Flix list
has linear indexing and `size`; the prototype's inherited indexed iterator consequently costs
quadratic traversal for a full scan. A production sequential view needs a cursor-based iterator,
or an `AbstractCollection`/`AbstractSequentialList` shape. The JDK explicitly recommends
[AbstractSequentialList for sequential storage](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/AbstractList.html).
Vector indexing and cached lengths may suit a list view, but require separate implementation
and measurements. Chain traversal should use an explicit cursor/stack rather than indexed
repeated scans.

Deferred conversion changes when effects and exceptions occur, can repeat work, retains the
backing value, and may retain a large value through a small subview. A handler active during
construction may no longer exist at access time. Therefore an initial production view should
require region-free immutable backing and pure element conversion, with thread-safety and
retention documented; caching needs its own memory/concurrency policy. Native mutable Java
values inside elements do not become immutable merely because the outer collection is a view.

Keep default snapshots. Add an explicit view wrapper/type with its own `JavaResult` instance
if the performance benefit is demonstrated. Avoid conflicting with the default List instance.
Java-to-Flix inputs should retain snapshot conversion and eager null validation initially;
a lazy input view would change mutation, validation and lifetime semantics as well. These
constraints agree with the distinction between unmodifiable views and immutable collections
in the [JDK Collection contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/util/Collection.html).

## Evidence and next implementation gates

`TestJavaBoundaryPhase4Probes` contains two isolated Java-first runtime tests for the patterns
above. Both pass within the **176-test, 13-suite** combined boundary/instance/metadata gate on
`287b3d289`, with zero failures or aborted suites. No automatic handler binding, asynchronous resumption, production cursor implementation,
collection benchmark or default-policy change is claimed.

For an implementation milestone, first specify operation mappings and handler precedence,
then test generated source, effect elimination, missing handlers and reentrancy. For views,
measure allocation and full traversal for immutable List/Vector/Chain; test nested conversion,
retention, concurrent reads and error timing before introducing the explicit view type.


Both probes also pass in the independent full compiler-suite gate on `279ad487a`:
18,063 tests passed in 125 suites, zero failed or aborted, with 8 ignored tests.
The [Phase 3 validation record](JAVA-BOUNDARY-PHASE3.md#independent-full-compiler-suite-gate)
records the exact command and the corrected JDI startup race from the first attempt.
