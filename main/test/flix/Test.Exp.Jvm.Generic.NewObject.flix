mod Test.Exp.Jvm.Generic.NewObject {

    use Assert.{assertEq, assertTrue}

    import java.io.Serializable
    import java.lang.{Comparable => JComparable}
    import java.lang.{Integer => JInteger}
    import java.lang.{Iterable => JIterable}
    import java.lang.Object
    import java.lang.Runnable
    import java.util.ArrayList
    import java.util.{Collections => JCollections}
    import java.util.Comparator
    import java.util.{Iterator => JIterator}
    import java.util.TreeMap
    import java.util.concurrent.Callable
    import java.util.function.BiFunction

    // CompileTests

    @CompileTest
    def testComparatorCompile01(): Comparator[String] \ IO =
        new Comparator[String] {
            def compare(_this: Comparator[String], t: String, u: String): Int32 =
                t.compareTo(u)
        }

    @CompileTest
    def testComparatorCompile02(): Comparator[JInteger] \ IO =
        new Comparator[JInteger] {
            def compare(_this: Comparator[JInteger], t: JInteger, u: JInteger): Int32 =
                t.compareTo(u)
        }

    @CompileTest
    def testCallableString01(): Callable[String] \ IO =
        new Callable[String] {
            def call(_this: Callable[String]): String =
                "hello"
        }

    @CompileTest
    def testCallableInteger01(): Callable[JInteger] \ IO =
        new Callable[JInteger] {
            def call(_this: Callable[JInteger]): JInteger =
                JInteger.valueOf(42i32)
        }

    @CompileTest
    def testIterableCompile01(): JIterable[Object] \ IO =
        new JIterable[Object] {
            def iterator(_this: JIterable[Object]): JIterator[Object] \ IO =
                let l: ArrayList[Object] = new ArrayList();
                l.iterator()
        }

    @CompileTest
    def testComparableCompile01(): JComparable[String] \ IO =
        new JComparable[String] {
            def compareTo(_this: JComparable[String], o: String): Int32 =
                "hello".compareTo(o)
        }

    // Tests

    @Test
    def testComparatorString01(): Unit \ {Assert, IO} =
        let c = new Comparator[String] {
            def compare(_this: Comparator[String], t: String, u: String): Int32 =
                t.compareTo(u)
        };
        assertTrue(c.compare("a", "b") < 0)

    @Test
    def testComparatorString02(): Unit \ {Assert, IO} =
        let c = new Comparator[String] {
            def compare(_this: Comparator[String], t: String, u: String): Int32 =
                u.compareTo(t)
        };
        assertTrue(c.compare("a", "b") > 0)

    @Test
    def testComparatorInteger01(): Unit \ {Assert, IO} =
        let c = new Comparator[JInteger] {
            def compare(_this: Comparator[JInteger], t: JInteger, u: JInteger): Int32 =
                t.compareTo(u)
        };
        assertTrue(c.compare(JInteger.valueOf(1i32), JInteger.valueOf(2i32)) < 0)

    @Test
    def testComparatorWithTreeMap01(): Unit \ {Assert, IO} =
        let c = new Comparator[String] {
            def compare(_this: Comparator[String], t: String, u: String): Int32 =
                u.compareTo(t)
        };
        let m: TreeMap[String, String] = new TreeMap(c);
        m.put("a", "1");
        m.put("b", "2");
        m.put("c", "3");
        let r: String = m.firstKey();
        assertEq(expected = "c", r)

    @Test
    def testComparatorRaw01(): Unit \ {Assert, IO} =
        let c = new Comparator[String] {
            def compare(_this: Comparator[String], _t: String, _u: String): Int32 = 0
        };
        assertEq(expected = 0, c.compare("foo", "bar"))

    @Test
    def testRunnableWithGenericCapture01(): Unit \ {Assert, IO} =
        let l: ArrayList[String] = new ArrayList();
        let r = new Runnable {
            def $run(_this: Runnable): Unit \ IO =
                l.add("captured");
                ()
        };
        r.$run();
        assertEq(expected = 1, l.size())

    @Test
    def testComparatorCompare01(): Unit \ {Assert, IO} =
        let c = new Comparator[String] {
            def compare(_this: Comparator[String], t: String, u: String): Int32 =
                t.compareTo(u)
        };
        assertEq(expected = 0, c.compare("hello", "hello"))

    @Test
    def testComparatorCompareReverse01(): Unit \ {Assert, IO} =
        let c = new Comparator[String] {
            def compare(_this: Comparator[String], t: String, u: String): Int32 =
                u.compareTo(t)
        };
        assertTrue(c.compare("a", "b") > 0)

    @Test
    def testComparatorUsedInSort01(): Unit \ {Assert, IO} =
        let c = new Comparator[String] {
            def compare(_this: Comparator[String], t: String, u: String): Int32 =
                u.compareTo(t)
        };
        let l: ArrayList[String] = new ArrayList();
        l.add("a");
        l.add("c");
        l.add("b");
        JCollections.sort(l, c);
        let r: String = l.get(0);
        assertEq(expected = "c", r)

    @Test
    def testComparatorCapture01(): Unit \ {Assert, IO} =
        let n = 1;
        let c = new Comparator[JInteger] {
            def compare(_this: Comparator[JInteger], t: JInteger, u: JInteger): Int32 =
                t.compareTo(u) + n - n
        };
        assertTrue(c.compare(JInteger.valueOf(1i32), JInteger.valueOf(2i32)) < 0)

    @Test
    def testSerializableStillWorks01(): Unit \ {Assert, IO} =
        let a = new Serializable { };
        assertTrue(a.toString().startsWith("dev.flix.gen.Anon"))

    @Test
    def testComparatorWithTreeMapOrdering01(): Unit \ {Assert, IO} =
        let c = new Comparator[String] {
            def compare(_this: Comparator[String], t: String, u: String): Int32 =
                t.compareTo(u)
        };
        let m: TreeMap[String, String] = new TreeMap(c);
        m.put("c", "3");
        m.put("a", "1");
        m.put("b", "2");
        let r: String = m.firstKey();
        assertEq(expected = "a", r)

    @Test
    def testRunnableCapturingGeneric01(): Unit \ {Assert, IO} =
        let l: ArrayList[String] = new ArrayList();
        l.add("before");
        let r = new Runnable {
            def $run(_this: Runnable): Unit \ IO =
                l.add("after");
                ()
        };
        r.$run();
        assertEq(expected = 2, l.size())

    // Tests for explicit type arguments on new object expressions.
    // With type arguments, method parameters use the resolved generic types
    // (e.g., String instead of Object), eliminating the need for casts.

    // --- Compile tests: verify type arguments produce the correct result type ---

    @CompileTest
    def testNewObjectTargsComparatorString01(): Comparator[String] \ IO =
        new Comparator[String] {
            def compare(_this: Comparator[String], t: String, u: String): Int32 =
                t.compareTo(u)
        }

    @CompileTest
    def testNewObjectTargsComparatorInteger01(): Comparator[JInteger] \ IO =
        new Comparator[JInteger] {
            def compare(_this: Comparator[JInteger], t: JInteger, u: JInteger): Int32 =
                t.compareTo(u)
        }

    @CompileTest
    def testNewObjectTargsCallableString01(): Callable[String] \ IO =
        new Callable[String] {
            def call(_this: Callable[String]): String =
                "hello"
        }

    @CompileTest
    def testNewObjectTargsCallableInteger01(): Callable[JInteger] \ IO =
        new Callable[JInteger] {
            def call(_this: Callable[JInteger]): JInteger =
                JInteger.valueOf(42i32)
        }

    @CompileTest
    def testNewObjectTargsIterableString01(): JIterable[String] \ IO =
        new JIterable[String] {
            def iterator(_this: JIterable[String]): JIterator[String] \ IO =
                let l: ArrayList[String] = new ArrayList();
                l.iterator()
        }

    @CompileTest
    def testNewObjectTargsComparableString01(): JComparable[String] \ IO =
        new JComparable[String] {
            def compareTo(_this: JComparable[String], o: String): Int32 =
                "hello".compareTo(o)
        }

    // --- Runtime tests: verify end-to-end correctness with generic params ---

    @Test
    def testNewObjectTargsComparatorRuntime01(): Unit \ {Assert, IO} =
        let c = new Comparator[String] {
            def compare(_this: Comparator[String], t: String, u: String): Int32 =
                t.compareTo(u)
        };
        assertTrue(c.compare("a", "b") < 0)

    @Test
    def testNewObjectTargsComparatorRuntime02(): Unit \ {Assert, IO} =
        let c = new Comparator[JInteger] {
            def compare(_this: Comparator[JInteger], t: JInteger, u: JInteger): Int32 =
                t.compareTo(u)
        };
        assertTrue(c.compare(JInteger.valueOf(1i32), JInteger.valueOf(2i32)) < 0)

    @Test
    def testNewObjectTargsComparatorTreeMap01(): Unit \ {Assert, IO} =
        let c = new Comparator[String] {
            def compare(_this: Comparator[String], t: String, u: String): Int32 =
                u.compareTo(t)
        };
        let m: TreeMap[String, String] = new TreeMap(c);
        m.put("a", "1");
        m.put("b", "2");
        m.put("c", "3");
        let r: String = m.firstKey();
        assertEq(expected = "c", r)

    @Test
    def testNewObjectTargsComparatorSort01(): Unit \ {Assert, IO} =
        let c = new Comparator[String] {
            def compare(_this: Comparator[String], t: String, u: String): Int32 =
                u.compareTo(t)
        };
        let l: ArrayList[String] = new ArrayList();
        l.add("a");
        l.add("c");
        l.add("b");
        JCollections.sort(l, c);
        let r: String = l.get(0);
        assertEq(expected = "c", r)

    @Test
    def testNewObjectTargsCallableRuntime01(): Unit \ {Assert, IO} =
        let c: Callable[String] = new Callable[String] {
            def call(_this: Callable[String]): String =
                "hello"
        };
        let r: String = c.call();
        assertEq(expected = "hello", r)

    // --- Backward compatibility: explicit Object type arg works like pre-targs behavior ---

    @Test
    def testNewObjectTargsBackwardCompat01(): Unit \ {Assert, IO} =
        let c = new Comparator[String] {
            def compare(_this: Comparator[String], _t: String, _u: String): Int32 = 0
        };
        assertEq(expected = 0, c.compare("foo", "bar"))

    @Test
    def testNewObjectTargsBackwardCompat02(): Unit \ {Assert, IO} =
        let c: Comparator[String] = new Comparator[String] {
            def compare(_this: Comparator[String], t: String, u: String): Int32 =
                t.compareTo(u)
        };
        assertTrue(c.compare("a", "b") < 0)

    // --- Generic type args match resolved method signatures ---

    @Test
    def testNewObjectTargsObjectParams01(): Unit \ {Assert, IO} =
        let c = new Comparator[String] {
            def compare(_this: Comparator[String], t: String, u: String): Int32 =
                t.compareTo(u)
        };
        assertTrue(c.compare("a", "b") < 0)

    // --- Parameterized _this type ---

    @Test
    def testNewObjectTargsThisParam01(): Unit \ {Assert, IO} =
        let c = new Comparator[String] {
            def compare(_this: Comparator[String], t: String, u: String): Int32 =
                t.compareTo(u)
        };
        assertTrue(c.compare("a", "b") < 0)

    // --- Generic interface instantiated with a non-reference (primitive / Unit) type param ---
    // Regression tests for https://github.com/flix/flix/issues/12876. The overridden method's
    // erased JVM return type is `Object`, so a primitive result must be boxed (e.g. via
    // `Integer.valueOf`) and `Unit` returned as its runtime value; otherwise the generated
    // anonymous class fails JVM verification with a `VerifyError`.

    @Test
    def testNewObjectPrimitiveCallableInt8(): Unit \ {Assert, IO} =
        let c = new Callable[Int8] {
            def call(_this: Callable[Int8]): Int8 = 42i8
        };
        assertEq(expected = 42i8, c.call())

    @Test
    def testNewObjectPrimitiveCallableInt16(): Unit \ {Assert, IO} =
        let c = new Callable[Int16] {
            def call(_this: Callable[Int16]): Int16 = 42i16
        };
        assertEq(expected = 42i16, c.call())

    @Test
    def testNewObjectPrimitiveCallableInt32(): Unit \ {Assert, IO} =
        let c = new Callable[Int32] {
            def call(_this: Callable[Int32]): Int32 = 42i32
        };
        assertEq(expected = 42i32, c.call())

    @Test
    def testNewObjectPrimitiveCallableInt64(): Unit \ {Assert, IO} =
        let c = new Callable[Int64] {
            def call(_this: Callable[Int64]): Int64 = 42i64
        };
        assertEq(expected = 42i64, c.call())

    @Test
    def testNewObjectPrimitiveCallableFloat32(): Unit \ {Assert, IO} =
        let c = new Callable[Float32] {
            def call(_this: Callable[Float32]): Float32 = 42.0f32
        };
        assertEq(expected = 42.0f32, c.call())

    @Test
    def testNewObjectPrimitiveCallableFloat64(): Unit \ {Assert, IO} =
        let c = new Callable[Float64] {
            def call(_this: Callable[Float64]): Float64 = 42.0f64
        };
        assertEq(expected = 42.0f64, c.call())

    @Test
    def testNewObjectPrimitiveCallableBool(): Unit \ {Assert, IO} =
        let c = new Callable[Bool] {
            def call(_this: Callable[Bool]): Bool = true
        };
        assertTrue(c.call())

    @Test
    def testNewObjectPrimitiveCallableChar(): Unit \ {Assert, IO} =
        let c = new Callable[Char] {
            def call(_this: Callable[Char]): Char = 'x'
        };
        assertEq(expected = 'x', c.call())

    @Test
    def testNewObjectUnitCallable(): Unit \ {Assert, IO} =
        let c = new Callable[Unit] {
            def call(_this: Callable[Unit]): Unit = ()
        };
        assertEq(expected = (), c.call())

    // --- Generic interface instantiated with a reference type param (list, tuple, enum, record) ---
    // These are the reference-typed counterparts of the primitive tests above. The overridden
    // method's erased JVM return type is `Object`; a reference value already matches it, so no
    // boxing is needed and the value is `areturn`ed directly. They also exercise the internal
    // TypeVerifier, which must accept any Flix reference type as a subtype of the erased `Object`.

    enum Shape with Eq, ToString {
        case Circle(Int32)
        case Square(Int32)
    }

    @Test
    def testNewObjectReferenceCallableList(): Unit \ {Assert, IO} =
        let c = new Callable[List[Int32]] {
            def call(_this: Callable[List[Int32]]): List[Int32] = 1 :: 2 :: 3 :: Nil
        };
        assertEq(expected = 1 :: 2 :: 3 :: Nil, c.call())

    @Test
    def testNewObjectReferenceCallableListString(): Unit \ {Assert, IO} =
        let c = new Callable[List[String]] {
            def call(_this: Callable[List[String]]): List[String] = "a" :: "b" :: Nil
        };
        assertEq(expected = "a" :: "b" :: Nil, c.call())

    @Test
    def testNewObjectReferenceCallableTuple(): Unit \ {Assert, IO} =
        let c = new Callable[(Int32, String)] {
            def call(_this: Callable[(Int32, String)]): (Int32, String) = (1, "one")
        };
        assertEq(expected = (1, "one"), c.call())

    @Test
    def testNewObjectReferenceCallableOption(): Unit \ {Assert, IO} =
        let c = new Callable[Option[Int32]] {
            def call(_this: Callable[Option[Int32]]): Option[Int32] = Some(42)
        };
        assertEq(expected = Some(42), c.call())

    @Test
    def testNewObjectReferenceCallableEnum(): Unit \ {Assert, IO} =
        let c = new Callable[Shape] {
            def call(_this: Callable[Shape]): Shape = Shape.Circle(5)
        };
        assertEq(expected = Shape.Circle(5), c.call())

    @Test
    def testNewObjectReferenceCallableRecord(): Unit \ {Assert, IO} =
        let c = new Callable[{ x = Int32, y = Int32 }] {
            def call(_this: Callable[{ x = Int32, y = Int32 }]): { x = Int32, y = Int32 } = { x = 1, y = 2 }
        };
        let r = c.call();
        assertEq(expected = 1, r#x)

    // --- A single generic anonymous-class site specialized at multiple types ---
    // Regression tests for the anonymous-class specialization collision. The `new Callable[a]`
    // site below lives in one generic def; monomorphization specializes it once per type. Each
    // specialization must get its own generated anonymous class. Otherwise distinct
    // specializations (e.g. `boxedCallable[Int32]` vs `boxedCallable[Bool]`) share one class
    // name and collide, so one specialization runs with another's class -- a `ClassCastException`
    // (or a `VerifyError` when the colliding representations differ) at runtime.

    def boxedCallable(x: a): Callable[a] \ IO =
        new Callable[a] { def call(_this: Callable[a]): a = x }

    @Test
    def testNewObjectSpecializationInt8(): Unit \ {Assert, IO} =
        assertEq(expected = 1i8, boxedCallable(1i8).call())

    @Test
    def testNewObjectSpecializationInt16(): Unit \ {Assert, IO} =
        assertEq(expected = 2i16, boxedCallable(2i16).call())

    @Test
    def testNewObjectSpecializationInt32(): Unit \ {Assert, IO} =
        assertEq(expected = 3i32, boxedCallable(3i32).call())

    @Test
    def testNewObjectSpecializationInt64(): Unit \ {Assert, IO} =
        assertEq(expected = 4i64, boxedCallable(4i64).call())

    @Test
    def testNewObjectSpecializationFloat32(): Unit \ {Assert, IO} =
        assertEq(expected = 5.0f32, boxedCallable(5.0f32).call())

    @Test
    def testNewObjectSpecializationFloat64(): Unit \ {Assert, IO} =
        assertEq(expected = 6.0f64, boxedCallable(6.0f64).call())

    @Test
    def testNewObjectSpecializationBool(): Unit \ {Assert, IO} =
        assertEq(expected = true, boxedCallable(true).call())

    @Test
    def testNewObjectSpecializationChar(): Unit \ {Assert, IO} =
        assertEq(expected = 'q', boxedCallable('q').call())

    @Test
    def testNewObjectSpecializationUnit(): Unit \ {Assert, IO} =
        assertEq(expected = (), boxedCallable(()).call())

    @Test
    def testNewObjectSpecializationString(): Unit \ {Assert, IO} =
        assertEq(expected = "hello", boxedCallable("hello").call())

    @Test
    def testNewObjectSpecializationList(): Unit \ {Assert, IO} =
        assertEq(expected = 1 :: 2 :: Nil, boxedCallable(1 :: 2 :: Nil).call())

    @Test
    def testNewObjectSpecializationTuple(): Unit \ {Assert, IO} =
        assertEq(expected = (1, "one"), boxedCallable((1, "one")).call())

    @Test
    def testNewObjectSpecializationOption(): Unit \ {Assert, IO} =
        assertEq(expected = Some(13), boxedCallable(Some(13)).call())

    @Test
    def testNewObjectSpecializationEnum(): Unit \ {Assert, IO} =
        assertEq(expected = Shape.Square(7), boxedCallable(Shape.Square(7)).call())

    // Mixes many specializations of the same site in one expression -- this is the exact shape
    // that failed before the fix: the first call would run with a later specialization's class.
    // Includes reference types (list, tuple, enum) alongside primitives so a collision between a
    // primitive and a reference representation surfaces as a `VerifyError`/`ClassCastException`.
    @Test
    def testNewObjectSpecializationMixed(): Unit \ {Assert, IO} =
        assertEq(expected = 7i8, boxedCallable(7i8).call());
        assertEq(expected = 8i16, boxedCallable(8i16).call());
        assertEq(expected = 9i32, boxedCallable(9i32).call());
        assertEq(expected = 10i64, boxedCallable(10i64).call());
        assertEq(expected = 11.0f32, boxedCallable(11.0f32).call());
        assertEq(expected = 12.0f64, boxedCallable(12.0f64).call());
        assertEq(expected = false, boxedCallable(false).call());
        assertEq(expected = 'z', boxedCallable('z').call());
        assertEq(expected = (), boxedCallable(()).call());
        assertEq(expected = "world", boxedCallable("world").call());
        assertEq(expected = 14 :: 15 :: Nil, boxedCallable(14 :: 15 :: Nil).call());
        assertEq(expected = (16, "x"), boxedCallable((16, "x")).call());
        assertEq(expected = Shape.Circle(17), boxedCallable(Shape.Circle(17)).call())

    // A generic Java interface instantiated at a primitive declares its parameters erased --
    // `Comparator[Int64].compare` is `compare(Object, Object)` -- while the typer requires the
    // Flix method to take `Int64`. The method body must therefore unbox, or it loads a reference
    // slot with `LLOAD` and fails verification before running a single instruction.
    @Test
    def testComparatorPrimitiveInt32(): Unit \ {Assert, IO} =
        let c = new Comparator[Int32] {
            def compare(_this: Comparator[Int32], t: Int32, u: Int32): Int32 = t - u
        };
        assertTrue(c.compare(1, 2) < 0)

    @Test
    def testComparatorPrimitiveInt64(): Unit \ {Assert, IO} =
        let c = new Comparator[Int64] {
            def compare(_this: Comparator[Int64], t: Int64, u: Int64): Int32 =
                if (t < u) -1 else if (t > u) 1 else 0
        };
        assertTrue(c.compare(1i64, 2i64) < 0)

    @Test
    def testComparatorPrimitiveFloat64(): Unit \ {Assert, IO} =
        let c = new Comparator[Float64] {
            def compare(_this: Comparator[Float64], t: Float64, u: Float64): Int32 =
                if (t < u) -1 else if (t > u) 1 else 0
        };
        assertTrue(c.compare(1.0f64, 2.0f64) < 0)

    // A 64-bit parameter claims two stack slots where the erased frame allocated one, so a
    // parameter *after* it lands at the wrong index. Both orders are covered because only one of
    // them puts a later parameter behind the wide one.
    @Test
    def testBiFunctionWideThenNarrow(): Unit \ {Assert, IO} =
        let f = new BiFunction[Int64, Int32, String] {
            def apply(_this: BiFunction[Int64, Int32, String], a: Int64, b: Int32): String = "${a}/${b}"
        };
        assertEq(expected = "9000000000/7", f.apply(9000000000i64, 7))

    @Test
    def testBiFunctionNarrowThenWide(): Unit \ {Assert, IO} =
        let f = new BiFunction[Int32, Int64, String] {
            def apply(_this: BiFunction[Int32, Int64, String], a: Int32, b: Int64): String = "${a}/${b}"
        };
        assertEq(expected = "7/9000000000", f.apply(7, 9000000000i64))

    // A reference parameter beside a wide primitive one: the reference needs no unboxing, so this
    // pins that the bridge is applied per parameter rather than to all or none.
    @Test
    def testBiFunctionReferenceAndWide(): Unit \ {Assert, IO} =
        let f = new BiFunction[String, Int64, String] {
            def apply(_this: BiFunction[String, Int64, String], a: String, b: Int64): String = "${a}/${b}"
        };
        assertEq(expected = "x/42", f.apply("x", 42i64))

}
