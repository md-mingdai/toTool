package com.mingdai.toTool.math

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.acosh
import kotlin.math.asin
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.atanh
import kotlin.math.cbrt
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh

/** 三角函数使用的角度单位。 */
enum class AngleMode(val label: String) {
    RADIAN("弧度"),
    DEGREE("角度"),
}

/** 表达式解析失败时抛出，message 直接展示给用户。 */
class MathParseException(message: String) : Exception(message)

/**
 * 单变量数学表达式。
 *
 * 支持：
 * - 四则运算、乘方 `^`、取余 `%`，以及省略乘号的写法（`2x`、`3(x+1)`、`x(x-1)`）；
 * - 常见函数：sin/cos/tan、asin/acos/atan、sinh/cosh/tanh、ln/log/log2/log10、sqrt/cbrt、
 *   abs/exp/floor/ceil/round/sign、min/max/pow/hypot/atan2/mod、cot/sec/csc；
 * - 常数 pi（π）、e、tau。
 *
 * 解析一次即可反复求值，绘图时每帧会调用上千次，所以这里不保留原始字符串再重新解析。
 */
class MathExpression private constructor(
    private val root: Node,
    val source: String,
    val variables: Set<String>,
    private val angle: AngleMode,
) {
    /** 代入未知数的值求值；无定义时返回 NaN。 */
    fun eval(x: Double): Double = root.eval(x, angle)

    /** 把表达式转成可直接交给 LaTeX 渲染器的数学模式字符串。 */
    fun toLatex(): String = root.toLatex()

    companion object {
        fun parse(source: String, angle: AngleMode = AngleMode.RADIAN): MathExpression {
            val normalized = normalize(source)
            if (normalized.isBlank()) throw MathParseException("请输入表达式")
            val tokens = tokenize(normalized)
            val root = Parser(tokens).parse()
            val variables = linkedSetOf<String>()
            root.collectVariables(variables)
            return MathExpression(root, source, variables, angle)
        }

        /** 去掉输入框里可能带的 `y =` / `f(x) =` 前缀。 */
        fun normalizeFunctionInput(source: String): String {
            var s = source.trim()
            s = s.replaceFirst(Regex("^[A-Za-z]\\s*\\(\\s*[A-Za-z]\\s*\\)\\s*="), "")
            s = s.replaceFirst(Regex("^[yY]\\s*="), "")
            return s.trim()
        }
    }
}

/* ------------------------------- 语法树 -------------------------------- */

private sealed interface Node {
    fun eval(x: Double, angle: AngleMode): Double
    fun collectVariables(out: MutableSet<String>)
    fun toLatex(parentPrecedence: Int = 0): String
}

private class NumNode(
    private val value: Double,
    private val latexLabel: String? = null,
) : Node {
    override fun eval(x: Double, angle: AngleMode): Double = value
    override fun collectVariables(out: MutableSet<String>) = Unit
    override fun toLatex(parentPrecedence: Int): String =
        latexWrap(latexLabel ?: latexNumber(value), LatexAtom, parentPrecedence)
}

private class VarNode(private val name: String) : Node {
    // 工具只允许一个未知数，所以直接取传入的值；名字只用来提示用户。
    override fun eval(x: Double, angle: AngleMode): Double = x
    override fun collectVariables(out: MutableSet<String>) {
        out.add(name)
    }

    override fun toLatex(parentPrecedence: Int): String =
        latexWrap(name, LatexAtom, parentPrecedence)
}

private class UnaryNode(private val negative: Boolean, private val operand: Node) : Node {
    override fun eval(x: Double, angle: AngleMode): Double {
        val v = operand.eval(x, angle)
        return if (negative) -v else v
    }

    override fun collectVariables(out: MutableSet<String>) = operand.collectVariables(out)

    override fun toLatex(parentPrecedence: Int): String {
        val inner = operand.toLatex(LatexPower)
        val text = (if (negative) "-" else "") + inner
        return latexWrap(text, LatexUnary, parentPrecedence)
    }
}

