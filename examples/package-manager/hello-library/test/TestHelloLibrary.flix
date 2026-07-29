mod TestHelloLibrary {
    @Test
    def test01(): Unit \ Assert = Assert.assertEq(expected = 42, HelloLibrary.foo())
}
