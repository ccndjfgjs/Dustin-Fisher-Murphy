package dev.opencode.mobile.ai

import org.junit.Assert.assertEquals
import org.junit.Test

class RequestRetryTest {
    @Test fun backoffAndRetryAfterAreBounded() {
        assertEquals(0L, RequestRetry.delayMillis(0, "0", 0))
        assertEquals(2000L, RequestRetry.delayMillis(0, "2", 0))
        assertEquals(10000L, RequestRetry.delayMillis(0, "1000", 0))
        assertEquals(750L, RequestRetry.delayMillis(0, null, 0))
        assertEquals(1500L, RequestRetry.delayMillis(1, "invalid", 0))
    }
}