private class BinaryNode(
    private val op: Char,
    private val left: Node,
    private val right: Node,
) : Node {
    override fun eval(x: Double, angle: AngleMode): Double {
        val a = left.eval(x, angle)
        val b = right.eval(x, angle)
        return when (op) {
            '+' -> a + b
            '-' -> a - b
            '*' -> a * b
            '/' -> a / b
            '%' -> a % b
            '^' -> a.pow(b)
            else -> Double.NaN
        }
    }

    override fun collectVariables(out: MutableSet<String>) {
        left.collectVariables(out)
        right.collectVariables(out)
    }

    override fun toLatex(parentPrecedence: Int): String {
        val (text, precedence) = when (op) {
            '+' -> (left.toLatex(LatexAdd) + " + " + right.toLatex(LatexAdd)) to LatexAdd
            '-' -> (left.toLatex(LatexAdd) + " - " + right.toLatex(LatexMul)) to LatexAdd
            '*' -> (left.toLatex(LatexMul) + " \\cdot " + right.toLatex(LatexMul)) to LatexMul
            '/' -> "\\frac{${left.toLatex()}}{${right.toLatex()}}" to LatexAtom
            '%' -> (left.toLatex(LatexMul) + " \\bmod " + right.toLatex(LatexMul)) to LatexMul
            '^' -> (left.toLatex(LatexAtom) + "^{${right.toLatex()}}") to LatexPower
            else -> ("${left.toLatex(LatexMul)} $op ${right.toLatex(LatexMul)}") to LatexMul
        }
        return latexWrap(text, precedence, parentPrecedence)
    }
}

private class CallNode(private val name: String, private val args: List<Node>) : Node {
    override fun eval(x: Double, angle: AngleMode): Double {
        fun a(index: Int): Double = args[index].eval(x, angle)
        return when (name) {
            "sin" -> sin(toRadians(a(0), angle))
            "cos" -> cos(toRadians(a(0), angle))
            "tan" -> tan(toRadians(a(0), angle))
            "cot" -> 1.0 / tan(toRadians(a(0), angle))
            "sec" -> 1.0 / cos(toRadians(a(0), angle))
            "csc" -> 1.0 / sin(toRadians(a(0), angle))
            "asin" -> fromRadians(asin(a(0)), angle)
            "acos" -> fromRadians(acos(a(0)), angle)
            "atan" -> fromRadians(atan(a(0)), angle)
            "atan2" -> fromRadians(atan2(a(0), a(1)), angle)
            "sinh" -> sinh(a(0))
            "cosh" -> cosh(a(0))
            "tanh" -> tanh(a(0))
            "asinh" -> asinh(a(0))
            "acosh" -> acosh(a(0))
            "atanh" -> atanh(a(0))
            "ln" -> ln(a(0))
            "log" -> if (args.size == 1) log10(a(0)) else log(a(0), a(1))
            "log2" -> log2(a(0))
            "log10" -> log10(a(0))
            "sqrt" -> sqrt(a(0))
            "cbrt" -> cbrt(a(0))
            "abs" -> abs(a(0))
            "exp" -> exp(a(0))
            "floor" -> floor(a(0))
            "ceil" -> ceil(a(0))
            "round" -> round(a(0))
            "sign" -> sign(a(0))
            "min" -> min(a(0), a(1))
            "max" -> max(a(0), a(1))
            "pow" -> a(0).pow(a(1))
            "hypot" -> hypot(a(0), a(1))
            "mod" -> a(0) % a(1)
            else -> Double.NaN
        }
    }

    override fun collectVariables(out: MutableSet<String>) {
        args.forEach { it.collectVariables(out) }
    }

