package cn.nuist.pocket.data

import kotlinx.serialization.json.*

internal data class ClassReminderState(
    val reservations: List<QuietReservation> = emptyList(), val deliveries: List<ClassDelivery> = emptyList(),
    val lease: QuietLease? = null, val hiddenUntil: Long = 0, val error: String? = null,
)

internal fun encodeClassReminderState(state: ClassReminderState): String = buildJsonObject {
    put("version", 1)
    put("reservations", buildJsonArray { state.reservations.forEach { saved -> add(buildJsonObject {
        put("key", saved.key); put("track", saved.track.name); put("title", saved.title)
        put("start", saved.startsAt); put("end", saved.endsAt); put("mode", saved.mode.name)
    }) } })
    put("deliveries", buildJsonArray { state.deliveries.forEach { saved -> add(buildJsonObject {
        put("key", saved.key); put("end", saved.endsAt); put("dismissed", saved.dismissed)
    }) } })
    state.lease?.let { lease -> put("lease", buildJsonObject {
        put("id", lease.id); put("mode", lease.mode.name); put("end", lease.endsAt)
        lease.previousRinger?.let { put("ringer", it) }; lease.ruleId?.let { put("rule", it) }
    }) }
    put("hiddenUntil", state.hiddenUntil)
    state.error?.let { put("error", it) }
}.toString().also { require(it.toByteArray(Charsets.UTF_8).size <= 256 * 1024) }

internal fun decodeClassReminderState(text: String): ClassReminderState {
    require(text.toByteArray(Charsets.UTF_8).size <= 256 * 1024)
    val root = Json.parseToJsonElement(text).jsonObject
    require(root.getValue("version").jsonPrimitive.int == 1)
    fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content.also { require(it.length <= 8_000) }
    fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long
    fun key(value: String) = value.also { require(it.matches(Regex("[0-9a-f]{64}"))) }
    val reservations = root.getValue("reservations").jsonArray.also { require(it.size <= MAX_QUIET_RESERVATIONS) }.map {
        val o = it.jsonObject
        QuietReservation(key(o.string("key")), StudentTrack.valueOf(o.string("track")), o.string("title"),
            o.long("start"), o.long("end"), QuietMode.valueOf(o.string("mode"))).also { saved ->
            require(saved.startsAt > 0 && saved.endsAt > saved.startsAt && saved.endsAt - saved.startsAt <= 24 * 60 * 60_000L)
        }
    }
    require(reservations.map { it.key }.distinct().size == reservations.size)
    val deliveries = root.getValue("deliveries").jsonArray.also { require(it.size <= 256) }.map {
        val o = it.jsonObject
        ClassDelivery(key(o.string("key")), o.long("end"), o.getValue("dismissed").jsonPrimitive.boolean)
    }
    val lease = root["lease"]?.jsonObject?.let { o ->
        val mode = QuietMode.valueOf(o.string("mode"))
        val ringer = o["ringer"]?.jsonPrimitive?.int
        val rule = o["rule"]?.jsonPrimitive?.content
        require((mode == QuietMode.SILENT && ringer in RINGER_SILENT..RINGER_NORMAL && rule == null) ||
            (mode == QuietMode.DND && ringer == null && !rule.isNullOrBlank()))
        QuietLease(mode, o.long("end"), ringer, rule, o.string("id")).also {
            require(it.endsAt > 0 && it.id.matches(Regex("[0-9a-f-]{36}")))
        }
    }
    return ClassReminderState(reservations, deliveries, lease,
        root.getValue("hiddenUntil").jsonPrimitive.long, root["error"]?.jsonPrimitive?.content?.take(1_000))
}
