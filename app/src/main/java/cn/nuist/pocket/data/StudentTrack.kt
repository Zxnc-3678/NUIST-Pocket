package cn.nuist.pocket.data

enum class StudentTrack(val title: String, val cacheName: String, val importName: String, val schoolHost: String) {
    UNDERGRADUATE("本科生", "school_cache", "offline_schedule", "jwxt.nuist.edu.cn"),
    GRADUATE("研究生", "graduate_school_cache", "graduate_offline_schedule", "nxdyjs.nuist.edu.cn");

    val loginHosts: Set<String> = setOf(schoolHost, "authserver.nuist.edu.cn", "i.nuist.edu.cn")
    val supportsRooms: Boolean get() = this == UNDERGRADUATE
    fun isTabVisible(index: Int): Boolean = index == 0 || index == 2 || index == 3 || (index == 1 && supportsRooms)
    fun visibleTab(index: Int): Int = if (isTabVisible(index)) index else 0
    fun preferenceKey(name: String): String = if (this == UNDERGRADUATE) name else "graduate_$name"
    companion object {
        fun read(value: String?): StudentTrack = entries.find { it.name == value } ?: UNDERGRADUATE
    }
}
