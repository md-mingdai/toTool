package com.mingdai.toTool.ui.screens.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mingdai.toTool.database.DbColumn
import com.mingdai.toTool.database.DbResult
import com.mingdai.toTool.database.DbTable
import com.mingdai.toTool.database.MySqlProfile
import com.mingdai.toTool.database.MySqlRepository
import com.mingdai.toTool.database.SqliteRepository
import com.mingdai.toTool.database.friendlyDbError
import com.mingdai.toTool.ui.components.AppDialog
import com.mingdai.toTool.ui.components.HintText
import com.mingdai.toTool.ui.components.PrimaryButton
import com.mingdai.toTool.ui.components.SecondaryButton
import com.mingdai.toTool.ui.components.SectionLabel
import com.mingdai.toTool.ui.components.SelectChip
import com.mingdai.toTool.ui.components.ToolBody
import com.mingdai.toTool.ui.components.WarnColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class DbMode { MYSQL, SQLITE }

private val SystemDatabases = setOf("information_schema", "performance_schema", "mysql", "sys")
private const val DB_PREFS = "database_manager"
private const val PAGE_SIZE = 50

@Composable
fun DatabaseTool(padding: PaddingValues) {
    var mode by rememberSaveable { mutableStateOf(DbMode.MYSQL) }
    ToolBody(padding) {
        SectionLabel("数据库管理")
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("直接在手机上管理数据库", style = MiuixTheme.textStyles.body1)
                HintText(
                    "远程连接使用 MySQL / MariaDB 协议，从手机直接连到服务器；" +
                        "请只管理你有权访问的数据库。密码只在本次会话中使用，不会写入本地存储；" +
                        "公网连接建议启用 SSL。SQLite 会先复制到应用私有目录，修改后需点「保存」写回原文件。",
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectChip(
                selected = mode == DbMode.MYSQL,
                onClick = { mode = DbMode.MYSQL },
                label = "MySQL / MariaDB",
            )
            SelectChip(
                selected = mode == DbMode.SQLITE,
                onClick = { mode = DbMode.SQLITE },
                label = "SQLite 文件",
            )
        }
        when (mode) {
            DbMode.MYSQL -> MySqlManager()
            DbMode.SQLITE -> SqliteManager()
        }
    }
}

/* ───────────────────────────── MySQL ───────────────────────────── */

