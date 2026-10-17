package com.classmemory.app.asr

/** ASR comparison hotword settings kept independent of Android and sherpa runtime classes. */
data class AsrHotwordSettings(
    val profile: String,
    val nativeHotwords: List<String>,
    val hotwordsScore: Float,
    val decodingMethod: String,
    val maxActivePaths: Int,
    val modelingUnit: String,
) {
    val enabled: Boolean get() = profile != NONE
    val requiresBpeVocab: Boolean get() = enabled && modelingUnit == "bpe"
    val tokenizerInput: String get() = nativeHotwords.joinToString("/") { toTokenizerInput(it) }

    companion object {
        const val NONE = "none"
        const val COMPUTER_ORG = "computer_org"
        const val DATA_STRUCTURE = "data_structure"

        private val computerOrgTerms = listOf(
            "计算机组成原理", "运算器", "控制器", "存储器", "主存储器", "中央处理器", "寄存器", "总线",
            "地址总线", "数据总线", "控制总线", "指令", "指令周期", "机器周期", "时钟周期", "微指令",
            "微程序", "硬布线控制", "算术逻辑单元", "程序计数器", "指令寄存器", "地址寄存器", "数据寄存器",
            "补码", "原码", "反码", "移码", "定点数", "浮点数", "尾数", "阶码", "溢出", "寻址方式",
            "直接寻址", "间接寻址", "变址寻址", "相对寻址", "流水线", "中断", "直接存储器访问",
            "缓存", "高速缓存", "命中率", "CPU", "ALU", "RAM", "ROM", "Cache", "DMA", "SRAM",
            "DRAM", "RISC", "CISC", "指令Cache", "数据Cache",
        )

        private val dataStructureTerms = listOf(
            "数据结构", "线性表", "单链表", "顺序表", "链表", "结点", "节点", "数据元素", "数据项",
            "数据域", "指针域", "指针", "头指针", "尾指针", "头结点", "首元结点", "终端结点",
            "前驱结点", "后继结点", "空表", "空指针", "存储结构", "逻辑结构", "顺序存储",
            "链式存储", "随机存取", "顺序存取", "动态分配", "静态分配", "地址", "存储地址",
            "内存空间", "分配空间", "释放空间", "建立链表", "查找结点", "按位查找", "按值查找",
            "插入结点", "删除结点", "头插法", "尾插法", "带头结点", "不带头结点", "时间复杂度",
            "malloc", "free", "struct", "node", "next", "NULL", "data", "head", "tail",
            "LNode", "LinkList", "ElemType", "sizeof", "return", "while", "链域",
            "结构体", "数组下标", "首地址", "遍历", "计数器", "赋值", "前插", "后插", "逆序读入",
        )

        fun forProfile(profile: String): AsrHotwordSettings = when (profile) {
            NONE -> AsrHotwordSettings(NONE, emptyList(), 0.0f, "greedy_search", 4, "")
            COMPUTER_ORG -> AsrHotwordSettings(COMPUTER_ORG, computerOrgTerms, 1.5f, "modified_beam_search", 4, "bpe")
            DATA_STRUCTURE -> AsrHotwordSettings(DATA_STRUCTURE, dataStructureTerms, 1.5f, "modified_beam_search", 4, "bpe")
            else -> error("Unknown ASR hotword profile: $profile")
        }.also { settings ->
            require(settings.nativeHotwords.distinct().size == settings.nativeHotwords.size) { "Hotwords must be unique" }
            require(settings.nativeHotwords.all { it.isNotBlank() && '/' !in it && '\n' !in it && '\r' !in it }) {
                "Hotwords must be non-empty and cannot contain slash or newline"
            }
        }

        /** sherpa's BPE hotword parser encodes each whitespace-delimited word independently. */
        fun toTokenizerInput(term: String): String = buildString {
            var previousHan = false
            for (char in term) {
                val han = char.isHan()
                if (han && previousHan) append(' ')
                else if (han && isNotEmpty()) append(' ')
                else if (!han && previousHan) append(' ')
                append(char)
                previousHan = han
            }
        }

        private fun Char.isHan(): Boolean = this in '\u3400'..'\u4dbf' || this in '\u4e00'..'\u9fff'
    }
}
