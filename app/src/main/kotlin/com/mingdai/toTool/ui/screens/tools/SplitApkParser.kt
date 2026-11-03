package com.mingdai.toTool.ui.screens.tools

import java.util.Locale

/*
 * 拆分安装包（APKS / XAPK / APKM / 一组散装 .apk）的“看文件名”选择逻辑。
 *
 * 这个文件只依赖 Kotlin 标准库，不碰任何 Android API，方便单独做逻辑测试。
 * 真正读压缩包、写 PackageInstaller 会话的代码在 InstallerTool.kt。
 *
 * 背景：App Bundle 会把一个应用拆成很多 APK ——
 *   主包（base）+ 按 CPU 架构拆 + 按屏幕密度拆 + 按语言拆 + 动态功能模块。
 * 全部装上既浪费空间，ABI 还可能互相冲突，所以要按本机配置挑出“该装的那几个”。
 * 常见的命名方式有三种：
 *   APKMirror / SAI : base.apk · split_config.arm64_v8a.apk · split_config.xxhdpi.apk · split_config.en.apk
 *   APKPure（XAPK） : com.foo.apk · config.arm64_v8a.apk · config.xxhdpi.apk · config.en.apk
 *   bundletool APKS : splits/base-master.apk · splits/base-arm64_v8a.apk · splits/base-xxhdpi.apk · splits/base-en.apk
 */

/** 一个 APK 条目在整套安装包里扮演的角色。 */
enum class SplitKind {
    /** 主包（base）。必须安装。 */
    BASE,

    /** 动态功能模块、资源包等其它需要一起装的拆分。 */
    FEATURE,

    /** 按 CPU 架构拆分（arm64_v8a …）：只装本机最合适的一个。 */
    ABI,

    /** 按屏幕密度拆分（xxhdpi …）：只装最贴近本机的一个。 */
    DENSITY,

    /** 按语言拆分（en / zh …）：装本机用到的语言。 */
    LANGUAGE,
}

data class SplitEntry(
    /** 压缩包里的完整路径（散装文件则是显示名）。 */
    val path: String,
    /** 未压缩大小（字节）；未知为 -1。 */
    val size: Long,
    val kind: SplitKind,
    /** 所属模块名：主包和它的配置拆分都是 "base"。 */
    val module: String,
    /** 归一化的配置标记：arm64_v8a / xxhdpi / zh …；主包、功能模块本体为空串。 */
    val token: String,
)

/** 本机配置，用来挑拆分。 */
data class DeviceProfile(
    /** 按偏好排序的 ABI，直接取 Build.SUPPORTED_ABIS。 */
    val abis: List<String>,
    val densityDpi: Int,
    /** 小写的语言代码（zh、en …），按偏好排序。 */
    val languages: List<String>,
)

/** [SplitApkParser.plan] 的结果。 */
data class SplitPlan(
    /** 全部条目（若原本没认出主包，最大的那个会被提升为主包）。 */
    val entries: List<SplitEntry>,
    /** 默认勾选的条目路径，按“主包 → 其它”的顺序排列。 */
    val selected: Set<String>,
    val warnings: List<String>,
)

object SplitApkParser {

    private val ABIS = setOf(
        "arm64_v8a", "armeabi_v7a", "armeabi", "x86", "x86_64", "mips", "mips64", "riscv64",
    )

    private val DENSITIES = mapOf(
        "ldpi" to 120, "mdpi" to 160, "tvdpi" to 213, "hdpi" to 240,
        "xhdpi" to 320, "xxhdpi" to 480, "xxxhdpi" to 640,
    )

    /** zh / zh_rcn / zh_cn / pt_br / es_419；4 个字母以上的（tcf_astc、tier_1）不是语言。 */
    private val LANGUAGE = Regex("^[a-z]{2,3}(_(r?[a-z]{2}|[0-9]{3}))?$")

    /** 压缩包里这些目录下的 APK 不是给本机直接装的。 */
    private val IGNORED_DIRS = listOf("standalones/", "instant/", "asset-slices/", "__macosx/")

