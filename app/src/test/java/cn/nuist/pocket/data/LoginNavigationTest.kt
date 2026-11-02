package cn.nuist.pocket.data

import org.junit.Assert.*
import org.junit.Test

class LoginNavigationTest {
    private val auth = "https://authserver.nuist.edu.cn/authserver/login"
    @Test fun embeddedBlankDocumentCanBootstrapMfa() {
        assertTrue(LoginNavigation.allowed("about:blank", false, auth))
        assertTrue(LoginNavigation.allowed("about:blank#step2", false, auth))
        assertTrue(LoginNavigation.allowed("about:srcdoc", false, auth))
    }
    @Test fun blankDocumentCannotReplaceLoginPage() {
        assertFalse(LoginNavigation.allowed("about:blank", true, auth))
    }
    @Test fun embeddedDocumentsRequireTrustedTopLevel() {
        assertFalse(LoginNavigation.allowed("about:blank", false, "https://evil.example/"))
        assertFalse(LoginNavigation.allowed("about:blank", false, null))
        assertFalse(LoginNavigation.allowed("https://authserver.nuist.edu.cn/", false, "https://evil.example/"))
    }
    @Test fun schoolHostedMfaFrameIsAllowedButNotArbitraryTopLevel() {
        assertTrue(LoginNavigation.allowed("https://mfa.nuist.edu.cn/component", false, auth))
        assertFalse(LoginNavigation.allowed("https://mfa.nuist.edu.cn/component", true, auth))
    }
    @Test fun parameterPunctuationDoesNotChangeTrustedOrigin() {
        assertTrue(LoginNavigation.allowed("$auth?config={\"stage\":2}", true, auth))
    }
    @Test fun suffixSpoofAndHttpRemainBlocked() {
        listOf("https://authserver.nuist.edu.cn.evil.example/", "http://authserver.nuist.edu.cn/",
            "https://x@authserver.nuist.edu.cn/", "https://authserver.nuist.edu.cn:8888/", "file:///storage/test").forEach {
            assertFalse(it, LoginNavigation.allowed(it, false, auth))
        }
    }
    @Test fun blobBootstrapKeepsSchoolOwnerConstraint() {
        assertTrue(LoginNavigation.allowed("blob:https://authserver.nuist.edu.cn/frame-id", false, auth))
        assertFalse(LoginNavigation.allowed("blob:https://evil.example/frame-id", false, auth))
    }
    @Test fun labelsNeverContainQueryPathOrSecrets() {
        val label = LoginNavigation.destinationLabel("https://authserver.nuist.edu.cn/private/SENTINEL?ticket=SECRET#CODE")
        assertEquals("https://authserver.nuist.edu.cn", label)
        assertFalse(label.contains("SECRET")); assertFalse(label.contains("SENTINEL"))
        assertEquals("data:", LoginNavigation.destinationLabel("data:text/html,SECRET"))
        assertEquals("javascript:", LoginNavigation.destinationLabel("javascript:SECRET()"))
    }
}