    override fun toLatex(parentPrecedence: Int): String {
        val argLatex = { index: Int -> args[index].toLatex() }
        val joined = args.joinToString(", ") { it.toLatex() }
        val text = when (name) {
            "sqrt" -> "\\sqrt{${argLatex(0)}}"
            "cbrt" -> "\\sqrt[3]{${argLatex(0)}}"
            "abs" -> "\\left|${argLatex(0)}\\right|"
            "floor" -> "\\left\\lfloor ${argLatex(0)} \\right\\rfloor"
            "ceil" -> "\\left\\lceil ${argLatex(0)} \\right\\rceil"
            "exp" -> "e^{${argLatex(0)}}"
            "ln" -> "\\ln\\left(${argLatex(0)}\\right)"
            "log" -> if (args.size == 1) {
                "\\log_{10}\\left(${argLatex(0)}\\right)"
            } else {
                "\\log_{${argLatex(1)}}\\left(${argLatex(0)}\\right)"
            }
            "log2" -> "\\log_{2}\\left(${argLatex(0)}\\right)"
            "log10" -> "\\log_{10}\\left(${argLatex(0)}\\right)"
            "pow" -> "\\left(${argLatex(0)}\\right)^{${argLatex(1)}}"
            "min" -> "\\min\\left($joined\\right)"
            "max" -> "\\max\\left($joined\\right)"
            "asin" -> "\\arcsin\\left(${argLatex(0)}\\right)"
            "acos" -> "\\arccos\\left(${argLatex(0)}\\right)"
            "atan" -> "\\arctan\\left(${argLatex(0)}\\right)"
            "sin", "cos", "tan", "cot", "sec", "csc",
            "sinh", "cosh", "tanh", "asinh", "acosh", "atanh"
            -> "\\$name\\left(${argLatex(0)}\\right)"
            "sign" -> "\\operatorname{sgn}\\left(${argLatex(0)}\\right)"
            else -> "\\operatorname{$name}\\left($joined\\right)"
        }
        return latexWrap(text, LatexAtom, parentPrecedence)
    }
}

private fun toRadians(value: Double, angle: AngleMode): Double =
    if (angle == AngleMode.DEGREE) value * PI / 180.0 else value

private fun fromRadians(value: Double, angle: AngleMode): Double =
    if (angle == AngleMode.DEGREE) value * 180.0 / PI else value

/* ------------------------------- 词法分析 ------------------------------- */

private sealed interface Tok {
    class Num(val value: Double) : Tok
    class Ident(val name: String) : Tok
    class Op(val ch: Char) : Tok
    object LParen : Tok
    object RParen : Tok
    object Comma : Tok
}

private data class FnSpec(val name: String, val minArgs: Int, val maxArgs: Int)

private val functionAliases: Map<String, String> = mapOf(
    "arcsin" to "asin",
    "arccos" to "acos",
    "arctan" to "atan",
    "arcsinh" to "asinh",
    "arccosh" to "acosh",
    "arctanh" to "atanh",
    "lg" to "log10",
)

private val functionSpecs: Map<String, FnSpec> = listOf(
    FnSpec("sin", 1, 1), FnSpec("cos", 1, 1), FnSpec("tan", 1, 1),
    FnSpec("cot", 1, 1), FnSpec("sec", 1, 1), FnSpec("csc", 1, 1),
    FnSpec("asin", 1, 1), FnSpec("acos", 1, 1), FnSpec("atan", 1, 1),
    FnSpec("sinh", 1, 1), FnSpec("cosh", 1, 1), FnSpec("tanh", 1, 1),
    FnSpec("asinh", 1, 1), FnSpec("acosh", 1, 1), FnSpec("atanh", 1, 1),
    FnSpec("ln", 1, 1), FnSpec("log2", 1, 1), FnSpec("log10", 1, 1),
    FnSpec("sqrt", 1, 1), FnSpec("cbrt", 1, 1), FnSpec("abs", 1, 1),
    FnSpec("exp", 1, 1), FnSpec("floor", 1, 1), FnSpec("ceil", 1, 1),
    FnSpec("round", 1, 1), FnSpec("sign", 1, 1),
    FnSpec("log", 1, 2), FnSpec("atan2", 2, 2), FnSpec("min", 2, 2),
    FnSpec("max", 2, 2), FnSpec("pow", 2, 2), FnSpec("hypot", 2, 2),
    FnSpec("mod", 2, 2),
).associateBy { it.name }