@Composable
private fun MySqlManager() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { MySqlRepository() }
    val prefs = remember {
        context.getSharedPreferences(DB_PREFS, Context.MODE_PRIVATE)
    }
    DisposableEffect(Unit) {
        onDispose { repo.close() }
    }

    var host by rememberSaveable { mutableStateOf(prefs.getString("mysql_host", "") ?: "") }
    var portText by rememberSaveable { mutableStateOf(prefs.getInt("mysql_port", 3306).toString()) }
    var user by rememberSaveable { mutableStateOf(prefs.getString("mysql_user", "") ?: "") }
    var password by remember { mutableStateOf("") }
    var database by rememberSaveable { mutableStateOf(prefs.getString("mysql_database", "") ?: "") }
    var useSsl by rememberSaveable { mutableStateOf(prefs.getBoolean("mysql_ssl", false)) }
    var connected by remember { mutableStateOf(false) }
    var serverLabel by remember { mutableStateOf("") }

    var databases by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedDatabase by remember { mutableStateOf("") }
    var tables by remember { mutableStateOf<List<DbTable>>(emptyList()) }
    var selectedTable by remember { mutableStateOf<String?>(null) }
    var tablePage by remember { mutableIntStateOf(0) }
    var tableResult by remember { mutableStateOf<DbResult?>(null) }
    var sql by rememberSaveable { mutableStateOf("SHOW TABLES;") }
    var queryResult by remember { mutableStateOf<DbResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var showDbPicker by remember { mutableStateOf(false) }
    var showDanger by remember { mutableStateOf(false) }
    var pendingSql by remember { mutableStateOf<String?>(null) }

    fun connect() {
        if (busy) return
        val hostValue = host.trim()
        val port = portText.toIntOrNull()
        val userValue = user.trim()
        when {
            hostValue.isEmpty() -> { error = "请输入服务器地址"; return }
            port == null || port !in 1..65535 -> { error = "端口需要在 1–65535 之间"; return }
            userValue.isEmpty() -> { error = "请输入用户名"; return }
        }
        busy = true
        error = null
        notice = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    repo.connect(
                        MySqlProfile(
                            host = hostValue,
                            port = port,
                            user = userValue,
                            database = database.trim(),
                            useSsl = useSsl,
                        ),
                        password,
                    )
                }
                val loadedDatabases = withContext(Dispatchers.IO) { repo.databases() }
                databases = loadedDatabases
                val initial = database.trim().takeIf { it in loadedDatabases }
                    ?: loadedDatabases.firstOrNull { it !in SystemDatabases }
                    ?: loadedDatabases.firstOrNull().orEmpty()
                selectedDatabase = initial
                database = initial
                val loadedTables = if (initial.isNotBlank()) {
                    withContext(Dispatchers.IO) {
                        repo.selectDatabase(initial)
                        repo.tables()
                    }
                } else {
                    emptyList()
                }
                tables = loadedTables
                selectedTable = null
                tableResult = null
                serverLabel = listOf(repo.serverName, repo.serverVersion)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                connected = true
                sql = if (initial.isNotBlank()) "SHOW TABLES;" else "SHOW DATABASES;"
                prefs.edit()
                    .putString("mysql_host", hostValue)
                    .putInt("mysql_port", port)
                    .putString("mysql_user", userValue)
                    .putString("mysql_database", initial)
                    .putBoolean("mysql_ssl", useSsl)
                    .apply()
                if (loadedDatabases.isEmpty()) {
                    notice = "已连接，但当前账号没有可见的数据库。"
                }
            } catch (e: Throwable) {
                repo.close()
                connected = false
                error = friendlyDbError(e)
            } finally {
                busy = false
            }
        }
    }

    fun disconnect() {
        repo.close()
        connected = false
        databases = emptyList()
        tables = emptyList()
        selectedDatabase = ""
        selectedTable = null
        tableResult = null
        queryResult = null
        serverLabel = ""
        password = ""
        error = null
        notice = null
    }

    fun chooseDatabase(name: String) {
        if (busy) return
        busy = true
        error = null
        notice = null
        selectedDatabase = name
        database = name
        selectedTable = null
        tableResult = null
        scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    repo.selectDatabase(name)
                    repo.tables()
                }
                tables = loaded
                prefs.edit().putString("mysql_database", name).apply()
                if (loaded.isEmpty()) notice = "这个数据库里没有表或视图。"
            } catch (e: Throwable) {
                error = friendlyDbError(e)
            } finally {
                busy = false
            }
        }
    }

    fun refreshTables() {
        if (busy || !connected) return
        busy = true
        error = null
        scope.launch {
            try {
                tables = withContext(Dispatchers.IO) { repo.tables() }
            } catch (e: Throwable) {
                error = friendlyDbError(e)
            } finally {
                busy = false
            }
        }
    }

    fun loadTable(table: String, page: Int) {
        if (busy) return
        busy = true
        error = null
        selectedTable = table
        scope.launch {
            try {
                tableResult = withContext(Dispatchers.IO) {
                    repo.browse(table, page, PAGE_SIZE)
                }
                tablePage = page
            } catch (e: Throwable) {
                error = friendlyDbError(e)
            } finally {
                busy = false
            }
        }
    }

    fun runQuery(statement: String) {
        if (busy) return
        busy = true
        error = null
        notice = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { repo.execute(statement, 200) }
                queryResult = result
                if (result.updateCount != null) {
                    val refreshed = try {
                        withContext(Dispatchers.IO) { repo.tables() }
                    } catch (e: Exception) {
                        tables
                    }
                    tables = refreshed
                    val table = selectedTable
                    if (table != null) {
                        val browsed = try {
                            withContext(Dispatchers.IO) { repo.browse(table, tablePage, PAGE_SIZE) }
                        } catch (e: Exception) {
                            null
                        }
                        if (browsed != null) tableResult = browsed
                    }
                    notice = result.message
                }
            } catch (e: Throwable) {
                error = friendlyDbError(e)
            } finally {
                busy = false
            }
        }
    }

    fun executeSql() {
        val statement = sql.trim()
        if (statement.isBlank()) {
            error = "请输入 SQL"
            return
        }
        if (Regex("^\\s*(DROP|TRUNCATE)\\b", RegexOption.IGNORE_CASE).containsMatchIn(statement)) {
            pendingSql = statement
            showDanger = true
        } else {
            runQuery(statement)
        }
    }

    if (!connected) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("连接远程服务器", style = MiuixTheme.textStyles.body2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextField(
                        value = host,
                        onValueChange = { host = it; error = null },
                        label = "主机 / IP",
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextField(
                        value = portText,
                        onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                        label = "端口",
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(96.dp),
                    )
                }
                TextField(
                    value = user,
                    onValueChange = { user = it; error = null },
                    label = "用户名",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                DatabasePasswordField(
                    value = password,
                    onValueChange = { password = it },
                    enabled = !busy,
                )
                TextField(
                    value = database,
                    onValueChange = { database = it },
                    label = "数据库（可选）",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("SSL / TLS", style = MiuixTheme.textStyles.body2)
                        Text(
                            "公网数据库建议开启；自签名证书可能连接失败",
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    Switch(checked = useSsl, onCheckedChange = { useSsl = it })
                }
                if (busy) LinearProgressIndicator(progress = null, modifier = Modifier.fillMaxWidth())
                error?.let { DbErrorText(it) }
                notice?.let { HintText(it) }
                PrimaryButton(
                    onClick = { connect() },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (busy) "正在连接…" else "连接")
                }
                HintText("密码只在本次应用进程内使用，不写入 SharedPreferences。")
            }
        }
        return
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("●", color = MiuixTheme.colorScheme.primary, style = MiuixTheme.textStyles.title4)
                Column(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                    Text("已连接", style = MiuixTheme.textStyles.body1)
                    Text(
                        serverLabel.ifBlank { "MySQL / MariaDB" },
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
                SecondaryButton(onClick = { disconnect() }, enabled = !busy) {
                    Text("断开")
                }
            }
            if (busy) LinearProgressIndicator(progress = null, modifier = Modifier.fillMaxWidth())
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = { if (!busy) showDbPicker = true },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "当前数据库",
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Text(
                    selectedDatabase.ifBlank { "未选择" },
                    style = MiuixTheme.textStyles.body1,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionLabel("表与视图")
        Spacer(Modifier.weight(1f))
        SecondaryButton(onClick = { refreshTables() }, enabled = !busy) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("刷新")
        }
    }
    DbTableList(
        tables = tables,
        selected = selectedTable,
        onSelect = { loadTable(it, 0) },
    )

    selectedTable?.let { table ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "表：$table",
                style = MiuixTheme.textStyles.body2,
                modifier = Modifier.weight(1f),
            )
            SecondaryButton(
                onClick = {
                    selectedTable = null
                    tableResult = null
                },
            ) { Text("返回表列表") }
        }
        DbResultGrid(tableResult)
        Pager(
            page = tablePage,
            hasNext = tableResult?.truncated == true,
            enabled = !busy,
            onPrev = { loadTable(table, tablePage - 1) },
            onNext = { loadTable(table, tablePage + 1) },
        )
    }

    SectionLabel("SQL")
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextField(
                value = sql,
                onValueChange = { sql = it },
                label = "SQL 语句",
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(
                    onClick = { executeSql() },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (busy) "执行中…" else "执行")
                }
                SecondaryButton(
                    onClick = { sql = "" },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("清空")
                }
            }
            HintText("一次执行一条语句；DROP / TRUNCATE 会二次确认。结果最多预览 200 行。")
        }
    }
    error?.let { DbErrorText(it) }
    notice?.let { HintText(it) }
    DbResultGrid(queryResult)

    if (showDbPicker) {
        DbPickerDialog(
            title = "选择数据库",
            items = databases,
            onDismiss = { showDbPicker = false },
            onSelect = {
                showDbPicker = false
                chooseDatabase(it)
            },
        )
    }

    if (showDanger) {
        AppDialog(
            show = true,
            title = "执行危险 SQL？",
            summary = pendingSql?.take(180),
            confirmText = "执行",
            onDismiss = {
                showDanger = false
                pendingSql = null
            },
            onConfirm = {
                val statement = pendingSql
                showDanger = false
                pendingSql = null
                if (statement != null) runQuery(statement)
            },
        )
    }
}

