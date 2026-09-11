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
        // 翻譯後套用「重點名中英結合」替換（Brynna → 布琳娜 (Brynna)）。
        // 只在最外層套一次：內層遞迴走 translateInner 避免雙重包裹（如 Romeo → 羅密歐 (Romeo) 再包一次）。
        return applyNameBilingual(translateCore(text, requirePhrase))
    }

    /**
     * 對話頁翻譯：把同一頁（可能因顯示寬度被預拆成多段）的片段先合併成整句翻譯；
     * 整句命中（產生中文）則用整句譯文，否則退回逐片段翻譯（相容既有片段 key）。
     * 最後以 splitLines 依寬度重新分行。解決「逐片段查表命中率低 → 句中露英文」。
     */
    @JvmStatic
    fun translatePage(lines: Array<out String?>): List<String> {
        val valid = lines.filterNotNull().toTypedArray()
        if (valid.isEmpty()) return emptyList()
        if (valid.size == 1) return splitLines(translate(valid[0])).toList()
        val joined = valid.joinToString(" ")
        val t = translate(joined)
        if (t != joined && hasCjk(t)) return splitLines(t).toList()
        val out = ArrayList<String>()
        for (l in valid) out.addAll(splitLines(translate(l)).toList())
        return out
    }

    /** 內層翻譯（不套 applyNameBilingual，供 translateCore 遞迴使用）。 */
    private fun translateInner(text: String, requirePhrase: Boolean = false): String = translateCore(text, requirePhrase)

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
        // 多變數數字模板（如 "You have completed $n tasks ... $m points"），支援 2+ 個內嵌數字
        matchVarTemplate(text)?.let { return it }
        // 任務指南組合句（"Completion of X"、"A total of N Quest Points."）
        translateComposed(text)?.let { return it }
        // 整句 miss → 拆 token 逐一查表回填（解決執行期拼接如 "Lumbridge Area Tasks"）
        translateTokenFallback(text)?.let { return it }
        return text
    }

    /** 任務指南組合句處理。 */
    private fun translateComposed(text: String): String? {
        // 先剝除前綴標籤（<red>/<str>/<col=..>），組合句另外處理標籤回填
        val cleanText = TAG_PATTERN.matcher(text).replaceAll("")
        return translateComposedCore(cleanText)
    }

    private fun translateComposedCore(text: String): String? {
        // "Friends List - <server> <world>" → "好友清單 - <server> <world>"
        if (text.startsWith("Friends List - ")) {
            return "好友清單 - " + text.substring("Friends List - ".length)
        }
        // "Completion of X" → "完成 X"
        if (text.startsWith("Completion of ")) {
            val rest = text.substring("Completion of ".length).trim()
            val zh = translateInner(rest, false)
            if (zh != rest) return "完成 " + zh
            return null
        }
        // "A total of 5 Quest Points." / "5 Quest Points." → "總共需要 5 點任務點數。"
        if (text.endsWith(" Quest Points.") || text.endsWith(" Quest Points")) {
            val m2 = NUMBER_PATTERN.matcher(text)
            if (m2.find()) {
                val num = m2.group()
                val prefix = text.substring(0, m2.start()).trim()
                val zhPrefix = when {
                    prefix.isEmpty() -> ""
                    prefix == "A total of" -> "總共需要 "
                    else -> (translations[prefix] ?: translations[prefix.capitalize()] ?: "") + " "
                }
                val suffix = if (text.endsWith(".")) "。" else ""
                return zhPrefix + num + " 點任務點數" + suffix
            }
        }
        // "I must have completed X." / "I must have completed X" → "我必須先完成 X。"
        val mc = Regex("^I must have completed (.+?)\\.?$").find(text)
        if (mc != null) {
            val cn = mc.groupValues[1]
            val zh = translateInner(cn, false)
            if (zh != cn) return "我必須先完成 " + zh + (if (text.endsWith(".")) "。" else "")
            return null
        }
        // "Level 30 Ranged" / "Level N Skill" → "等級 N ＜技能＞"
        val lc = Regex("^Level (\\d+) ([A-Za-z ]+)\\.?$").find(text)
        if (lc != null) {
            val skill = lc.groupValues[2]
            val zhSkill = translateInner(skill, false)
            if (zhSkill != skill) return "等級 " + lc.groupValues[1] + " " + zhSkill
            return null
        }
        return null
    }
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
        "White Knight's Castle" to "白騎士城堡",
        "Ice Mountain" to "冰山",
        "Goblin Village" to "哥布林村莊",
        "Dwarven Mines" to "矮人礦場",
        "King Roald" to "羅爾德國王",
        "Sir Amik Varze" to "阿米克·瓦澤爵士",
        "Generals Wartface" to "沃特菲斯將軍",
        "Bentnoze" to "本特諾斯",
        "Denulth" to "德努爾斯",
        "Imperial Guard camp" to "帝國守衛營地",
        "Cook" to "廚師",
        "Kitchen" to "廚房",
        "Duke of Lumbridge" to "倫布里奇公爵",
        "Lumbridge Castle" to "倫布里奇城堡",
        "Lost City" to "失落之城",
        "Heroes' Quest" to "英雄任務",
        "Monkey Madness" to "猴子瘋狂",
        "Priest in Peril" to "陷入險境的牧師",
        "Shadow of the Storm" to "風暴之影子",
        "Demon Slayer" to "屠魔任務",
        "The Knight's Sword" to "騎士之劍",
        "Elemental Workshop" to "元素工坊",
        "Wolfbane Dagger" to "狼毒匕首",
        "Darklight" to "暗光",
        "Silverlight" to "銀光",
        "Faladian/Blurite Sword" to "法拉迪安/藍晶石劍",
        "Granite maul" to "花崗岩大錘",
        "Tzaar-Ket-Om" to "茲哈爾-凱特-奧姆",
        "Level 33 Knights" to "33 級騎士",
        "Romeo" to "羅密歐",
        "Juliet" to "茱麗葉",
        "Fred the Farmer" to "農夫佛雷德",
        "Reldo" to "雷爾多",
        "Aubury" to "奧伯里",
        "Gertrude" to "葛特魯德",
        "Thurgo" to "瑟戈",
        "Zaff" to "扎夫",
        "Wayne" to "韋恩",
        "Aggie" to "艾姬",
        "Evil Dave" to "邪惡的戴夫",
        "Squire Asgarnia" to "侍從阿斯加尼亞",
        "Sir Vyvin" to "維文爵士",
        "Ernest" to "歐尼斯",
        "Veronica" to "薇若妮卡",
        "Marjory" to "瑪喬莉",
        "Wizard Distentor" to "法師迪斯騰特",
        "Captain Tobias" to "托比亞斯船長",
        "Lumbridge Swamp" to "倫布里奇沼澤",
        "Varrock Palace" to "瓦洛克宮殿",
        "Imcando Dwarves" to "英曼卡多矮人",
        "Animal Magnetism" to "動物磁鐵",
        "Sheep Shearer" to "綿羊剪毛工",
        "Cook's Assistant" to "廚師的助手",
        "Black Knights' Fortress" to "黑騎士要塞",
        "Dragon Slayer" to "龍獵殺",
        "Vampire Slayer" to "吸血鬼獵殺者",
        "Prince Ali Rescue" to "營救阿里王子",
        "Goblin Diplomacy" to "哥布林外交",
        "The Restless Ghost" to "不安的幽靈",
        "Romeo & Juliet" to "羅密歐與朱麗葉",
        "Witch's Potion" to "女巫的藥水",
        "Shield of Arrav" to "阿拉夫之盾",
        "Imp Catcher" to "小惡魔捕捉者",
        "Pirate's Treasure" to "海盜的寶藏",
        "Rune Mysteries" to "符文之謎",
        "Doric's Quest" to "多里克的任務",
        "Ernest the Chicken" to "歐尼斯與雞",
        "Quest Points" to "任務點數",
        "Alfi Marino" to "阿爾菲·馬里諾",
        "Ali Morrisane" to "艾利·莫里森",
        "Ana" to "安娜",
        "Aris Maye" to "艾瑞斯·梅伊",
        "Armadyl" to "阿瑪迪爾",
        "Bandos" to "班多斯",
        "Charles Lyeman" to "查爾斯·萊曼",
        "Dissy" to "迪西",
        "Dramen" to "德拉門",
        "Duradel" to "杜拉德爾",
        "Elidinis" to "艾利迪尼斯",
        "Elsie Parks" to "艾爾西·帕克斯",
        "Enhtor Prysin" to "恩托·普里辛",
        "Ethel Prim" to "艾瑟·普林",
        "Excalibur" to "埃斯卡利伯",
        "Fluffs" to "弗拉夫斯",
        "Fred Parks" to "佛雷德·帕克斯",
        "Fremennik" to "弗雷梅尼克",
        "Gaffit Malore" to "加菲特·馬洛",
        "Gianne" to "姬安",
        "Guthix" to "古希斯",
        "Hartigen" to "哈提根",
        "Hartwin Prim" to "哈特溫·普林",
        "Idonea Ramlock" to "伊多尼亞·拉姆洛克",
        "Iffie Nitter" to "伊菲·尼特",
        "Imcando" to "恩坎多",
        "JalYt-Ket" to "賈伊特·凱特",
        "Keli" to "凱莉",
        "Kermit" to "柯米特",
        "Konar" to "柯娜",
        "Koschei" to "科舍伊",
        "Laheeb" to "拉希布",
        "Lalli" to "拉利",
        "Mabel Malore" to "梅貝爾·馬洛",
        "Malignius Mortifer" to "馬利尼烏斯·莫提費爾",
        "Meiyerditch" to "梅耶迪奇",
        "Nieve" to "妮芙",
        "Phearthee Levalsyx" to "費瑟·萊瓦爾希克斯",
        "Port Phasmatys" to "幽靈港",
        "Rellugosh" to "雷魯戈什",
        "Saradomin" to "薩拉多明",
        "Seren" to "瑟倫",
        "Sithik Ints" to "西提克·因茨",
        "Surok Magis" to "蘇洛克·馬吉斯",
        "Thessalia Nitter" to "賽莎莉亞·尼特",
        "Tokkul" to "托庫爾",
        "Trevick Ramlock" to "特雷維克·拉姆洛克",
        "TzHaar" to "茲哈爾",
        "TzTok-Jad" to "茲托克·賈德",
        "Vet'ion" to "維提恩",
        "Vyrewatch" to "巫妖守衛",
        "Zamorak" to "扎莫拉克",
        "Zaros" to "扎羅斯",
        // ==== 擴充：高頻保留英文的重要地名/人名（本地模型音譯）====
        "Zanaris" to "札納里斯",
        "Rimmington" to "里明頓",
        "Brimhaven" to "布里姆哈文",
        "Crandor" to "克蘭多",
        "Pollnivneach" to "波利尼夫內赫",
        "Bedabin" to "貝達賓",
        "Taverly" to "塔弗利",
        "Sophanem" to "索法內姆",
        "Lletya" to "列蒂亞",
        "Piscatoris" to "皮斯卡托利斯",
        "Jatizso" to "賈提佐",
        "Neitiznot" to "奈提茲諾特",
        "Hemenster" to "海門斯特",
        "Asgarnia" to "阿斯加尼亞",
        "Dorgeshuun" to "多吉修恩",
        "Paterdomus" to "帕特爾多穆斯",
        "Khazard" to "卡扎德",
        "Shantay" to "珊泰",
        "Jiggig" to "吉吉格",
        "Isafdar" to "伊薩夫達",
        "Keldagrim" to "凱爾達格林",
        "Miscellania" to "米賽蘭尼亞",
        "Entrana" to "恩特拉納",
        "Mort Myre" to "莫特邁爾",
        "Tai Bwo Wannai" to "泰博瓦納伊",
        "Shilo Village" to "希洛村",
        "Lunar Isle" to "月亮島",
        "Port Sarim" to "薩林港",
        "Yanille" to "亞尼爾",
        "Ape Atoll" to "猿猴環礁",
        "Waterbirth Island" to "水生島",
        "Relleka" to "雷萊卡",
        "Gutanoth" to "古塔諾斯",
        "Oo'glog" to "歐格洛格",
        "Ava" to "艾娃",
        "Glarial" to "格拉里爾",
        "Baxtorian" to "巴克斯托里亞",
        "Filliman Tarlock" to "費利曼·塔洛克",
        "Filliman" to "費利曼",
        "Irena" to "伊蕾娜",
        "Eluned" to "艾露妮德",
        "Kennith" to "肯尼斯",
        "Turael" to "圖拉艾爾",
        "Lawgof" to "羅戈夫",
        "Sithik" to "希提克",
        "Thok" to "托克",
        "Brundt" to "布倫特",
        "Surok" to "蘇洛克",
        "Olaf" to "奧拉夫",
        "Tiffy" to "緹菲",
        "Rennard" to "雷納德",
        "Brentle" to "布倫特爾",
        "Vahn" to "凡恩",
        "Shabim" to "夏比姆",
        "Herbi" to "赫比",
        "Amik" to "阿米克",
        "Avan Fitzharmon" to "艾凡·費茲哈蒙",
        "Avan" to "艾凡",
        "Wally" to "沃利",
        "Balando" to "巴蘭多",
        "Ceril" to "賽里爾",
        "Zavistic" to "扎維斯提克",
        "Siad" to "希亞德",
        "Effendi" to "艾芬迪",
        "Trufitus" to "特魯菲圖斯",
        "Tenzing" to "丹增",
        "Hetty" to "海蒂",
        "Grish" to "格里什",
        "Pikkupstix" to "皮克普斯提克斯",
        "Almera" to "阿爾梅拉",
        "Hudon" to "赫登",
        "Blaze Sharpeye" to "烈焰銳眼",
        "Lennissa" to "蓮妮莎",
        "Askeladden" to "阿斯凱拉登",
        "Dimintheis" to "迪明西斯",
        "Leela" to "莉拉",
        "Golrie" to "戈里",
        "Doric" to "多里克",
        "Kaqemeex" to "卡克米克斯",
        "Holgart" to "霍加特",
        "Katrine" to "卡翠琳",
        "Dunstan" to "鄧斯坦",
        "Sanfew" to "桑費",
        "Hazeel" to "海茲爾",
        "Thormac" to "索馬克",
        "Achietties" to "阿奇提斯",
        "Charlie" to "查理",
        "Osman" to "奧斯曼",
        "Rantz" to "蘭茨",
        "Mordaut" to "莫多",
        "Sumona" to "蘇夢娜",
        "Icthlarin" to "伊克薩林",
        "Juna" to "茱娜",
        "Sigli" to "希格利",
        "Ghommal" to "葛莫爾",
        "Axit" to "阿克希特",
        "Tilae" to "緹萊",
        "Kolodion" to "科洛迪恩",
        "Cyrisus" to "賽瑞斯",
        "Dalvi" to "達爾維",
        "Marjory" to "瑪喬麗",
        "Gus" to "葛斯",
        "K'ril Tsutsaroth" to "庫里爾·茨蘇薩羅斯",
        "The Wise Old Man" to "智者老翁",
        "Kalphite Queen" to "卡爾法特女王",
    )

    /** 判斷是否含中文字元（CJK）。用於雙語化的「已翻譯」守門。 */
    private fun hasCjk(s: String): Boolean {
        for (c in s) {
            if (c.code in 0x4E00..0x9FFF || c.code in 0x3400..0x4DBF) return true
        }
        return false
    }

    /** 預編譯雙語化規則（長名優先，避免短名先吃字）。僅在載入後建一次。 */
    private data class BiRule(val en: String, val zh: String, val bilingual: String, val pat: Regex)
    private val BI_RULES: List<BiRule> by lazy(LazyThreadSafetyMode.NONE) {
        NAME_BI.entries
            .sortedByDescending { it.key.length }
            .map { (en, zh) -> BiRule(en, zh, "$zh ($en)", Regex("(?<![A-Za-z])${Regex.escape(en)}(?![A-Za-z])", RegexOption.IGNORE_CASE)) }
    }

    /**
     * 把譯文中出現的重點名替換為「中文 (英文)」。
     * 僅替換「英文字符串中獨立出現」的名稱（避免誤替換單詞內部）。
     */
    private fun applyNameBilingual(text: String): String {
        if (text.isEmpty() || !isLoaded()) return text
        // 守門：只在「已翻譯（含中文）」的譯文上做雙語化。
        // 未翻譯的純英文訊息原樣返回，避免把整句英文塞成中英夾雜垃圾（如未譯的『Talk to Ava.』變成混排）。
        if (!hasCjk(text)) return text
        var result = text
        for (r in BI_RULES) {
            // 若譯文中已是「中文 (英文)」或已是純中文，跳過
            if (result.contains(r.bilingual)) continue
            if (result.contains(r.zh)) continue
            if (!r.pat.containsMatchIn(result)) continue
            result = result.replace(r.pat, r.bilingual)
        }
        return result
    }

    /** 數字佔位模式：`$percent%`、`$number%` 等 `$xxx%` 變數。 */
    private val NUMBER_VAR_PATTERN = Pattern.compile("\\\$[A-Za-z_]+%?")

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

    /** 執行期文字中的數字（含千分位/小數）。 */
    private val VARNUM = Regex("""\\d[\\d,.]*""")

    /**
     * 多 `$var` 數字模板：表內 key 含 2+ 個 `$xxx`（或 `$xxx%`），
     * 以字面段切掉 `$var` 組出錨定正規式，逐段捕獲執行期數字再回填譯文。
     */
    private fun matchVarTemplate(text: String): String? {
        if (text.isEmpty() || !VARNUM.containsMatchIn(text)) return null
        val varPat = Regex("""\\\$[A-Za-z_][A-Za-z_0-9]*%?""")
        for ((key, value) in translations) {
            if (!key.startsWith("$") && !varPat.containsMatchIn(key)) continue
            val nvars = varPat.findAll(key).count()
            if (nvars < 2) continue // 單變數交由 matchNumberTemplate 處理
            val segs = varPat.split(key).map { Regex.escape(it) }
            val pattern = Regex("^" + segs.joinToString("(\\d[\\d,.]*)") + "$")
            val m = pattern.matchEntire(text) ?: continue
            val groups = m.groupValues.drop(1)
            var gi = 0
            val filled = varPat.replace(value) { groups[gi++] }
            return filled
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
