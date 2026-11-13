package com.mingdai.toTool.math

import java.math.BigInteger

/** 方程里有函数、根号、π、e 等非多项式内容时抛出，调用方可以退回数值求解。 */
class NotPolynomialException(message: String = "不是多项式方程") : Exception(message)

/** 精确求解过程中用户能看懂的输入错误（除以零、次数过高、语法错误等）。 */
class ExactSolveException(message: String) : Exception(message)

data class ExactEquationResult(
    val standardForm: String,
    val kind: String,
    val description: String,
    val domainNote: String?,
    val discriminant: String?,
    val roots: List<ExactRoot>,
    val extraneous: List<ExactRoot>,
    val identity: Boolean = false,
    val contradiction: Boolean = false,
)

/**
 * 精确求解一元一次 / 二次方程（也支持可化为多项式的分式方程）。
 * 系数用任意精度有理数计算，根保留分数和最简根号形式，例如：
 *   x^2 - 2 = 0  ->  ±√2
 *   x^2 + x + 1 = 0 -> (-1 ± √3 i)/2
 */
fun solvePolynomialEquation(input: String): ExactEquationResult {
    val text = input.trim()
    if (text.isEmpty()) throw ExactSolveException("请输入方程")

    val parser = ExactParser(tokenizeExact(text))
    val ast = parser.parse()
    val value = evalExact(ast)

    var numerator = polyTrim(value.numerator)
    var denominator = polyTrim(value.denominator)
    if (denominator.isEmpty()) throw ExactSolveException("分母恒为零，方程无意义")
    val originalDenominator = denominator

    if (numerator.isNotEmpty() && denominator.isNotEmpty()) {
        val gcd = polyGcd(numerator, denominator)
        if (gcd.isNotEmpty()) {
            numerator = polyDivMod(numerator, gcd).first
            denominator = polyDivMod(denominator, gcd).first
            if (denominator.isEmpty()) denominator = listOf(Rational.ONE)
        }
    }

    numerator = polyNormalize(numerator)
    denominator = polyNormalize(denominator).ifEmpty { listOf(Rational.ONE) }
    val domainNote = if (originalDenominator.size > 1) {
        "原方程要求 ${formatPoly(polyNormalize(originalDenominator))} ≠ 0"
    } else null

    val degree = numerator.size - 1
    if (degree < 0) {
        return ExactEquationResult(
            standardForm = "0 = 0",
            kind = "恒等式",
            description = "等式恒成立（定义域内），无穷多解",
            domainNote = domainNote,
            discriminant = null,
            roots = emptyList(),
            extraneous = emptyList(),
            identity = true,
        )
    }
    if (degree == 0) {
        return ExactEquationResult(
            standardForm = "${numerator[0]} = 0",
            kind = "常数方程",
            description = "常数不为零，无解",
            domainNote = domainNote,
            discriminant = null,
            roots = emptyList(),
            extraneous = emptyList(),
            contradiction = true,
        )
    }
    if (degree > 2) throw NotPolynomialException("超过二次的方程改用数值求解")

    val roots = ArrayList<ExactRoot>()
    var discriminantText: String? = null
    val description: String
    val kind: String

    if (degree == 1) {
        kind = "一元一次方程"
        description = "一个实根"
        val a = numerator[1]
        val b = numerator[0]
        roots.add(RealExactRoot(Surd.rational(-b / a)))
    } else {
        kind = "一元二次方程"
        val a = numerator[2]
        val b = numerator[1]
        val c = numerator[0]
        val discriminant = b * b - Rational.of(4) * a * c
        discriminantText = "Δ = ${b}² - 4·${a}·${c} = $discriminant"
        when {
            discriminant.isZero() -> {
                description = "一个重根"
                roots.add(RealExactRoot(Surd.rational(-b / (Rational.of(2) * a))))
            }

            discriminant.signum() > 0 -> {
                description = "两个不同实根"
                val p = discriminant.num
                val q = discriminant.den
                val (s, k) = extractSquareFactor(p * q)
                val real = -b / (Rational.of(2) * a)
                val coefficient = Rational.of(s) / (Rational.of(2) * a * Rational.of(q))
                roots.add(RealExactRoot(Surd.of(real, coefficient, k)))
                roots.add(RealExactRoot(Surd.of(real, -coefficient, k)))
            }

            else -> {
                description = "两个共轭复根"
                val positive = -discriminant
                val (s, k) = extractSquareFactor(positive.num * positive.den)
                val real = -b / (Rational.of(2) * a)
                val coefficient = (Rational.of(s) /
                    (Rational.of(2) * a * Rational.of(positive.den))).abs()
                val imaginary = Surd.of(Rational.ZERO, coefficient, k)
                roots.add(ComplexExactRoot(real, imaginary))
                roots.add(ComplexExactRoot(real, imaginary.negate()))
            }
        }
    }

    val valid = ArrayList<ExactRoot>()
    val extraneous = ArrayList<ExactRoot>()
    for (root in roots) {
        val isExtraneous = if (originalDenominator.size > 1) {
            when (root) {
                is RealExactRoot -> isZeroAtSurd(originalDenominator, root.value)
                is ComplexExactRoot -> isZeroAtComplex(originalDenominator, root.real, root.imaginary)
            }
        } else false
        if (isExtraneous) extraneous.add(root) else valid.add(root)
    }

    return ExactEquationResult(
        standardForm = formatPoly(numerator) + " = 0",
        kind = kind,
        description = description,
        domainNote = domainNote,
        discriminant = discriminantText,
        roots = valid,
        extraneous = extraneous,
    )
}

