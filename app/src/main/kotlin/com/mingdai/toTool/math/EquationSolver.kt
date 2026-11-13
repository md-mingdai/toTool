package com.mingdai.toTool.math

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sqrt

/** 方程求解的结果。 */
data class EquationSolution(
    /** 未知数名字；方程两边都是常数时为 null。 */
    val variable: String?,
    /** 找到的实根（升序）。 */
    val roots: List<Double>,
    val from: Double,
    val to: Double,
    /** 在给定范围内恒成立（例如 x + 1 = 1 + x）。 */
    val identity: Boolean = false,
    /** 两边都是常数但恒不相等，无解。 */
    val contradiction: Boolean = false,
    /** 根太多，只保留了前若干个。 */
    val truncated: Boolean = false,
)

private const val MAX_ROOTS = 60
private const val SAMPLE_COUNT = 6000

/**
 * 求解形如 `左边 = 右边` 的一元方程（没有等号时按 `表达式 = 0` 处理）。
 *
 * 做法是数值扫描：把 [from]..[to] 分成若干段，找符号变化用二分法逼近；
 * 对于重根（曲线只碰到 x 轴、两边不变号），再用黄金分割找 |f| 的局部极小值。
 * 这样不限定方程形式，`sin(x)=0.5`、`x^3-2x+1=0`、`1/x=2` 都能处理。
 */
fun solveEquation(
    input: String,
    from: Double,
    to: Double,
    angleMode: AngleMode = AngleMode.RADIAN,
): EquationSolution {
    if (!from.isFinite() || !to.isFinite()) throw MathParseException("范围必须是有效数字")
    if (from >= to) throw MathParseException("范围下限必须小于上限")

    val normalized = input.replace("==", "=").trim()
    if (normalized.isEmpty()) throw MathParseException("请输入方程")

    val (leftText, rightText) = splitEquation(normalized)
    val left = MathExpression.parse(leftText, angleMode)
    val right = MathExpression.parse(rightText, angleMode)

    val variables = (left.variables + right.variables).toList()
    if (variables.size > 1) {
        throw MathParseException("只支持一个未知数（检测到 ${variables.joinToString("、")}）")
    }

    fun f(x: Double): Double = left.eval(x) - right.eval(x)

    if (variables.isEmpty()) {
        val value = f(0.0)
        if (!value.isFinite()) throw MathParseException("方程里有无法计算的部分（例如除以 0）")
        val isZero = abs(value) <= 1e-12
        return EquationSolution(
            variable = null,
            roots = emptyList(),
            from = from,
            to = to,
            identity = isZero,
            contradiction = !isZero,
        )
    }

    val probes = listOf(from, (from + to) / 2.0, to)
    if (probes.none { f(it).isFinite() }) {
        throw MathParseException("在给定范围内方程没有定义")
    }

    val variable = variables.first()
    if (looksLikeIdentity(f, from, to)) {
        return EquationSolution(variable, emptyList(), from, to, identity = true)
    }

    val (roots, truncated) = scanRoots(f, from, to)
    return EquationSolution(variable, roots, from, to, truncated = truncated)
}

/** 在顶层（括号外）找到唯一的等号，拆成左右两边；没有等号时右边补 0。 */
private fun splitEquation(text: String): Pair<String, String> {
    var depth = 0
    var equalsIndex = -1
    text.forEachIndexed { index, ch ->
        when (ch) {
            '(' -> depth++
            ')' -> depth--
            '=' -> {
                if (depth == 0) {
                    if (equalsIndex >= 0) throw MathParseException("方程里只能有一个等号")
                    equalsIndex = index
                }
            }
        }
    }
    if (equalsIndex < 0) return text to "0"
    val left = text.substring(0, equalsIndex).trim()
    val right = text.substring(equalsIndex + 1).trim()
    if (left.isEmpty()) throw MathParseException("等号左边是空的")
    if (right.isEmpty()) throw MathParseException("等号右边是空的")
    return left to right
}

/** 采样若干点判断两边是否恒等（例如 sin(x)^2 + cos(x)^2 = 1）。 */
private fun looksLikeIdentity(f: (Double) -> Double, from: Double, to: Double): Boolean {
    var finiteCount = 0
    for (i in 0..6) {
        val x = from + (to - from) * i / 6.0
        val value = f(x)
        if (!value.isFinite()) continue
        finiteCount++
        val tolerance = 1e-9 * max(1.0, abs(x))
        if (abs(value) > tolerance) return false
    }
    return finiteCount >= 3
}

