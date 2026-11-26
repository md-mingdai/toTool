package com.mingdai.toTool.database

/** 一列结果的显示信息。 */
data class DbColumn(
    val name: String,
    val type: String = "",
)

/** 一次查询/执行的结果。表格最多保留 UI 需要的行数，避免大结果耗尽内存。 */
data class DbResult(
    val columns: List<DbColumn> = emptyList(),
    val rows: List<List<String?>> = emptyList(),
    val updateCount: Int? = null,
    val message: String? = null,
    val truncated: Boolean = false,
    val elapsedMs: Long = 0L,
) {
    val hasGrid: Boolean get() = columns.isNotEmpty()
}

data class DbTable(
    val name: String,
    val type: String = "table",
)

data class MySqlProfile(
    val host: String,
    val port: Int,
    val user: String,
    val database: String,
    val useSsl: Boolean,
)

internal fun friendlyDbError(error: Throwable): String {
    val raw = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
    val lower = raw.lowercase()
    return when {
        "access denied" in lower ->
            "账号或密码错误，或该账号不允许从当前网络登录。"
        "unknown database" in lower ->
            "数据库不存在。"
        "communications link failure" in lower ||
            "connection refused" in lower ||
            "failed to connect" in lower ||
            "no route to host" in lower ->
            "无法连接服务器，请检查主机、端口和网络。"
        "ssl" in lower || "tls" in lower ->
            "SSL/TLS 握手失败；可以尝试关闭 SSL，或检查服务器证书。"
        "timeout" in lower || "timed out" in lower ->
            "连接超时，请检查网络和服务器防火墙。"
        else -> raw.take(400)
    }
}