/* ------------------------------- 词法 / 语法 ------------------------------- */

private sealed interface ExactToken {
    class Num(val value: Rational) : ExactToken
    object Var : ExactToken
    class Op(val ch: Char) : ExactToken
}

private fun preprocessExact(source: String): String {
    val replaced = buildString(source.length) {
        source.forEach { ch ->
            when (ch) {
                '×', '·', '⋅', '∙', '＊' -> append('*')
                '÷', '／' -> append('/')
                '−', '–', '—', '－' -> append('-')
                '＋' -> append('+')
                '（' -> append('(')
                '）' -> append(')')
                '²' -> append("^2")
                '³' -> append("^3")
                '⁴' -> append("^4")
                'π' -> append("pi")
                else -> append(ch)
            }
        }
    }.replace("**", "^")
    return replaced.filterNot { it.isWhitespace() }
}

private fun tokenizeExact(source: String): List<ExactToken> {
    val text = preprocessExact(source)
    if (text.isBlank()) throw ExactSolveException("请输入方程")
    val tokens = ArrayList<ExactToken>()
    var i = 0
    while (i < text.length) {
        val ch = text[i]
        when {
            ch.isDigit() || ch == '.' -> {
                val start = i
                var hasDot = false
                while (i < text.length && (text[i].isDigit() || (text[i] == '.' && !hasDot))) {
                    if (text[i] == '.') hasDot = true
                    i++
                }
                val literal = text.substring(start, i)
                if (literal == ".") throw ExactSolveException("小数点位置不对")
                val value = Rational.parse(literal)
                    ?: throw ExactSolveException("无法识别的数字：$literal")
                tokens.add(ExactToken.Num(value))
            }

            ch == 'x' || ch == 'X' -> {
                tokens.add(ExactToken.Var)
                i++
            }

            ch == '+' || ch == '-' || ch == '*' || ch == '/' || ch == '^' ||
                ch == '(' || ch == ')' || ch == '=' -> {
                tokens.add(ExactToken.Op(ch))
                i++
            }

            ch == '√' || ch == '%' || ch.isLetter() -> {
                throw NotPolynomialException("方程含函数、根号或常数，改用数值求解")
            }

            else -> throw ExactSolveException("无法识别的字符：$ch")
        }
    }
    return tokens
}

private sealed interface ExactNode {
    class Num(val value: Rational) : ExactNode
    object Var : ExactNode
    class Neg(val operand: ExactNode) : ExactNode
    class Bin(val op: Char, val left: ExactNode, val right: ExactNode) : ExactNode
}

private class ExactParser(private val tokens: List<ExactToken>) {
    private var index = 0

    fun parse(): ExactNode {
        if (tokens.isEmpty()) throw ExactSolveException("请输入方程")
        var node = parseExpression()
        val token = peek()
        if (token is ExactToken.Op && token.ch == '=') {
            index++
            node = ExactNode.Bin('-', node, parseExpression())
        }
        if (index < tokens.size) {
            val extra = peek()
            if (extra is ExactToken.Op && extra.ch == '=') {
                throw ExactSolveException("方程里只能有一个等号")
            }
            throw ExactSolveException("表达式里有无法解析的内容")
        }
        return node
    }

    private fun peek(): ExactToken? = tokens.getOrNull(index)

    private fun parseExpression(): ExactNode {
        var left = parseTerm()
        while (true) {
            val token = peek()
            if (token is ExactToken.Op && (token.ch == '+' || token.ch == '-')) {
                index++
                left = ExactNode.Bin(token.ch, left, parseTerm())
            } else {
                return left
            }
        }
    }

