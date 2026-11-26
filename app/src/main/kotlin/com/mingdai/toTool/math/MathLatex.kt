package com.mingdai.toTool.math

import java.math.BigInteger

/* LaTeX 渲染的优先级：数值越大、结合越紧。 */
internal const val LatexAdd = 1
internal const val LatexMul = 2
internal const val LatexUnary = 3
internal const val LatexPower = 4
internal const val LatexAtom = 5

internal fun latexWrap(text: String, precedence: Int, parentPrecedence: Int): String =
    if (precedence < parentPrecedence) "\\left($text\\right)" else text

/** 把普通数字转成 LaTeX；科学计数法会展开成 ×10^n。 */
internal fun latexNumber(value: Double): String {
    val text = formatNumber(value)
    if (text == "无定义") return "\\text{undefined}"
    if (text == "+∞") return "\\infty"
    if (text == "−∞") return "-\\infty"
    val exponentIndex = text.indexOfFirst { it == 'e' || it == 'E' }
    if (exponentIndex >= 0) {
        val mantissa = text.substring(0, exponentIndex).trimEnd('0').trimEnd('.')
        val exponent = text.substring(exponentIndex + 1)
            .removePrefix("+")
            .trimStart('0')
            .ifBlank { "0" }
        return "$mantissa \\times 10^{$exponent}"
    }
    return text.replace('−', '-')
}

/** 把用户输入的一元方程（可没有等号）转换成 LaTeX；解析失败返回 null。 */
internal fun equationToLatex(source: String, angleMode: AngleMode = AngleMode.RADIAN): String? {
    val text = source.trim()
    if (text.isEmpty()) return null
    val parts = splitTopLevelEquals(text)
    return runCatching {
        if (parts == null) {
            MathExpression.parse(text, angleMode).toLatex()
        } else {
            val left = MathExpression.parse(parts.first, angleMode).toLatex()
            val right = MathExpression.parse(parts.second, angleMode).toLatex()
            "$left = $right"
        }
    }.getOrNull()
}

/** 把函数输入（可带 y = / f(x) = 前缀）转换成 LaTeX。 */
internal fun functionToLatex(source: String, angleMode: AngleMode = AngleMode.RADIAN): String? {
    val normalized = MathExpression.normalizeFunctionInput(source)
    if (normalized.isEmpty()) return null
    return runCatching { MathExpression.parse(normalized, angleMode).toLatex() }.getOrNull()
}

internal fun exactRootLatex(root: ExactRoot): String = when (root) {
    is RealExactRoot -> surdLatex(root.value)
    is ComplexExactRoot -> complexLatex(root.real, root.imaginary)
}

internal fun exactResultLatex(result: ExactEquationResult): String? {
    if (result.identity || result.contradiction || result.roots.isEmpty()) return null
    return result.roots.mapIndexed { index, root ->
        val name = if (result.roots.size == 1) "x" else "x_{${index + 1}}"
        "$name = ${exactRootLatex(root)}"
    }.joinToString(",\\quad ")
}

internal fun numericResultLatex(solution: EquationSolution): String? {
    if (solution.identity || solution.contradiction || solution.roots.isEmpty()) return null
    val variable = solution.variable ?: "x"
    return solution.roots.mapIndexed { index, value ->
        val name = if (solution.roots.size == 1) variable else "${variable}_{${index + 1}}"
        "$name = ${latexNumber(value)}"
    }.joinToString(",\\quad ")
}

private fun splitTopLevelEquals(text: String): Pair<String, String>? {
    var depth = 0
    var equals = -1
    text.forEachIndexed { index, ch ->
        when (ch) {
            '(' -> depth++
            ')' -> depth--
            '=' -> if (depth == 0) {
                if (equals >= 0) return null
                equals = index
            }
        }
    }
    if (equals < 0) return null
    val left = text.substring(0, equals).trim()
    val right = text.substring(equals + 1).trim()
    if (left.isEmpty() || right.isEmpty()) return null
    return left to right
}

private fun rationalLatex(value: Rational): String {
    if (value.den == BigInteger.ONE) return value.num.toString()
    val sign = if (value.num.signum() < 0) "-" else ""
    return "\\frac{$sign${value.num.abs()}}{${value.den}}"
}

private fun surdLatex(surd: Surd): String {
    if (surd.b.isZero()) return rationalLatex(surd.a)
    val radical = "\\sqrt{${surd.k}}"
    val absB = surd.b.abs()
    fun coefficient(): String =
        if (absB.num == BigInteger.ONE && absB.den == BigInteger.ONE) "" else rationalLatex(absB)

    if (surd.a.isZero()) {
        val sign = if (surd.b.num.signum() < 0) "-" else ""
        return sign + coefficient() + radical
    }
    if (surd.a.den == absB.den && surd.a.den > BigInteger.ONE) {
        val numerator = buildString {
            append(surd.a.num)
            append(if (surd.b.num.signum() < 0) " - " else " + ")
            if (absB.num != BigInteger.ONE) append(absB.num)
            append(radical)
        }
        return "\\frac{$numerator}{${surd.a.den}}"
    }
    val sign = if (surd.b.num.signum() < 0) "-" else "+"
    return "${rationalLatex(surd.a)} $sign ${coefficient()}$radical"
}

private fun complexLatex(real: Rational, imaginary: Surd): String {
    val negative = imaginary.toDouble() < 0
    val absImaginary = if (negative) imaginary.negate() else imaginary
    val imaginaryText = "${surdLatex(absImaginary)}i"
    return when {
        real.isZero() -> if (negative) "-$imaginaryText" else imaginaryText
        negative -> "${rationalLatex(real)} - $imaginaryText"
        else -> "${rationalLatex(real)} + $imaginaryText"
    }
}
