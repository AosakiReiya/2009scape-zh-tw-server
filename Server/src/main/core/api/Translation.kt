package core.api

import core.ServerConstants
import org.json.simple.JSONObject
import org.json.simple.parser.JSONParser
import java.io.FileReader
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * 正體中文翻譯攔截器。
 *
 * 從 data/configs/translations_zh-tw.json 載入 en -> zh-tw 對照表，
 * 並提供 translate() 供對話/訊息/介面文字出口呼叫。
 *
 * 翻譯表格式（JSON object）：
 * {
 *   "Ho Ho Ho!": "呵呵呵！",
 *   "Merry Christmas, ": "聖誕快樂，"
 * }
 *
 * 注意：僅翻譯「完整英文句子」，避免誤譯玩家名/數字/技術字串。
 */
object Translation {

    private var translations: Map<String, String> = emptyMap()

    /** 只翻譯含可辨識單字的文字。 */
    private val WORD_PATTERN = Pattern.compile("[A-Za-z]{3,}")

    /** 含有這些標籤的訊息（如 <col=...>）需先剝除再查，翻譯後還原。 */
    private val TAG_PATTERN = Pattern.compile("<[^>]+>")

    @JvmStatic
    fun init() {
        val path = ServerConstants.CONFIG_PATH + "translations_zh-tw.json"
        try {
            val parser = JSONParser()
            val obj = parser.parse(FileReader(path)) as JSONObject
            val map = HashMap<String, String>(obj.size)
            for (key in obj.keys) {
                map[key.toString()] = obj[key].toString()
            }
            translations = map
            println("[Translation] 已載入 ${translations.size} 條翻譯: $path")
        } catch (e: Exception) {
            translations = emptyMap()
            println("[Translation] 無法載入翻譯表 $path (${e.message})，將輸出原文。")
        }
    }

    @JvmStatic
    fun isLoaded(): Boolean = translations.isNotEmpty()

    /**
     * 翻譯單一文字。未命中或不宜翻譯時原樣回傳。
     *
     * @param text 原文
     * @param requirePhrase 若為 true，只有含空格/多詞的句子才翻譯（避免誤翻玩家名/單字）
     */
    @JvmStatic
    @JvmOverloads
    fun translate(text: String, requirePhrase: Boolean = false): String {
        if (text.isEmpty() || !isLoaded()) {
            return text
        }
        if (requirePhrase && !hasMultipleWords(text)) {
            return text
        }
        // 精確命中
        translations[text]?.let { return it }

        // 剝除標籤後命中（如 <col=ff0000>Attack</col>）
        val tags = ArrayList<String>()
        val tagMatcher = TAG_PATTERN.matcher(text)
        val stripped = tagMatcher.replaceAll { m ->
            tags.add(m.group())
            "\u0000"
        }
        if (stripped != text) {
            translations[stripped]?.let { translated ->
                var idx = 0
                val sb = StringBuilder()
                for (ch in translated) {
                    if (ch == '\u0000' && idx < tags.size) {
                        sb.append(tags[idx++])
                    } else {
                        sb.append(ch)
                    }
                }
                return sb.toString()
            }
        }
        return text
    }

    private fun hasMultipleWords(text: String): Boolean {
        val stripped = TAG_PATTERN.matcher(text).replaceAll("")
        return stripped.contains(" ")
    }

    /**
     * 判斷文字是否適合翻譯（含可辨識英文單字，且不是純數字/單一字元）。
     */
    @JvmStatic
    fun isTranslatable(text: String): Boolean {
        if (text.isBlank()) {
            return false
        }
        val stripped = TAG_PATTERN.matcher(text).replaceAll("")
        val m = WORD_PATTERN.matcher(stripped)
        return m.find()
    }
}
