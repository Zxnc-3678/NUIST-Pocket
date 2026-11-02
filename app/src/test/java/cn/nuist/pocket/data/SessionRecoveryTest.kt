package cn.nuist.pocket.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class SessionRecoveryTest {
    private val semester = Semester("2026-2027-1", LocalDate.of(2026, 8, 31))
    private val date = LocalDate.of(2026, 10, 22)
    private fun page(vararg ids: String) = """{"code":"0","datas":{"cxjsqk":{"totalSize":3,"rows":[${ids.joinToString(",") { """{"JASDM":"$it","JASMC":"$it","JXLDM":"1-301"}""" }}]}}}"""
    private class Transport(val postReply: (Int) -> String, val portalExpired: Boolean = false) : SchoolTransport {
        val posts = mutableListOf<SchoolRequest>()
        val gets = mutableListOf<String>()
        override suspend fun get(url: String): String {
            gets += url
            if (portalExpired && url == SchoolRequests.PORTAL) throw SchoolLoginRequired()
            return "school page"
        }
        override suspend fun post(request: SchoolRequest): String { posts += request; return postReply(posts.size) }
    }
    @Test fun existingSsoSessionCanRecoverAndReturnEveryPage() = runBlocking {
        val http = Transport({ n -> when(n) { 1 -> throw SchoolLoginRequired(); 2 -> page("A", "B"); else -> page("C") } })
        val result = SchoolApi(http).rooms(semester, date, StudyBuilding.BINJIANG)
        assertEquals(listOf("A", "B", "C"), result.rooms.map { it.code })
        assertEquals(listOf(SchoolRequests.ROOM_ENTRY, SchoolRequests.PORTAL, SchoolRequests.ROOM_ENTRY), http.gets)
        assertEquals(listOf("1", "1", "2"), http.posts.map { it.fields["pageNumber"] })
    }
    @Test fun expiryDuringPaginationRestartsFromPageOneWithoutMixingRecords() = runBlocking {
        val http = Transport({ n -> when(n) { 1 -> page("OLD1", "OLD2"); 2 -> throw SchoolLoginRequired(); 3 -> page("NEW1", "NEW2"); else -> page("NEW3") } })
        val result = SchoolApi(http).rooms(semester, date, StudyBuilding.BINJIANG)
        assertEquals(listOf("NEW1", "NEW2", "NEW3"), result.rooms.map { it.code })
        assertEquals(listOf("1", "2", "1", "2"), http.posts.map { it.fields["pageNumber"] })
    }
    @Test fun realSsoExpiryStopsBeforeRetryingQuery() = runBlocking {
        val http = Transport({ throw SchoolLoginRequired() }, portalExpired = true)
        try { SchoolApi(http).rooms(semester, date, StudyBuilding.BINJIANG); fail("Needs user login") } catch (_: SchoolLoginRequired) { }
        assertEquals(1, http.posts.size)
    }
    @Test fun secondFailureNeverCreatesAnInfiniteRecoveryLoop() = runBlocking {
        val http = Transport({ throw SchoolLoginRequired() })
        try { SchoolApi(http).rooms(semester, date, StudyBuilding.BINJIANG); fail("Needs user login") } catch (_: SchoolLoginRequired) { }
        assertEquals(2, http.posts.size)
        assertEquals(1, http.gets.count { it == SchoolRequests.PORTAL })
    }
    @Test fun ordinaryNetworkFailureDoesNotTriggerLoginRecovery() = runBlocking {
        val http = Transport({ throw SchoolConnectionError("timeout") })
        try { SchoolApi(http).rooms(semester, date, StudyBuilding.BINJIANG); fail("Needs retry") } catch (_: SchoolConnectionError) { }
        assertEquals(listOf(SchoolRequests.ROOM_ENTRY), http.gets)
    }
}
