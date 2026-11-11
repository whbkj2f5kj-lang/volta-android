package de.jarvis.w204

import de.jarvis.w204.vision.SignNet
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class SignModelTest {
    @Test fun packagedModelMatchesSourceAndRunsWithoutNativeRuntime() {
        val bytes=File("src/main/assets/signnet.bin").readBytes()
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        assertEquals("718e70cdc6aaec7d3b2680f1dbb488c1f29124f3d178e3b4c691ffc53ce5e390",hash)
        val net=bytes.inputStream().use { SignNet.load(it) }
        assertEquals(44,net.classes)
        val result=net.classify(IntArray(32*32){0xff777777.toInt()})
        assertTrue(result.cls in 0..43)
        assertTrue(result.p.isFinite() && result.p in 0f..1f)
    }
}
