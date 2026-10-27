package atlas.util

/**
 * Pure Kotlin SHA-256 implementation conforming to FIPS 180-4.
 * Zero platform or C dependencies, fully portable across Kotlin/Native targets.
 */
object Sha256 {

    private val K = intArrayOf(
        0x428a2f98.toInt(), 0x71374491.toInt(), 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(),
        0x3956c25b.toInt(), 0x59f111f1.toInt(), 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
        0xd807aa98.toInt(), 0x12835b01.toInt(), 0x243185be.toInt(), 0x550c7dc3.toInt(),
        0x72be5d74.toInt(), 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
        0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6.toInt(), 0x240ca1cc.toInt(),
        0x2de92c6f.toInt(), 0x4a7484aa.toInt(), 0x5cb0a9dc.toInt(), 0x76f988da.toInt(),
        0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(),
        0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351.toInt(), 0x14292967.toInt(),
        0x27b70a85.toInt(), 0x2e1b2138.toInt(), 0x4d2c6dfc.toInt(), 0x53380d13.toInt(),
        0x650a7354.toInt(), 0x766a0abb.toInt(), 0x81c2c92e.toInt(), 0x92722c85.toInt(),
        0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(),
        0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070.toInt(),
        0x19a4c116.toInt(), 0x1e376c08.toInt(), 0x2748774c.toInt(), 0x34b0bcb5.toInt(),
        0x391c0cb3.toInt(), 0x4ed8aa4a.toInt(), 0x5b9cca4f.toInt(), 0x682e6ff3.toInt(),
        0x748f82ee.toInt(), 0x78a5636f.toInt(), 0x84c87814.toInt(), 0x8cc70208.toInt(),
        0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt()
    )

    fun digest(data: ByteArray): String {
        val state = intArrayOf(
            0x6a09e667.toInt(), 0xbb67ae85.toInt(), 0x3c6ef372.toInt(), 0xa54ff53a.toInt(),
            0x510e527f.toInt(), 0x9b05688c.toInt(), 0x1f83d9ab.toInt(), 0x5be0cd19.toInt()
        )
        val padded = padMessage(data)
        val w = IntArray(64)
        val numChunks = padded.size / 64

        for (chunk in 0 until numChunks) {
            expandMessageSchedule(padded, chunk * 64, w)
            compressChunk(state, w)
        }

        return formatDigestHex(state)
    }

    private fun padMessage(data: ByteArray): ByteArray {
        val bitLength = data.size.toLong() * 8L
        val padLength = (((56 - (data.size + 1) % 64) % 64 + 64) % 64)
        val totalLength = data.size + 1 + padLength + 8
        val padded = ByteArray(totalLength)
        data.copyInto(padded, 0, 0, data.size)
        padded[data.size] = 0x80.toByte()

        for (i in 0 until 8) {
            padded[totalLength - 1 - i] = ((bitLength ushr (i * 8)) and 0xFF).toByte()
        }
        return padded
    }

    private fun expandMessageSchedule(padded: ByteArray, offset: Int, w: IntArray) {
        for (i in 0 until 16) {
            val j = offset + i * 4
            w[i] = ((padded[j].toInt() and 0xFF) shl 24) or
                ((padded[j + 1].toInt() and 0xFF) shl 16) or
                ((padded[j + 2].toInt() and 0xFF) shl 8) or
                (padded[j + 3].toInt() and 0xFF)
        }
        for (i in 16 until 64) {
            val s0 = (w[i - 15] rotateRight 7) xor (w[i - 15] rotateRight 18) xor (w[i - 15] ushr 3)
            val s1 = (w[i - 2] rotateRight 17) xor (w[i - 2] rotateRight 19) xor (w[i - 2] ushr 10)
            w[i] = w[i - 16] + s0 + w[i - 7] + s1
        }
    }

    private fun compressChunk(state: IntArray, w: IntArray) {
        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        var e = state[4]
        var f = state[5]
        var g = state[6]
        var h = state[7]

        for (i in 0 until 64) {
            val s1 = (e rotateRight 6) xor (e rotateRight 11) xor (e rotateRight 25)
            val ch = (e and f) xor (e.inv() and g)
            val temp1 = h + s1 + ch + K[i] + w[i]
            val s0 = (a rotateRight 2) xor (a rotateRight 13) xor (a rotateRight 22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val temp2 = s0 + maj

            h = g
            g = f
            f = e
            e = d + temp1
            d = c
            c = b
            b = a
            a = temp1 + temp2
        }

        state[0] += a
        state[1] += b
        state[2] += c
        state[3] += d
        state[4] += e
        state[5] += f
        state[6] += g
        state[7] += h
    }

    private fun formatDigestHex(state: IntArray): String {
        val sb = StringBuilder(64)
        for (v in state) {
            appendIntHex(sb, v)
        }
        return sb.toString()
    }

    private fun appendIntHex(sb: StringBuilder, value: Int) {
        for (byteIdx in 3 downTo 0) {
            val b = (value ushr (byteIdx * 8)) and 0xFF
            val hex = b.toString(16)
            if (hex.length == 1) sb.append('0')
            sb.append(hex)
        }
    }

    fun digest(text: String): String = digest(text.encodeToByteArray())

    private infix fun Int.rotateRight(bits: Int): Int = (this ushr bits) or (this shl (32 - bits))
}
