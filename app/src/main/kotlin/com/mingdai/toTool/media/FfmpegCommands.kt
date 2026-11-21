package com.mingdai.toTool.media

/** 返回的是 ffmpeg 的参数（不含可执行文件、全局参数、输出路径）。 */
internal fun buildFfmpegAttempts(
    plan: ConversionPlan,
    capabilities: FfmpegCapabilities,
    inputPath: String,
    coverPath: String?,
    sourceDurationMs: Long,
    musicPath: String? = null,
): List<List<String>> {
    val encoders = capabilities.encoders
    return when (plan.branch) {
        ConversionBranch.IMAGE_VIDEO -> {
            val spec = FORMAT_SPECS[plan.target.id]
                ?: throw ConvertException("不支持的目标视频格式：${plan.target.id}")
            val video = firstAvailable(spec.videoEncoders, encoders)
                ?: throw ConvertException("没有可用的 ${plan.target.id} 视频编码器")
            val audio = musicPath?.let {
                firstAvailable(spec.audioEncoders, encoders)
                    ?: throw ConvertException("没有可用的 ${plan.target.id} 音频编码器")
            }
            val args = mutableListOf<String>()
            val staticDuration = if (plan.source.animated) {
                null
            } else {
                plan.durationSeconds ?: throw ConvertException("请输入视频时长")
            }
            if (plan.source.animated) {
                args += listOf("-i", inputPath)
            } else {
                args += listOf("-loop", "1", "-framerate", "25", "-i", inputPath)
            }
            if (musicPath != null) {
                args += listOf("-stream_loop", "-1", "-i", musicPath)
                args += listOf("-map", "0:v:0", "-map", "1:a:0", "-shortest")
            } else {
                args += "-an"
            }
            args += listOf(
                "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2,format=yuv420p",
                "-r", "25",
                "-c:v", video,
            )
            args += encoderArgs(video)
            if (audio != null) {
                args += listOf("-c:a", audio)
                args += encoderArgs(audio)
            }
            staticDuration?.let { args += listOf("-t", formatSeconds(it)) }
            listOf(args)
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

        ConversionBranch.VIDEO_GIF,
        ConversionBranch.IMAGE_GIF -> {
            listOf(
                mutableListOf(
                    "-i", inputPath,
                    "-an",
                    "-vf", "fps=12,scale=480:-1:flags=lanczos,split[s0][s1];[s0]palettegen[p];[s1][p]paletteuse=dither=sierra2_4a",
                    "-loop", "0",
                ),
            )
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
