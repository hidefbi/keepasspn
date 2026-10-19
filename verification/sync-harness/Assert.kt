package org.junit
object Assert {
    fun assertEquals(expected: Any?, actual: Any?) {
        if (expected != actual) throw AssertionError("expected <$expected> but was <$actual>")
    }
    fun assertTrue(value: Boolean) { if (!value) throw AssertionError("expected true") }
    fun assertFalse(value: Boolean) { if (value) throw AssertionError("expected false") }
    fun assertArrayEquals(expected: ByteArray, actual: ByteArray) {
        if (!expected.contentEquals(actual)) throw AssertionError("byte arrays differ")
    }
    fun fail(message: String): Nothing = throw AssertionError(message)
    fun <T : Throwable> assertThrows(type: Class<T>, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            if (type.isInstance(e)) return
            throw AssertionError("expected ${type.name} but was ${e::class.java.name}", e)
        }
        throw AssertionError("expected ${type.name} to be thrown")
    }
}
