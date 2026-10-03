package com.mingdai.toTool.ui.wallpaper

import android.media.MediaPlayer
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.io.File

class VideoWallpaperService : WallpaperService() {

    companion object {
        /** Cache-relative file name the video wallpaper tool writes the picked clip to. */
        const val VIDEO_FILE = "wallpaper.mp4"
    }

    override fun onCreateEngine(): Engine = VideoEngine()

    inner class VideoEngine : Engine() {

        private var player: MediaPlayer? = null

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            startPlayback(holder)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            if (player == null) startPlayback(holder)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            val p = player
            if (p == null) {
                if (visible) surfaceHolder?.let { startPlayback(it) }
            } else {
                // 切到后台只暂停，回到桌面再继续，避免每次都重新创建播放器（会从头播放且耗电）
                runCatching { if (visible) p.start() else p.pause() }
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            stopPlayback()
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            stopPlayback()
            super.onDestroy()
        }

        private fun startPlayback(holder: SurfaceHolder) {
            val surface = holder.surface
            if (!surface.isValid) return
            val file = File(this@VideoWallpaperService.cacheDir, VIDEO_FILE)
            if (!file.exists()) return
            stopPlayback()
            runCatching {
                val p = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    setSurface(surface)
                    isLooping = true
                    setVolume(0f, 0f) // 桌面壁纸不应该出声
                    prepare()
                    // 铺满屏幕（裁掉多余部分），而不是留黑边
                    runCatching { setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING) }
                    if (this@VideoEngine.isVisible) start()
                }
                player = p
            }.onFailure { stopPlayback() }
        }

        private fun stopPlayback() {
            runCatching { player?.stop() }
            runCatching { player?.release() }
            player = null
        }
    }
}
