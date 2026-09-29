package ru.radiationx.anilibria.common

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Минимальный генератор QR-кода: байтовый режим, коррекция L, версии 1–6 (до 132 байт).
 * Хватает для ссылок входа во внешние сервисы; отдельной библиотеки в проекте нет.
 */
object QrCode {

    private const val MAX_VERSION = 6
    private val RAW_CODEWORDS = intArrayOf(0, 26, 44, 70, 100, 134, 172)
    private val ECC_PER_BLOCK = intArrayOf(0, 7, 10, 15, 20, 26, 18)
    private val BLOCKS = intArrayOf(0, 1, 1, 1, 1, 1, 2)
    private val ALIGN = arrayOf(
        intArrayOf(), intArrayOf(), intArrayOf(6, 18), intArrayOf(6, 22),
        intArrayOf(6, 26), intArrayOf(6, 30), intArrayOf(6, 34),
    )

    /** Модули QR (true — тёмный), `[y][x]`; null, если текст не помещается. */
    fun encode(text: String): Array<BooleanArray>? {
        val bytes = text.toByteArray(Charsets.UTF_8)
        var version = 1
        while (version <= MAX_VERSION) {
            if (bytes.size * 8 + 12 <= dataCodewords(version) * 8) break
            version++
        }
        if (version > MAX_VERSION) return null

        val dataCap = dataCodewords(version)
        val bits = ArrayList<Boolean>()
        fun put(value: Int, count: Int) {
            for (i in count - 1 downTo 0) bits.add((value shr i) and 1 == 1)
        }
        put(0b0100, 4)
        put(bytes.size, 8)
        bytes.forEach { put(it.toInt() and 0xFF, 8) }
        put(0, minOf(4, dataCap * 8 - bits.size))
        while (bits.size % 8 != 0) bits.add(false)
        var pad = 0xEC
        while (bits.size < dataCap * 8) {
            put(pad, 8)
            pad = pad xor 0xEC xor 0x11
        }
        val data = ByteArray(dataCap)
        bits.forEachIndexed { i, b -> if (b) data[i shr 3] = (data[i shr 3].toInt() or (1 shl (7 - (i and 7)))).toByte() }

        val codewords = addEccAndInterleave(data, version)
        val size = version * 4 + 17
        val modules = Array(size) { BooleanArray(size) }
        val isFunction = Array(size) { BooleanArray(size) }
        drawFunctionPatterns(modules, isFunction, version)
        drawCodewords(modules, isFunction, codewords)

        var bestMask = 0
        var bestPenalty = Int.MAX_VALUE
        for (mask in 0 until 8) {
            applyMask(modules, isFunction, mask)
            drawFormat(modules, isFunction, mask)
            val penalty = penalty(modules)
            if (penalty < bestPenalty) {
                bestPenalty = penalty
                bestMask = mask
            }
            applyMask(modules, isFunction, mask)
        }
        applyMask(modules, isFunction, bestMask)
        drawFormat(modules, isFunction, bestMask)
        return modules
    }

    /** Чёрно-белый Bitmap с тихой зоной 4 модуля; [scale] пикселей на модуль. */
    fun toBitmap(text: String, scale: Int = 8): Bitmap? {
        val modules = encode(text) ?: return null
        val quiet = 4
        val dim = (modules.size + quiet * 2) * scale
        val pixels = IntArray(dim * dim) { Color.WHITE }
        for (y in modules.indices) for (x in modules.indices) {
            if (!modules[y][x]) continue
            for (dy in 0 until scale) for (dx in 0 until scale) {
                pixels[((y + quiet) * scale + dy) * dim + (x + quiet) * scale + dx] = Color.BLACK
            }
        }
        return Bitmap.createBitmap(pixels, dim, dim, Bitmap.Config.ARGB_8888)
    }

    private fun dataCodewords(version: Int) =
        RAW_CODEWORDS[version] - ECC_PER_BLOCK[version] * BLOCKS[version]

    private fun addEccAndInterleave(data: ByteArray, version: Int): ByteArray {
        val blocks = BLOCKS[version]
        val eccLen = ECC_PER_BLOCK[version]
        val blockDataLen = data.size / blocks
        val dataBlocks = (0 until blocks).map { data.copyOfRange(it * blockDataLen, (it + 1) * blockDataLen) }
        val divisor = reedSolomonDivisor(eccLen)
        val eccBlocks = dataBlocks.map { reedSolomonRemainder(it, divisor) }
        val out = ArrayList<Byte>()
        for (i in 0 until blockDataLen) dataBlocks.forEach { out.add(it[i]) }
        for (i in 0 until eccLen) eccBlocks.forEach { out.add(it[i]) }
        return out.toByteArray()
    }

