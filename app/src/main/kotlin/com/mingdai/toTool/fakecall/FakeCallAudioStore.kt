package com.mingdai.toTool.fakecall

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.util.Locale

/** 接听后的自定义声音；复制到应用私有目录，避免定时触发时 URI 授权已失效。 */
internal object FakeCallAudioStore {
    private const val PREFS = "fake_call_audio"
    private const val KEY_PATH = "audio_path"
    private const val KEY_NAME = "audio_name"
    private const val DIR = "fake_call"

    fun audioPath(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PATH, null)
            ?.takeIf { File(it).isFile }

    fun audioName(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_NAME, null)

    fun setAudio(context: Context, uri: Uri): Result<String> = runCatching {
        val displayName = queryDisplayName(context, uri) ?: "voice"
        val extension = displayName.substringAfterLast('.', "")
            .lowercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() }
            .take(5)
            .ifBlank { "audio" }
        val directory = File(context.filesDir, DIR).apply { mkdirs() }
        val target = File(directory, "voice.$extension")
        val temp = File(directory, "voice.tmp")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
            } ?: throw IOException("无法读取所选音频")
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
        directory.listFiles()?.filter { it != target }?.forEach { it.delete() }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PATH, target.absolutePath)
            .putString(KEY_NAME, displayName)
            .apply()
        displayName
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_PATH)
            .remove(KEY_NAME)
            .apply()
        File(context.filesDir, DIR).deleteRecursively()
    }

    fun defaultMessage(context: Context): String =
        if (Locale.getDefault().language.lowercase(Locale.ROOT).startsWith("zh")) {
            "喂，你好，我现在不方便接电话，请稍后再打。"
        } else {
            "Hello, I can't take your call right now. Please try again later."
        }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && !cursor.isNull(index)) return cursor.getString(index)
            }
        }
        return uri.lastPathSegment
    }
}
