package com.mingdai.toTool.math

import java.util.Locale

/**
 * 把用户粘贴的 LaTeX 数学公式转换成内部表达式语法。
 * 只覆盖计算需要的结构（分数、根号、上下标、常见函数和定界符）；
 * 纯文本公式原样通过。未知的复杂排版命令不会让转换器崩溃，而是留给
 * MathExpression 给出具体的语法错误。
 */
internal fun latexToMathInput(source: String): String =
    LatexToMathConverter(sanitizeLatexSource(source)).convert()

/** 去掉 `$`、`\(...\)`、`\[...\]` 等数学模式外壳，保留内部 LaTeX。 */
internal fun sanitizeLatexSource(source: String): String {
    var text = source.trim()
    if (text.startsWith("$$") && text.endsWith("$$") && text.length >= 4) {
        text = text.substring(2, text.length - 2).trim()
    } else if (text.startsWith("$") && text.endsWith("$") && text.length >= 2) {
        text = text.substring(1, text.length - 1).trim()
    }
    return text
        .replace("\\(", "")
        .replace("\\)", "")
        .replace("\\[", "")
        .replace("\\]", "")
        // 普通圆括号/方括号的 \left \right 可以先化简，省得函数参数解析被定界符打断。
        .replace("\\left(", "(")
        .replace("\\right)", ")")
        .replace("\\left[", "(")
        .replace("\\right]", ")")
        .replace("\\left\\{", "(")
        .replace("\\right\\}", ")")
        .replace("\\left.", "")
        .replace("\\right.", "")
        .trim()
}

/** 函数输入框里粘贴 `y = ...` / `f(x) = ...` 时只保留右边。 */
internal fun normalizeLatexFunctionInput(source: String): String {
    var text = sanitizeLatexSource(source)
    text = text.replaceFirst(Regex("^\\s*[yY]\\s*=\\s*"), "")
    text = text.replaceFirst(Regex("^\\s*[A-Za-z]\\s*\\(\\s*[A-Za-z]\\s*\\)\\s*=\\s*"), "")
    return text.trim()
}

private val LATEX_FUNCTIONS = mapOf(
    "sin" to "sin",
    "cos" to "cos",
    "tan" to "tan",
    "cot" to "cot",
    "sec" to "sec",
    "csc" to "csc",
    "sinh" to "sinh",
    "cosh" to "cosh",
    "tanh" to "tanh",
    "arcsin" to "asin",
    "arccos" to "acos",
    "arctan" to "atan",
    "arsinh" to "asinh",
    "arcosh" to "acosh",
    "artanh" to "atanh",
    "ln" to "ln",
    "log" to "log",
    "exp" to "exp",
    "min" to "min",
    "max" to "max",
    "abs" to "abs",
    "floor" to "floor",
    "ceil" to "ceil",
)

private class LatexToMathConverter(private val text: String) {
    private var index = 0

    fun convert(): String = parse(stop = null).trim()

    private fun parse(stop: Char?): String {
        val out = StringBuilder()
        while (index < text.length) {
            val ch = text[index]
            if (stop != null && ch == stop) {
                index++
                return out.toString()
            }
            when (ch) {
                '\\' -> {
                    index++
                    out.append(readCommand())
                }
                '{' -> {
                    index++
                    out.append('(').append(parse('}')).append(')')
                }
                '^' -> {
                    index++
                    val exponent = readArgument()
                    if (exponent.trim() == "°" || exponent.trim() == "circ") {
                        out.append("*pi/180")
                    } else {
                        out.append("^(").append(exponent).append(')')
                    }
                }
                '_' -> {
                    index++
                    readArgument() // 内部表达式不支持下标，丢掉以免破坏变量名
                }
                '%' -> {
                    while (index < text.length && text[index] != '\n') index++
                }
                '&' -> index++
                '~' -> {
                    out.append(' ')
                    index++
                }
                else -> {
                    out.append(ch)
                    index++
                }
            }
        }
        return out.toString()
    }