/**
 * 扫描 [from]..[to] 上的零点。
 * @return 根列表（升序）和“是否因为太多而截断”。
 */
private fun scanRoots(f: (Double) -> Double, from: Double, to: Double): Pair<List<Double>, Boolean> {
    val roots = ArrayList<Double>()
    var truncated = false
    val step = (to - from) / SAMPLE_COUNT

    fun addRoot(value: Double) {
        if (!value.isFinite()) return
        val tolerance = 1e-7 * max(1.0, abs(value)) + abs(step) * 1e-3
        if (roots.any { abs(it - value) <= tolerance }) return
        if (roots.size >= MAX_ROOTS) {
            truncated = true
            return
        }
        roots.add(value)
    }

    var previousX = from
    var previousY = f(from)
    var previousPreviousX = Double.NaN
    var previousPreviousY = Double.NaN

    if (previousY.isFinite() && abs(previousY) < 1e-12) addRoot(previousX)

    for (i in 1..SAMPLE_COUNT) {
        val x = if (i == SAMPLE_COUNT) to else from + i * step
        val y = f(x)

        if (y.isFinite() && previousY.isFinite()) {
            if (abs(y) < 1e-12) {
                addRoot(x)
            } else if (sign(previousY) != sign(y)) {
                bisect(f, previousX, previousY, x, y)?.let { addRoot(it) }
            }
        }

        // 重根不会变号，找 |f| 的局部极小值再细化
        if (i >= 2 && previousPreviousY.isFinite() && previousY.isFinite() && y.isFinite()) {
            val a = abs(previousPreviousY)
            val b = abs(previousY)
            val c = abs(y)
            if (b <= a && b <= c && b < 1e-2) {
                minimizeAbs(f, previousPreviousX, x)?.let { addRoot(it) }
            }
        }

        previousPreviousX = previousX
        previousPreviousY = previousY
        previousX = x
        previousY = y
    }

    roots.sort()
    return roots to truncated
}

/** 二分法求根；遇到渐近线（函数值不趋近 0）时返回 null。 */
private fun bisect(f: (Double) -> Double, a: Double, fa: Double, b: Double, fb: Double): Double? {
    var low = a
    var high = b
    var lowValue = fa
    repeat(80) {
        val mid = low + (high - low) / 2.0
        if (mid == low || mid == high) {
            // 已经到浮点精度极限
            val value = f(mid)
            return if (value.isFinite() && abs(value) <= 1e-6 * (1.0 + min(abs(fa), abs(fb)))) mid else null
        }
        val value = f(mid)
        if (!value.isFinite()) return null
        if (value == 0.0) return mid
        if (sign(lowValue) == sign(value)) {
            low = mid
            lowValue = value
        } else {
            high = mid
        }
        if (abs(high - low) <= 1e-13 * max(1.0, abs(mid))) {
            val root = (low + high) / 2.0
            val rootValue = f(root)
            // tan 在 π/2 附近也会变号，但函数值会非常大，用这个条件排除假根
            return if (rootValue.isFinite() && abs(rootValue) <= 1e-6 * (1.0 + min(abs(fa), abs(fb)))) root else null
        }
    }
    val root = (low + high) / 2.0
    val value = f(root)
    return if (value.isFinite() && abs(value) <= 1e-6 * (1.0 + min(abs(fa), abs(fb)))) root else null
}

/** 黄金分割找 |f| 的局部极小值；只有函数值确实接近 0 才当成重根。 */
private fun minimizeAbs(f: (Double) -> Double, a: Double, b: Double): Double? {
    var low = a
    var high = b
    val ratio = (sqrt(5.0) - 1.0) / 2.0
    repeat(90) {
        val m1 = high - ratio * (high - low)
        val m2 = low + ratio * (high - low)
        val f1 = abs(f(m1))
        val f2 = abs(f(m2))
        if (f1 < f2) high = m2 else low = m1
    }
    val root = (low + high) / 2.0
    val value = f(root)
    // (x-1)^2 这类重根能到 1e-20 以下；仅仅“很接近”的曲线不会被误判
    return if (value.isFinite() && abs(value) < 1e-12) root else null
}