    /** 这个压缩包条目是不是“可能要装”的 APK。 */
    fun isInstallableApk(path: String): Boolean {
        if (path.endsWith("/")) return false
        val lower = path.lowercase(Locale.ROOT)
        if (!lower.endsWith(".apk")) return false
        if (IGNORED_DIRS.any { lower.startsWith(it) }) return false
        // macOS 压缩时附带的 ._xxx.apk 元数据文件
        return !lower.substringAfterLast('/').startsWith("._")
    }

    /** 本机 ABI 名 → 拆分文件名里的写法：arm64-v8a → arm64_v8a。 */
    fun normalizeAbi(abi: String): String = abi.lowercase(Locale.ROOT).replace('-', '_')

    /** Java 老式语言码 → 拆分文件名里用的新码（iw → he）。 */
    fun normalizeLanguage(language: String): String = when (val l = language.lowercase(Locale.ROOT)) {
        "iw" -> "he"
        "in" -> "id"
        "ji" -> "yi"
        else -> l
    }

    /**
     * 判断一个 APK 条目的角色。
     *
     * @param nameHint 优先用它代替文件名来判断（XAPK 的 manifest 里每个拆分都有 id，比文件名可靠）
     * @param baseHint 已知的主包标识（包名或 "base"）；与之相等的条目直接认定为主包
     */
    fun classify(path: String, size: Long, nameHint: String? = null, baseHint: String? = null): SplitEntry {
        val fileName = path.substringAfterLast('/')
        val rawStem = nameHint?.takeIf { it.isNotBlank() }
            ?: if (fileName.endsWith(".apk", ignoreCase = true)) fileName.dropLast(4) else fileName
        val stem = rawStem.lowercase(Locale.ROOT)

        if (baseHint != null && stem == baseHint.lowercase(Locale.ROOT)) {
            return SplitEntry(path, size, SplitKind.BASE, "base", "")
        }

        val (module, rawToken) = splitStem(stem)
        if (rawToken.isEmpty()) {
            val kind = if (module == "base" || module == "universal") SplitKind.BASE else SplitKind.FEATURE
            return SplitEntry(path, size, kind, module, "")
        }
        val (kind, token) = kindOf(rawToken)
        return SplitEntry(path, size, kind, module, token)
    }

    /** 把小写文件名主干拆成（模块名，配置标记）。配置标记为空表示这是模块本体。 */
    private fun splitStem(s: String): Pair<String, String> {
        // APKMirror / SAI：split_config.xxx → 主包的配置拆分
        if (s.startsWith("split_config.")) return "base" to masterToEmpty(s.removePrefix("split_config."))
        // APKPure：config.xxx → 主包的配置拆分
        if (s.startsWith("config.")) return "base" to masterToEmpty(s.removePrefix("config."))
        // 功能模块的配置拆分：split_feature.config.xxx / feature.config.xxx
        val i = s.indexOf(".config.")
        if (i > 0) {
            return s.substring(0, i).removePrefix("split_") to masterToEmpty(s.substring(i + ".config.".length))
        }
        // split_feature → 功能模块本体
        if (s.startsWith("split_")) return s.removePrefix("split_") to ""
        // bundletool：module-token，例如 base-master / base-xxhdpi / feature-en
        val d = s.indexOf('-')
        if (d > 0) return s.substring(0, d) to masterToEmpty(s.substring(d + 1))
        return s to ""
    }

    private fun masterToEmpty(token: String): String = if (token == "master") "" else token

    /** 配置标记 → 类别 + 归一化标记。 */
    private fun kindOf(rawToken: String): Pair<SplitKind, String> {
        val t = rawToken.replace('-', '_')
        if (t in ABIS) return SplitKind.ABI to t
        if (t in DENSITIES) return SplitKind.DENSITY to t
        if (t.startsWith("b+")) {
            // BCP-47 写法：b+sr+Latn → 语言 sr
            val lang = t.split('+').getOrNull(1).orEmpty()
            if (lang.length in 2..3) return SplitKind.LANGUAGE to lang
        }
        if (LANGUAGE.matches(t)) return SplitKind.LANGUAGE to t
        // 其它（tcf_astc、tier_1 …）：不知道怎么挑，保守地都装上
        return SplitKind.FEATURE to t
    }

