package com.mingdai.toTool.database

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

/**
 * 单文件 SQLite 数据库：先把 SAF 选中的文件复制到应用私有目录再打开，
 * 所有写操作都落在这份工作副本里；“保存到原文件”时关闭数据库并把副本写回。
 * 这样不需要把整个数据库读进内存，也不会受 SAF 不支持按路径打开的限制。
 */
internal class SqliteRepository(private val context: Context) {
    init {
        // 工作副本只用于当前会话；上次进程异常退出留下的副本不应长期保留。
        File(context.noBackupFilesDir, "database_manager")
            .listFiles()
            ?.forEach { it.delete() }
    }

    private var database: SQLiteDatabase? = null
    private var workingFile: File? = null

    var sourceUri: Uri? = null
        private set
    var displayName: String = ""
        private set
    var dirty: Boolean = false
        private set

    val isOpen: Boolean
        get() = database?.isOpen == true

    @Synchronized
    fun openFromUri(uri: Uri, name: String) {
        close()
        val file = prepareWorkingFile(uri, name)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            } ?: throw IOException("无法读取所选数据库文件")
            val opened = SQLiteDatabase.openDatabase(
                file.path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            )
            database = opened
            workingFile = file
            sourceUri = uri
            displayName = name
            dirty = false
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
    }

    @Synchronized
    fun createNew(uri: Uri, name: String) {
        close()
        val file = prepareWorkingFile(uri, name)
        file.delete()
        val opened = SQLiteDatabase.openOrCreateDatabase(file, null)
        database = opened
        workingFile = file
        sourceUri = uri
        displayName = name
        dirty = true
    }

    @Synchronized
    fun tables(): List<DbTable> {
        val db = requireDatabase()
        return db.rawQuery(
            "SELECT name, type FROM sqlite_master " +
                "WHERE type IN ('table','view') AND name NOT LIKE 'sqlite_%' ORDER BY name",
            null,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0) ?: continue
                    add(DbTable(name, cursor.getString(1) ?: "table"))
                }
            }
        }
    }

    @Synchronized
    fun browse(table: String, page: Int, pageSize: Int = 50): DbResult {
        val quoted = quote(table)
        val offset = page.coerceAtLeast(0) * pageSize
        val result = execute(
            "SELECT * FROM $quoted LIMIT ${pageSize + 1} OFFSET $offset",
            limit = pageSize,
        )
        val types = tableTypes(table)
        return if (types.isEmpty()) result else result.copy(
            columns = result.columns.map { it.copy(type = types[it.name] ?: it.type) },
        )
    }

    @Synchronized
    fun execute(sql: String, limit: Int = 200): DbResult {
        val db = requireDatabase()
        val started = System.nanoTime()
        val firstWord = sql.trimStart()
            .takeWhile { !it.isWhitespace() }
            .uppercase(Locale.ROOT)
        val returnsRows = firstWord in setOf("SELECT", "PRAGMA", "EXPLAIN", "WITH", "VALUES") ||
            Regex("\\bRETURNING\\b", RegexOption.IGNORE_CASE).containsMatchIn(sql)

        if (!returnsRows) {
            db.execSQL(sql)
            dirty = true
            val count = runCatching {
                db.rawQuery("SELECT changes()", null).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0).toInt() else 0
                }
            }.getOrDefault(0)
            return DbResult(
                updateCount = count,
                message = "执行成功，影响 $count 行",
                elapsedMs = (System.nanoTime() - started) / 1_000_000,
            )
        }

        db.rawQuery(sql, null).use { cursor ->
            val names = cursor.columnNames
            val types = Array(names.size) { "" }
            val rows = ArrayList<List<String?>>()
            var truncated = false
            var first = true
            while (cursor.moveToNext()) {
                if (limit > 0 && rows.size >= limit) {
                    truncated = true
                    break
                }
                if (first) {
                    for (i in names.indices) types[i] = typeName(cursor.getType(i))
                    first = false
                }
                rows += List(names.size) { index -> readCell(cursor, index) }
            }
            return DbResult(
                columns = names.mapIndexed { index, name -> DbColumn(name, types[index]) },
                rows = rows,
                truncated = truncated,
                message = if (truncated) "仅显示前 $limit 行" else null,
                elapsedMs = (System.nanoTime() - started) / 1_000_000,
            )
        }
    }

    /** 关闭数据库并把工作副本覆盖回原文件。 */
    @Synchronized
    fun saveToSource() {
        val uri = sourceUri ?: throw IllegalStateException("没有打开 SQLite 文件")
        val file = workingFile ?: throw IllegalStateException("工作副本不存在")
        val db = database ?: throw IllegalStateException("SQLite 未打开")
        database = null
        db.close()
        try {
            val output = context.contentResolver.openOutputStream(uri, "wt")
                ?: context.contentResolver.openOutputStream(uri)
                ?: throw IOException("文件提供方不允许写入")
            output.use { out -> file.inputStream().use { it.copyTo(out, 64 * 1024) } }
            dirty = false
        } finally {
            database = SQLiteDatabase.openDatabase(
                file.path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            )
        }
    }

    /** 导出工作副本，不改动原文件。 */
    @Synchronized
    fun exportTo(uri: Uri) {
        val file = workingFile ?: throw IllegalStateException("工作副本不存在")
        val db = database
        database = null
        runCatching { db?.close() }
        try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                file.inputStream().use { it.copyTo(out, 64 * 1024) }
            } ?: throw IOException("无法写入导出文件")
        } finally {
            database = SQLiteDatabase.openDatabase(
                file.path,
                null,
                SQLiteDatabase.OPEN_READWRITE,
            )
        }
    }

    @Synchronized
    fun close() {
        runCatching { database?.close() }
        database = null
        workingFile?.delete()
        workingFile = null
        sourceUri = null
        displayName = ""
        dirty = false
    }

    private fun requireDatabase(): SQLiteDatabase =
        database?.takeIf { it.isOpen } ?: throw IllegalStateException("尚未打开 SQLite 文件")

    private fun tableTypes(table: String): Map<String, String> {
        val db = database ?: return emptyMap()
        return runCatching {
            db.rawQuery("PRAGMA table_info(${quote(table)})", null).use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(1) ?: continue
                        put(name, cursor.getString(2).orEmpty())
                    }
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun readCell(cursor: Cursor, index: Int): String? = when (cursor.getType(index)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_BLOB -> hexPreview(cursor.getBlob(index))
        else -> cursor.getString(index)?.take(2_000)
    }

    private fun typeName(type: Int): String = when (type) {
        Cursor.FIELD_TYPE_NULL -> "NULL"
        Cursor.FIELD_TYPE_INTEGER -> "INTEGER"
        Cursor.FIELD_TYPE_FLOAT -> "REAL"
        Cursor.FIELD_TYPE_STRING -> "TEXT"
        Cursor.FIELD_TYPE_BLOB -> "BLOB"
        else -> ""
    }

    private fun hexPreview(bytes: ByteArray?): String? {
        if (bytes == null) return null
        val shown = bytes.take(32)
        return "0x" + shown.joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        } + if (bytes.size > shown.size) "…" else ""
    }

    private fun quote(name: String): String =
        "\"" + name.replace("\"", "\"\"") + "\""

    private fun prepareWorkingFile(uri: Uri, name: String): File {
        val dir = File(context.noBackupFilesDir, "database_manager").apply { mkdirs() }
        val base = name.substringAfterLast('/')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "database" }
            .take(48)
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(uri.toString().toByteArray())
            .take(8)
            .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
        return File(dir, "${base}_$hash.db")
    }
}
