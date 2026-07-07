package com.garfiec.librechat.core.data.datastore

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.security.GeneralSecurityException

class EncryptedPrefsRecoveryTest {

    @Test
    fun success_passesThrough_withoutWiping() {
        var wipeCount = 0
        val result = createWithRecovery(create = { "ok" }, wipe = { wipeCount++ })
        assertThat(result).isEqualTo("ok")
        assertThat(wipeCount).isEqualTo(0)
    }

    @Test
    fun firstAttemptThrows_wipesThenReturnsRetryValue() {
        var attempts = 0
        var wipeCount = 0
        val result = createWithRecovery(
            create = {
                attempts++
                if (attempts == 1) throw GeneralSecurityException("boom") else "recovered"
            },
            wipe = { wipeCount++ },
        )
        assertThat(result).isEqualTo("recovered")
        assertThat(attempts).isEqualTo(2)
        assertThat(wipeCount).isEqualTo(1)
    }

    @Test
    fun bothAttemptsThrow_returnsNull_afterExactlyOneWipe() {
        var attempts = 0
        var wipeCount = 0
        val result = createWithRecovery<String>(
            create = { attempts++; throw SecurityException("still broken") },
            wipe = { wipeCount++ },
        )
        assertThat(result).isNull()
        assertThat(attempts).isEqualTo(2)
        assertThat(wipeCount).isEqualTo(1)
    }

    @Test
    fun wipeItselfThrowing_doesNotMaskRetry() {
        var attempts = 0
        val result = createWithRecovery(
            create = {
                attempts++
                if (attempts == 1) throw SecurityException("boom") else "recovered"
            },
            wipe = { throw IllegalStateException("wipe failed") },
        )
        assertThat(result).isEqualTo("recovered")
        assertThat(attempts).isEqualTo(2)
    }
}
