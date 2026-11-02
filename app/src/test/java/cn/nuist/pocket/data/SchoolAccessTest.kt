package cn.nuist.pocket.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class SchoolAccessTest {
    private val semester = Semester("2026-2027-1", LocalDate.of(2026, 8, 31))
    private fun page(total: Int, vararg ids: String, building: String = "1-120"): String = buildJsonObject {
        put("code", "0"); putJsonObject("datas") { putJsonObject("cxjsqk") {
            put("totalSize", total); put("pageNumber", 1); put("pageSize", 10)
            putJsonArray("rows") { ids.forEach { id -> addJsonObject {
                put("JASDM", id); put("JASMC", id); put("JXLDM", building); put("JC1", "0_01")
            } } }
        } }
    }.toString()
    private class FakeTransport(val answers: List<String>) : SchoolTransport {
        val requests = mutableListOf<SchoolRequest>()
        override suspend fun get(url: String) = "school page"
        override suspend fun post(request: SchoolRequest): String {
            requests += request
            return answers.getOrElse(requests.size - 1) { error("Unexpected request") }
        }
    }
    @Test fun loginOriginsRequireExactHttpsSchoolHosts() {
        assertTrue(SchoolOrigins.allowed("https://authserver.nuist.edu.cn/authserver/login"))
        assertTrue(SchoolOrigins.allowed(SchoolRequests.PORTAL))
        listOf("http://jwxt.nuist.edu.cn/", "https://jwxt.nuist.edu.cn.evil.test/", "https://evil.test/nuist.edu.cn",
            "https://user@jwxt.nuist.edu.cn/", "file:///tmp/login", "https://jwxt.nuist.edu.cn:8888/").forEach {
            assertFalse(it, SchoolOrigins.allowed(it))
        }
    }
    @Test fun wendeUsesConfirmedBuildingCodeAndAllPages() = runBlocking {
        val http = FakeTransport(listOf(page(3, "N101", "N102"), page(3, "N103")))
        val result = SchoolApi(http).rooms(semester, LocalDate.of(2026, 10, 22), StudyBuilding.WENDE)
        assertEquals(3, result.rooms.size)
        assertEquals(listOf("1", "2"), http.requests.map { it.fields["pageNumber"] })
        http.requests.forEach {
            val query = Json.parseToJsonElement(it.fields.getValue("querySetting")).jsonArray
            assertEquals("1-120", query.first { item -> item.jsonObject["name"]?.jsonPrimitive?.content == "JXLDM" }
                .jsonObject.getValue("value").jsonPrimitive.content)
        }
    }
    @Test fun missingPageNeverBecomesPartialSuccess() = runBlocking {
        val http = FakeTransport(listOf(page(3, "N101"), page(3)))
        try { SchoolApi(http).rooms(semester, LocalDate.of(2026, 10, 22), StudyBuilding.WENDE); fail("Expected failure") }
        catch (_: SchoolConnectionError) { }
    }
    @Test fun repeatedPageIsDetected() = runBlocking {
        val http = FakeTransport(listOf(page(3, "N101"), page(3, "N101")))
        try { SchoolApi(http).rooms(semester, LocalDate.of(2026, 10, 22), StudyBuilding.WENDE); fail("Expected failure") }
        catch (_: SchoolConnectionError) { }
    }
    @Test fun anotherBuildingIsRejected() = runBlocking {
        val http = FakeTransport(listOf(page(1, "N101", building = "1-202")))
        try { SchoolApi(http).rooms(semester, LocalDate.of(2026, 10, 22), StudyBuilding.WENDE); fail("Expected failure") }
        catch (_: SchoolConnectionError) { }
    }
    @Test fun changedTotalIsRejected() = runBlocking {
        val http = FakeTransport(listOf(page(3, "N101"), page(4, "N102")))
        try { SchoolApi(http).rooms(semester, LocalDate.of(2026, 10, 22), StudyBuilding.WENDE); fail("Expected failure") }
        catch (_: SchoolConnectionError) { }
    }
    @Test fun zeroRoomsIsValidNotDemoFallback() = runBlocking {
        val result = SchoolApi(FakeTransport(listOf(page(0)))).rooms(semester, LocalDate.of(2026, 10, 22), StudyBuilding.WENDE)
        assertTrue(result.rooms.isEmpty()); assertEquals(0, result.reportedTotal)
    }
    @Test fun emptyAndInvalidSemesterResponsesAreRejected() {
        assertEquals("2026-2027-1", SchoolParser.currentSemester("""{"code":"0","datas":{"dqxnxq":{"rows":[{"DM":"2026-2027-1"}]}}}"""))
        try { SchoolParser.currentSemester("""{"code":"0","datas":{"dqxnxq":{"rows":[]}}}"""); fail("Expected failure") }
        catch (_: SchoolDataException) { }
    }
    @Test fun examCacheKeepsSeatButStripsIdentity() {
        val raw = """{"code":"0","datas":{"cxxsksap":{"rows":[{"KCM":"示例","ZWH":"07","XH":"IDENTITY_SENTINEL","XSBH":"STUDENT_SENTINEL"}]}}}"""
        val safe = SchoolParser.safeExamJson(SchoolParser.exams(raw))
        assertFalse(safe.contains("IDENTITY_SENTINEL")); assertFalse(safe.contains("STUDENT_SENTINEL"))
        assertEquals("07", SchoolParser.exams(safe).single().seat)
    }
    @Test fun dictionaryAloneDoesNotVerifyLogin() = runBlocking {
        val http = FakeTransport(listOf(
            """{"code":"0","datas":{"dqxnxq":{"rows":[{"DM":"2026-2027-1"}]}}}""", "<html>Login</html>"))
        try { SchoolApi(http).verifyLogin(); fail("Expected failure") } catch (_: SchoolDataException) { }
        assertEquals(2, http.requests.size)
    }
}
