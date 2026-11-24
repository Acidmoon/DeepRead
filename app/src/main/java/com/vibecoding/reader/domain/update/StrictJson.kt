package com.vibecoding.reader.domain.update

/**
 * 仅覆盖本模块需要的 JSON 子集：对象、数组、字符串、整数、布尔、null。
 * 拒绝小数、重复键与过深嵌套，便于在 JVM 单测中不依赖 Android 的 org.json。
 */
internal sealed class JsonVal {
    data class Obj(val fields: Map<String, JsonVal>) : JsonVal()
    data class Arr(val items: List<JsonVal>) : JsonVal()
    data class Str(val value: String) : JsonVal()
    data class IntNum(val value: Long) : JsonVal()
    data class Bool(val value: Boolean) : JsonVal()
    data object Null : JsonVal()
}

internal class JsonParseException(message: String) : IllegalArgumentException(message)

internal object StrictJson {
    private const val MAX_DEPTH = 16
    private const val MAX_CHARS = 1_048_576

    fun parse(text: String): JsonVal {
        if (text.length > MAX_CHARS) throw JsonParseException("JSON 过长")
        val p = Parser(text)
        val value = p.parseValue(0)
        p.skipWs()
        if (!p.eof) throw JsonParseException("JSON 尾部存在多余内容")
        return value
    }

    fun asObject(text: String): Map<String, JsonVal> {
        val value = parse(text)
        return (value as? JsonVal.Obj)?.fields
            ?: throw JsonParseException("JSON 根节点必须是对象")
    }

    private class Parser(private val s: String) {
        var i = 0
        val eof: Boolean get() = i >= s.length

        fun skipWs() {
            while (i < s.length) {
                val c = s[i]
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++ else break
            }
        }

        fun parseValue(depth: Int): JsonVal {
            if (depth > MAX_DEPTH) throw JsonParseException("JSON 嵌套过深")
            skipWs()
            if (eof) throw JsonParseException("JSON 不完整")
            return when (val c = s[i]) {
                '{' -> parseObject(depth + 1)
                '[' -> parseArray(depth + 1)
                '"' -> JsonVal.Str(parseString())
                't' -> kw("true", JsonVal.Bool(true))
                'f' -> kw("false", JsonVal.Bool(false))
                'n' -> kw("null", JsonVal.Null)
                '-', in '0'..'9' -> parseInt()
                else -> throw JsonParseException("意外字符：$c")
            }
        }

        private fun parseObject(depth: Int): JsonVal.Obj {
            expect('{')
            skipWs()
            if (peek('}')) {
                i++
                return JsonVal.Obj(emptyMap())
            }
            val fields = LinkedHashMap<String, JsonVal>()
            while (true) {
                skipWs()
                if (eof || s[i] != '"') throw JsonParseException("对象键必须是字符串")
                val key = parseString()
                if (fields.containsKey(key)) throw JsonParseException("重复键：$key")
                skipWs()
                expect(':')
                fields[key] = parseValue(depth)
                skipWs()
                when {
                    peek(',') -> {
                        i++
                        skipWs()
                        if (peek('}')) throw JsonParseException("对象末尾存在多余逗号")
                    }
                    peek('}') -> {
                        i++
                        break
                    }
                    else -> throw JsonParseException("对象中缺少逗号或结束符")
                }
            }
            return JsonVal.Obj(fields)
        }

        private fun parseArray(depth: Int): JsonVal.Arr {
            expect('[')
            skipWs()
            if (peek(']')) {
                i++
                return JsonVal.Arr(emptyList())
            }
            val items = ArrayList<JsonVal>()
            while (true) {
                items.add(parseValue(depth))
                skipWs()
                when {
                    peek(',') -> {
                        i++
                        skipWs()
                        if (peek(']')) throw JsonParseException("数组末尾存在多余逗号")
                    }
                    peek(']') -> {
                        i++
                        break
                    }
                    else -> throw JsonParseException("数组中缺少逗号或结束符")
                }
            }
            return JsonVal.Arr(items)
        }

        private fun parseString(): String {
            expect('"')
            val out = StringBuilder()
            while (!eof) {
                when (val c = s[i++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (eof) throw JsonParseException("字符串转义不完整")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> out.append(e)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw JsonParseException("Unicode 转义不完整")
                                val hex = s.substring(i, i + 4)
                                val cp = hex.toIntOrNull(16) ?: throw JsonParseException("非法 Unicode 转义")
                                out.append(cp.toChar())
                                i += 4
                            }
                            else -> throw JsonParseException("非法转义")
                        }
                    }
                    in '\u0000'..'\u001F' -> throw JsonParseException("字符串含控制字符")
                    else -> out.append(c)
                }
            }
            throw JsonParseException("字符串未闭合")
        }

        private fun parseInt(): JsonVal.IntNum {
            val start = i
            if (peek('-')) i++
            if (eof || s[i] !in '0'..'9') throw JsonParseException("非法数字")
            if (s[i] == '0') {
                i++
                if (!eof && s[i] in '0'..'9') throw JsonParseException("数字不得有前导零")
            } else {
                while (!eof && s[i] in '0'..'9') i++
            }
            if (!eof && (s[i] == '.' || s[i] == 'e' || s[i] == 'E')) {
                throw JsonParseException("仅允许整数")
            }
            val raw = s.substring(start, i)
            val value = raw.toLongOrNull() ?: throw JsonParseException("整数越界")
            return JsonVal.IntNum(value)
        }

        private fun kw(word: String, value: JsonVal): JsonVal {
            if (i + word.length > s.length || s.substring(i, i + word.length) != word) {
                throw JsonParseException("非法字面量")
            }
            i += word.length
            return value
        }

        private fun expect(c: Char) {
            skipWs()
            if (eof || s[i] != c) throw JsonParseException("期望 $c")
            i++
        }

        private fun peek(c: Char): Boolean = !eof && s[i] == c
    }
}
