package cn.nuist.pocket

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import cn.nuist.pocket.data.LaunchRequest
import cn.nuist.pocket.data.LaunchRequests

internal class PocketNavigation : ViewModel() {
    private val requests = LaunchRequests()
    var pending by mutableStateOf<LaunchRequest?>(null)
        private set
    fun open(tab: Int, date: String? = null, todos: Boolean = false) { pending = requests.open(tab, date, todos) }
    fun consume(request: LaunchRequest) = requests.consume(request)
}
