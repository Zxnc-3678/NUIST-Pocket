package cn.nuist.pocket

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.TextView
import java.time.LocalTime

/** Native wheels provide fling, accessibility actions and wraparound without a custom timer. */
class TimeWheelView(context: Context) : LinearLayout(context) {
    private val hourPicker = wheel(23, "小时")
    private val minutePicker = wheel(59, "分钟")
    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        addView(column("时", hourPicker), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(column("分", minutePicker), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
    }
    private fun wheel(maximum: Int, label: String) = NumberPicker(context).apply {
        minValue = 0; maxValue = maximum
        wrapSelectorWheel = true
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        contentDescription = label
        setFormatter { value -> if (value < 10) "0$value" else value.toString() }
        setTextColor(Color.rgb(24, 38, 65))
        setTextSize(27f * resources.displayMetrics.scaledDensity)
        setSelectionDividerHeight((1 * resources.displayMetrics.density).toInt().coerceAtLeast(1))
    }
    private fun column(label: String, picker: NumberPicker) = LinearLayout(context).apply {
        orientation = VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        addView(TextView(context).apply {
            text = label; gravity = Gravity.CENTER; textSize = 14f
            setTextColor(Color.rgb(104, 117, 142))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        val height = (220 * resources.displayMetrics.density * resources.configuration.fontScale.coerceIn(1f, 1.4f)).toInt()
        addView(picker, LayoutParams(LayoutParams.MATCH_PARENT, height))
    }
    fun setTime(hour: Int, minute: Int) {
        require(hour in 0..23 && minute in 0..59)
        hourPicker.value = hour; minutePicker.value = minute
    }
    fun selectedTime(): LocalTime = LocalTime.of(hourPicker.value, minutePicker.value)
    fun setOnTimeChanged(listener: ((LocalTime) -> Unit)?) {
        val change = if (listener == null) null else NumberPicker.OnValueChangeListener { _, _, _ -> listener(selectedTime()) }
        hourPicker.setOnValueChangedListener(change); minutePicker.setOnValueChangedListener(change)
    }
}
