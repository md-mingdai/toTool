package com.mingdai.toTool.ui.screens.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Persistent script library and run history for the Shell tool.
 *
 * The script text is kept in its own file so a long script does not end up in
 * SharedPreferences / a single huge JSON string. Metadata and history are small
 * JSON files in the app's private files directory.
 */
internal data class SavedShellScript(
    val id: String,
    val name: String,
    val bytes: Int,
    val savedAt: Long,
    val text: String,
) {
    val lineCount: Int = text.trimEnd('\n').count { it == '\n' } + 1

    fun toShellScript(): ShellScript = ShellScript(name, text, bytes)
}

internal data class ShellHistoryEntry(
    val id: String,
    val title: String,
    val kind: String,
    val startedAt: Long,
    val durationMs: Long,
    val exitCode: Int?,
    val stopped: Boolean,
    val failed: Boolean,
)

internal object ShellLibrary {
    private const val MAX_SCRIPTS = 30
    private const val MAX_HISTORY = 50
    private const val DIR_NAME = "shell-library"

    private fun baseDir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { mkdirs() }

    private fun scriptsDir(context: Context): File =
        File(baseDir(context), "scripts").apply { mkdirs() }

    private fun metaFile(context: Context): File = File(baseDir(context), "scripts.json")
    private fun historyFile(context: Context): File = File(baseDir(context), "history.json")

    @Synchronized
    fun loadScripts(context: Context): List<SavedShellScript> {
        val array = readArray(metaFile(context)) ?: return emptyList()
        val result = mutableListOf<SavedShellScript>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
            val name = item.optString("name").takeIf { it.isNotBlank() } ?: continue
            val file = File(scriptsDir(context), "$id.sh")
            if (!file.isFile) continue
            val text = runCatching { file.readText() }.getOrNull() ?: continue
            if (text.isBlank()) continue
            result += SavedShellScript(
                id = id,
                name = name,
                bytes = item.optInt("bytes", text.toByteArray(Charsets.UTF_8).size),
                savedAt = item.optLong("savedAt", 0L),
                text = text,
            )
        }
        return result.sortedByDescending { it.savedAt }
    }

    @Synchronized
    fun saveScript(context: Context, script: ShellScript): SavedShellScript {
        val now = System.currentTimeMillis()
        val existing = loadScripts(context)
        val duplicate = existing.firstOrNull { it.name == script.name && it.text == script.text }
        if (duplicate != null) {
            val updated = duplicate.copy(savedAt = now)
            val all = (existing.filterNot { it.id == duplicate.id } + updated)
                .sortedByDescending { it.savedAt }
            writeMeta(context, all)
            return updated
        }

        val id = UUID.randomUUID().toString()
        writeAtomically(File(scriptsDir(context), "$id.sh"), script.text)
        val saved = SavedShellScript(
            id = id,
            name = script.name,
            bytes = script.bytes,
            savedAt = now,
            text = script.text,
        )
        val all = (existing + saved).sortedByDescending { it.savedAt }
        val kept = all.take(MAX_SCRIPTS)
        all.drop(MAX_SCRIPTS).forEach { old ->
            runCatching { File(scriptsDir(context), "${old.id}.sh").delete() }
        }
        writeMeta(context, kept)
        return saved
    }

    @Synchronized
    fun deleteScript(context: Context, id: String) {
        val remaining = loadScripts(context).filterNot { it.id == id }
        runCatching { File(scriptsDir(context), "$id.sh").delete() }
        writeMeta(context, remaining)
    }

    @Synchronized
    fun loadHistory(context: Context): List<ShellHistoryEntry> {
        val array = readArray(historyFile(context)) ?: return emptyList()
        val result = mutableListOf<ShellHistoryEntry>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            result += ShellHistoryEntry(
                id = item.optString("id", "${index}"),
                title = item.optString("title"),
                kind = item.optString("kind", "command"),
                startedAt = item.optLong("startedAt", 0L),
                durationMs = item.optLong("durationMs", 0L),
                exitCode = if (item.isNull("exitCode")) null else item.optInt("exitCode"),
                stopped = item.optBoolean("stopped", false),
                failed = item.optBoolean("failed", false),
            )
        }
        return result
    }

    @Synchronized
    fun appendHistory(context: Context, entry: ShellHistoryEntry): List<ShellHistoryEntry> {
        val all = (listOf(entry) + loadHistory(context))
            .distinctBy { it.id }
            .take(MAX_HISTORY)
        val array = JSONArray()
        all.forEach { item ->
            array.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("title", item.title)
                    put("kind", item.kind)
                    put("startedAt", item.startedAt)
                    put("durationMs", item.durationMs)
                    put("exitCode", item.exitCode ?: JSONObject.NULL)
                    put("stopped", item.stopped)
                    put("failed", item.failed)
                },
            )
        }
        writeAtomically(historyFile(context), array.toString())
        return all
    }

    @Synchronized
    fun clearHistory(context: Context) {
        runCatching { historyFile(context).delete() }
    }

    private fun writeMeta(context: Context, scripts: List<SavedShellScript>) {
        val array = JSONArray()
        scripts.forEach { script ->
            array.put(
                JSONObject().apply {
                    put("id", script.id)
                    put("name", script.name)
                    put("bytes", script.bytes)
                    put("savedAt", script.savedAt)
                },
            )
        }
        writeAtomically(metaFile(context), array.toString())
    }

    private fun readArray(file: File): JSONArray? {
        return try {
            if (!file.isFile) return null
            val text = file.readText()
            if (text.isBlank()) null else JSONArray(text)
        } catch (e: Exception) {
            null
        }
    }

    private fun writeAtomically(file: File, text: String) {
        val parent = file.parentFile ?: return
        parent.mkdirs()
        val temp = File(parent, "${file.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            file.writeText(text)
            temp.delete()
        }
    }
}
