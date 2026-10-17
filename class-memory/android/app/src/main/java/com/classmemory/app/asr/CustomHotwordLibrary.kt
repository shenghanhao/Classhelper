package com.classmemory.app.asr

import android.content.Context
import com.classmemory.app.BuildConfig
import com.classmemory.app.i18n.tr
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.UUID

data class CustomHotwordDraft(val name: String, val terms: List<String>)

data class CustomHotwordLibrary(val id: String, val name: String, val terms: List<String>)

data class SelectedHotwordCourse(val id: String, val name: String, val isCustom: Boolean) {
    companion object {
        val NONE = SelectedHotwordCourse(AsrHotwordSettings.NONE, tr("未选择词库", "No library selected"), isCustom = false)
    }
}

object CustomHotwordLibraryCodec {
    const val MAX_INPUT_BYTES = 1024 * 1024
    const val MAX_TERMS = 200
    const val MAX_TERM_LENGTH = 80
    const val MAX_NAME_LENGTH = 60
    private val jsonKeys = setOf("schemaVersion", "name", "terms")

    fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val remaining = MAX_INPUT_BYTES + 1 - total
            if (remaining <= 0) throw IllegalArgumentException(tr("文件超过 1 MiB 限制", "File exceeds the 1 MiB limit"))
            val count = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (count < 0) break
            total += count
            if (total > MAX_INPUT_BYTES) throw IllegalArgumentException(tr("文件超过 1 MiB 限制", "File exceeds the 1 MiB limit"))
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    fun decode(bytes: ByteArray, txtName: String? = null): CustomHotwordDraft {
        require(bytes.isNotEmpty()) { tr("文件内容为空", "File is empty") }
        require(bytes.size <= MAX_INPUT_BYTES) { tr("文件超过 1 MiB 限制", "File exceeds the 1 MiB limit") }
        val content = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            throw IllegalArgumentException(tr("文件不是有效的 UTF-8 文本", "File is not valid UTF-8 text"))
        }
        val draft = if (content.trimStart().startsWith("{")) decodeJson(content) else decodeText(content, txtName)
        return validate(draft)
    }

    fun encode(draft: CustomHotwordDraft): ByteArray {
        val safe = validate(draft)
        val json = JSONObject()
            .put("schemaVersion", 1)
            .put("name", safe.name)
            .put("terms", JSONArray(safe.terms))
            .toString(2)
        val bytes = json.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_INPUT_BYTES) { tr("配置超过 1 MiB 限制", "Configuration exceeds the 1 MiB limit") }
        return bytes
    }

    fun validate(draft: CustomHotwordDraft): CustomHotwordDraft {
        val name = draft.name.trim()
        require(name.isNotEmpty() && name.codePointCount(0, name.length) <= MAX_NAME_LENGTH) {
            tr("名称长度需为 1–$MAX_NAME_LENGTH 个字符", "Name must be 1–$MAX_NAME_LENGTH characters long")
        }
        require(name.none { it.isISOControl() }) { tr("名称不能包含控制字符", "Name cannot contain control characters") }
        require(draft.terms.isNotEmpty() && draft.terms.size <= MAX_TERMS) {
            tr("词条数量需为 1–$MAX_TERMS 条", "Library must contain 1–$MAX_TERMS terms")
        }
        val terms = draft.terms.map(String::trim)
        require(terms.all { it.isNotEmpty() }) { tr("词条不能为空", "Terms cannot be empty") }
        require(terms.all { it.codePointCount(0, it.length) <= MAX_TERM_LENGTH }) {
            tr("单个词条不能超过 $MAX_TERM_LENGTH 个字符", "Each term must be no longer than $MAX_TERM_LENGTH characters")
        }
        require(terms.all { term ->
            term.none { it.isISOControl() || it in "/\\<>" }
        }) { tr("词条不能包含斜线、控制字符或特殊标记符号", "Terms cannot contain slashes, control characters, or special marker symbols") }
        require(terms.distinct().size == terms.size) { tr("词库中有重复词条", "Library contains duplicate terms") }
        return CustomHotwordDraft(name, terms.toList())
    }

    private fun decodeJson(content: String): CustomHotwordDraft {
        val json = try {
            parseSingleJsonObject(content)
        } catch (_: Exception) {
            throw IllegalArgumentException(tr("JSON 文件格式无效", "Invalid JSON file format"))
        }
        val keys = mutableSetOf<String>()
        json.keys().forEachRemaining { keys.add(it) }
        require(keys == jsonKeys) { tr("JSON 只允许 schemaVersion、name、terms 三个字段", "JSON may contain only schemaVersion, name, and terms fields") }
        val version = json.opt("schemaVersion")
        require(version is Int && version == 1) { tr("不支持的词库版本", "Unsupported library version") }
        val name = json.opt("name") as? String ?: throw IllegalArgumentException(tr("name 必须是文本", "name must be text"))
        val values = json.opt("terms") as? JSONArray ?: throw IllegalArgumentException(tr("terms 必须是数组", "terms must be an array"))
        val terms = (0 until values.length()).map { index ->
            values.opt(index) as? String ?: throw IllegalArgumentException(tr("第 ${index + 1} 个词条必须是文本", "Term ${index + 1} must be text"))
        }
        return CustomHotwordDraft(name, terms)
    }

    internal fun parseSingleJsonObject(content: String): JSONObject {
        val tokener = JSONTokener(content)
        val value = tokener.nextValue()
        require(tokener.nextClean() == '\u0000') { tr("JSON 文件包含多余内容", "JSON file contains trailing content") }
        return value as? JSONObject ?: throw IllegalArgumentException(tr("JSON 根节点必须是对象", "JSON root must be an object"))
    }

    private fun decodeText(content: String, txtName: String?): CustomHotwordDraft {
        val name = txtName ?: throw IllegalArgumentException(tr("导入 TXT 时请先填写词库名称", "Enter a library name before importing a TXT file"))
        val normalized = content.removeSuffix("\n")
        val terms = normalized.split('\n').map { it.removeSuffix("\r").trim() }
        require(terms.none(String::isEmpty)) { tr("TXT 每行必须包含一个非空词条", "Each TXT line must contain one non-empty term") }
        return CustomHotwordDraft(name, terms)
    }
}

