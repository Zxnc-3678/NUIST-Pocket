package cn.nuist.pocket.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.io.File
import java.net.URLDecoder

class SchoolDataTest {
    private fun asset(name: String) = File("src/main/assets/$name").readText()
    private fun courseResponse(row: String) = """{"code":"0","datas":{"cxxszhxqkb":{"rows":[$row]}}}"""

    @Test fun weekMaskPreservesLeadingZerosAndGaps() {
        assertEquals(setOf(1, 2, 3, 5, 6, 9, 10, 11, 13, 14, 15, 16),
            SchoolParser.parseWeeks("1110110011101111000000000"))
        assertEquals(setOf(6, 7), SchoolParser.parseWeeks("0000011"))
    }
    @Test(expected = SchoolDataException::class) fun corruptWeekMaskIsRejected() { SchoolParser.parseWeeks("01x1") }
    @Test fun weekBoundaryAndPreTermDoNotRoundTowardZero() {
        val term = Semester("2026-2027-1", LocalDate.of(2026, 8, 31))
        assertEquals(8, term.weekOn(LocalDate.of(2026, 10, 21)))
        assertEquals(0, term.weekOn(LocalDate.of(2026, 8, 30)))
        assertEquals(LocalDate.of(2026, 10, 22), term.dateOf(8, 4))
    }
    @Test fun nullCourseTimesUsePeriodDictionaryAndIncludeLastPeriod() {
        val course = SchoolParser.courses(asset("courses-demo.json")).first()
        assertEquals(LocalTime.of(8, 0) to LocalTime.of(9, 40), course.times(SchoolParser.periods(asset("periods.json"))))
    }
    @Test fun explicitCourseTimesArePreserved() {
        val course = SchoolParser.courses(asset("courses-demo.json")).first { it.id == "demo-6" }
        assertEquals(LocalTime.of(15, 55) to LocalTime.of(17, 35), course.times(emptyList()))
    }
    @Test fun sameCourseWithDifferentSlotsIsNotDeduplicated() {
        val rows = SchoolParser.courses(asset("courses-demo.json"))
        assertEquals(2, rows.count { it.code == "DEMO01" })
    }
    @Test fun unassignedCourseIsKeptButNeverScheduled() {
        val row = SchoolParser.courses(asset("courses-demo.json")).last()
        assertFalse(row.arranged)
        assertFalse(row.occurs(8, 1))
    }
    @Test(expected = SchoolDataException::class) fun reversedPeriodRangeIsRejected() {
        SchoolParser.courses(courseResponse("""{"KCM":"示例","KSJC":"4","JSJC":"2","SKZC":"1"}"""))
    }
    @Test fun persistedCoursesRemoveIdentityFields() {
        val input = courseResponse("""{"KCM":"示例","SKZC":"0001","XH":"IDENTITY_SENTINEL","XM":"PERSON_SENTINEL"}""")
        val saved = SchoolParser.safeCourseJson(SchoolParser.courses(input))
        assertFalse(saved.contains("IDENTITY_SENTINEL")); assertFalse(saved.contains("PERSON_SENTINEL"))
        assertEquals(setOf(4), SchoolParser.courses(saved).single().weeks)
    }
    @Test fun nullAndMalformedOccupancyAreNotFree() {
        listOf(null, "", "undefined", "2_01", "0_01,broken", "0_01,").forEach {
            assertEquals(Occupancy.UNKNOWN, decodeOccupancy(it))
        }
    }
    @Test fun occupancyIncludesAllSources() {
        assertEquals(Occupancy.OCCUPIED, decodeOccupancy("0_01,1_04"))
        assertEquals(Occupancy.NO_MARK, decodeOccupancy("0_01,0_04"))
    }
    @Test fun roomSamplesDoNotClaimAllPagesOrWholeBlockFree() {
        val page = SchoolParser.classrooms(asset("rooms-demo.json"))
        assertEquals(6, page.total); assertEquals(6, page.rows.size); assertTrue(page.complete)
        val room = page.rows.single { it.name == "示例教学楼A102" }
        assertEquals(Occupancy.OCCUPIED, room.status(7))
        assertEquals(Occupancy.NO_MARK, room.status(8))
        assertEquals(Occupancy.OCCUPIED, room.status(7..8))
        assertEquals(Occupancy.UNKNOWN, page.rows.single { it.name == "示例教学楼A101" }.status(1..2))
    }
    @Test fun negativeTotalSizeDoesNotHideExamRows() {
        assertEquals(4, SchoolParser.exams(asset("exams-demo.json")).size)
    }
    @Test fun examsUseOwnTimesAndDifferentiateEndedFromUnarranged() {
        val exams = SchoolParser.exams(asset("exams-demo.json"))
        val now = LocalDateTime.of(2026, 10, 21, 12, 0)
        assertEquals(LocalTime.of(13, 30), exams.first().start)
        assertEquals(ExamState.UPCOMING, exams.first().state(now))
        assertEquals(ExamState.UNARRANGED, exams[2].state(now))
        assertEquals(ExamState.ENDED, exams[3].state(now))
    }
    @Test fun examStartIsInclusiveAndEndIsExclusive() {
        val exam = SchoolParser.exams(asset("exams-demo.json")).first()
        assertEquals(ExamState.ONGOING, exam.state(LocalDateTime.of(2026, 11, 20, 13, 30)))
        assertEquals(ExamState.ENDED, exam.state(LocalDateTime.of(2026, 11, 20, 15, 30)))
    }
    @Test(expected = SchoolDataException::class) fun failedQueryIsNotEmptySuccess() {
        SchoolParser.exams("""{"code":"0","datas":{"cxxsksap":{"extParams":{"code":-1},"rows":[]}}}""")
    }
    @Test(expected = SchoolDataException::class) fun loginHtmlIsNotEmptySuccess() { SchoolParser.exams("<html>Login</html>") }
    @Test fun twelfthPeriodIsPreservedAsDisabled() {
        val periods = SchoolParser.periods(asset("periods.json"))
        assertEquals(12, periods.size); assertFalse(periods.last().enabled)
    }
    @Test fun roomRequestKeepsDateWeekAndQuerySettingConsistent() {
        val request = SchoolRequests.rooms(Semester("2026-2027-1", LocalDate.of(2026, 8, 31)),
            LocalDate.of(2026, 10, 22), "1-202", 2)
        assertEquals("8", request.fields["ZC"]); assertEquals("4", request.fields["XQ"])
        assertEquals("2", request.fields["pageNumber"])
        val query = Json.parseToJsonElement(request.fields.getValue("querySetting")).jsonArray.associate {
            val item = it.jsonObject; item.getValue("name").jsonPrimitive.content to item.getValue("value").jsonPrimitive.content
        }
        for (key in listOf("ZC", "XQ", "RQ", "XNXQDM", "*order")) assertEquals(request.fields[key], query[key])
        assertEquals("1-202", query["JXLDM"])
    }
    @Test fun formEncodingDoesNotTurnSortPlusIntoSpace() {
        val request = SchoolRequests.rooms(Semester("2026-2027-1", LocalDate.of(2026, 8, 31)),
            LocalDate.of(2026, 10, 22), "1-202", 1)
        val fields = request.encodedForm().split('&').associate {
            val (key, value) = it.split('=', limit = 2)
            URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8")
        }
        assertEquals("+LC,+JASMC", fields["*order"])
    }
    @Test fun examsKeepNestedFormParameter() {
        val request = SchoolRequests.exams("2026-2027-1")
        assertEquals(setOf("requestParamStr"), request.fields.keys)
        assertEquals("-KSRQ,-KSSJMS", Json.parseToJsonElement(request.fields.getValue("requestParamStr"))
            .jsonObject.getValue("*order").jsonPrimitive.content)
    }
}

