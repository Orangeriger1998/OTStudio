package com.orangetiger.otstudio

import org.junit.Assert.*
import org.junit.Test

/** Android-only: NativeEngine lives beside Android code, so the JVM verification build skips this file. */
class NativeEngineTest {
    @Test fun theFastEngineNeedsDotProductAndInt8OnEveryCore() {
        val g5 = "processor\t: 0\nFeatures\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp asimddp i8mm bf16 sve2\n\nprocessor\t: 1\nFeatures\t: fp asimd asimddp i8mm\n"
        assertTrue(NativeEngine.supportsFast(g5))
        // Tensor G1 (Pixel 6) has dot product but not int8 matrix multiply.
        assertFalse(NativeEngine.supportsFast("Features\t: fp asimd asimddp asimdhp\n"))
        assertFalse(NativeEngine.supportsFast("Features\t: fp asimd asimddp i8mm\nFeatures\t: fp asimd\n"))
        assertFalse(NativeEngine.supportsFast(""))
    }
}