object CustomHotwordLibraryCollection {
    const val MAX_LIBRARIES = 50
    private val envelopeKeys = setOf("schemaVersion", "libraries")

    fun encode(libraries: List<CustomHotwordLibrary>): String {
        validateCollection(libraries)
        val entries = JSONArray()
        libraries.forEach { library ->
            entries.put(JSONObject()
                .put("id", library.id)
                .put("name", library.name)
                .put("terms", JSONArray(library.terms)))
        }
        return JSONObject().put("schemaVersion", 1).put("libraries", entries).toString()
            .also { require(it.toByteArray(Charsets.UTF_8).size <= CustomHotwordLibraryCodec.MAX_INPUT_BYTES) {
                tr("自定义词库总配置超过 1 MiB 限制", "Custom libraries exceed the 1 MiB total limit")
            } }
    }

    fun decode(content: String?): List<CustomHotwordLibrary> {
        if (content == null) return emptyList()
        require(content.toByteArray(Charsets.UTF_8).size <= CustomHotwordLibraryCodec.MAX_INPUT_BYTES) {
            tr("自定义词库数据超过 1 MiB，已保留原始内容", "Custom library data exceeds 1 MiB; the original value was preserved")
        }
        val json = try { CustomHotwordLibraryCodec.parseSingleJsonObject(content) } catch (_: Exception) {
            throw IllegalArgumentException(tr("自定义词库数据损坏，已保留原始内容", "Custom library data is corrupted; the original value was preserved"))
        }
        val keys = mutableSetOf<String>()
        json.keys().forEachRemaining { keys.add(it) }
        require(keys == envelopeKeys && json.opt("schemaVersion") is Int && json.opt("schemaVersion") == 1) {
            tr("自定义词库版本或结构无效，已保留原始内容", "Custom library version or structure is invalid; the original value was preserved")
        }
        val entries = json.opt("libraries") as? JSONArray
            ?: throw IllegalArgumentException(tr("自定义词库列表无效，已保留原始内容", "Custom library list is invalid; the original value was preserved"))
        val libraries = (0 until entries.length()).map { index ->
            val item = entries.opt(index) as? JSONObject
                ?: throw IllegalArgumentException(tr("自定义词库记录无效，已保留原始内容", "Custom library record is invalid; the original value was preserved"))
            require(item.length() == 3) { tr("自定义词库字段无效，已保留原始内容", "Custom library fields are invalid; the original value was preserved") }
            val id = item.opt("id") as? String ?: throw IllegalArgumentException(tr("自定义词库标识无效，已保留原始内容", "Custom library ID is invalid; the original value was preserved"))
            val name = item.opt("name") as? String ?: throw IllegalArgumentException(tr("自定义词库名称无效，已保留原始内容", "Custom library name is invalid; the original value was preserved"))
            val termsArray = item.opt("terms") as? JSONArray
                ?: throw IllegalArgumentException(tr("自定义词库词条无效，已保留原始内容", "Custom library terms are invalid; the original value was preserved"))
            val terms = (0 until termsArray.length()).map { termIndex ->
                termsArray.opt(termIndex) as? String
                    ?: throw IllegalArgumentException(tr("自定义词库词条无效，已保留原始内容", "Custom library terms are invalid; the original value was preserved"))
            }
            require(id.startsWith("custom_") && runCatching {
                val uuid = id.removePrefix("custom_")
                UUID.fromString(uuid).toString() == uuid
            }.getOrDefault(false)) {
                tr("自定义词库标识无效，已保留原始内容", "Custom library ID is invalid; the original value was preserved")
            }
            val safe = CustomHotwordLibraryCodec.validate(CustomHotwordDraft(name, terms))
            CustomHotwordLibrary(id, safe.name, safe.terms)
        }
        validateCollection(libraries)
        return libraries
    }

