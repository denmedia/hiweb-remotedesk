package com.hiweb.hiwebremotedesk.rdp

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Примитивы для NTLM. MD4 и RC4 реализованы вручную: в JCA на Android их нет. */
object Crypto {
    fun md5(vararg parts: ByteArray): ByteArray {
        val d = MessageDigest.getInstance("MD5")
        parts.forEach { d.update(it) }
        return d.digest()
    }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val d = MessageDigest.getInstance("SHA-256")
        parts.forEach { d.update(it) }
        return d.digest()
    }

    fun hmacMd5(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacMD5")
        mac.init(SecretKeySpec(key, "HmacMD5"))
        parts.forEach { mac.update(it) }
        return mac.doFinal()
    }

    fun md4(input: ByteArray): ByteArray {
        val bitLen = input.size.toLong() * 8
        val padLen = ((56 - (input.size + 1) % 64) + 64) % 64
        val msg = ByteArray(input.size + 1 + padLen + 8)
        input.copyInto(msg)
        msg[input.size] = 0x80.toByte()
        for (i in 0 until 8) msg[msg.size - 8 + i] = ((bitLen ushr (8 * i)) and 0xFF).toByte()

        var a = 0x67452301
        var b = 0xefcdab89.toInt()
        var c = 0x98badcfe.toInt()
        var d = 0x10325476
        val x = IntArray(16)

        fun rol(v: Int, s: Int) = (v shl s) or (v ushr (32 - s))
        fun f(x: Int, y: Int, z: Int) = (x and y) or (x.inv() and z)
        fun g(x: Int, y: Int, z: Int) = (x and y) or (x and z) or (y and z)
        fun h(x: Int, y: Int, z: Int) = x xor y xor z

        for (off in msg.indices step 64) {
            for (i in 0 until 16) {
                val p = off + i * 4
                x[i] = (msg[p].toInt() and 0xFF) or ((msg[p + 1].toInt() and 0xFF) shl 8) or
                    ((msg[p + 2].toInt() and 0xFF) shl 16) or ((msg[p + 3].toInt() and 0xFF) shl 24)
            }
            val aa = a; val bb = b; val cc = c; val dd = d

            for (i in 0 until 4) {
                a = rol(a + f(b, c, d) + x[i * 4], 3)
                d = rol(d + f(a, b, c) + x[i * 4 + 1], 7)
                c = rol(c + f(d, a, b) + x[i * 4 + 2], 11)
                b = rol(b + f(c, d, a) + x[i * 4 + 3], 19)
            }
            for (i in 0 until 4) {
                a = rol(a + g(b, c, d) + x[i] + 0x5a827999, 3)
                d = rol(d + g(a, b, c) + x[i + 4] + 0x5a827999, 5)
                c = rol(c + g(d, a, b) + x[i + 8] + 0x5a827999, 9)
                b = rol(b + g(c, d, a) + x[i + 12] + 0x5a827999, 13)
            }
            for (i in intArrayOf(0, 2, 1, 3)) {
                a = rol(a + h(b, c, d) + x[i] + 0x6ed9eba1, 3)
                d = rol(d + h(a, b, c) + x[i + 8] + 0x6ed9eba1, 9)
                c = rol(c + h(d, a, b) + x[i + 4] + 0x6ed9eba1, 11)
                b = rol(b + h(c, d, a) + x[i + 12] + 0x6ed9eba1, 15)
            }
            a += aa; b += bb; c += cc; d += dd
        }
        val out = ByteArray(16)
        intArrayOf(a, b, c, d).forEachIndexed { i, v ->
            for (j in 0 until 4) out[i * 4 + j] = ((v ushr (8 * j)) and 0xFF).toByte()
        }
        return out
    }
}

/** Потоковый RC4. Состояние сохраняется между вызовами [process]. */
class Rc4(key: ByteArray) {
    private val s = IntArray(256) { it }
    private var i = 0
    private var j = 0

    init {
        var k = 0
        for (n in 0 until 256) {
            k = (k + s[n] + (key[n % key.size].toInt() and 0xFF)) and 0xFF
            val t = s[n]; s[n] = s[k]; s[k] = t
        }
    }

    fun process(data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        for (n in data.indices) {
            i = (i + 1) and 0xFF
            j = (j + s[i]) and 0xFF
            val t = s[i]; s[i] = s[j]; s[j] = t
            out[n] = (data[n].toInt() xor s[(s[i] + s[j]) and 0xFF]).toByte()
        }
        return out
    }
}