private val constantValues: Map<String, Double> = mapOf(
    "pi" to PI,
    "e" to Math.E,
    "tau" to 2.0 * PI,
)

/** 把常见的中文/数学输入法字符换成可直接解析的形式。 */
private fun normalize(source: String): String {
    val replaced = buildString(source.length) {
        source.forEach { ch ->
            when (ch) {
                '×', '·', '⋅', '∙', '＊' -> append('*')
                '÷', '／' -> append('/')
                '−', '–', '—', '－' -> append('-')
                '＋' -> append('+')
                '（' -> append('(')
                '）' -> append(')')
                '，' -> append(',')
                'π' -> append("pi")
                '√' -> append("sqrt")
                '°' -> append("*pi/180")
                '²' -> append("^2")
                '³' -> append("^3")
                '⁴' -> append("^4")
                else -> append(ch)
            }
        }
    }
    return replaced.filterNot { it.isWhitespace() }
}

private fun tokenize(source: String): List<Tok> {
    val tokens = ArrayList<Tok>()
    var i = 0
    while (i < source.length) {
        val ch = source[i]
        when {
            ch.isDigit() || ch == '.' -> {
                val start = i
                var hasDot = false
                while (i < source.length && (source[i].isDigit() || (source[i] == '.' && !hasDot))) {
                    if (source[i] == '.') hasDot = true
                    i++
                }
                // 科学计数法：1e-3、2.5E6（后面不是数字时按常数 e 处理）
                if (i < source.length && (source[i] == 'e' || source[i] == 'E')) {
                    var j = i + 1
                    if (j < source.length && (source[j] == '+' || source[j] == '-')) j++
                    if (j < source.length && source[j].isDigit()) {
                        while (j < source.length && source[j].isDigit()) j++
                        i = j
                    }
                }
                val text = source.substring(start, i)
                val value = text.toDoubleOrNull()
                    ?: throw MathParseException("无法识别的数字：$text")
                tokens.add(Tok.Num(value))
            }

            ch.isLetter() || ch == '_' -> {
                val start = i
                while (i < source.length && (source[i].isLetterOrDigit() || source[i] == '_')) i++
                tokens.add(Tok.Ident(source.substring(start, i).lowercase(Locale.US)))
            }

            ch == '+' || ch == '-' || ch == '*' || ch == '/' || ch == '%' || ch == '^' -> {
                tokens.add(Tok.Op(ch))
                i++
            }

            ch == '(' -> {
                tokens.add(Tok.LParen)
                i++
            }

            ch == ')' -> {
                tokens.add(Tok.RParen)
                i++
            }

            ch == ',' -> {
                tokens.add(Tok.Comma)
                i++
            }

            ch == '=' -> throw MathParseException("表达式中不能包含等号")

            else -> throw MathParseException("无法识别的字符：$ch")
        }
    }
    return tokens
}

/* ------------------------------- 语法分析 ------------------------------- */

/**
 * 递归下降：
 *   加减 → 乘除 / 省略乘号 → 一元正负 → 乘方（右结合） → 原子（数字、变量、函数、括号）
 * 一元负号比乘方低一级，所以 `-x^2` 是 `-(x^2)`，`2^-3` 也能正确解析。
 */
private class Parser(private val tokens: List<Tok>) {
    private var index = 0

    fun parse(): Node {
        if (tokens.isEmpty()) throw MathParseException("请输入表达式")
        val node = parseAdd()
        if (index < tokens.size) throw MathParseException("表达式里有无法解析的内容")
        return node
    }

    private fun peek(): Tok? = tokens.getOrNull(index)

    private fun parseAdd(): Node {
        var left = parseMultiply()
        while (true) {
            val token = peek()
            if (token is Tok.Op && (token.ch == '+' || token.ch == '-')) {
                index++
                left = BinaryNode(token.ch, left, parseMultiply())
            } else {
                return left
            }
        }
    }

