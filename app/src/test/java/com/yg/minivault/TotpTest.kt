package com.yg.minivault

import com.yg.minivault.crypto.Totp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** RFC 6238 附录 B 的测试向量（种子 = "12345678901234567890" 的 Base32）。 */
class TotpTest {

    private val seed = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"

    @Test
    fun matchesRfc6238Vectors() {
        // 6 位码：T=59s → 287082；1111111109s → 081804；1234567890s → 005924
        assertEquals("287082", Totp.parse(seed)!!.code(59_000L))
        assertEquals("081804", Totp.parse(seed)!!.code(1_111_111_109_000L))
        assertEquals("005924", Totp.parse(seed)!!.code(1_234_567_890_000L))
    }

    @Test
    fun parsesOtpAuthUri() {
        val spec = Totp.parse("otpauth://totp/GitHub:me?secret=$seed&issuer=GitHub&digits=6&period=30")
        assertEquals(6, spec!!.digits)
        assertEquals(30, spec.period)
        assertEquals("287082", spec.code(59_000L))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(Totp.parse(""))
        assertNull(Totp.parse("not base32 !!!"))
        assertNull(Totp.parse("otpauth://hotp/x?secret=$seed"))
    }

    @Test
    fun remainingIsWithinPeriod() {
        val spec = Totp.parse(seed)!!
        val rem = spec.remainingSeconds(59_000L)
        assertTrue(rem in 1..30)
    }
}