package cn.nuist.pocket.data

import kotlinx.serialization.json.*
import java.time.LocalDate
import java.net.URLEncoder

/** 已核对的表单请求契约。 */
data class SchoolRequest(val url: String, val fields: Map<String, String>) {
    fun encodedForm(): String = fields.entries.joinToString("&") {
        "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}"
    }
}

object SchoolRequests {
    private const val BASE = "https://jwxt.nuist.edu.cn/jwapp/sys/"
    const val PORTAL = "https://jwxt.nuist.edu.cn/jwapp/sys/emaphome/portal/index.do"
    const val COURSE_ENTRY = "https://jwxt.nuist.edu.cn/jwapp/sys/wdkb/*default/index.do"
    const val ROOM_ENTRY = "https://jwxt.nuist.edu.cn/jwapp/sys/kxjas/*default/index.do"
    const val EXAM_ENTRY = "https://jwxt.nuist.edu.cn/jwapp/sys/studentWdksapApp/*default/index.do"
    fun currentSemester() = SchoolRequest(BASE + "wdkb/modules/jshkcb/dqxnxq.do", emptyMap())
    fun periods() = SchoolRequest(BASE + "wdkb/modules/jshkcb/jc.do", emptyMap())
    fun courses(semester: String) = SchoolRequest(BASE + "wdkb/modules/xskcb/cxxszhxqkb.do", mapOf("XNXQDM" to semester))
    fun exams(semester: String) = SchoolRequest(BASE + "studentWdksapApp/WdksapController/cxxsksap.do",
        mapOf("requestParamStr" to buildJsonObject {
            put("XNXQDM", semester); put("*order", "-KSRQ,-KSSJMS")
        }.toString()))

    fun rooms(semester: Semester, date: LocalDate, buildingCode: String, page: Int, pageSize: Int = 10): SchoolRequest {
        val week = semester.weekOn(date)
        require(week >= 1 && page >= 1 && pageSize in 1..100 && buildingCode.isNotBlank())
        val day = date.dayOfWeek.value
        val order = "+LC,+JASMC"
        val query = buildJsonArray {
            fun condition(name: String, value: JsonPrimitive, builder: String = "equal") {
                addJsonObject { put("name", name); put("value", value); put("builder", builder); put("linkOpt", "AND") }
            }
            condition("JXLDM", JsonPrimitive(buildingCode))
            condition("XNXQDM", JsonPrimitive(semester.code))
            condition("ZC", JsonPrimitive(week)); condition("XQ", JsonPrimitive(day))
            condition("RQ", JsonPrimitive(date.toString())); condition("*order", JsonPrimitive(order), "m_value_equal")
        }
        return SchoolRequest(BASE + "kxjas/modules/kxjas/cxjsqk.do", linkedMapOf(
            "XNXQDM" to semester.code, "ZC" to week.toString(), "XQ" to day.toString(), "RQ" to date.toString(),
            "querySetting" to query.toString(), "*order" to order, "pageSize" to pageSize.toString(), "pageNumber" to page.toString(),
        ))
    }
}
