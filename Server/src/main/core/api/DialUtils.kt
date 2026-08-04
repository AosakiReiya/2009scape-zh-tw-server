package core.api
import java.util.*
import kotlin.math.ceil

object DialUtils {
    val tagRegex = "<([A-Za-z0-9=/]+)>".toRegex()

    fun removeMatches(message: String, regex: Regex): String {
        return regex.replace(message, "")
    }
}

/**
 * 估算字元在對話框中的像素寬度。
 * 中文（CJK）全形字約 12px、ASCII 約 6px、其餘約 8px（與 CJKRenderer 全形一致）。
 */
fun charWidthPx(c: Char): Int {
    return when {
        c == '\n' || c == '\r' -> 0
        Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN -> 12
        c.code <= 0x7E && c.code >= 0x20 -> 6
        else -> 8
    }
}

/** 計算字串去標籤後的像素寬度。 */
fun stringWidthPx(s: String): Int {
    var w = 0
    for (c in s) w += charWidthPx(c)
    return w
}

/**
 * 按像素寬度自動拆行（預設每行最多 300px ≈ 25 個中文字，對應對話框文字區實際寬度）。
 * 支援 <tag> 標籤（不計寬度）。在中文標點（。，、」』！？；：）之後優先斷行，
 * 避免把專名（如「倫布里奇」）從中間切開。
 */
fun splitLines(message: String, perLinePx: Int = 300): Array<String> {
    if (message.isEmpty()) return arrayOf("")
    val lines = mutableListOf<String>()
    val current = StringBuilder()
    var currentPx = 0
    var lastPunctIdx = -1  // 目前行內最後一個可斷行標點的位置（current 內的索引）
    var lastPunctPx = 0
    var i = 0
    val n = message.length
    val tagRegex = DialUtils.tagRegex
    val punct = "。，、」』！？；：，．；：？！"

    fun pushLine() {
        if (current.isEmpty()) return
        lines.add(current.toString())
        current.clear()
        currentPx = 0
        lastPunctIdx = -1
        lastPunctPx = 0
    }

    while (i < n) {
        val c = message[i]
        // 標籤：整段 `<...>` 不計寬度，原樣保留
        if (c == '<') {
            val m = tagRegex.find(message, i)
            if (m != null) {
                current.append(m.value)
                i = m.range.last + 1
                continue
            }
        }
        val w = charWidthPx(c)
        if (currentPx + w > perLinePx && currentPx > 0) {
            // 若行內有標點，在最後標點後斷行（避免切專名）；否則硬切
            if (lastPunctIdx > 0) {
                // 拆出到標點（含標點）的部分，其餘留給下一行
                val keep = current.substring(0, lastPunctIdx + 1)
                val rest = current.substring(lastPunctIdx + 1)
                lines.add(keep)
                current.setLength(0)
                current.append(rest)
                currentPx = currentPx - lastPunctPx
                lastPunctIdx = -1
                lastPunctPx = 0
                // 重新掃描 rest 內的標點
                for (ri in 0 until current.length) {
                    if (punct.indexOf(current[ri]) >= 0) {
                        lastPunctIdx = ri
                        lastPunctPx = stringWidthPx(current.substring(0, ri + 1))
                    }
                }
            } else {
                pushLine()
            }
            continue
        }
        if (punct.indexOf(c) >= 0) {
            lastPunctIdx = current.length
            lastPunctPx = currentPx + w
        }
        current.append(c)
        currentPx += w
        i++
    }
    pushLine()

    // 最多 4 行；超過時以 <br> 併入第 4 行（保留最後兩行內容）
    if (lines.size > 4) {
        val extra = lines.drop(3).joinToString("<br>")
        return arrayOf(lines[0], lines[1], lines[2], extra)
    }
    return lines.toTypedArray()
}
