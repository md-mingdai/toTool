package com.mingdai.toTool.notification

import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 将当前通知列表导出为 zip：通知 JSON 与 media 目录下的图片。 */
internal object NotificationExporter {
    fun export(items: List<NotificationHiderItem>, output: OutputStream): Int {
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            val array = JSONArray()
            items.forEachIndexed { index, item ->
                val mediaFiles = ArrayList<String>()
                item.media.forEachIndexed { mediaIndex, bitmap ->
                    val name = "media/${index + 1}-${mediaIndex + 1}.png"
                    runCatching {
                        zip.putNextEntry(ZipEntry(name))
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)
                        zip.closeEntry()
                        mediaFiles.add(name)
                    }
                }
                array.put(JSONObject().apply {
                    put("key", item.key)
                    put("packageName", item.packageName)
                    put("appName", item.appName)
                    put("title", item.title)
                    put("text", item.text)
                    put("subText", item.subText)
                    put("bigText", item.bigText)
                    put("infoText", item.infoText)
                    put("textLines", JSONArray(item.textLines))
                    put("category", item.category)
                    put("channelId", item.channelId)
                    put("ongoing", item.ongoing)
                    put("clearable", item.clearable)
                    item.progress?.let { progress ->
                        put("progress", JSONObject().apply {
                            put("value", progress.value)
                            put("max", progress.max)
                            put("indeterminate", progress.indeterminate)
                        })
                    }
                    put("actions", JSONArray(item.actions))
                    put("media", JSONArray(mediaFiles))
                    put("postTime", item.postTime)
                    put("hidden", item.isHidden)
                    put("hiddenAt", item.hiddenAt)
                })
            }
            zip.putNextEntry(ZipEntry("notifications.json"))
            zip.write(array.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return items.size
    }

    /** 只导出通知里的媒体图片。 */
    fun exportMedia(items: List<NotificationHiderItem>, output: OutputStream): Int {
        var count = 0
        ZipOutputStream(BufferedOutputStream(output)).use { zip ->
            items.forEachIndexed { index, item ->
                item.media.forEachIndexed { mediaIndex, bitmap ->
                    runCatching {
                        zip.putNextEntry(ZipEntry("notification-${index + 1}-${mediaIndex + 1}.png"))
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip)
                        zip.closeEntry()
                        count += 1
                    }
                }
            }
        }
        return count
    }
}
