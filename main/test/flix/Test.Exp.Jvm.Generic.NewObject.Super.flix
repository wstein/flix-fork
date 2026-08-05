mod Test.Exp.Jvm.Generic.NewObject.Super {

    use Assert.{assertEq, assertTrue}

    import dev.flix.test.TestGenericAbstractClass
    import java.lang.{Integer => JInteger}

    // Tests for calling super methods on generic abstract classes

    @Test
    def testGenericSuperGetValue01(): Unit \ {Assert, IO} =
        let obj = new TestGenericAbstractClass[String] {
            def new(): TestGenericAbstractClass[String] \ IO = super("original")
            def transform(_this: TestGenericAbstractClass[String], input: String): String \ IO =
                let v: String = super.getValue();
                v + "+" + input
        };
        let r: String = obj.transform("added");
        assertEq(expected = "original+added", r)

    @Test
    def testGenericSuperGetValue02(): Unit \ {Assert, IO} =
        let obj = new TestGenericAbstractClass[JInteger] {
            def new(): TestGenericAbstractClass[JInteger] \ IO = super(JInteger.valueOf(10i32))
            def transform(_this: TestGenericAbstractClass[JInteger], input: JInteger): JInteger \ IO =
                let v: JInteger = super.getValue();
                JInteger.valueOf(v.intValue() + input.intValue())
        };
        let r: JInteger = obj.transform(JInteger.valueOf(32i32));
        assertEq(expected = 42, r.intValue())

    @Test
    def testGenericSuperDescribe01(): Unit \ {Assert, IO} =
        let obj = new TestGenericAbstractClass[String] {
            def new(): TestGenericAbstractClass[String] \ IO = super("test")
            def transform(_this: TestGenericAbstractClass[String], input: String): String =
                input
            def describe(_this: TestGenericAbstractClass[String]): String \ IO =
                let orig: String = super.describe();
                "overridden(" + orig + ")"
        };
        let r: String = obj.describe();
        assertEq(expected = "overridden(value=test)", r)

    @Test
    def testGenericSuperWithCapture01(): Unit \ {Assert, IO} =
        let bonus = 100;
        let obj = new TestGenericAbstractClass[JInteger] {
            def new(): TestGenericAbstractClass[JInteger] \ IO = super(JInteger.valueOf(5i32))
            def transform(_this: TestGenericAbstractClass[JInteger], input: JInteger): JInteger \ IO =
                let v: JInteger = super.getValue();
                JInteger.valueOf(v.intValue() + input.intValue() + bonus)
        };
        let r: JInteger = obj.transform(JInteger.valueOf(1i32));
        assertEq(expected = 106, r.intValue())

    // A generic superclass extended at a *primitive* erases its type parameter to `Object`, so a
    // `super(...)` argument has to be boxed on the way in and a `super.…()` result unboxed on the
    // way out -- the same bridging `InvokeConstructor` and `InvokeMethod` already do. Without it
    // the type verifier rejects the program outright ("Mismatched types: Int32, class Object").
    //
    // Every test above instantiates at `String` or `JInteger`, where the Flix type and the erased
    // Java type are both references and agree by accident, which is why none of them saw this.

    @Test
    def testGenericSuperPrimitiveInt32(): Unit \ {Assert, IO} =
        let obj = new TestGenericAbstractClass[Int32] {
            def new(): TestGenericAbstractClass[Int32] \ IO = super(1)
            def transform(_this: TestGenericAbstractClass[Int32], input: Int32): Int32 \ IO =
                let v: Int32 = super.getValue();
                v + input
        };
        let r: Int32 = obj.transform(41);
        assertEq(expected = 42, r)

    // A 64-bit parameter claims two stack slots where the erased frame allocated one.
    @Test
    def testGenericSuperPrimitiveInt64(): Unit \ {Assert, IO} =
        let obj = new TestGenericAbstractClass[Int64] {
            def new(): TestGenericAbstractClass[Int64] \ IO = super(9000000000i64)
            def transform(_this: TestGenericAbstractClass[Int64], input: Int64): Int64 \ IO =
                let v: Int64 = super.getValue();
                v + input
        };
        let r: Int64 = obj.transform(1i64);
        assertEq(expected = 9000000001i64, r)

    @Test
    def testGenericSuperPrimitiveFloat64(): Unit \ {Assert, IO} =
        let obj = new TestGenericAbstractClass[Float64] {
            def new(): TestGenericAbstractClass[Float64] \ IO = super(1.5f64)
            def transform(_this: TestGenericAbstractClass[Float64], input: Float64): Float64 \ IO =
                let v: Float64 = super.getValue();
                v + input
        };
        let r: Float64 = obj.transform(0.5f64);
        assertEq(expected = 2.0f64, r)

    // `super.echo(x)` is a super *method* with an argument, which is the half of the defect the
    // constructor does not cover: the argument is bridged in and the result bridged back out.
    @Test
    def testGenericSuperMethodArgumentInt32(): Unit \ {Assert, IO} =
        let obj = new TestGenericAbstractClass[Int32] {
            def new(): TestGenericAbstractClass[Int32] \ IO = super(0)
            def transform(_this: TestGenericAbstractClass[Int32], input: Int32): Int32 \ IO =
                let v: Int32 = super.echo(input);
                v * 2
        };
        let r: Int32 = obj.transform(21);
        assertEq(expected = 42, r)

    @Test
    def testGenericSuperMethodArgumentInt64(): Unit \ {Assert, IO} =
        let obj = new TestGenericAbstractClass[Int64] {
            def new(): TestGenericAbstractClass[Int64] \ IO = super(0i64)
            def transform(_this: TestGenericAbstractClass[Int64], input: Int64): Int64 \ IO =
                let v: Int64 = super.echo(input);
                v + 1i64
        };
        let r: Int64 = obj.transform(41i64);
        assertEq(expected = 42i64, r)

}
