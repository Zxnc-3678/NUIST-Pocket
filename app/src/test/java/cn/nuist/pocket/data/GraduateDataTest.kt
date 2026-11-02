package cn.nuist.pocket.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class GraduateDataTest {
    private fun json(value: String) = Json.parseToJsonElement(value)
    private val code = "2026-2027-1"
    private val term = """[{"termcode":"99","termname":"2025-2026春季学期"},{"termcode":"55","termname":"2026-2027秋季学期","selected":true}]"""
    private fun cell(name: String = "虚构课程", weeks: String = "1-4", mode: String = "连续", room: String = "测试教室"): String =
        "<br/>课程:$name<br/>班级:虚构测试班<br/>[$weeks" + "周] ($mode)测试教师[$room]"
    private fun row(number: Int, value: String? = null, day: Int = 1, label: String = "上午$number") = buildJsonObject {
        put("jcid", 800 + number); put("mc", label); put("sjbz", "测试时段")
        (1..7).forEach { put("z$it", if (it == day && value != null) JsonPrimitive(value) else JsonNull) }
    }
    private fun schedule(vararg rows: JsonObject) = buildJsonObject { put("rows", JsonArray(rows.toList())); put("week", "Monday") }
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected invalid school data to be rejected") } catch (_: SchoolDataException) { }
    }

    @Test fun independentEncryptedEmptyVectorIsAccepted() {
        assertEquals(JsonArray(emptyList()), GraduateCodec.decode("2Ok7AnippEVOKF+5fpkV3A=="))
    }
    @Test fun independentEncryptedUnicodeVectorIsAccepted() {
        assertEquals("虚构测试", GraduateCodec.decode("9s23WXvIJBt/i4SqRDmePFkHdnVhR+uhmterwc7zf4CNea1cCCVczxBHtyLfdoSX").jsonObject["label"]?.jsonPrimitive?.content)
    }
    @Test fun plainJsonIsCompatibleButErrorPrimitivesAreRejected() {
        assertEquals(0, GraduateCodec.decode(" [] ").jsonArray.size)
        listOf("", "error", "false", "broken ciphertext").forEach { rejected { GraduateCodec.decode(it) } }
    }
    @Test fun htmlCannotBecomeAnEmptySchedule() {
        try { GraduateCodec.decode("<!DOCTYPE html><title>Login</title>"); fail("Expected login required") }
        catch (_: SchoolLoginRequired) { }
    }
    @Test fun oversizedResponseIsRejectedBeforeDecode() { rejected { GraduateCodec.decode("a".repeat(4 * 1024 * 1024 + 1)) } }
    @Test fun defaultSemesterUsesSelectedRatherThanLargestId() {
        val selected = GraduateParser.selectedTerm(json(term))
        assertEquals("55", selected.id); assertEquals(code, selected.code)
    }
    @Test fun springSemesterMapsToSecondTerm() {
        assertEquals("2025-2026-2", GraduateParser.terms(json(term)).first().code)
    }
    @Test fun unfamiliarHistoricalTermNameDoesNotBlockTheSelectedTerm() {
        assertEquals(code,GraduateParser.selectedTerm(json(term.replace("2025-2026春季学期","较早的其他学期名称"))).code)
        rejected { GraduateParser.selectedTerm(json(term.replace("2026-2027秋季学期","未知当前学期"))) }
    }
    @Test fun oversizedTermDictionaryIsRejected() {
        val data=buildJsonArray { repeat(513) { i -> addJsonObject { put("termcode",i); put("termname","测试学期") } } }
        rejected { GraduateParser.selectedTerm(data) }
    }
    @Test fun ambiguousOrMissingSelectedTermIsRejected() {
        rejected { GraduateParser.selectedTerm(json(term.replace(",\"selected\":true", ""))) }
        rejected { GraduateParser.selectedTerm(json(term.replace("\"termcode\":\"99\"", "\"selected\":true,\"termcode\":\"99\""))) }
    }
    @Test fun duplicateIdsAndInvalidAcademicYearAreRejected() {
        rejected { GraduateParser.terms(json(term.replace("\"99\"", "\"55\""))) }
        rejected { GraduateParser.terms(json(term.replace("2026-2027秋季", "2026-2030秋季"))) }
    }
    @Test fun adjacentEqualCellsBecomeOneFullLengthCourse() {
        val text = cell("虚构的特别长课程名称保持完整不缩写")
        val result = GraduateParser.courses(schedule(row(1,text),row(2,text)),code).single()
        assertEquals("虚构的特别长课程名称保持完整不缩写", result.name)
        assertEquals(1,result.firstPeriod); assertEquals(2,result.lastPeriod); assertEquals(setOf(1,2,3,4),result.weeks)
        assertEquals("测试教师",result.teacher); assertEquals("测试教室",result.room)
        assertFalse(result.id.contains("800"))
    }
    @Test fun emptyGapPreventsMerging() {
        val text=cell()
        val result=GraduateParser.courses(schedule(row(1,text),row(2),row(3,text)),code)
        assertEquals(2,result.size); assertEquals(listOf(1,3),result.map { it.firstPeriod })
    }
    @Test fun onlyExactlyEqualRawCellsAreMergedLikeTheSchoolPage() {
        val result=GraduateParser.courses(schedule(row(1,cell()),row(2," "+cell())),code)
        assertEquals(2,result.size)
    }
    @Test fun lineBreaksInsideLongNamesArePreserved() {
        assertEquals("虚构长课程\n名称继续",GraduateParser.courses(schedule(row(1,cell("虚构长课程\n名称继续"))),code).single().name)
    }
    @Test fun equalCellsInDifferentDaysStaySeparate() {
        val r=row(1,cell()).toMutableMap().apply { put("z2",JsonPrimitive(cell())) }
        assertEquals(listOf(1,2),GraduateParser.courses(schedule(JsonObject(r)),code).map { it.weekday })
    }
    @Test fun mergedRowNumbersMustBeConsecutive() { rejected { GraduateParser.courses(schedule(row(1,cell()),row(3,cell())),code) } }
    @Test fun singleWeekAndParityArePreserved() {
        assertEquals(setOf(2),GraduateParser.weeks("2-2","连续"))
        assertEquals(setOf(1,3,5),GraduateParser.weeks("1-6","单周"))
        assertEquals(setOf(2,4,6),GraduateParser.weeks("1-6","双周"))
        assertEquals(setOf(1,3,5,6),GraduateParser.weeks("1、3，5-6","不连续"))
    }
    @Test fun malformedWeeksAndUnknownModesAreNotSilentlyIgnored() {
        listOf("0-4","4-1","1-65","1,x").forEach { rejected { GraduateParser.weeks(it,"连续") } }
        rejected { GraduateParser.weeks("1-2","未知模式") }
    }
    @Test fun oneCellCanContainMoreThanOneCourse() {
        val result=GraduateParser.courses(schedule(row(1,cell("测试甲")+cell("测试乙"))),code)
        assertEquals(listOf("测试甲","测试乙"),result.map { it.name })
    }
    @Test fun specialCharactersInNamesAndTeachersCannotCollapseDifferentCourses() {
        val first=cell("A|B").replace("测试教师","C")
        val second=cell("A").replace("测试教师","B|C")
        val result=GraduateParser.courses(schedule(row(1,first+second)),code)
        assertEquals(2,result.size); assertNotEquals(result[0].id,result[1].id)
    }
    @Test fun equivalentWeekOrderKeepsTheSameCourseIdentity() {
        val first=GraduateParser.courses(schedule(row(1,cell(weeks="1,3"))),code).single()
        val second=GraduateParser.courses(schedule(row(1,cell(weeks="3,1"))),code).single()
        assertEquals(first.id,second.id)
    }
    @Test fun numericHtmlEntitiesDecodeOnceWithoutLosingTheFullName() {
        val course=GraduateParser.courses(schedule(row(1,cell("测试&#x8BFE;&#31243;&amp;lt;&#x1F4D8;"))),code).single()
        assertEquals("测试课程&lt;📘",course.name)
    }
    @Test fun aCourseCanHaveDifferentRoomsInDifferentWeeks() {
        val text=cell(weeks="1-2")+"<br/>[3-4周] (连续)测试教师[另一虚构教室]"
        val result=GraduateParser.courses(schedule(row(1,text)),code)
        assertEquals(2,result.size); assertEquals(setOf(3,4),result.last().weeks); assertEquals("另一虚构教室",result.last().room)
    }
    @Test fun unassignedCourseAndNoPhysicalRoomArePreserved() {
        val result=GraduateParser.courses(schedule(row(1,cell(room="无实际教室"),label="无节次")),code).single()
        assertFalse(result.arranged); assertEquals("无实际教室",result.room); assertEquals("虚构课程",result.name)
    }
    @Test fun unknownNonemptyCellsAndWrongRootCannotBecomeSuccess() {
        rejected { GraduateParser.courses(schedule(row(1,"unrecognized nonempty course")),code) }
        rejected { GraduateParser.courses(json("[]"),code) }
        rejected { GraduateParser.courses(schedule(row(1,cell(),label="未知节次")),code) }
    }
    @Test fun duplicatePeriodLabelsAreRejected() { rejected { GraduateParser.courses(schedule(row(1),row(1)),code) } }
    @Test fun emptyExamsAreValidButWrongShapeIsNot() {
        assertTrue(GraduateParser.exams(json("[]")).isEmpty())
        rejected { GraduateParser.exams(json("{}")) }
        rejected { GraduateParser.exams(json("[{}]")) }
    }
    @Test fun syntheticExamUsesSeatRatherThanStudentCount() {
        val item=GraduateParser.exams(json("""[{"termname":"测试学期","kcbh":"test","kcmc":"虚构考试","dz":"测试考场","ksrs":80,"ksrq":"2026-12-01","kssj":"8:00-9:40","zwh":"007"}]""")).single()
        assertEquals("007",item.seat); assertEquals(LocalDate.of(2026,12,1),item.date)
        assertEquals(LocalTime.of(8,0),item.start); assertEquals(LocalTime.of(9,40),item.end)
    }
    @Test fun malformedDateOrTimeDoesNotInventAnExamTime() {
        val base="""[{"kcmc":"虚构考试","ksrq":"2026-12-01","kssj":"08:00-09:40"}]"""
        rejected { GraduateParser.exams(json(base.replace("2026-12-01","2026-02-30"))) }
        rejected { GraduateParser.exams(json(base.replace("08:00-09:40","待核对的格式"))) }
        rejected { GraduateParser.exams(json(base.replace("08:00-09:40","10:00-09:00"))) }
    }
    @Test fun examIdentityDoesNotDependOnTheServerRowOrder() {
        val first=buildJsonObject { put("kcmc","测试甲"); put("zwh","01") }
        val second=buildJsonObject { put("kcmc","测试乙"); put("zwh","02") }
        val a=GraduateParser.exams(JsonArray(listOf(first,second))).associate { it.name to it.id }
        val b=GraduateParser.exams(JsonArray(listOf(second,first))).associate { it.name to it.id }
        assertEquals(a,b); assertNotEquals(a["测试甲"],a["测试乙"])
    }
    @Test fun missingExamTimesRemainUnarrangedAndStartOnlyHasANote() {
        val item=GraduateParser.exams(json("""[{"kcmc":"虚构考试","ksrq":"2026-12-01","kssj":"08:00"}]""")).single()
        assertEquals(LocalTime.of(8,0),item.start); assertNull(item.end); assertTrue(item.note.orEmpty().contains("结束时间"))
        assertNull(GraduateParser.exams(json("""[{"kcmc":"虚构考试"}]""")).single().date)
    }
    @Test fun requestsUseObservedEmptyExamParametersAndOpaqueSemesterId() {
        assertEquals(emptyMap<String,String>(),GraduateRequests.exams().fields)
        assertEquals(mapOf("kblx" to "xs","termcode" to "55"),GraduateRequests.courses("55").fields)
    }
    @Test fun trackIsolationRetainsLegacyUndergraduateNamesAndHidesOnlyGraduateRooms() {
        val ug=StudentTrack.UNDERGRADUATE; val pg=StudentTrack.GRADUATE
        assertEquals("school_cache",ug.cacheName); assertNotEquals(ug.cacheName,pg.cacheName)
        assertEquals("offline_schedule",ug.importName); assertNotEquals(ug.importName,pg.importName)
        assertEquals(1,ug.visibleTab(1)); assertEquals(0,pg.visibleTab(1))
        listOf(0,2,3).forEach { assertEquals(it,pg.visibleTab(it)) }
        assertNotEquals(ug.preferenceKey("home_tab"),pg.preferenceKey("home_tab"))
        assertNotEquals(ug.preferenceKey("last_reminder_date"),pg.preferenceKey("last_reminder_date"))
    }
    @Test fun graduateCalendarSharesVerifiedDatesButHonorsExplicitCorrections() {
        assertEquals(LocalDate.of(2026,8,31),graduateFirstMonday(code,null))
        assertEquals(LocalDate.of(2027,2,22),graduateFirstMonday("2026-2027-2",null))
        assertEquals(LocalDate.of(2026,9,7),graduateFirstMonday(code,LocalDate.of(2026,9,7)))
        assertNull(graduateFirstMonday("2027-2028-1",null))
    }
    @Test fun partialExamTimesKeepAllKnownInformationVisible() {
        val item=GraduateParser.exams(json("""[{"kcmc":"虚构考试","ksrq":"2026-12-01","kssj":"08:00"}]""")).single()
        val label=examTimeLabel(item)
        assertTrue(label.contains("2026-12-01")); assertTrue(label.contains("08:00")); assertTrue(label.contains("结束时间待定"))
    }
    @Test fun graduateHostsDoNotWeakenTheUndergraduatePolicy() {
        val pg=StudentTrack.GRADUATE
        assertFalse(SchoolOrigins.allowed(GraduateRequests.PORTAL))
        assertTrue(SchoolOrigins.allowed(GraduateRequests.PORTAL,pg))
        assertFalse(LoginNavigation.allowed(GraduateRequests.PORTAL,true,null))
        assertTrue(LoginNavigation.allowed(GraduateRequests.PORTAL,true,null,pg))
        listOf("http://nxdyjs.nuist.edu.cn/","https://nxdyjs.nuist.edu.cn.evil.test/","https://user@nxdyjs.nuist.edu.cn/","https://nxdyjs.nuist.edu.cn:8443/").forEach {
            assertFalse(SchoolOrigins.allowed(it,pg)); assertFalse(LoginNavigation.allowed(it,true,null,pg))
        }
    }
    private inner class GraduateTransport(private val exam: String="[]",private val courseData: String = schedule(row(1,cell())).toString()) : SchoolTransport {
        val requested=mutableListOf<String>()
        override suspend fun get(url:String):String { requested+=url; return if(url==GraduateRequests.SEMESTERS)term else "school page" }
        override suspend fun post(request:SchoolRequest):String {
            requested+=request.url
            return if(request.url.endsWith("py_kbcx_ew")) courseData else exam
        }
    }
    @Test fun unavailableCalendarReportsItsSemesterAndMissingPeriodsAreRejected() = runBlocking {
        val http=GraduateTransport()
        assertEquals(code,GraduateApi(http,{null},{emptyList()}).verifyLogin())
        try { GraduateApi(http,{null},{emptyList()}).sync(); fail("Expected calendar required") }
        catch (error:GraduateCalendarRequired) { assertEquals(code,error.semesterCode) }
        try { GraduateApi(http,{LocalDate.of(2026,8,31)},{emptyList()}).sync(); fail("Expected periods required") } catch (_:SchoolConnectionError) { }
    }
    @Test fun graduateSyncUsesItsOwnEndpointsAndAllowsEmptyExams() = runBlocking {
        val http=GraduateTransport()
        val periods=listOf(Period(1,LocalTime.of(8,0),LocalTime.of(8,45),true))
        val result=SchoolApi(http,StudentTrack.GRADUATE,{LocalDate.of(2026,8,31)},{periods}).sync()
        assertEquals(code,result.semester.code); assertTrue(result.exams.isEmpty()); assertEquals(1,result.courses.size)
        assertTrue(http.requested.all { it.startsWith("https://nxdyjs.nuist.edu.cn/") })
    }
    @Test fun graduateRoomQueriesStopBeforeAnyNetworkCall() = runBlocking {
        val http=GraduateTransport()
        try { SchoolApi(http,StudentTrack.GRADUATE).rooms(Semester(code,LocalDate.of(2026,8,31)),LocalDate.of(2026,9,1),StudyBuilding.MINGDE); fail("Expected disabled feature") }
        catch (_:SchoolConnectionError) { }
        assertTrue(http.requested.isEmpty())
    }
    @Test fun mergedCoursesCannotSkipAnUnknownMiddlePeriod() = runBlocking {
        val http=GraduateTransport(courseData=schedule(row(1,cell()),row(2,cell()),row(3,cell())).toString())
        val periods=listOf(1,3).map { Period(it,LocalTime.of(8+it,0),LocalTime.of(8+it,45),true) }
        try { GraduateApi(http,{LocalDate.of(2026,8,31)},{periods}).sync(); fail("Expected missing period failure") }
        catch (_:SchoolDataException) { }
        assertFalse(http.requested.any { it.endsWith("kckccx_list") })
    }
}
