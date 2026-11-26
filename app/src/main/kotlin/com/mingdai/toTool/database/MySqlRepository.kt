package com.mingdai.toTool.database

import java.math.BigDecimal
import java.sql.Blob
import java.sql.Clob
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.util.Locale

/**
 * 远程 MySQL / MariaDB 会话。只依赖 MariaDB Connector/J，不使用 ORM 或连接池，
 * 以控制 APK 体积；所有方法都应从 IO 线程调用。
 */
internal class MySqlRepository {
    private var connection: Connection? = null

    var serverName: String = ""
        private set
    var serverVersion: String = ""
        private set

    val connected: Boolean
        get() = connection?.isClosed == false

    @Synchronized
    fun connect(profile: MySqlProfile, password: String) {
        disconnect()
        Class.forName("org.mariadb.jdbc.Driver")
        val host = profile.host.trim().let {
            if (it.contains(':') && !it.startsWith("[")) "[$it]" else it
        }
        val options = buildList {
            add("connectTimeout=8000")
            add("socketTimeout=30000")
            add("allowPublicKeyRetrieval=true")
            add("useSsl=${profile.useSsl}")
        }.joinToString("&")
        val url = "jdbc:mariadb://$host:${profile.port}/?$options"
        val opened = DriverManager.getConnection(url, profile.user, password)
        try {
            opened.autoCommit = true
            val meta = opened.metaData
            serverName = meta.databaseProductName.orEmpty()
            serverVersion = meta.databaseProductVersion.orEmpty()
            connection = opened
        } catch (e: Throwable) {
            runCatching { opened.close() }
            throw e
        }
    }

    @Synchronized
    fun disconnect() {
        runCatching { connection?.close() }
        connection = null
        serverName = ""
        serverVersion = ""
    }

    @Synchronized
    fun selectDatabase(database: String) {
        if (database.isBlank()) return
        requireConnection().catalog = database
    }

    @Synchronized
    fun databases(): List<String> {
        val db = requireConnection()
        return db.createStatement().use { statement ->
            statement.executeQuery("SHOW DATABASES").use { rs ->
                buildList {
                    while (rs.next()) rs.getString(1)?.let(::add)
                }
            }.sorted()
        }
    }

    @Synchronized
    fun tables(): List<DbTable> {
        val db = requireConnection()
        return db.createStatement().use { statement ->
            statement.executeQuery("SHOW FULL TABLES").use { rs ->
                buildList {
                    while (rs.next()) {
                        val name = rs.getString(1) ?: continue
                        val type = runCatching { rs.getString(2) }.getOrNull()
                        add(DbTable(name, type ?: "table"))
                    }
                }
            }.sortedBy { it.name.lowercase(Locale.ROOT) }
        }
    }

    @Synchronized
    fun browse(table: String, page: Int, pageSize: Int = 50): DbResult {
        val quoted = "`" + table.replace("`", "``") + "`"
        val offset = page.coerceAtLeast(0) * pageSize
        return execute(
            "SELECT * FROM $quoted LIMIT ${pageSize + 1} OFFSET $offset",
            limit = pageSize,
        )
    }

    @Synchronized
    fun execute(sql: String, limit: Int = 200): DbResult {
        val db = requireConnection()
        val started = System.nanoTime()
        db.createStatement().use { statement ->
            if (limit > 0) statement.maxRows = limit + 1
            val hasResult = statement.execute(sql)
            if (!hasResult) {
                val count = statement.updateCount.takeIf { it >= 0 }
                return DbResult(
                    updateCount = count,
                    message = if (count == null) "执行成功" else "影响 $count 行",
                    elapsedMs = (System.nanoTime() - started) / 1_000_000,
                )
            }
            statement.resultSet.use { rs ->
                val meta = rs.metaData
                val columns = List(meta.columnCount) { index ->
                    val i = index + 1
                    DbColumn(
                        name = runCatching { meta.getColumnLabel(i) }
                            .getOrNull()
                            ?.takeIf { it.isNotBlank() }
                            ?: runCatching { meta.getColumnName(i) }.getOrDefault("列 $i"),
                        type = runCatching { meta.getColumnTypeName(i) }.getOrDefault(""),
                    )
                }
                val rows = ArrayList<List<String?>>()
                var truncated = false
                while (rs.next()) {
                    if (limit > 0 && rows.size >= limit) {
                        truncated = true
                        break
                    }
                    rows += List(meta.columnCount) { index -> readCell(rs, index + 1) }
                }
                return DbResult(
                    columns = columns,
                    rows = rows,
                    truncated = truncated,
                    message = if (truncated) "仅显示前 $limit 行" else null,
                    elapsedMs = (System.nanoTime() - started) / 1_000_000,
                )
            }
        }
    }

    private fun readCell(rs: ResultSet, index: Int): String? {
        val value = try {
            rs.getObject(index)
        } catch (e: Exception) {
            return "(读取失败)"
        } ?: return null
        return when (value) {
            is ByteArray -> hexPreview(value)
            is Blob -> "(BLOB ${runCatching { value.length() }.getOrDefault(0)} B)"
            is Clob -> "(CLOB ${runCatching { value.length() }.getOrDefault(0)} 字符)"
            is BigDecimal -> value.toPlainString()
            is Boolean -> if (value) "1" else "0"
            is Array<*> -> value.joinToString(",") { it?.toString().orEmpty() }
            else -> value.toString().take(2_000)
        }
    }

    private fun hexPreview(bytes: ByteArray): String {
        val shown = bytes.take(32)
        return "0x" + shown.joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        } + if (bytes.size > shown.size) "…" else ""
    }

    private fun requireConnection(): Connection =
        connection?.takeIf { !it.isClosed }
            ?: throw IllegalStateException("尚未连接数据库")

    fun close() = disconnect()
}
