package core.api

import core.ServerConstants
import org.json.simple.JSONObject
import org.json.simple.parser.JSONParser
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
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
            // 明確以 UTF-8 讀取（Windows 平台預設編碼為 GBK，FileReader 會解析失敗）
            val reader = InputStreamReader(FileInputStream(path), StandardCharsets.UTF_8)
            val obj = parser.parse(reader) as JSONObject
            reader.close()
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
     * 萃取 cache 定義中的所有字串（介面/NPC/物件右鍵選項 + 名稱），輸出 JSON 供翻譯。
     * 需在 Cache.init() 之後呼叫。
     */
    @JvmStatic
    fun extractCacheStrings(outPath: String) {
        val strings = LinkedHashSet<String>()
        // 介面字串
        try {
            val ifaceCount = core.cache.Cache.getInterfaceDefinitionsSize()
            for (i in 0 until ifaceCount) {
                try {
                    val def = core.cache.def.impl.IfaceDefinition.forId(i) ?: continue
                    addIfaceStrings(def, strings)
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        // NPC 右鍵選項 + 名稱
        try {
            val npcCount = core.cache.Cache.getNPCDefinitionsSize()
            for (i in 0 until npcCount) {
                try {
                    val def = core.cache.def.impl.NPCDefinition.forId(i) ?: continue
                    def.getOptions()?.filterNotNull()?.forEach { strings.add(it) }
                    val nm = def.getName() ?: ""
                    if (nm.isNotBlank() && nm != "null") strings.add(nm)
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        // 物件右鍵選項 + 名稱
        try {
            val objCount = core.cache.Cache.getObjectDefinitionsSize()
            for (i in 0 until objCount) {
                try {
                    val def = core.cache.def.impl.SceneryDefinition.forId(i) ?: continue
                    def.getOptions()?.filterNotNull()?.forEach { strings.add(it) }
                    val nm = def.getName() ?: ""
                    if (nm.isNotBlank() && nm != "null") strings.add(nm)
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
        // 物品名稱
        try {
            val itemCount = core.cache.Cache.getItemDefinitionsSize()
            for (i in 0 until itemCount) {
                try {
                    val def = core.cache.def.impl.ItemDefinition.forId(i) ?: continue
                    val nm = def.getName() ?: ""
                    if (nm.isNotBlank() && nm != "null") strings.add(nm)
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}

        val sb = StringBuilder("[")
        var first = true
        for (s in strings) {
            if (!s.isBlank()) {
                if (!first) sb.append(",")
                first = false
                sb.append("{\"en\":").append(org.json.simple.JSONValue.toJSONString(s))
                sb.append(",\"zh-tw\":\"\",\"status\":\"untranslated\"}")
            }
        }
        sb.append("]")
        java.io.File(outPath).writeText(sb.toString())
        println("[Translation] 萃取 cache 字串 ${strings.size} 條 -> $outPath")
    }

    private fun addIfaceStrings(def: core.cache.def.impl.IfaceDefinition, out: MutableSet<String>) {
        def.text?.let { if (it.isNotBlank()) out.add(it) }
        def.activeText?.let { if (it.isNotBlank()) out.add(it) }
        def.optionBase?.let { if (it.isNotBlank()) out.add(it) }
        def.opCircumfix?.let { if (it.isNotBlank()) out.add(it) }
        def.opSuffix?.let { if (it.isNotBlank()) out.add(it) }
        def.option?.let { if (it.isNotBlank()) out.add(it) }
        def.ops?.filterNotNull()?.forEach { if (it.isNotBlank()) out.add(it) }
        def.invOptions?.filterNotNull()?.forEach { if (it.isNotBlank()) out.add(it) }
        def.children?.forEach { c -> addIfaceStrings(c, out) }
    }

    /**
     * 翻譯單一文字。未命中或不宜翻譯時原樣回傳。
     *
     * @param text 原文
     * @param requirePhrase 若為 true，只有含空格/多詞的句子才翻譯（避免誤翻玩家名/單字）
     */
    @JvmStatic
    @JvmOverloads
    fun translate(text: String, requirePhrase: Boolean = false): String {
        // 翻譯後套用「重點名中英結合」替換（Brynna → 布琳娜 (Brynna)）
        return applyNameBilingual(translateCore(text, requirePhrase))
    }

    /** 翻譯核心（不做重點名替換）。 */
    private fun translateCore(text: String, requirePhrase: Boolean): String {
        if (text.isEmpty() || !isLoaded()) {
            return text
        }
        val hasTags = TAG_PATTERN.matcher(text).find()
        // 帶標籤的字串不受單字守衛限制（剝標籤後仍可能命中單字 key，如 <col>Attack</col>）
        if (requirePhrase && !hasTags && !hasMultipleWords(text) && translations[text] == null) {
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
        // 模板匹配（動態拼接字串，如 "You need a crafting level of 25 to craft X."）
        matchTemplate(text)?.let { return it }
        // 中段數值變數匹配（如執行期 "45% Done" 對應表內 "$percent% Done"）
        matchNumberTemplate(text)?.let { return it }
        // 整句 miss → 拆 token 逐一查表回填（解決執行期拼接如 "Lumbridge Area Tasks"）
        translateTokenFallback(text)?.let { return it }
        return text
    }

    /** 整句查不到時，依空白拆 token 逐一查表回填。僅當所有文字 token 全數命中才套用（避免中英混雜）。 */
    private fun translateTokenFallback(text: String): String? {
        if (text.isBlank() || !text.contains(" ")) return null
        val words = text.split(" ")
        if (words.size < 2) return null
        val sb = StringBuilder()
        var allTranslated = true
        for (w in words) {
            if (sb.isNotEmpty()) sb.append(" ")
            val tw = if (w.isNotEmpty()) lookupSingle(w) else w
            sb.append(tw)
            // 純標點/數字 token 不需翻譯；含英文字母且未命中 → 失敗
            if (tw == w && w.any { it.isLetter() }) {
                allTranslated = false
            }
        }
        return if (allTranslated) sb.toString() else null
    }

    /** 重點名清單：英文名 -> 中文名（對話中嵌入時顯示「中文 (英文)」）。 */
    private val NAME_BI = mapOf(
        "Brynna" to "布琳娜",
        "Master Chef" to "主廚導師",
        "Survival Expert" to "生存專家",
        "Quest Guide" to "任務嚮導",
        "Mining Instructor" to "採礦導師",
        "Financial Advisor" to "財務顧問",
        "Combat Instructor" to "戰鬥導師",
        "Prayer Instructor" to "祈禱導師",
        "Magic Instructor" to "魔法導師",
        "RuneScape Guide" to "RuneScape 嚮導",
        "Duke Horacio" to "霍拉西奧公爵",
        "Varrock" to "瓦洛克",
        "Falador" to "法魯多",
        "Lumbridge" to "倫布里奇",
        "Draynor" to "卓奈爾",
        "Catherby" to "卡瑟比",
        "Ardougne" to "阿爾多內",
        "Yanille" to "亞尼勒",
        "Canifis" to "卡尼菲斯",
        "Rellekka" to "雷萊卡",
        "Taverley" to "塔弗利",
        "Burthorpe" to "伯索普",
        "Edgeville" to "艾吉村",
        "Karamja" to "卡拉姆賈",
        "Al Kharid" to "阿爾卡里德",
        "Camelot" to "卡美洛",
        "Tutorial Island" to "教學島",
    )

    /**
     * 把譯文中出現的重點名替換為「中文 (英文)」。
     * 僅替換「英文字符串中獨立出現」的名稱（避免誤替換單詞內部）。
     */
    private fun applyNameBilingual(text: String): String {
        if (text.isEmpty() || !isLoaded()) return text
        var result = text
        for ((en, zh) in NAME_BI) {
            // 若譯文中已是「中文 (英文)」或已是純中文，跳過
            if (result.contains("$zh ($en)")) continue
            if (result.contains(zh)) continue
            // 用邊界替換避免誤命中單詞（如 varrock 在 larger 中）
            result = result.replace(Regex("(?<![A-Za-z])${Regex.escape(en)}(?![A-Za-z])"), "$zh ($en)")
        }
        return result
    }

    /** 數字佔位模式：`$percent%`、`$number%` 等 `$xxx%` 變數。 */
    private val NUMBER_VAR_PATTERN = Pattern.compile("\\\$[A-Za-z_]+%")

    /** 執行期字串中的數字（含小數與百分號後綴，如 45%、25）。 */
    private val NUMBER_PATTERN = Pattern.compile("\\d+(\\.\\d+)?%?")

    /**
     * 匹配「中段含數值變數」的模板。
     * 表內 key 形如 `"$percent% Done"`，執行期字串 `"45% Done"` 的數字會被抽離，
     * 正規化後比對（數字→$xxx%），命中則把譯文的變數位用原數字回填。
     */
    private fun matchNumberTemplate(text: String): String? {
        if (text.isEmpty()) return null
        // 執行期字串中出現的數字（如 45、25、3）
        val m = NUMBER_PATTERN.matcher(text)
        if (!m.find()) return null
        val number = m.group()
        // 把執行期字串的數字替換為佔位符，構造查詢 key
        val normalizedKey = text.replace(number, "\u0000\u0000")
        // 掃描翻譯表中含 $xxx% 變數的 key，正規化比對
        for ((key, value) in translations) {
            if (!key.contains("$")) continue
            val normalized = NUMBER_VAR_PATTERN.matcher(key).replaceAll("\u0000\u0000")
            if (normalized == normalizedKey) {
                // 回填：譯文中的 $xxx% 佔位符用原數字取代
                val filled = NUMBER_VAR_PATTERN.matcher(value).replaceAll(number)
                return filled
            }
        }
        return null
    }

    /**
     * 翻譯 cache 定義字串（介面標籤/右鍵選項）。精確命中翻譯表則回傳譯文，否則原樣。
     * 供 Iface/NPC/Scenery Definition 載入時呼叫。
     */
    @JvmStatic
    fun translateDef(s: String): String {
        if (s.isEmpty() || !isLoaded()) return s
        translations[s]?.let { return it }
        // 大小寫不敏感
        translations[s.capitalize()]?.let { return it }
        return s
    }

    /**
     * 遍歷翻譯 IfaceDefinition 的所有字串欄位（介面標籤/右鍵選項）。
     */
    @JvmStatic
    fun translateIfaceDefinition(def: core.cache.def.impl.IfaceDefinition) {
        if (!isLoaded()) return
        def.text = def.text?.let { translateDef(it) }
        def.activeText = def.activeText?.let { translateDef(it) }
        def.optionBase = def.optionBase?.let { translateDef(it) }
        def.opCircumfix = def.opCircumfix?.let { translateDef(it) }
        def.opSuffix = def.opSuffix?.let { translateDef(it) }
        def.option = def.option?.let { translateDef(it) }
        def.ops?.let { ops ->
            for (i in ops.indices) {
                ops[i] = ops[i]?.let { translateDef(it) }
            }
        }
        def.invOptions?.let { opts ->
            for (i in opts.indices) {
                opts[i] = opts[i]?.let { translateDef(it) }
            }
        }
        def.children?.forEach { c -> if (c != null) translateIfaceDefinition(c) }
    }

    /**
     * 翻譯動態值（模板中的變數部分）。優先精確，其次大小寫不敏感，最後逐詞。
     */
    private fun translateDynamic(value: String): String {
        if (value.isEmpty()) return value
        translations[value]?.let { return it }
        translations[value.capitalize()]?.let { return it }
        translations[value.lowercase()]?.let { return it }
        // 純數字或無英文：原樣
        if (!value.any { it.isLetter() }) return value
        // 單詞：不再拆分（避免無窮遞迴）
        if (!value.contains(" ")) return value
        // 多詞：逐詞查表（不遞迴）
        val sb = StringBuilder()
        var changed = false
        for (word in value.split(" ")) {
            if (sb.isNotEmpty()) sb.append(" ")
            val tw = lookupSingle(word)
            if (tw != word) changed = true
            sb.append(tw)
        }
        return if (changed) sb.toString() else value
    }

    private fun lookupSingle(word: String): String {
        translations[word]?.let { return it }
        translations[word.capitalize()]?.let { return it }
        translations[word.lowercase()]?.let { return it }
        return word
    }

    /**
     * 模板翻譯：處理執行期動態拼接的字串（"前綴 " + var + " 後綴"）。
     *
     * 翻譯表條目格式（鍵以 @pre: 開頭）：
     *   "@pre:<英文前綴>~~<英文後綴>" : "<中文前綴>~~<中文後綴>"
     *
     * 例：
     *   "@pre:You need a crafting level of ~~ to craft " : "你需要製作等級 ~~ 才能製作"
     *   實際字串 "You need a crafting level of 25 to craft X." 匹配後
     *   → "你需要製作等級 25 才能製作X。"
     */
    private fun matchTemplate(text: String): String? {
        if (text.isEmpty()) return null
        var bestLen = -1
        var bestZh: String? = null
        var bestPre = ""
        var bestPost = ""
        for ((key, value) in translations) {
            if (!key.startsWith("@pre:")) continue
            val sepIdx = key.indexOf("~~", 5)
            if (sepIdx < 0) continue
            val enPre = key.substring(5, sepIdx)
            val enPost = key.substring(sepIdx + 2)
            // 前綴須比目前已匹配的更長（長前綴優先，避免 "You need " 吃掉 "You need a crafting level of "）
            if (enPre.length <= bestLen) continue
            if (!text.startsWith(enPre)) continue
            if (enPost.isNotEmpty()) {
                val restStart = text.indexOf(enPost, enPre.length)
                if (restStart < 0) continue
                val mid = text.substring(enPre.length, restStart)
                if (mid.isEmpty()) continue
                // 後綴後若還有內容（代表還有其他動態值），跳過避免殘缺翻譯
                val rest = text.substring(restStart + enPost.length)
                if (rest.isNotBlank()) continue
                bestPre = enPre
                bestPost = enPost
                bestLen = enPre.length
                val zhSep = value.indexOf("~~")
                val zhPre = if (zhSep >= 0) value.substring(0, zhSep) else value
                val zhPost = if (zhSep >= 0) value.substring(zhSep + 2) else ""
                val midZh = translateDynamic(mid)
                bestZh = zhPre + midZh + zhPost
            } else {
                // 後綴為空：前綴後全部是動態值（如 "Welcome to X"）
                val mid = text.substring(enPre.length)
                if (mid.isEmpty()) continue
                bestPre = enPre
                bestPost = ""
                bestLen = enPre.length
                val zhSep = value.indexOf("~~")
                val zhPre = if (zhSep >= 0) value.substring(0, zhSep) else value
                val zhPost = if (zhSep >= 0) value.substring(zhSep + 2) else ""
                val midZh = translateDynamic(mid)
                bestZh = zhPre + midZh + zhPost
            }
        }
        return bestZh
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
