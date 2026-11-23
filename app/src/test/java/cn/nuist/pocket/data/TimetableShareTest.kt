package cn.nuist.pocket.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

class TimetableShareTest {
    @Test fun courseDetailsAndTemporaryDayOffSurviveACompleteRoundTrip() {
        val term = Semester("2026-2027-1", LocalDate.of(2026, 8, 31))
        val course = CourseSlot("demo", "C1", "新时代中国特色社会主义理论与实践", "教师", "明德N101", 4, 3, 4,
            setOf(2, 4, 7, 9), LocalTime.of(10, 10), LocalTime.of(11, 50), term.code)
        val dayOff = LocalDate.of(2026, 11, 5)
        val schedule = WidgetSchedule(term, listOf(course), listOf(Period(3, LocalTime.of(10, 10), LocalTime.of(10, 55), true)),
            "测试", suspendedDates = setOf(dayOff))
        val result = decodeSharedTimetable(encodeSharedTimetable(schedule))
        assertEquals(schedule.semester, result.semester)
        assertEquals(course, result.courses.single())
        assertEquals(schedule.periods, result.periods)
        assertEquals(setOf(dayOff), result.suspendedDates)
    }

    @Test fun severalFramesCanBeScannedOutOfOrderAndReassembled() {
        val payload = (0 until 5600).joinToString("") { (it % 10).toString() }
        val frames = timetableShareFrames(payload)
        assertTrue(frames.size > 1)
        val collector = TimetableShareCollector()
        val results = frames.reversed().map(collector::accept)
        assertEquals(payload, results.last())
        assertEquals(frames.size, collector.received)
    }

    @Test fun qrCodeExpiresExactlyAfterThirtyMinutesEvenIfPartlyScanned() {
        val start = 1_700_000_000_000L
        val frames = timetableShareFrames("a".repeat(5600), start)
        val collector = TimetableShareCollector()
        assertNull(collector.accept(frames.first(), start + TIMETABLE_SHARE_LIFETIME_MS - 1))
        assertEquals(1, collector.received)
        assertNull(collector.accept(frames.last(), start + TIMETABLE_SHARE_LIFETIME_MS))
        assertTrue(collector.expired)
        assertEquals(0, collector.received)
        val valid = timetableShareFrames("ok", start)
        assertEquals("ok", collector.accept(valid.single(), start + TIMETABLE_SHARE_LIFETIME_MS - 1))
    }

    @Test fun aLargerTimetableUsesOneCompatibleQrAndFileSharesHaveTheSameExpiry() {
        val now = 1_700_000_000_000L
        val payload = "a".repeat(2200)
        val frames = timetableShareFrames(payload, now)
        assertEquals(1, frames.size)
        val grid = QRCodeWriter().encode(frames.single(), BarcodeFormat.QR_CODE, 800, 800,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 2))
        val pixels = IntArray(grid.width * grid.height) { i -> if (grid[i % grid.width, i / grid.width]) -0x1000000 else -1 }
        val image = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(grid.width, grid.height, pixels)))
        assertEquals(payload, TimetableShareCollector().accept(QRCodeReader().decode(image).text, now))
        val file = timetableShareFile(payload, now)
        assertEquals(payload, TimetableShareCollector().accept(file, now + TIMETABLE_SHARE_LIFETIME_MS - 1))
        val expired = TimetableShareCollector()
        assertNull(expired.accept(file, now + TIMETABLE_SHARE_LIFETIME_MS))
        assertTrue(expired.expired)
    }

    @Test fun earlierTwoPartQrSharesRemainReadable() {
        val now = 1_700_000_000_000L
        val expires = now + TIMETABLE_SHARE_LIFETIME_MS
        val collector = TimetableShareCollector()
        assertNull(collector.accept("NP1|OLD|$expires|2|0|first", now))
        assertEquals("firstsecond", collector.accept("NP1|OLD|$expires|2|1|second", now))
    }

    @Test fun sixteenCoursesCanBeEncodedInOneQrAndReadBackWithoutLosingDetails() {
        val names = listOf("新时代中国特色社会主义理论与实践", "大气科学数值模拟与高性能计算", "雷达与卫星气象学基础", "气象统计方法及其实际应用",
            "动力气象学", "天气学原理与预报技术", "大学英语视听说", "大学物理实验", "数据结构与算法设计", "程序设计与计算思维",
            "遥感原理与数据处理", "概率论与数理统计", "人工智能导论与应用", "大学体育专项训练", "现代通信技术基础", "形势与政策")
        val term = Semester("2026-2027-1", LocalDate.of(2026, 8, 31))
        val courses = names.mapIndexed { i, name -> CourseSlot("demo$i", "TEST$i", name, "测试教师$i", "明德楼N${201 + i}",
            i / 3 + 1, i % 3 * 2 + 1, i % 3 * 2 + 2, (1..16).toSet(), null, null, term.code) }
        val periods = (1..11).map { Period(it, LocalTime.of(8, 0), LocalTime.of(8, 45), true) }
        val schedule = WidgetSchedule(term, courses, periods, "虚构", suspendedDates = setOf(
            LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 6), LocalDate.of(2026, 11, 7)))
        val frames = timetableShareFrames(encodeSharedTimetable(schedule))
        assertEquals(1, frames.size)
        val grid = QRCodeWriter().encode(frames.single(), BarcodeFormat.QR_CODE, 800, 800,
            mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 2))
        val pixels = IntArray(grid.width * grid.height) { i -> if (grid[i % grid.width, i / grid.width]) -0x1000000 else -1 }
        val image = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(grid.width, grid.height, pixels)))
        val decodedFrame = QRCodeReader().decode(image).text
        val decoded = decodeSharedTimetable(TimetableShareCollector().accept(decodedFrame)!!)
        assertEquals(courses, decoded.courses)
        assertEquals(periods, decoded.periods)
        assertEquals(schedule.suspendedDates, decoded.suspendedDates)
    }
}