    /** 语言标记里只取语言部分：zh_rcn → zh。 */
    private fun languageOf(token: String): String = normalizeLanguage(token.substringBefore('_'))

    /**
     * 按本机配置挑出该装的条目。
     * 规则：主包必装；功能模块全装；ABI 取本机偏好顺序里第一个有的；
     * 密度取“不小于本机密度的最小档”（没有就取最大档）；语言取本机语言 + 英语。
     */
    fun plan(entries: List<SplitEntry>, device: DeviceProfile): SplitPlan {
        val warnings = mutableListOf<String>()

        // ── 1. 确定主包 ──────────────────────────────────────────────
        var all = entries
        val bases = all.filter { it.kind == SplitKind.BASE }
        var base: SplitEntry? = bases.firstOrNull { it.path.lowercase(Locale.ROOT).startsWith("splits/") }
            ?: bases.firstOrNull()
        if (base == null) {
            // 没按名字认出主包（例如单个 “my-app-release.apk”）：
            // 只有一个文件时它就是主包；否则把最大的“非配置”条目提升为主包
            val candidate = if (all.size == 1) {
                all.first()
            } else {
                all.filter { it.kind == SplitKind.FEATURE }.maxByOrNull { it.size }
            }
            if (candidate != null) {
                val promoted = candidate.copy(kind = SplitKind.BASE, module = "base", token = "")
                all = all.map { if (it === candidate) promoted else it }
                base = promoted
            }
        }
        if (base == null) warnings += "没有找到主包（base.apk），这套文件无法单独安装。"
        if (bases.size > 1) warnings += "压缩包里有多个主包，已使用 ${base?.path?.substringAfterLast('/')}。"

        val selected = linkedSetOf<String>()
        base?.let { selected += it.path }

        // ── 2. 功能模块 / 其它杂项：全装 ──────────────────────────────
        all.filter { it.kind == SplitKind.FEATURE }.forEach { selected += it.path }

        // ── 3. 配置拆分：按模块分组各挑各的 ───────────────────────────
        val wantedAbis = device.abis.map { normalizeAbi(it) }
        val wantedLanguages = (device.languages.map { normalizeLanguage(it) } + "en").toSet()

        val byModule = all.filter { it.kind == SplitKind.ABI || it.kind == SplitKind.DENSITY || it.kind == SplitKind.LANGUAGE }
            .groupBy { it.module }
        for ((_, group) in byModule) {
            val abiEntries = group.filter { it.kind == SplitKind.ABI }
            if (abiEntries.isNotEmpty()) {
                val pick = wantedAbis.firstNotNullOfOrNull { abi -> abiEntries.firstOrNull { it.token == abi } }
                if (pick != null) {
                    selected += pick.path
                } else {
                    val have = abiEntries.joinToString("、") { it.token.replace('_', '-') }
                    val mine = device.abis.firstOrNull() ?: "未知"
                    warnings += "安装包只带有 $have 的原生库，本机是 $mine，安装后可能无法运行。"
                }
            }

            val densityEntries = group.filter { it.kind == SplitKind.DENSITY }
            if (densityEntries.isNotEmpty()) {
                val sorted = densityEntries.sortedBy { DENSITIES[it.token] ?: 160 }
                val pick = sorted.firstOrNull { (DENSITIES[it.token] ?: 160) >= device.densityDpi } ?: sorted.last()
                selected += pick.path
            }

            group.filter { it.kind == SplitKind.LANGUAGE && languageOf(it.token) in wantedLanguages }
                .forEach { selected += it.path }
        }

        // 保持稳定顺序：主包在最前，其余按原始顺序
        val ordered = linkedSetOf<String>()
        base?.let { ordered += it.path }
        all.forEach { if (it.path in selected) ordered += it.path }
        return SplitPlan(all, ordered, warnings)
    }
}
