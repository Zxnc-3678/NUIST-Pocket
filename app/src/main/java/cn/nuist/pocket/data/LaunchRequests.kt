package cn.nuist.pocket.data

data class LaunchRequest(val revision: Int, val tab: Int, val date: String? = null, val todos: Boolean = false)

/** A fresh launch wins over restored page state; a configuration recreation doesn't
 * consume the same launch twice and unexpectedly move the user to a different tab. */
class LaunchRequests {
    private var revision = 0
    private var consumed = 0
    fun open(tab: Int, date: String? = null, todos: Boolean = false): LaunchRequest = LaunchRequest(++revision, tab, date, todos)
    fun consume(request: LaunchRequest): Boolean {
        if (request.revision <= consumed) return false
        consumed = request.revision
        return true
    }
}