    private fun parseMultiply(): Node {
        var left = parseUnary()
        while (true) {
            val token = peek()
            when {
                token is Tok.Op && (token.ch == '*' || token.ch == '/' || token.ch == '%') -> {
                    index++
                    left = BinaryNode(token.ch, left, parseUnary())
                }

                token is Tok.Num || token is Tok.Ident || token === Tok.LParen -> {
                    // 省略乘号：2x、3(x+1)、x(x-1)、2sin(x)
                    left = BinaryNode('*', left, parseUnary())
                }

                else -> return left
            }
        }
    }

    private fun parseUnary(): Node {
        val token = peek()
        if (token is Tok.Op && (token.ch == '+' || token.ch == '-')) {
            index++
            val operand = parseUnary()
            return if (token.ch == '-') UnaryNode(true, operand) else operand
        }
        return parsePower()
    }

    private fun parsePower(): Node {
        val base = parseAtom()
        val token = peek()
        if (token is Tok.Op && token.ch == '^') {
            index++
            // 右结合：2^3^2 = 2^(3^2)
            return BinaryNode('^', base, parseUnary())
        }
        return base
    }

    private fun parseAtom(): Node {
        val token = tokens.getOrNull(index) ?: throw MathParseException("表达式不完整")
        index++
        return when (token) {
            is Tok.Num -> NumNode(token.value)

            is Tok.Ident -> {
                val raw = token.name
                val fnName = functionAliases[raw] ?: raw
                val spec = functionSpecs[fnName]
                if (spec != null) {
                    if (peek() !== Tok.LParen) {
                        throw MathParseException("函数 $raw 后面要加括号，例如 $raw(x)")
                    }
                    index++
                    val args = parseArguments()
                    if (args.size < spec.minArgs || args.size > spec.maxArgs) {
                        val need = if (spec.minArgs == spec.maxArgs) "${spec.minArgs}"
                        else "${spec.minArgs}~${spec.maxArgs}"
                        throw MathParseException("$raw 需要 $need 个参数")
                    }
                    CallNode(fnName, args)
                } else {
                    val constant = constantValues[raw]
                    when {
                        constant != null -> NumNode(
                            constant,
                            when (raw) {
                                "pi" -> "\\pi"
                                "tau" -> "2\\pi"
                                "e" -> "e"
                                else -> null
                            },
                        )
                        peek() === Tok.LParen && raw.length > 1 ->
                            throw MathParseException("未知函数：$raw")
                        else -> VarNode(raw)
                    }
                }
            }

            Tok.LParen -> {
                val inner = parseAdd()
                if (peek() !== Tok.RParen) throw MathParseException("缺少右括号")
                index++
                inner
            }

            Tok.Comma -> throw MathParseException("逗号的位置不对")
            Tok.RParen -> throw MathParseException("多余的右括号")
            is Tok.Op -> throw MathParseException("运算符 ${token.ch} 的位置不对")
            else -> throw MathParseException("无法解析的表达式")
        }
    }

    private fun parseArguments(): List<Node> {
        val args = ArrayList<Node>()
        if (peek() === Tok.RParen) {
            index++
            return args
        }
        while (true) {
            args.add(parseAdd())
            when {
                peek() === Tok.Comma -> index++
                peek() === Tok.RParen -> {
                    index++
                    return args
                }

                else -> throw MathParseException("函数参数缺少右括号")
            }
        }
    }
}

/* ------------------------------- 数字格式 ------------------------------- */

/** 面向用户的结果数字：尽量简洁，极端值用科学计数法。 */
fun formatNumber(value: Double): String {
    if (value.isNaN()) return "无定义"
    if (!value.isFinite()) return if (value > 0) "+∞" else "−∞"
    val magnitude = abs(value)
    if (magnitude < 1e-12) return "0"
    if (magnitude < 1e7 && abs(value - round(value)) < 1e-9) {
        return round(value).toLong().toString()
    }
    if (magnitude >= 1e7 || magnitude < 1e-4) {
        return String.format(Locale.US, "%.6e", value)
    }
    var text = String.format(Locale.US, "%.10f", value)
    text = text.trimEnd('0').trimEnd('.')
    return if (text == "-0") "0" else text
}