    private fun gfMul(a: Int, b: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z shr 7) * 0x11D)
            z = z xor (((b shr i) and 1) * a)
        }
        return z
    }

    private fun reedSolomonDivisor(degree: Int): IntArray {
        val result = IntArray(degree)
        result[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in 0 until degree) {
                result[j] = gfMul(result[j], root)
                if (j + 1 < degree) result[j] = result[j] xor result[j + 1]
            }
            root = gfMul(root, 2)
        }
        return result
    }

    private fun reedSolomonRemainder(data: ByteArray, divisor: IntArray): ByteArray {
        val result = IntArray(divisor.size)
        for (b in data) {
            val factor = (b.toInt() and 0xFF) xor result[0]
            for (i in 0 until result.size - 1) result[i] = result[i + 1]
            result[result.size - 1] = 0
            for (i in result.indices) result[i] = result[i] xor gfMul(divisor[i], factor)
        }
        return ByteArray(result.size) { result[it].toByte() }
    }

    private fun drawFunctionPatterns(m: Array<BooleanArray>, f: Array<BooleanArray>, version: Int) {
        val size = m.size
        fun set(x: Int, y: Int, dark: Boolean) {
            m[y][x] = dark
            f[y][x] = true
        }
        for (i in 0 until size) {
            set(6, i, i % 2 == 0)
            set(i, 6, i % 2 == 0)
        }
        fun finder(cx: Int, cy: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val x = cx + dx
                val y = cy + dy
                if (x !in 0 until size || y !in 0 until size) continue
                val dist = maxOf(Math.abs(dx), Math.abs(dy))
                set(x, y, dist != 2 && dist != 4)
            }
        }
        finder(3, 3)
        finder(size - 4, 3)
        finder(3, size - 4)
        val pos = ALIGN[version]
        for (i in pos.indices) for (j in pos.indices) {
            if ((i == 0 && j == 0) || (i == 0 && j == pos.size - 1) || (i == pos.size - 1 && j == 0)) continue
            for (dy in -2..2) for (dx in -2..2) {
                set(pos[i] + dx, pos[j] + dy, maxOf(Math.abs(dx), Math.abs(dy)) != 1)
            }
        }
        drawFormat(m, f, 0)
    }

    private fun drawFormat(m: Array<BooleanArray>, f: Array<BooleanArray>, mask: Int) {
        val size = m.size
        val data = (1 shl 3) or mask // коррекция L = 01
        var rem = data
        for (i in 0 until 10) rem = (rem shl 1) xor ((rem shr 9) * 0x537)
        val bits = ((data shl 10) or rem) xor 0x5412
        fun bit(i: Int) = (bits shr i) and 1 == 1
        fun set(x: Int, y: Int, dark: Boolean) {
            m[y][x] = dark
            f[y][x] = true
        }
        for (i in 0..5) set(8, i, bit(i))
        set(8, 7, bit(6))
        set(8, 8, bit(7))
        set(7, 8, bit(8))
        for (i in 9..14) set(14 - i, 8, bit(i))
        for (i in 0..7) set(size - 1 - i, 8, bit(i))
        for (i in 8..14) set(8, size - 15 + i, bit(i))
        set(8, size - 8, true)
    }

    private fun drawCodewords(m: Array<BooleanArray>, f: Array<BooleanArray>, data: ByteArray) {
        val size = m.size
        var i = 0
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until size) {
                for (j in 0..1) {
                    val x = right - j
                    val upward = ((right + 1) and 2) == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!f[y][x] && i < data.size * 8) {
                        m[y][x] = ((data[i shr 3].toInt() shr (7 - (i and 7))) and 1) == 1
                        i++
                    }
                }
            }
            right -= 2
        }
    }

    private fun applyMask(m: Array<BooleanArray>, f: Array<BooleanArray>, mask: Int) {
        for (y in m.indices) for (x in m.indices) {
            val invert = when (mask) {
                0 -> (x + y) % 2 == 0
                1 -> y % 2 == 0
                2 -> x % 3 == 0
                3 -> (x + y) % 3 == 0
                4 -> (x / 3 + y / 2) % 2 == 0
                5 -> x * y % 2 + x * y % 3 == 0
                6 -> (x * y % 2 + x * y % 3) % 2 == 0
                else -> ((x + y) % 2 + x * y % 3) % 2 == 0
            }
            if (invert && !f[y][x]) m[y][x] = !m[y][x]
        }
    }

    private fun penalty(m: Array<BooleanArray>): Int {
        val size = m.size
        var result = 0
        // правило 1 (серии одного цвета) и 3 (шаблон 1:1:3:1:1) по строкам и столбцам
        for (line in 0 until 2 * size) {
            val row = line < size
            val a = if (row) line else line - size
            fun at(i: Int) = if (row) m[a][i] else m[i][a]
            var run = 1
            for (i in 1 until size) {
                if (at(i) == at(i - 1)) {
                    run++
                    if (run == 5) result += 3 else if (run > 5) result++
                } else {
                    run = 1
                }
            }
            for (i in 0..size - 7) {
                if (at(i) && !at(i + 1) && at(i + 2) && at(i + 3) && at(i + 4) && !at(i + 5) && at(i + 6)) result += 40
            }
        }
        // правило 2 (блоки 2x2)
        for (y in 0 until size - 1) for (x in 0 until size - 1) {
            val c = m[y][x]
            if (c == m[y][x + 1] && c == m[y + 1][x] && c == m[y + 1][x + 1]) result += 3
        }
        // правило 4 (баланс тёмных модулей)
        var dark = 0
        for (row in m) for (v in row) if (v) dark++
        val total = size * size
        val k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1
        result += k * 10
        return result
    }
}
