package cn.nuist.pocket.data

import kotlinx.serialization.json.*
import java.time.LocalDate
import java.time.LocalTime

data class TodoItem(
    val id: String, val title: String, val date: LocalDate,
    val time: LocalTime? = null, val note: String = "", val completed: Boolean = false,
)

val todoOrder: Comparator<TodoItem> = compareBy<TodoItem> { it.completed }
    .thenBy { it.date }.thenBy { it.time ?: LocalTime.MAX }.thenBy { it.title }.thenBy { it.id }

/** Overdue work stays visible; future work previews only after today's work is finished. */
fun widgetTodos(items: List<TodoItem>, today: LocalDate): List<TodoItem> {
    val pending = items.filterNot { it.completed }.sortedWith(todoOrder)
    return pending.filter { !it.date.isAfter(today) }.ifEmpty {
        pending.filter { it.date == today.plusDays(1) }
    }
}

object TodoCodec {
    const val MAX_ITEMS = 1000
    const val MAX_BYTES = 1024 * 1024
    fun validate(items: List<TodoItem>) {
        require(items.size <= MAX_ITEMS) { "最多保存1000条待办，请先整理已完成事项" }
        require(items.map { it.id }.distinct().size == items.size) { "待办编号重复" }
        items.forEach {
            require(it.id.length in 1..80 && it.title.isNotBlank() && it.title.length <= 120 && it.note.length <= 500) { "待办内容格式不正确" }
            require(it.date.year in 1900..2200 && (it.time == null || (it.time.second == 0 && it.time.nano == 0))) { "待办日期或时间格式不正确" }
        }
    }
    fun encode(items: List<TodoItem>): String {
        validate(items)
        return buildJsonObject {
            put("version", 1)
            putJsonArray("items") { items.forEach { item -> addJsonObject {
                put("id", item.id); put("title", item.title); put("date", item.date.toString())
                item.time?.let { put("time", it.toString()) }
                put("note", item.note); put("completed", item.completed)
            } } }
        }.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "待办内容过多，请先整理已完成事项" } }
    }
    fun decode(text: String): List<TodoItem> {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val root = Json.parseToJsonElement(text).jsonObject
        require(root.getValue("version").jsonPrimitive.int == 1)
        return root.getValue("items").jsonArray.map { element ->
            val item = element.jsonObject
            TodoItem(item.getValue("id").jsonPrimitive.content, item.getValue("title").jsonPrimitive.content,
                LocalDate.parse(item.getValue("date").jsonPrimitive.content),
                item["time"]?.jsonPrimitive?.contentOrNull?.let(LocalTime::parse),
                item.getValue("note").jsonPrimitive.content, item.getValue("completed").jsonPrimitive.boolean)
        }.also(::validate)
    }
}

data class WidgetTodoLayout(val courseCapacity: Int, val todoRows: Int, val showSummary: Boolean)

/** Reserve a compact footer without hiding the first class or overflowing a short widget. */
fun widgetTodoLayout(width: Int, height: Int, fontScale: Float, courses: Int, todos: Int): WidgetTodoLayout {
    val policy = widgetLayoutPolicy(width, height, fontScale)
    if (todos == 0) return WidgetTodoLayout(policy.capacity, 0, false)
    val scale = fontScale.coerceAtLeast(1f)
    val available = height / scale - WIDGET_HEADER_DP
    if (available < 28) return WidgetTodoLayout(policy.capacity, 0, false)
    val courseHeight = widgetCourseRowDp(policy.compact)
    val capacity = ((available - 28) / courseHeight).toInt().coerceIn(0, policy.capacity)
    if (courses > 0 && policy.capacity > 0 && capacity == 0) return WidgetTodoLayout(policy.capacity, 0, false)
    val used = minOf(courses, capacity) * courseHeight
    val rows = ((available - used - 28) / 28).toInt().coerceIn(0, minOf(todos, 2))
    return WidgetTodoLayout(capacity, rows, true)
}
