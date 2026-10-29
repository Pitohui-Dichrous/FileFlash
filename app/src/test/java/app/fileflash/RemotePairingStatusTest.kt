package app.fileflash

import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePairingStatusTest {
    @Test fun revokedSessionAndStaleQrRequireNewPairing() {
        assertTrue(HttpFailure(401, "连接已失效，请重新配对", "session_expired").isPairingExpiredFailure())
        assertTrue(HttpFailure(401, "配对码无效，请扫描新的二维码", "invalid_token").isPairingExpiredFailure())
    }

    @Test fun networkInterruptionAndUserCancellationAreNotExpiredPairing() {
        assertFalse(SocketTimeoutException("读取超时").isPairingExpiredFailure())
        assertFalse(IOException("连接已失效，请重新配对").isPairingExpiredFailure())
        assertFalse(TransferCancelled().isPairingExpiredFailure())
    }

    @Test fun rejectedOriginsAndTaskPermissionsDoNotMasqueradeAsExpiredPairing() {
        for (code in listOf("origin_rejected", "host_rejected", "not_owner", "upload_key_required")) {
            assertFalse(HttpFailure(403, "请求被拒绝", code).isPairingExpiredFailure())
        }
        assertFalse(HttpFailure(429, "请求过于频繁", "pairing_limit").isPairingExpiredFailure())
    }

    @Test fun unknownOrMismatchedErrorsRemainOrdinaryErrors() {
        assertFalse(HttpFailure(401, "连接授权已失效，请重新扫码").isPairingExpiredFailure())
        assertFalse(HttpFailure(401, "连接失败", "unrecognized_proxy_error").isPairingExpiredFailure())
        assertFalse(HttpFailure(500, "服务端失败", "session_expired").isPairingExpiredFailure())
        assertFalse(HttpFailure(403, "拒绝", "invalid_token").isPairingExpiredFailure())
    }
}
