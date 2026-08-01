mod Test.Exp.Fixpoint.Stringify {

    use Assert.assertEq
    use Fixpoint3.Boxed
    use Fixpoint3.Ast.Datalog.BodyTerm
    use Fixpoint3.Ast.Datalog.HeadTerm
    use Fixpoint3.Ast.Datalog.VarSym

    ///////////////////////////////////////////////////////////////////////////
    // Terms must render their value, not their runtime representation.       //
    //                                                                        //
    // These printers back the Datalog debug dumps. They previously used      //
    // `Debug.stringify`, which reflects on the JVM representation and so     //
    // rendered every boxed value as an opaque identity hash such as          //
    // `Tag$Int32@6a933be2`.                                                  //
    ///////////////////////////////////////////////////////////////////////////

    @Test
    def headTermLitInt32(): Unit \ Assert =
        let term: HeadTerm = HeadTerm.Lit(Boxed.BoxedInt32(42));
        assertEq(expected = "42", "${term}")

    @Test
    def headTermLitBool(): Unit \ Assert =
        let term: HeadTerm = HeadTerm.Lit(Boxed.BoxedBool(true));
        assertEq(expected = "true", "${term}")

    @Test
    def headTermLitChar(): Unit \ Assert =
        let term: HeadTerm = HeadTerm.Lit(Boxed.BoxedChar('x'));
        assertEq(expected = "x", "${term}")

    @Test
    def headTermLitInt64(): Unit \ Assert =
        let term: HeadTerm = HeadTerm.Lit(Boxed.BoxedInt64(9i64));
        assertEq(expected = "9", "${term}")

    @Test
    def headTermVar(): Unit \ Assert =
        let term: HeadTerm = HeadTerm.Var(VarSym.VarSym("x"));
        assertEq(expected = "VarSym(x)", "${term}")

    @Test
    def bodyTermLitInt32(): Unit \ Assert =
        let term: BodyTerm = BodyTerm.Lit(Boxed.BoxedInt32(7));
        assertEq(expected = "7", "${term}")

    @Test
    def bodyTermLitBool(): Unit \ Assert =
        let term: BodyTerm = BodyTerm.Lit(Boxed.BoxedBool(false));
        assertEq(expected = "false", "${term}")

    @Test
    def bodyTermWild(): Unit \ Assert =
        let term: BodyTerm = BodyTerm.Wild;
        assertEq(expected = "_", "${term}")

    @Test
    def bodyTermVar(): Unit \ Assert =
        let term: BodyTerm = BodyTerm.Var(VarSym.VarSym("y"));
        assertEq(expected = "VarSym(y)", "${term}")

}
