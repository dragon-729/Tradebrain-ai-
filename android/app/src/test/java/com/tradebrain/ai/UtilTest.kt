package com.tradebrain.ai

import com.tradebrain.ai.data.extractErrorMessage
import com.tradebrain.ai.data.friendly
import com.tradebrain.ai.data.rateLimitMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.UnknownHostException

class UtilTest {
    @Test fun parsesFastApiDetail() =
        assertEquals("File too large", extractErrorMessage("""{"detail":"File too large"}"""))

    @Test fun parsesSupabaseError() =
        assertEquals("Invalid login credentials", extractErrorMessage("""{"error_description":"Invalid login credentials"}"""))

    @Test fun ignoresNonJson() = assertNull(extractErrorMessage("<html>502</html>"))

    @Test fun rateLimitUsesRetryAfter() = assertTrue(rateLimitMessage("42").contains("42 seconds"))

    @Test fun rateLimitWithoutHeader() = assertTrue(rateLimitMessage(null).contains("shortly"))

    @Test fun backendUnavailableIsFriendly() {
        assertTrue(ConnectException("refused").friendly().contains("Can't reach the server"))
        assertTrue(UnknownHostException("x").friendly().contains("Can't reach the server"))
    }
}
