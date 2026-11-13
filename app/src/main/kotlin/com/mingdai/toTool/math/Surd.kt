package com.mingdai.toTool.math

import java.math.BigInteger

/**
 * A + B·√k，A、B 为有理数，k 为正整数。
 * 二次方程的根可以用它精确保存；完全平方的因子会先开出来（√8 = 2√2）。
 */
class Surd private constructor(
    val a: Rational,
    val b: Rational,
    val k: BigInteger,
) {
    companion object {
        fun of(a: Rational, b: Rational, k: BigInteger = BigInteger.ONE): Surd = when {
            b.isZero() -> Surd(a, Rational.ZERO, BigInteger.ONE)
            k == BigInteger.ONE -> Surd(a + b, Rational.ZERO, BigInteger.ONE)
            else -> Surd(a, b, k)
        }

        fun rational(value: Rational): Surd = of(value, Rational.ZERO)
    }

    fun isZero(): Boolean = a.isZero() && b.isZero()
    fun negate(): Surd = of(-a, -b, k)
    fun toDouble(): Double = a.toDouble() + b.toDouble() * kotlin.math.sqrt(k.toDouble())

    operator fun plus(other: Surd): Surd = when {
        b.isZero() -> of(a + other.a, other.b, other.k)
        other.b.isZero() -> of(a + other.a, b, k)
        k == other.k -> of(a + other.a, b + other.b, k)
        else -> throw ExactSolveException("不同根号项不能相加")
    }

    operator fun minus(other: Surd): Surd = this + other.negate()

    operator fun times(other: Surd): Surd = when {
        b.isZero() -> of(a * other.a, a * other.b, other.k)
        other.b.isZero() -> of(a * other.a, b * other.a, k)
        k == other.k -> of(
            a * other.a + b * other.b * Rational.of(k),
            a * other.b + other.a * b,
            k,
        )
        else -> throw ExactSolveException("不同根号项不能相乘")
    }
}

/** 把 `A + B√k` 写成最简形式；分母相同的项合并成 `(a + b√k)/d`。 */
fun formatSurd(surd: Surd): String {
    if (surd.b.isZero()) return surd.a.toString()

    if (surd.a.den == surd.b.den && surd.a.den > BigInteger.ONE) {
        val d = surd.a.den
        val an = surd.a.num
        val bn = surd.b.num
        if (an.signum() == 0) {
            return when {
                bn == BigInteger.ONE -> "√${surd.k}/$d"
                bn == BigInteger.valueOf(-1) -> "-√${surd.k}/$d"
                else -> "${bn}√${surd.k}/$d"
            }
        }
        return if (bn.signum() > 0) {
            val part = if (bn == BigInteger.ONE) "√${surd.k}" else "${bn}√${surd.k}"
            "($an + $part)/$d"
        } else {
            val absBn = bn.abs()
            val part = if (absBn == BigInteger.ONE) "√${surd.k}" else "${absBn}√${surd.k}"
            "($an - $part)/$d"
        }
    }

    if (surd.a.isZero()) {
        if (surd.b.den == BigInteger.ONE) {
            return when {
                surd.b == Rational.ONE -> "√${surd.k}"
                surd.b == -Rational.ONE -> "-√${surd.k}"
                else -> "${surd.b.num}√${surd.k}"
            }
        }
        val n = surd.b.num
        val d = surd.b.den
        return when {
            n == BigInteger.ONE -> "√${surd.k}/$d"
            n == BigInteger.valueOf(-1) -> "-√${surd.k}/$d"
            else -> "${n}√${surd.k}/$d"
        }
    }

    val aText = surd.a.toString()
    val bAbs = surd.b.abs()
    val bText = when {
        bAbs.den == BigInteger.ONE && bAbs.num == BigInteger.ONE -> "√${surd.k}"
        bAbs.den == BigInteger.ONE -> "${bAbs.num}√${surd.k}"
        bAbs.num == BigInteger.ONE -> "√${surd.k}/${bAbs.den}"
        else -> "${bAbs.num}√${surd.k}/${bAbs.den}"
    }
    return "$aText ${if (surd.b.signum() > 0) "+" else "-"} $bText"
}

fun formatComplex(real: Rational, imaginary: Surd): String {
    val negative = imaginary.toDouble() < 0.0
    val absImaginary = if (negative) imaginary.negate() else imaginary
    val coefficient = formatSurd(absImaginary)
    val imaginaryText = when {
        coefficient == "1" -> "i"
        coefficient == "-1" -> "-i"
        coefficient.contains('/') -> "($coefficient)i"
        else -> "${coefficient}i"
    }
    return when {
        real.isZero() -> if (negative) "-$imaginaryText" else imaginaryText
        negative -> "$real - $imaginaryText"
        else -> "$real + $imaginaryText"
    }
}

/** 精确验根：把根代入多项式，返回是否为 0。 */
fun isZeroAtSurd(poly: Poly, root: Surd): Boolean {
    return try {
        var result = Surd.rational(Rational.ZERO)
        for (i in poly.indices.reversed()) {
            result = result * root + Surd.rational(poly[i])
        }
        result.isZero()
    } catch (e: ExactSolveException) {
        val value = root.toDouble()
        val scale = poly.maxOfOrNull { kotlin.math.abs(it.toDouble()) } ?: 1.0
        kotlin.math.abs(polyEvalDouble(poly, value)) < 1e-9 * (1.0 + kotlin.math.abs(value)) * scale
    }
}

fun isZeroAtComplex(poly: Poly, real: Rational, imaginary: Surd): Boolean {
    val x = doubleArrayOf(real.toDouble(), imaginary.toDouble())
    var re = 0.0
    var im = 0.0
    for (i in poly.indices.reversed()) {
        val c = poly[i].toDouble()
        val newRe = re * x[0] - im * x[1] + c
        val newIm = re * x[1] + im * x[0]
        re = newRe
        im = newIm
    }
    val magnitude = kotlin.math.hypot(re, im)
    val scale = poly.maxOfOrNull { kotlin.math.abs(it.toDouble()) } ?: 1.0
    return magnitude < 1e-9 * (1.0 + kotlin.math.hypot(x[0], x[1])) * scale
}

sealed interface ExactRoot {
    fun format(): String
    fun approximate(): String
}

data class RealExactRoot(val value: Surd) : ExactRoot {
    override fun format(): String = formatSurd(value)
    override fun approximate(): String = formatNumber(value.toDouble())
}

data class ComplexExactRoot(val real: Rational, val imaginary: Surd) : ExactRoot {
    override fun format(): String = formatComplex(real, imaginary)
    override fun approximate(): String {
        val im = imaginary.toDouble()
        val absIm = kotlin.math.abs(im)
        val imText = if (kotlin.math.abs(absIm - 1.0) < 1e-12) "i" else "${formatNumber(absIm)}i"
        return when {
            real.isZero() -> if (im < 0) "-$imText" else imText
            im < 0 -> "${formatNumber(real.toDouble())} - $imText"
            else -> "${formatNumber(real.toDouble())} + $imText"
        }
    }
}
