package com.mingdai.toTool.math

import java.math.BigInteger

/** 升幂排列的多项式（第 i 项是 x^i 的系数）。 */
typealias Poly = List<Rational>

fun polyTrim(poly: Poly): Poly {
    var end = poly.size
    while (end > 0 && poly[end - 1].isZero()) end--
    return if (end == poly.size) poly else poly.subList(0, end).toList()
}

fun polyAdd(a: Poly, b: Poly): Poly {
    val size = maxOf(a.size, b.size)
    val result = ArrayList<Rational>(size)
    for (i in 0 until size) {
        result.add((a.getOrNull(i) ?: Rational.ZERO) + (b.getOrNull(i) ?: Rational.ZERO))
    }
    return polyTrim(result)
}

fun polySub(a: Poly, b: Poly): Poly {
    val size = maxOf(a.size, b.size)
    val result = ArrayList<Rational>(size)
    for (i in 0 until size) {
        result.add((a.getOrNull(i) ?: Rational.ZERO) - (b.getOrNull(i) ?: Rational.ZERO))
    }
    return polyTrim(result)
}

fun polyMul(a: Poly, b: Poly): Poly {
    if (a.isEmpty() || b.isEmpty()) return emptyList()
    val result = MutableList(a.size + b.size - 1) { Rational.ZERO }
    for (i in a.indices) {
        if (a[i].isZero()) continue
        for (j in b.indices) {
            result[i + j] = result[i + j] + a[i] * b[j]
        }
    }
    return polyTrim(result)
}

fun polyNeg(a: Poly): Poly = a.map { -it }

fun polyEval(poly: Poly, x: Rational): Rational {
    var result = Rational.ZERO
    for (i in poly.indices.reversed()) {
        result = result * x + poly[i]
    }
    return result
}

fun polyEvalDouble(poly: Poly, x: Double): Double {
    var result = 0.0
    for (i in poly.indices.reversed()) {
        result = result * x + poly[i].toDouble()
    }
    return result
}

fun polyDivMod(dividend: Poly, divisor: Poly): Pair<Poly, Poly> {
    val b = polyTrim(divisor)
    if (b.isEmpty()) throw ExactSolveException("除以零多项式")
    val a = polyTrim(dividend)
    if (a.isEmpty()) return emptyList<Rational>() to emptyList()

    val quotient = MutableList(maxOf(0, a.size - b.size + 1)) { Rational.ZERO }
    var remainder = a.toMutableList()
    val leading = b.last()
    while (remainder.size >= b.size) {
        val shift = remainder.size - b.size
        val coefficient = remainder.last() / leading
        quotient[shift] = coefficient
        for (i in b.indices) {
            remainder[i + shift] = remainder[i + shift] - coefficient * b[i]
        }
        val trimmed = polyTrim(remainder)
        if (trimmed.size == remainder.size) break
        remainder = trimmed.toMutableList()
        if (remainder.isEmpty()) break
    }
    return polyTrim(quotient) to remainder
}

/** 多项式最大公因式（首项系数化为 1）。 */
fun polyGcd(a0: Poly, b0: Poly): Poly {
    var a = polyTrim(a0)
    var b = polyTrim(b0)
    while (b.isNotEmpty()) {
        val remainder = polyDivMod(a, b).second
        a = b
        b = remainder
    }
    if (a.isEmpty()) return emptyList()
    val leading = a.last()
    return a.map { it / leading }
}

/** 系数化为互质整数，且最高次项为正。 */
fun polyNormalize(poly0: Poly): Poly {
    val poly = polyTrim(poly0)
    if (poly.isEmpty()) return emptyList()

    var lcm = BigInteger.ONE
    for (coefficient in poly) {
        lcm = lcm / lcm.gcd(coefficient.den) * coefficient.den
    }
    val integers = poly.map { coefficient -> coefficient.num * (lcm / coefficient.den) }
    var gcd = BigInteger.ZERO
    for (value in integers) {
        gcd = gcd.gcd(value.abs())
    }
    if (gcd.signum() == 0) return emptyList()

    var values = integers.map { it / gcd }
    if (values.last().signum() < 0) {
        values = values.map { it.negate() }
    }
    return values.map { Rational.of(it) }
}

/** 格式化成 `x^2 - 5x + 6` 这样的字符串。 */
fun formatPoly(poly0: Poly): String {
    val poly = polyTrim(poly0)
    if (poly.isEmpty()) return "0"
    val terms = ArrayList<String>()
    for (i in poly.indices.reversed()) {
        val coefficient = poly[i]
        if (coefficient.isZero()) continue
        val variable = when (i) {
            0 -> ""
            1 -> "x"
            else -> "x^$i"
        }
        terms.add(formatPolyTerm(coefficient, variable))
    }
    if (terms.isEmpty()) return "0"
    val builder = StringBuilder(terms[0])
    for (i in 1 until terms.size) {
        val term = terms[i]
        if (term.startsWith("-")) {
            builder.append(" - ").append(term.substring(1))
        } else {
            builder.append(" + ").append(term)
        }
    }
    return builder.toString()
}

private fun formatPolyTerm(coefficient: Rational, variable: String): String {
    if (variable.isEmpty()) return coefficient.toString()
    return when {
        coefficient == Rational.ONE -> variable
        coefficient == -Rational.ONE -> "-$variable"
        coefficient.den == BigInteger.ONE -> "${coefficient.num}$variable"
        else -> "($coefficient)$variable"
    }
}
