package cn.nuist.pocket

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.time.LocalTime

@Composable internal fun TimeWheelDialog(initial: LocalTime, onDismiss: () -> Unit, onConfirm: (LocalTime) -> Unit) {
    var hour by rememberSaveable { mutableIntStateOf(initial.hour) }
    var minute by rememberSaveable { mutableIntStateOf(initial.minute) }
    var wheels by remember { mutableStateOf<TimeWheelView?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选择时间") }, text = {
        AndroidView(factory = { context -> TimeWheelView(context).apply {
            setTime(hour, minute)
            setOnTimeChanged { time -> hour = time.hour; minute = time.minute }
            wheels = this
        } },
            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
            onReset = null,
            onRelease = { view -> view.setOnTimeChanged(null); wheels = null })
    }, confirmButton = {
        TextButton(onClick = { wheels?.selectedTime()?.let(onConfirm) }) { Text("确定") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
