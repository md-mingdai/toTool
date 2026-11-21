package com.mingdai.toTool.media

/** 返回的是 ffmpeg 的参数（不含可执行文件、全局参数、输出路径）。 */
internal fun buildFfmpegAttempts(
    plan: ConversionPlan,
    capabilities: FfmpegCapabilities,
    inputPath: String,
    coverPath: String?,
    sourceDurationMs: Long,
): List<List<String>> {
    val encoders = capabilities.encoders
    return when (plan.branch) {
        ConversionBranch.IMAGE_VIDEO -> {
            val spec = FORMAT_SPECS[plan.target.id]
                ?: throw ConvertException("不支持的目标视频格式：${plan.target.id}")
            val video = firstAvailable(spec.videoEncoders, encoders)
                ?: throw ConvertException("没有可用的 ${plan.target.id} 视频编码器")
            val seconds = plan.durationSeconds
                ?: throw ConvertException("请输入视频时长")
            listOf(
                mutableListOf(
                    "-loop", "1",
                    "-framerate", "25",
                    "-i", inputPath,
                    "-t", formatSeconds(seconds),
                    "-an",
                    "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2,format=yuv420p",
                    "-r", "25",
                    "-c:v", video,
                ).apply {
                    addAll(encoderArgs(video))
                },
            )
        }

        ConversionBranch.AUDIO_VIDEO -> {
            val spec = FORMAT_SPECS[plan.target.id]
                ?: throw ConvertException("不支持的目标视频格式：${plan.target.id}")
            val video = firstAvailable(spec.videoEncoders, encoders)
                ?: throw ConvertException("没有可用的 ${plan.target.id} 视频编码器")
            val audio = firstAvailable(spec.audioEncoders, encoders)
                ?: throw ConvertException("没有可用的 ${plan.target.id} 音频编码器")
            if (plan.visualMode == VisualMode.WAVEFORM) {
                val width = plan.resolution.width
                val height = plan.resolution.height
                val filter = "[0:a]showwaves=s=${width}x${height}:mode=cline:rate=25:colors=0x8BC34A,format=yuv420p[v]"
                listOf(
                    mutableListOf(
                        "-i", inputPath,
                        "-filter_complex", filter,
                        "-map", "[v]",
                        "-map", "0:a:0",
                        "-c:v", video,
                    ).apply {
                        addAll(encoderArgs(video))
                        addAll(listOf("-c:a", audio))
                        addAll(encoderArgs(audio))
                        safetyDurationSeconds(sourceDurationMs)?.let { addAll(listOf("-t", it)) }
                        add("-shortest")
                    },
                )
            } else {
                val cover = coverPath ?: throw ConvertException("缺少音频视频画面")
                listOf(
                    mutableListOf(
                        "-loop", "1",
                        "-framerate", "25",
                        "-i", cover,
                        "-i", inputPath,
                        "-map", "0:v:0",
                        "-map", "1:a:0",
                        "-c:v", video,
                    ).apply {
                        addAll(encoderArgs(video))
                        addAll(listOf("-c:a", audio))
                        addAll(encoderArgs(audio))
                        safetyDurationSeconds(sourceDurationMs)?.let { addAll(listOf("-t", it)) }
                        add("-shortest")
                    },
                )
            }
        }

        ConversionBranch.VIDEO_VIDEO,
        ConversionBranch.VIDEO_AUDIO,
        ConversionBranch.AUDIO_AUDIO -> {
            buildAttempts(plan.target.id, plan.source.extension, encoders)
                .map { listOf("-i", inputPath) + it }
        }

        ConversionBranch.IMAGE_IMAGE,
        ConversionBranch.VIDEO_IMAGE -> emptyList()
    }
}
