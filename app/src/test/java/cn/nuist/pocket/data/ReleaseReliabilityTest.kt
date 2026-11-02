package cn.nuist.pocket.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ReleaseReliabilityTest {
    private fun term(code: String) = """{"code":"0","datas":{"dqxnxq":{"rows":[{"DM":"$code"}]}}}"""
    private fun courses(code: String) = """{"code":"0","datas":{"cxxszhxqkb":{"rows":[{"WID":"same-id","KCM":"课程","XNXQDM":"$code","SKZC":"1","KSJC":"1","JSJC":"2","SKXQ":1}]}}}"""
    private val periods = """{"code":"0","datas":{"jc":{"rows":[{"DM":1,"KSSJ":"08:00","JSSJ":"08:45","SFSY":1}]}}}"""
    private val exams = """{"code":"0","datas":{"cxxsksap":{"totalSize":-1,"rows":[]}}}"""
    private class Transport(private val replies: List<String>) : SchoolTransport {
        val posts = mutableListOf<SchoolRequest>()
        override suspend fun get(url: String) = "school page"
        override suspend fun post(request: SchoolRequest): String {
            posts += request
            return replies[posts.lastIndex]
        }
    }
    @Test fun syncReadsTimetableOnceAndKeepsEmptyExamsAsRealData() = runBlocking {
        val http = Transport(listOf(term("2026-2027-1"), courses("2026-2027-1"), periods, exams))
        val result = SchoolApi(http).sync()
        assertEquals(1, http.posts.count { it.url.endsWith("/cxxszhxqkb.do") })
        assertEquals(4, http.posts.size)
        assertEquals(LocalDate.of(2026, 8, 31), result.semester.firstMonday)
        assertEquals(1, result.courses.size); assertTrue(result.exams.isEmpty())
    }
    @Test fun mixedSemesterNeverPassesLoginVerification() = runBlocking {
        val http = Transport(listOf(term("2026-2027-1"), courses("2026-2027-2")))
        try { SchoolApi(http).verifyLogin(); fail("Expected rejection") } catch (_: SchoolConnectionError) {}
    }
    @Test fun unverifiedCalendarCannotBeSilentlyGuessed() = runBlocking {
        val http = Transport(listOf(term("2027-2028-1"), courses("2027-2028-1")))
        try { SchoolApi(http).sync(); fail("Expected rejection") } catch (_: SchoolConnectionError) {}
        assertEquals(2, http.posts.size)
        assertNull(AcademicCalendar.semester("2027-2028-1"))
    }
    @Test fun midnightFollowsTodayAcrossWeeksButKeepsManualSelections() {
        val previous = LocalDate.of(2026, 10, 25)
        val today = previous.plusDays(1)
        assertEquals(today, followToday(previous, previous, today))
        assertEquals(LocalDate.of(2026, 10, 22), followToday(LocalDate.of(2026, 10, 22), previous, today))
        val semester = AcademicCalendar.semester("2026-2027-1")!!
        assertEquals(9, semester.weekOn(followToday(previous, previous, today)))
    }
    @Test fun emptyPeriodDictionaryIsNotAValidSyncedSnapshot() {
        try { SchoolParser.periods("""{"code":"0","datas":{"jc":{"rows":[]}}}"""); fail("Expected rejection") }
        catch (_: SchoolDataException) {}
    }
    @Test fun explicitCourseTimeCannotRunBackwards() {
        val raw = courses("2026-2027-1").replace("\"KCM\":\"课程\"", "\"KCM\":\"课程\",\"KSSJ\":\"11:00\",\"JSSJ\":\"08:00\"")
        try { SchoolParser.courses(raw); fail("Expected rejection") } catch (_: SchoolDataException) {}
    }
    @Test fun overlappingRoomPagesAreRejectedEvenWhenUniqueCountReachesTotal() = runBlocking {
        fun page(vararg ids: String) = """{"code":"0","datas":{"cxjsqk":{"totalSize":3,"rows":[${ids.joinToString(",") { """{"JASDM":"$it","JASMC":"$it","JXLDM":"1-120"}""" }}]}}}"""
        val http = Transport(listOf(page("A", "B"), page("B", "C")))
        try { SchoolApi(http).rooms(AcademicCalendar.semester("2026-2027-1")!!, LocalDate.of(2026, 10, 22), StudyBuilding.WENDE); fail("Expected rejection") }
        catch (_: SchoolConnectionError) {}
    }
    @Test fun expirationDuringExamSyncNeverReturnsPartialSuccess() = runBlocking {
        var count = 0
        val transport = object : SchoolTransport {
            override suspend fun get(url: String) = "school page"
            override suspend fun post(request: SchoolRequest): String = when (++count) {
                1 -> term("2026-2027-1")
                2 -> courses("2026-2027-1")
                3 -> periods
                else -> throw SchoolLoginRequired()
            }
        }
        try { SchoolApi(transport).sync(); fail("Expired login must not return a new cache snapshot") }
        catch (_: SchoolLoginRequired) {}
        assertEquals(5, count) // The first request after one SSO recovery attempt still fails.
    }
}