/* ───────────────────────────── SQLite ───────────────────────────── */

@Composable
private fun SqliteManager() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { SqliteRepository(context) }
    DisposableEffect(Unit) {
        onDispose { repo.close() }
    }

    var open by remember { mutableStateOf(false) }
    var fileName by remember { mutableStateOf("") }
    var dirty by remember { mutableStateOf(false) }
    var tables by remember { mutableStateOf<List<DbTable>>(emptyList()) }
    var selectedTable by remember { mutableStateOf<String?>(null) }
    var tablePage by remember { mutableIntStateOf(0) }
    var tableResult by remember { mutableStateOf<DbResult?>(null) }
    var sql by rememberSaveable { mutableStateOf("SELECT name, type FROM sqlite_master;") }
    var queryResult by remember { mutableStateOf<DbResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var showDanger by remember { mutableStateOf(false) }
    var pendingSql by remember { mutableStateOf<String?>(null) }
    var showCloseConfirm by remember { mutableStateOf(false) }

    fun resetState() {
        open = false
        fileName = ""
        dirty = false
        tables = emptyList()
        selectedTable = null
        tableResult = null
        queryResult = null
        error = null
        notice = null
    }

    fun loadFile(uri: Uri, create: Boolean) {
        if (busy) return
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val name = queryDbName(context, uri)
        busy = true
        error = null
        notice = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (create) repo.createNew(uri, name) else repo.openFromUri(uri, name)
                }
                tables = withContext(Dispatchers.IO) { repo.tables() }
                open = true
                fileName = name
                dirty = repo.dirty
                selectedTable = null
                tableResult = null
                queryResult = null
                sql = if (create) "" else "SELECT name, type FROM sqlite_master;"
                if (create) notice = "新建的数据库尚未写回文件，修改后点「保存」导出。"
            } catch (e: Throwable) {
                error = friendlyDbError(e)
            } finally {
                busy = false
            }
        }
    }

    val openPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadFile(uri, create = false)
    }
    val createPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-sqlite3"),
    ) { uri ->
        if (uri != null) loadFile(uri, create = true)
    }
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        error = null
        notice = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { repo.exportTo(uri) }
                notice = "数据库副本已导出。"
            } catch (e: Throwable) {
                error = friendlyDbError(e)
            } finally {
                busy = false
            }
        }
    }

    fun save() {
        if (busy || !open || !dirty) return
        busy = true
        error = null
        notice = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) { repo.saveToSource() }
                dirty = repo.dirty
                notice = "已保存到原文件。"
            } catch (e: Throwable) {
                error = "保存失败：${friendlyDbError(e)}；可以改用「导出副本」。"
            } finally {
                busy = false
            }
        }
    }

    fun closeFile() {
        if (dirty) {
            showCloseConfirm = true
            return
        }
        repo.close()
        resetState()
    }

    fun loadTable(table: String, page: Int) {
        if (busy) return
        busy = true
        error = null
        selectedTable = table
        scope.launch {
            try {
                tableResult = withContext(Dispatchers.IO) {
                    repo.browse(table, page, PAGE_SIZE)
                }
                tablePage = page
                dirty = repo.dirty
            } catch (e: Throwable) {
                error = friendlyDbError(e)
            } finally {
                busy = false
            }
        }
    }

    fun runQuery(statement: String) {
        if (busy) return
        busy = true
        error = null
        notice = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { repo.execute(statement, 200) }
                queryResult = result
                dirty = repo.dirty
                if (result.updateCount != null) {
                    val refreshed = try {
                        withContext(Dispatchers.IO) { repo.tables() }
                    } catch (e: Exception) {
                        tables
                    }
                    tables = refreshed
                    val table = selectedTable
                    if (table != null) {
                        val browsed = try {
                            withContext(Dispatchers.IO) { repo.browse(table, tablePage, PAGE_SIZE) }
                        } catch (e: Exception) {
                            null
                        }
                        if (browsed != null) tableResult = browsed
                    }
                    notice = result.message
                }
            } catch (e: Throwable) {
                error = friendlyDbError(e)
            } finally {
                busy = false
            }
        }
    }

    fun executeSql() {
        val statement = sql.trim()
        if (statement.isBlank()) {
            error = "请输入 SQL"
            return
        }
        if (Regex("^\\s*(DROP|TRUNCATE)\\b", RegexOption.IGNORE_CASE).containsMatchIn(statement)) {
            pendingSql = statement
            showDanger = true
        } else {
            runQuery(statement)
        }
    }

    if (!open) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("SQLite 单文件数据库", style = MiuixTheme.textStyles.body2)
                HintText("打开 .db / .sqlite / .sqlite3 文件；修改会先写入私有工作副本，点「保存」后覆盖回原文件。")
                if (busy) LinearProgressIndicator(progress = null, modifier = Modifier.fillMaxWidth())
                error?.let { DbErrorText(it) }
                PrimaryButton(
                    onClick = { openPicker.launch(arrayOf("*/*")) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("打开 SQLite 文件")
                }
                SecondaryButton(
                    onClick = { createPicker.launch("database.sqlite3") },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("新建数据库")
                }
            }
        }
        return
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("SQLite", style = MiuixTheme.textStyles.footnote2, color = MiuixTheme.colorScheme.primary)
            Text(fileName, style = MiuixTheme.textStyles.body1, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                if (dirty) "有未保存的修改" else "文件已载入",
                style = MiuixTheme.textStyles.footnote2,
                color = if (dirty) WarnColor else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            if (busy) LinearProgressIndicator(progress = null, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(onClick = { save() }, enabled = dirty && !busy, modifier = Modifier.weight(1f)) {
                    Text(if (busy) "处理中…" else "保存到原文件")
                }
                SecondaryButton(
                    onClick = { exportPicker.launch("database_copy.sqlite3") },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("导出副本")
                }
            }
            SecondaryButton(onClick = { closeFile() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("关闭文件")
            }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionLabel("表与视图")
        Spacer(Modifier.weight(1f))
        SecondaryButton(
            onClick = {
                if (!busy) {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            tables = withContext(Dispatchers.IO) { repo.tables() }
                        } catch (e: Throwable) {
                            error = friendlyDbError(e)
                        } finally {
                            busy = false
                        }
                    }
                }
            },
            enabled = !busy,
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("刷新")
        }
    }
    DbTableList(tables = tables, selected = selectedTable, onSelect = { loadTable(it, 0) })

    selectedTable?.let { table ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("表：$table", style = MiuixTheme.textStyles.body2, modifier = Modifier.weight(1f))
            SecondaryButton(
                onClick = {
                    selectedTable = null
                    tableResult = null
                },
            ) { Text("返回表列表") }
        }
        DbResultGrid(tableResult)
        Pager(
            page = tablePage,
            hasNext = tableResult?.truncated == true,
            enabled = !busy,
            onPrev = { loadTable(table, tablePage - 1) },
            onNext = { loadTable(table, tablePage + 1) },
        )
    }

    SectionLabel("SQL")
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextField(
                value = sql,
                onValueChange = { sql = it },
                label = "SQL 语句",
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(onClick = { executeSql() }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text(if (busy) "执行中…" else "执行")
                }
                SecondaryButton(onClick = { sql = "" }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text("清空")
                }
            }
            HintText("一次执行一条语句；DROP 会二次确认；写操作后记得保存回原文件。")
        }
    }
    error?.let { DbErrorText(it) }
    notice?.let { HintText(it) }
    DbResultGrid(queryResult)

    if (showDanger) {
        AppDialog(
            show = true,
            title = "执行危险 SQL？",
            summary = pendingSql?.take(180),
            confirmText = "执行",
            onDismiss = {
                showDanger = false
                pendingSql = null
            },
            onConfirm = {
                val statement = pendingSql
                showDanger = false
                pendingSql = null
                if (statement != null) runQuery(statement)
            },
        )
    }

    if (showCloseConfirm) {
        AppDialog(
            show = true,
            title = "还有未保存的修改",
            summary = "关闭会丢弃工作副本中的修改。",
            confirmText = "放弃并关闭",
            onDismiss = { showCloseConfirm = false },
            onConfirm = {
                showCloseConfirm = false
                repo.close()
                resetState()
            },
        )
    }
}