    private fun readCommand(): String {
        val name = readCommandName()
        return when (name) {
            "left" -> readLeftDelimiter()
            "right" -> readRightDelimiter()
            "frac", "dfrac", "tfrac" -> {
                val a = readArgument()
                val b = readArgument()
                "(($a)/($b))"
            }
            "sqrt" -> {
                skipSpaces()
                var degree: String? = null
                if (index < text.length && text[index] == '[') {
                    index++
                    degree = parse(']')
                }
                val value = readArgument()
                if (degree == null) "sqrt($value)" else "(($value)^(1/($degree)))"
            }
            "operatorname", "mathrm", "text", "mathbf", "mathit", "mathsf", "mathtt" -> {
                val content = readArgument().trim()
                val function = LATEX_FUNCTIONS[content.lowercase(Locale.ROOT)]
                if (function != null) function else content
            }
            "overline", "underline", "hat", "widehat", "bar", "vec", "tilde", "dot", "ddot" ->
                readArgument()
            "begin", "end" -> {
                readArgument()
                ""
            }
            "pi" -> symbol("pi")
            "tau" -> symbol("tau")
            "e" -> symbol("e")
            "infty" -> "∞"
            "circ", "degree" -> "°"
            "cdot", "times", "ast" -> "*"
            "div" -> "/"
            "bmod", "mod" -> "%"
            "pm" -> "±"
            "mp" -> "∓"
            "le", "leq" -> "<="
            "ge", "geq" -> ">="
            "ne", "neq" -> "!="
            "lt" -> "<"
            "gt" -> ">"
            "quad", "qquad", "enspace", "thinspace", "medspace", "thickspace" -> " "
            "displaystyle", "textstyle", "scriptstyle", "scriptscriptstyle", "limits", "nolimits" -> ""
            "!", ",", ";", ":", " ", "\\" -> ""
            "lvert", "lVert" -> "abs("
            "rvert", "rVert" -> ")"
            "{" -> "("
            "}" -> ")"
            "alpha", "beta", "gamma", "delta", "epsilon", "varepsilon", "zeta", "eta",
            "theta", "vartheta", "iota", "kappa", "lambda", "mu", "nu", "xi", "rho",
            "sigma", "upsilon", "phi", "varphi", "chi", "psi", "omega",
            "Gamma", "Delta", "Theta", "Lambda", "Xi", "Pi", "Sigma", "Upsilon",
            "Phi", "Psi", "Omega"
            -> symbol(name)
            else -> {
                val function = LATEX_FUNCTIONS[name]
                if (function != null) readFunction(function) else symbol(name)
            }
        }
    }

    private fun readFunction(function: String): String {
        skipSpaces()
        if (index < text.length && text[index] == '^') {
            index++
            val exponent = readArgument()
            val argument = readFunctionArgument()
            return "($function($argument))^($exponent)"
        }
        if (index < text.length && text[index] == '_') {
            index++
            val subscript = readArgument()
            if (function == "log") {
                val argument = readFunctionArgument()
                return "log($argument, $subscript)"
            }
        }
        skipSpaces()
        if (index >= text.length) return function
        val next = text[index]
        if (next == '(' || next == '{' || startsWithCommand("left")) return function
        return "$function(${readArgument()})"
    }

    private fun readFunctionArgument(): String {
        skipSpaces()
        if (index >= text.length) return ""
        return when (text[index]) {
            '(' -> {
                index++
                parse(')')
            }
            '{' -> {
                index++
                parse('}')
            }
            else -> readArgument()
        }
    }

    private fun readArgument(): String {
        skipSpaces()
        if (index >= text.length) return ""
        return when (text[index]) {
            '{' -> {
                index++
                parse('}')
            }
            '\\' -> {
                index++
                readCommand()
            }
            else -> readAtom()
        }
    }

    private fun readAtom(): String {
        if (index >= text.length) return ""
        val start = index
        val ch = text[index]
        if (ch.isDigit() || ch == '.') {
            while (index < text.length && (text[index].isDigit() || text[index] == '.')) index++
            return text.substring(start, index)
        }
        if (ch.isLetter()) {
            while (index < text.length && text[index].isLetter()) index++
            return text.substring(start, index)
        }
        index++
        return ch.toString()
    }

    private fun readCommandName(): String {
        if (index >= text.length) return ""
        return if (text[index].isLetter()) {
            val start = index
            while (index < text.length && text[index].isLetter()) index++
            text.substring(start, index)
        } else {
            val ch = text[index]
            index++
            ch.toString()
        }
    }

    private fun readLeftDelimiter(): String {
        val delimiter = readDelimiter()
        return when (delimiter) {
            "", "." -> ""
            "|", "vert", "Vert", "lvert", "lVert" -> "abs("
            "\\|" -> "abs("
            else -> "("
        }
    }

    private fun readRightDelimiter(): String {
        val delimiter = readDelimiter()
        return when (delimiter) {
            "", "." -> ""
            "|", "vert", "Vert", "rvert", "rVert" -> ")"
            "\\|" -> ")"
            else -> ")"
        }
    }

    private fun readDelimiter(): String {
        skipSpaces()
        if (index >= text.length) return ""
        if (text[index] == '\\') {
            index++
            return readCommandName()
        }
        val ch = text[index]
        index++
        return ch.toString()
    }

    /** 常量/变量后面紧跟另一个因子时补乘号，例如 \pi x → pi*x。 */
    private fun symbol(value: String): String =
        if (nextStartsFactor()) "$value*" else value

    private fun nextStartsFactor(): Boolean {
        var i = index
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length) return false
        val ch = text[i]
        if (ch.isLetterOrDigit() || ch == '(' || ch == '{' || ch == '|') return true
        if (ch != '\\') return false
        val save = index
        index = i + 1
        val name = readCommandName()
        index = save
        return name !in setOf("right", "end")
    }

    private fun skipSpaces() {
        while (index < text.length && text[index].isWhitespace()) index++
    }

    private fun startsWithCommand(name: String): Boolean {
        if (index >= text.length || text[index] != '\\') return false
        val save = index
        index++
        val actual = readCommandName()
        index = save
        return actual == name
    }
}