    private fun parseTerm(): ExactNode {
        var left = parseUnary()
        while (true) {
            val token = peek()
            when {
                token is ExactToken.Op && (token.ch == '*' || token.ch == '/') -> {
                    index++
                    left = ExactNode.Bin(token.ch, left, parseUnary())
                }

                token is ExactToken.Num || token === ExactToken.Var ||
                    (token is ExactToken.Op && token.ch == '(') -> {
                    left = ExactNode.Bin('*', left, parseUnary())
                }

                else -> return left
            }
        }
    }

    private fun parseUnary(): ExactNode {
        val token = peek()
        if (token is ExactToken.Op && (token.ch == '+' || token.ch == '-')) {
            index++
            val operand = parseUnary()
            return if (token.ch == '-') ExactNode.Neg(operand) else operand
        }
        return parsePower()
    }

    private fun parsePower(): ExactNode {
        val base = parseAtom()
        val token = peek()
        if (token is ExactToken.Op && token.ch == '^') {
            index++
            return ExactNode.Bin('^', base, parseUnary())
        }
        return base
    }

    private fun parseAtom(): ExactNode {
        val token = tokens.getOrNull(index) ?: throw ExactSolveException("表达式不完整")
        index++
        return when (token) {
            is ExactToken.Num -> ExactNode.Num(token.value)
            ExactToken.Var -> ExactNode.Var
            is ExactToken.Op -> {
                if (token.ch == '(') {
                    val inner = parseExpression()
                    val close = peek()
                    if (close !is ExactToken.Op || close.ch != ')') {
                        throw ExactSolveException("缺少右括号")
                    }
                    index++
                    inner
                } else {
                    throw ExactSolveException("运算符 ${token.ch} 的位置不对")
                }
            }

            else -> throw ExactSolveException("无法解析的表达式")
        }
    }
}

/* ------------------------------- 有理式求值 ------------------------------- */

private data class RationalFunction(val numerator: Poly, val denominator: Poly)

private fun evalExact(node: ExactNode): RationalFunction = when (node) {
    is ExactNode.Num -> RationalFunction(listOf(node.value), listOf(Rational.ONE))
    ExactNode.Var -> RationalFunction(listOf(Rational.ZERO, Rational.ONE), listOf(Rational.ONE))
    is ExactNode.Neg -> {
        val value = evalExact(node.operand)
        RationalFunction(polyNeg(value.numerator), value.denominator)
    }

    is ExactNode.Bin -> {
        val left = evalExact(node.left)
        val right = evalExact(node.right)
        when (node.op) {
            '+' -> RationalFunction(
                polyAdd(polyMul(left.numerator, right.denominator), polyMul(right.numerator, left.denominator)),
                polyMul(left.denominator, right.denominator),
            )

            '-' -> RationalFunction(
                polySub(polyMul(left.numerator, right.denominator), polyMul(right.numerator, left.denominator)),
                polyMul(left.denominator, right.denominator),
            )

            '*' -> RationalFunction(polyMul(left.numerator, right.numerator), polyMul(left.denominator, right.denominator))
            '/' -> {
                if (right.numerator.isEmpty()) throw ExactSolveException("除以零多项式")
                RationalFunction(polyMul(left.numerator, right.denominator), polyMul(left.denominator, right.numerator))
            }

            '^' -> {
                if (right.numerator.size > 1 || right.denominator.size > 1) {
                    throw NotPolynomialException("指数不能含未知数")
                }
                val exponentValue = (right.numerator.firstOrNull() ?: Rational.ZERO) /
                    (right.denominator.firstOrNull() ?: Rational.ONE)
                if (!exponentValue.isInteger()) throw NotPolynomialException("指数不是整数")
                val exponent = runCatching { exponentValue.num.toInt() }.getOrNull()
                    ?: throw NotPolynomialException("指数过大")
                if (exponent > 30 || exponent < -30) throw NotPolynomialException("指数过大")
                rationalPower(left, exponent)
            }

            else -> throw ExactSolveException("无法计算的运算符：${node.op}")
        }.also { checkSize(it) }
    }

    else -> throw ExactSolveException("无法解析的表达式")
}

private fun rationalPower(value: RationalFunction, exponent: Int): RationalFunction {
    if (exponent < 0) {
        if (value.numerator.isEmpty()) throw ExactSolveException("0 不能取负次幂")
        return rationalPower(RationalFunction(value.denominator, value.numerator), -exponent)
    }
    var numerator = listOf(Rational.ONE)
    var denominator = listOf(Rational.ONE)
    for (i in 0 until exponent) {
        numerator = polyMul(numerator, value.numerator)
        denominator = polyMul(denominator, value.denominator)
        checkSize(RationalFunction(numerator, denominator))
    }
    return RationalFunction(numerator, denominator)
}

private fun checkSize(value: RationalFunction) {
    if (value.numerator.size > 64 || value.denominator.size > 64) {
        throw NotPolynomialException("方程次数过高，改用数值求解")
    }
}
