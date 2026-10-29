package dev.opencode.mobile.ai

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderUrlPolicyTest {
    @Test fun httpsAllowsApiKey() {
        assertNull(ProviderUrlPolicy.error("https://api.openai.com/v1", "secret"))
    }

    @Test fun localHttpAllowsKeylessEmulatorAndLan() {
        listOf("http://127.0.0.1:11434/v1", "http://10.0.2.2:11434/v1", "http://192.168.1.2:1234/v1", "http://[::1]:11434/v1").forEach {
            assertNull(it, ProviderUrlPolicy.error(it, ""))
        }
    }

    @Test fun httpCannotLeakCredentialsOrUsePublicServers() {
        assertNotNull(ProviderUrlPolicy.error("http://10.0.2.2:11434/v1", "secret"))
        assertNotNull(ProviderUrlPolicy.error("http://example.com/v1", ""))
        assertNotNull(ProviderUrlPolicy.error("http://172.32.0.1/v1", ""))
    }

    @Test fun rejectsMalformedAndCredentialBearingUrls() {
        listOf("", "ftp://localhost", "https://user:password@example.com", "https://example.com/v1?token=x", "https://example.com/#fragment").forEach {
            assertNotNull(it, ProviderUrlPolicy.error(it, ""))
        }
    }
}