    fun save(existing: List<CustomHotwordLibrary>, updated: CustomHotwordLibrary): List<CustomHotwordLibrary> {
        require(updated.id.startsWith("custom_") && runCatching {
            val uuid = updated.id.removePrefix("custom_")
            UUID.fromString(uuid).toString() == uuid
        }.getOrDefault(false)) { tr("自定义词库标识无效", "Invalid custom library ID") }
        val safe = CustomHotwordLibraryCodec.validate(CustomHotwordDraft(updated.name, updated.terms))
        require(existing.none { it.id != updated.id && it.name.equals(safe.name, ignoreCase = true) }) {
            tr("已有同名词库，请换一个名称；原词库未覆盖", "A library with this name already exists. Choose another name; the existing library was not changed.")
        }
        val result = existing.filterNot { it.id == updated.id } + CustomHotwordLibrary(updated.id, safe.name, safe.terms)
        validateCollection(result)
        return result
    }

    fun create(name: String, terms: List<String>, id: String = "custom_${UUID.randomUUID()}"): CustomHotwordLibrary {
        val safe = CustomHotwordLibraryCodec.validate(CustomHotwordDraft(name, terms))
        return CustomHotwordLibrary(id, safe.name, safe.terms).also {
            require(id.startsWith("custom_") && runCatching {
                val uuid = id.removePrefix("custom_")
                UUID.fromString(uuid).toString() == uuid
            }.getOrDefault(false)) {
                tr("自定义词库标识无效", "Invalid custom library ID")
            }
        }
    }

    private fun validateCollection(libraries: List<CustomHotwordLibrary>) {
        require(libraries.size <= MAX_LIBRARIES) { tr("最多保存 $MAX_LIBRARIES 个自定义词库", "You can save up to $MAX_LIBRARIES custom libraries") }
        require(libraries.map { it.id }.distinct().size == libraries.size) { tr("自定义词库标识重复", "Duplicate custom library IDs") }
        require(libraries.map { it.name.lowercase() }.distinct().size == libraries.size) { tr("自定义词库名称重复", "Duplicate custom library names") }
    }
}

object CustomHotwordRepresentability {
    fun parseTokenLines(content: String): Set<String> = content.lineSequence().mapNotNull { line ->
        val tokenEnd = line.indexOfLast(Char::isWhitespace)
        if (tokenEnd <= 0) null else line.substring(0, tokenEnd)
    }.toSet()