/* ───────────────────────────── 共享 UI ───────────────────────────── */

@Composable
private fun DatabasePasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
) {
    val colors = MiuixTheme.colorScheme
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        textStyle = MiuixTheme.textStyles.body1.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceContainer)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        decorationBox = { inner ->
            if (value.isEmpty()) {
                Text(
                    "密码",
                    style = MiuixTheme.textStyles.body1,
                    color = colors.onSurfaceVariantActions,
                )
            }
            inner()
        },
    )
}

@Composable
private fun DbTableList(
    tables: List<DbTable>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    val colors = MiuixTheme.colorScheme
    if (tables.isEmpty()) {
        HintText("没有表或视图。")
        return
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            tables.forEachIndexed { index, table ->
                if (index > 0) {
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.dividerLine))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(table.name) }
                        .background(
                            if (selected == table.name) colors.primary.copy(alpha = 0.08f)
                            else colors.surface,
                        )
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        table.name,
                        style = MiuixTheme.textStyles.body2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        if (table.type.contains("view", ignoreCase = true)) "视图" else "表",
                        style = MiuixTheme.textStyles.footnote2,
                        color = colors.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

@Composable
private fun DbResultGrid(result: DbResult?) {
    if (result == null) return
    val colors = MiuixTheme.colorScheme
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    result.message ?: if (result.hasGrid) "查询完成" else "执行完成",
                    style = MiuixTheme.textStyles.footnote1,
                    color = colors.onSurfaceVariantSummary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${result.rows.size} 行 · ${result.elapsedMs} ms",
                    style = MiuixTheme.textStyles.footnote2,
                    color = colors.onSurfaceVariantSummary,
                )
            }
            if (result.hasGrid) {
                val hScroll = rememberScrollState()
                val vScroll = rememberScrollState()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .horizontalScroll(hScroll),
                ) {
                    Column(modifier = Modifier.verticalScroll(vScroll)) {
                        Row(modifier = Modifier.background(colors.surfaceContainer)) {
                            result.columns.forEach { column ->
                                Column(
                                    modifier = Modifier
                                        .width(128.dp)
                                        .padding(horizontal = 8.dp, vertical = 7.dp),
                                ) {
                                    Text(
                                        column.name,
                                        style = MiuixTheme.textStyles.footnote2,
                                        color = colors.primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (column.type.isNotBlank()) {
                                        Text(
                                            column.type,
                                            style = MiuixTheme.textStyles.footnote2,
                                            color = colors.onSurfaceVariantSummary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                        result.rows.forEachIndexed { rowIndex, row ->
                            if (rowIndex > 0) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(0.5.dp)
                                        .background(colors.dividerLine),
                                )
                            }
                            Row {
                                row.forEachIndexed { columnIndex, cell ->
                                    val isNull = cell == null
                                    val columnWidth = 128.dp
                                    Text(
                                        text = cell ?: "NULL",
                                        style = MiuixTheme.textStyles.footnote2.copy(
                                            fontFamily = FontFamily.Monospace,
                                        ),
                                        color = if (isNull) {
                                            colors.onSurfaceVariantSummary.copy(alpha = 0.65f)
                                        } else {
                                            colors.onSurface
                                        },
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .width(columnWidth)
                                            .padding(horizontal = 8.dp, vertical = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                if (result.truncated) {
                    Text(
                        "结果已截断，只显示前面的行。",
                        style = MiuixTheme.textStyles.footnote2,
                        color = colors.onSurfaceVariantSummary,
                    )
                }
            }
        }
    }
}

@Composable
private fun Pager(
    page: Int,
    hasNext: Boolean,
    enabled: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SecondaryButton(
            onClick = onPrev,
            enabled = enabled && page > 0,
            modifier = Modifier.weight(1f),
        ) {
            Text("上一页")
        }
        Text(
            "第 ${page + 1} 页",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        SecondaryButton(
            onClick = onNext,
            enabled = enabled && hasNext,
            modifier = Modifier.weight(1f),
        ) {
            Text("下一页")
        }
    }
}

@Composable
private fun DbPickerDialog(
    title: String,
    items: List<String>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    AppDialog(
        show = true,
        title = title,
        onDismiss = onDismiss,
        confirmText = "关闭",
        onConfirm = onDismiss,
    ) {
        if (items.isEmpty()) {
            HintText("没有可用的数据库。")
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                items.forEach { name ->
                    Text(
                        name,
                        style = MiuixTheme.textStyles.body1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(name) }
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DbErrorText(message: String) {
    Text(
        message,
        style = MiuixTheme.textStyles.footnote1,
        color = MiuixTheme.colorScheme.error,
    )
}

private fun queryDbName(context: Context, uri: Uri): String {
    var name: String? = null
    runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) name = cursor.getString(0)
        }
    }
    return name?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.takeIf { it.isNotBlank() }
        ?: "database.db"
}
