package com.mingdai.toTool.gacha

import android.content.Context
import com.mingdai.toTool.permission.HighPermissionManager
import com.mingdai.toTool.permission.HighPermissionRun
import java.io.IOException
import java.util.Collections

/** 一次性读取最近日志。命令会自己结束，不需要保持前台服务。 */
internal object GachaRecentReader {
    private const val COMMAND =
        "logcat -d | grep -i gacha | grep -E 'https?://' | tail -n 50"

    fun read(context: Context, run: HighPermissionRun): GachaLinkResult {
        val output = Collections.synchronizedList(mutableListOf<String>())
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val exitCode = HighPermissionManager.execute(context, COMMAND, run = run) { isError, line ->
            if (isError) errors.add(line) else output.add(line)
        }
        val link = output.asSequence()
            .mapNotNull { GachaLinkParser.parse(it) }
            .lastOrNull()
        if (link != null) return link
        val detail = errors.joinToString(" ").take(200)
        throw IOException(
            if (exitCode == 0) "没有在最近日志里找到抽卡链接，请先打开游戏记录页"
            else "读取日志失败${if (detail.isBlank()) "" else "：$detail"}",
        )
    }
}