    fun unsupportedFragments(terms: List<String>, tokens: Set<String>): List<String> {
        class TrieNode {
            val children = mutableMapOf<Int, TrieNode>()
            var terminal = false
        }
        val root = TrieNode()
        tokens.forEach { token ->
            var node = root
            token.codePoints().forEach { cp -> node = node.children.getOrPut(cp) { TrieNode() } }
            node.terminal = true
        }
        val unsupported = linkedSetOf<String>()
        terms.forEach { term ->
            AsrHotwordSettings.toTokenizerInput(term).split(Regex("\\s+")).filter(String::isNotEmpty).forEach { word ->
                val points = ("▁" + word).codePoints().toArray()
                val reachable = BooleanArray(points.size + 1)
                reachable[0] = true
                for (start in points.indices) {
                    if (!reachable[start]) continue
                    var node = root
                    var end = start
                    while (end < points.size) {
                        node = node.children[points[end]] ?: break
                        end++
                        if (node.terminal) reachable[end] = true
                    }
                }
                if (!reachable[points.size]) unsupported += word
            }
        }
        return unsupported.toList()
    }

    fun requireRepresentable(terms: List<String>, tokens: Set<String>) {
        val missing = unsupportedFragments(terms, tokens)
        val preview = missing.take(12)
        val remaining = missing.size - preview.size
        require(missing.isEmpty()) {
            tr(
                "当前 X-ASR 词表无法表示这些片段：${preview.joinToString("、")}" + if (remaining > 0) "等${remaining}个片段" else "",
                "The current X-ASR vocabulary cannot represent these fragments: ${preview.joinToString(", ")}" + if (remaining > 0) " and $remaining more" else "",
            )
        }
    }
}

object CustomHotwordLibraryPreferences {
    internal const val PREFERENCES = "asr_hotword_settings"
    internal const val LIBRARIES_JSON = "custom_libraries_v1"
    private const val SELECTED_COURSE_ID = "selected_course_id"

    fun rawStoredValue(context: Context): String? =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(LIBRARIES_JSON, null)

    fun libraries(context: Context): List<CustomHotwordLibrary> = CustomHotwordLibraryCollection.decode(rawStoredValue(context))

    fun get(context: Context, id: String): CustomHotwordLibrary? = libraries(context).firstOrNull { it.id == id }

    fun create(context: Context, name: String, terms: List<String>): CustomHotwordLibrary {
        val library = CustomHotwordLibraryCollection.create(name, terms)
        save(context, library)
        return library
    }

    fun save(context: Context, library: CustomHotwordLibrary) {
        val tokens = readSupportedTokens(context)
        CustomHotwordRepresentability.requireRepresentable(library.terms, tokens)
        synchronized(lock) {
            val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            val original = preferences.getString(LIBRARIES_JSON, null)
            val existing = CustomHotwordLibraryCollection.decode(original)
            val updated = CustomHotwordLibraryCollection.save(existing, library)
            val encoded = CustomHotwordLibraryCollection.encode(updated)
            if (!preferences.edit().putString(LIBRARIES_JSON, encoded).commit()) {
                val rollback = preferences.edit()
                if (original == null) rollback.remove(LIBRARIES_JSON) else rollback.putString(LIBRARIES_JSON, original)
                rollback.commit()
                throw IllegalStateException(tr("保存失败，请重试", "Could not save. Try again."))
            }
        }
    }

    internal val lock = Any()

    fun readSupportedTokens(context: Context): Set<String> {
        fun read(name: String): Set<String> = context.assets
            .open("${BuildConfig.ASR_MODEL_DIR}/$name")
            .bufferedReader(Charsets.UTF_8)
            .use { CustomHotwordRepresentability.parseTokenLines(it.readText()) }
        return read("tokens.txt") intersect read("bpe.vocab")
    }

    fun select(context: Context, courseId: String) {
        synchronized(lock) {
            require(courseId.startsWith("custom_") && get(context, courseId) != null) { tr("找不到所选自定义词库", "Selected custom library was not found") }
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .putString(SELECTED_COURSE_ID, courseId)
                .apply()
        }
    }
}
