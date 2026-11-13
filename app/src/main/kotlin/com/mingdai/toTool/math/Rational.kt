package com.mingdai.toTool.math

import java.math.BigInteger

/**
 * 任意精度有理数（分子 / 分母），用于方程求解的精确计算。
 * 与 Double 不同，`1/3`、`0.1` 这类值不会丢精度，根可以保留成 `1/3`、`(-1 + √5)/2` 的形式。
 */
class Rational private constructor(
    val num: BigInteger,
    val den: BigInteger,
) : Comparable<Rational> {

    companion object {
        val ZERO = Rational(BigInteger.ZERO, BigInteger.ONE)
        val ONE = Rational(BigInteger.ONE, BigInteger.ONE)
        val TEN = Rational(BigInteger.TEN, BigInteger.ONE)

        fun of(numerator: BigInteger, denominator: BigInteger = BigInteger.ONE): Rational {
            require(denominator.signum() != 0) { "分母不能为 0" }
            var n = numerator
            var d = denominator
            if (d.signum() < 0) {
                n = n.negate()
                d = d.negate()
            }
            val gcd = n.gcd(d)
            if (gcd > BigInteger.ONE) {
                n = n / gcd
                d = d / gcd
            }
            return Rational(n, d)
        }

        fun of(value: Int): Rational = of(BigInteger.valueOf(value.toLong()))

        fun of(value: Long): Rational = of(BigInteger.valueOf(value))

        /** 解析十进制字面量（如 `12`、`0.25`、`.5`），解析失败返回 null。 */
        fun parse(text: String): Rational? {
            if (text.isEmpty()) return null
            val dot = text.indexOf('.')
            if (dot < 0) {
                return text.toBigIntegerOrNull()?.let { of(it) }
            }
            if (text.indexOf('.', dot + 1) >= 0) return null
            val whole = if (dot == 0) "0" else text.substring(0, dot)
            val fraction = text.substring(dot + 1)
            if (whole.any { !it.isDigit() } || fraction.any { !it.isDigit() }) return null
            val digits = (whole + fraction).toBigIntegerOrNull() ?: return null
            return of(digits, BigInteger.TEN.pow(fraction.length))
        }
    }

    fun isZero(): Boolean = num.signum() == 0
    fun isInteger(): Boolean = den == BigInteger.ONE
    fun abs(): Rational = if (num.signum() < 0) Rational(num.negate(), den) else this
    fun signum(): Int = num.signum()

    operator fun plus(other: Rational): Rational =
        of(num * other.den + other.num * den, den * other.den)

    operator fun minus(other: Rational): Rational =
        of(num * other.den - other.num * den, den * other.den)

    operator fun times(other: Rational): Rational =
        of(num * other.num, den * other.den)

    operator fun div(other: Rational): Rational {
        if (other.isZero()) throw ExactSolveException("除数不能为 0")
        return of(num * other.den, den * other.num)
    }

    operator fun unaryMinus(): Rational = Rational(num.negate(), den)

    override operator fun compareTo(other: Rational): Int =
        (num * other.den).compareTo(other.num * den)

    fun pow(exponent: Int): Rational {
        if (exponent == 0) return ONE
        if (exponent < 0) {
            if (isZero()) throw ExactSolveException("0 不能取负次幂")
            return of(den, num).pow(-exponent)
        }
        var result = ONE
        var base = this
        var exp = exponent
        while (exp > 0) {
            if (exp and 1 == 1) result *= base
            base *= base
            exp = exp shr 1
        }
        return result
    }

    fun toDouble(): Double = num.toDouble() / den.toDouble()

    override fun equals(other: Any?): Boolean =
        other is Rational && num == other.num && den == other.den

    override fun hashCode(): Int = 31 * num.hashCode() + den.hashCode()

    override fun toString(): String =
        if (den == BigInteger.ONE) num.toString() else "$num/$den"
}

/** 把整数写成 `s√k` 中最简的 `s` 和 `k`（k 无平方因子）。 */
fun extractSquareFactor(value: BigInteger): Pair<BigInteger, BigInteger> {
    if (value.signum() <= 0) return BigInteger.ONE to value.abs()
    var factor = BigInteger.ONE
    var rest = value
    var d = 2L
    // 常规范围的判别式几秒内就能分解完；特别大的数不做完全分解，保留原样仍然正确
    while (d <= 1_000_000L) {
        val square = BigInteger.valueOf(d * d)
        if (square > rest) break
        while (rest.mod(square).signum() == 0) {
            factor = factor * BigInteger.valueOf(d)
            rest = rest / square
        }
        d++
    }
    val root = bigSqrt(rest)
    if (root * root == rest) {
        factor = factor * root
        rest = BigInteger.ONE
    }
    return factor to rest
}

/** BigInteger 的整数平方根（向下取整）。 */
private fun bigSqrt(value: BigInteger): BigInteger {
    if (value.signum() < 0) throw IllegalArgumentException("负数没有平方根")
    if (value < BigInteger.valueOf(2)) return value
    var x = BigInteger.ONE.shiftLeft((value.bitLength() + 1) / 2)
    while (true) {
        val next = x.add(value.divide(x)).shiftRight(1)
        if (next >= x) return x
        x = next
    }
}
