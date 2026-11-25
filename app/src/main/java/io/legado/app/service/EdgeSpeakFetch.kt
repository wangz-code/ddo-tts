package io.legado.app.service

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

class EdgeSpeakFetch {

    companion object {
        private const val TAG = "EdgeSpeakFetch"

        private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val BASE_URL = "speech.platform.bing.com"
        private const val WSS_PATH = "/consumer/speech/synthesize/readaloud/edge/v1"
        private const val DEFAULT_VOICE = "zh-CN-XiaoxiaoNeural"

        private const val WIN_EPOCH = 11644473600L
        private const val S_TO_NS = 1e9

        private const val CHROMIUM_FULL_VERSION = "143.0.3650.75"
        private val CHROMIUM_MAJOR_VERSION =
            CHROMIUM_FULL_VERSION.split(".", limit = 2)[0]
        private const val SEC_MS_GEC_VERSION = "2-$CHROMIUM_FULL_VERSION"

        private val BASE_HEADERS = mapOf(
            "User-Agent" to
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36" +
                    " (KHTML, like Gecko) Chrome/${CHROMIUM_MAJOR_VERSION}.0.0.0 Safari/537.36" +
                    " Edg/${CHROMIUM_MAJOR_VERSION}.0.0.0",
            "Accept-Encoding" to "gzip, deflate, br, zstd",
            "Accept-Language" to "en-US,en;q=0.9"
        )

        private val WSS_HEADERS = HashMap<String, String>().apply {
            put("Pragma", "no-cache")
            put("Cache-Control", "no-cache")
            put("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            put("Sec-WebSocket-Version", "13")
            putAll(BASE_HEADERS)
        }

        // ================= 自定义 SHA-256 常量 =================
        private const val U32_MASK = 0xFFFFFFFFL

        private val SHA256_INITIAL = longArrayOf(
            0x6A09E667L, 0xBB67AE85L, 0x3C6EF372L, 0xA54FF53AL,
            0x510E527FL, 0x9B05688CL, 0x1F83D9ABL, 0x5BE0CD19L
        )
        private val SHA256_STATE = longArrayOf(
            0xFD9FBEA2L, 0x91243CE7L, 0xBE499094L, 0xCD2E1E0AL,
            0x6C006F49L, 0x04BD35ABL, 0xFEB0A7E4L, 0x03EFFBD2L
        )
        private val SHA256_SCHEDULE = longArrayOf(
            0xEB24BF9EL, 0x1558691FL, 0xA0C09EE2L, 0x0CFA32BAL,
            0xA622EE8CL, 0xD1DD9491L, 0xC0DD25A0L, 0xF9E70AB6L,
            0xF0C31573L, 0x7058CE4EL, 0x7188E606L, 0x00E5AB33L,
            0xD258B060L, 0x72766564L
        )
        private val SHA256_ROUND_CONSTANTS = longArrayOf(
            0x428A2F98L, 0x71374491L, 0xB5C0FBCFL, 0xE9B5DBA5L,
            0x3956C25BL, 0x59F111F1L, 0x923F82A4L, 0xAB1C5ED5L,
            0xD807AA98L, 0x12835B01L, 0x243185BEL, 0x550C7DC3L,
            0x72BE5D74L, 0x80DEB1FEL, 0x9BDC06A7L, 0xC19BF174L,
            0xE49B69C1L, 0xEFBE4786L, 0x0FC19DC6L, 0x240CA1CCL,
            0x2DE92C6FL, 0x4A7484AAL, 0x5CB0A9DCL, 0x76F988DAL,
            0x983E5152L, 0xA831C66DL, 0xB00327C8L, 0xBF597FC7L,
            0xC6E00BF3L, 0xD5A79147L, 0x06CA6351L, 0x14292967L,
            0x27B70A85L, 0x2E1B2138L, 0x4D2C6DFCL, 0x53380D13L,
            0x650A7354L, 0x766A0ABBL, 0x81C2C92EL, 0x92722C85L,
            0xA2BFE8A1L, 0xA81A664BL, 0xC24B8B70L, 0xC76C51A3L,
            0xD192E819L, 0xD6990624L, 0xF40E3585L, 0x106AA070L,
            0x19A4C116L, 0x1E376C08L, 0x2748774CL, 0x34B0BCB5L,
            0x391C0CB3L, 0x4ED8AA4AL, 0x5B9CCA4FL, 0x682E6FF3L,
            0x748F82EEL, 0x78A5636FL, 0x84C87814L, 0x8CC70208L,
            0x90BEFFFAL, 0xA4506CEBL, 0xBEF9A3F7L, 0xC67178F2L
        )
        private const val SHA256_ADJUSTMENT = 0xC9DC06A7L

        private val SHA256_BLOCK_CONSTANTS: LongArray = LongArray(64).also { arr ->
            for (i in 0 until 14) arr[i] = SHA256_ROUND_CONSTANTS[i]
            arr[14] = SHA256_ADJUSTMENT
            for (i in 15 until 64) arr[i] = SHA256_ROUND_CONSTANTS[i]
        }
        private const val SHA256_SIGMA0_ADJUSTMENT = 0x059C0B80L
        private const val SHA256_SIGMA1_ADJUSTMENT = 0x000B92C0L

        // ================= CS64 常量 =================
        private val CS64_COEFFICIENTS = arrayOf(
            longArrayOf(0xE82E91B1L, 0xB32700D0L),
            longArrayOf(0x44DE24C6L, 0x88DFF51DL),
            longArrayOf(0x3BEB65BCL, 0x29514038L),
            longArrayOf(0xC89C5933L, 0xB328CBDFL),
            longArrayOf(0x8D1EC211L, 0xD405B4D5L),
            longArrayOf(0x314EF5D5L, 0x26E55F3CL),
            longArrayOf(0xE92491B1L, 0x607801C6L),
            longArrayOf(0xF0A5B9D5L, 0x82B2C1FBL),
            longArrayOf(0x06064CF3L, 0xFA51A3DAL),
            longArrayOf(0xDA79FA60L, 0x207CC464L),
            longArrayOf(0xBED20560L, 0x3C3C74F3L),
            longArrayOf(0x89F9C9F8L, 0xD2D14B61L),
            longArrayOf(0x35FA38C6L, 0xBB04A2C1L),
            longArrayOf(0xCB49E3C6L, 0xC5626167L)
        )
        private const val CS64_FACTOR = 0x82893933L
        private const val CS64_ADJUSTMENT0 = 0xF2F90B1AL
        private const val CS64_ADJUSTMENT1 = 0x0B1A0000L

        // ================= 基础工具 =================
        private fun u32(v: Long): Long = v and U32_MASK

        private fun rol16(v: Long): Long {
            val x = v and U32_MASK
            return ((x shl 16) or (x ushr 16)) and U32_MASK
        }

        private fun bytesToHexUpper(bytes: ByteArray): String {
            val hex = "0123456789ABCDEF".toCharArray()
            val out = CharArray(bytes.size * 2)
            for (i in bytes.indices) {
                val v = bytes[i].toInt() and 0xFF
                out[i * 2] = hex[v ushr 4]
                out[i * 2 + 1] = hex[v and 0x0F]
            }
            return String(out)
        }

        private fun wordsLe4Even(data: ByteArray): LongArray {
            val padded = if (data.size % 4 == 0) data
            else data.copyOf(data.size + (4 - data.size % 4))
            if (padded.isEmpty()) return LongArray(0)
            val n = padded.size / 4
            val out = LongArray(if (n % 2 == 0) n else n + 1)
            for (i in 0 until n) {
                val o = i * 4
                out[i] = u32(
                    (padded[o].toLong() and 0xFF) or
                            ((padded[o + 1].toLong() and 0xFF) shl 8) or
                            ((padded[o + 2].toLong() and 0xFF) shl 16) or
                            ((padded[o + 3].toLong() and 0xFF) shl 24)
                )
            }
            return out
        }

        // ================= 自定义 SHA-256 =================

        private fun sigma0(value: Long): Long {
            val v = value and U32_MASK
            return u32(
                (v ushr 7) xor (v shl 25) xor (v ushr 18) xor
                        (v shl 14) xor (v ushr 3)
            )
        }

        private fun sigma1(value: Long): Long {
            val v = value and U32_MASK
            return u32(
                (v ushr 17) xor (v shl 15) xor (v ushr 19) xor
                        (v shl 13) xor (v ushr 10)
            )
        }

        /**
         * 逐行等价移植 _sha256_rounds（不使用 8 轮展开优化，直接 64 轮循环）。
         * 关键：rotE / rotA 必须保持 64 位，不能提前截断。
         */
        private fun sha256Rounds(
            state: LongArray,
            words: LongArray,
            firstRound: Int = 0
        ): LongArray {
            var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
            var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
            val mask = U32_MASK
            val constants =
                if (firstRound == 14) SHA256_BLOCK_CONSTANTS else SHA256_ROUND_CONSTANTS
            for (index in firstRound until 64) {
                val rotE = e * 0x100000001L   // 64 位，不截断
                val rotA = a * 0x100000001L
                val t1 = h +
                        ((rotE ushr 6) xor (rotE ushr 11) xor (rotE ushr 25)) +
                        (g xor (e and (f xor g))) +
                        constants[index] +
                        words[index]
                val t2 = ((rotA ushr 2) xor (rotA ushr 13) xor (rotA ushr 22)) +
                        ((a and b) or (c and (a or b)))
                h = g; g = f; f = e
                e = (d + t1) and mask
                d = c; c = b; b = a
                a = (t1 + t2) and mask
            }
            return longArrayOf(a, b, c, d, e, f, g, h)
        }

        private fun expandSha256Schedule(words: MutableList<Long>, firstWord: Int = 16) {
            for (index in firstWord until 64) {
                val x = words[index - 2]
                val y = words[index - 15]
                val s1 = u32(
                    (x ushr 17) xor (x shl 15) xor (x ushr 19) xor
                            (x shl 13) xor (x ushr 10)
                )
                val s0 = u32(
                    (y ushr 7) xor (y shl 25) xor (y ushr 18) xor
                            (y shl 14) xor (y ushr 3)
                )
                words.add(u32(s1 + words[index - 7] + s0 + words[index - 16]))
            }
        }

        /** 移植自 _sha256_init(data[:7]) */
        private fun sha256Init(data7: ByteArray): LongArray {
            val words = ArrayList<Long>(64)
            for (i in 0 until 14) words.add(0L)
            // words[14] = int.from_bytes(data[:3], "big")
            words.add(
                ((data7[0].toLong() and 0xFF) shl 16) or
                        ((data7[1].toLong() and 0xFF) shl 8) or
                        (data7[2].toLong() and 0xFF)
            )
            // words[15] = int.from_bytes(data[3:7], "big")
            words.add(
                ((data7[3].toLong() and 0xFF) shl 24) or
                        ((data7[4].toLong() and 0xFF) shl 16) or
                        ((data7[5].toLong() and 0xFF) shl 8) or
                        (data7[6].toLong() and 0xFF)
            )
            for (index in 16 until 30) {
                var value = SHA256_SCHEDULE[index - 16]
                if (index - 2 >= 14) {
                    value += if (index == 16)
                        (sigma1(words[index - 2]) xor SHA256_SIGMA1_ADJUSTMENT)
                    else sigma1(words[index - 2])
                }
                if (index - 7 >= 14) value += words[index - 7]
                if (index - 15 >= 14) {
                    value += if (index == 29)
                        (sigma0(words[index - 15]) xor SHA256_SIGMA0_ADJUSTMENT)
                    else sigma0(words[index - 15])
                }
                words.add(u32(value))
            }
            words.add(
                u32(
                    sigma1(words[28]) + words[23] + sigma0(words[15]) +
                            words[14] + SHA256_ADJUSTMENT - SHA256_ROUND_CONSTANTS[14]
                )
            )
            expandSha256Schedule(words, 31)
            val working = sha256Rounds(SHA256_STATE, words.toLongArray(), 14)
            val out = LongArray(8)
            for (i in 0 until 8) out[i] = u32(SHA256_INITIAL[i] + working[i])
            return out
        }

        /** 移植自 _sha256_digest */
        private fun sha256DigestCustom(data: ByteArray): ByteArray {
            require(data.size >= 7) { "Token salt must contain at least seven bytes" }
            val length = 57 + data.size
            val padLen = ((55 - length) % 64 + 64) % 64
            val tail = data.copyOfRange(7, data.size)
            val padded = ByteArray(tail.size + 1 + padLen + 8)
            System.arraycopy(tail, 0, padded, 0, tail.size)
            padded[tail.size] = 0x80.toByte()
            val bitLen = length.toLong() * 8
            for (i in 0 until 8) {
                padded[padded.size - 1 - i] =
                    ((bitLen ushr (8 * i)) and 0xFF).toByte()
            }

            var state = sha256Init(data.copyOfRange(0, 7))
            var offset = 0
            while (offset < padded.size) {
                val words = ArrayList<Long>(64)
                for (i in 0 until 16) {
                    val o = offset + i * 4
                    words.add(
                        ((padded[o].toLong() and 0xFF) shl 24) or
                                ((padded[o + 1].toLong() and 0xFF) shl 16) or
                                ((padded[o + 2].toLong() and 0xFF) shl 8) or
                                (padded[o + 3].toLong() and 0xFF)
                    )
                }
                expandSha256Schedule(words)
                val working = sha256Rounds(state, words.toLongArray())
                for (i in 0 until 8) state[i] = u32(state[i] + working[i])
                offset += 64
            }
            val digest = ByteArray(32)
            for (i in 0 until 8) {
                val v = state[i]
                digest[i * 4] = ((v ushr 24) and 0xFF).toByte()
                digest[i * 4 + 1] = ((v ushr 16) and 0xFF).toByte()
                digest[i * 4 + 2] = ((v ushr 8) and 0xFF).toByte()
                digest[i * 4 + 3] = (v and 0xFF).toByte()
            }
            return digest
        }

        /** 移植自 _cs64_digest */
        private fun cs64Digest(data: ByteArray, digest: ByteArray): LongArray {
            var keyA = u32(
                (digest[0].toLong() and 0xFF) or
                        ((digest[1].toLong() and 0xFF) shl 8) or
                        ((digest[2].toLong() and 0xFF) shl 16) or
                        ((digest[3].toLong() and 0xFF) shl 24)
            )
            var keyB = u32(
                (digest[4].toLong() and 0xFF) or
                        ((digest[5].toLong() and 0xFF) shl 8) or
                        ((digest[6].toLong() and 0xFF) shl 16) or
                        ((digest[7].toLong() and 0xFF) shl 24)
            )
            keyA = keyA or 1L
            keyB = keyB or 1L

            val factors = longArrayOf(u32(keyA * CS64_FACTOR), u32(keyB * CS64_FACTOR))

            val terms = ArrayList<Long>()
            val offsets = ArrayList<Long>()
            for (i in CS64_COEFFICIENTS.indices) {
                terms.add(u32(CS64_COEFFICIENTS[i][0] * factors[i % 2]))
                offsets.add(CS64_COEFFICIENTS[i][1])
            }

            val withZero = ByteArray(data.size + 1)
            System.arraycopy(data, 0, withZero, 1, data.size)
            val words = wordsLe4Even(withZero)
            for ((index, word) in words.withIndex()) {
                val key = if (index % 2 == 0) keyA else keyB
                var term = u32(word * key)
                var offset = u32(
                    rol16(word) * (if (index % 2 == 0) 0xEF0569FBL else 0xC31713DBL)
                )
                if (index == 0) {
                    term = u32(term + u32(CS64_ADJUSTMENT0 * factors[0]))
                    offset = u32(offset + CS64_ADJUSTMENT1)
                }
                terms.add(term)
                offsets.add(offset)
            }

            var swapAcc = 0L
            var swapLast = 0L
            var state = 0L
            var acc = 0L

            var i = 0
            while (i < terms.size) {
                val x = terms[i]
                val y = terms[i + 1]

                var t = rol16(u32(offsets[i] + x))
                var q = u32(t * 0x76F50F34L)
                swapAcc += u32(rol16(q) * 0xC3EFEA97L + q)

                t = rol16(u32(offsets[i + 1] + y))
                q = u32(t * 0x1881CE3CL)
                swapLast = u32(rol16(q) * 0x35BD1EC9L + q)
                swapAcc = u32(swapAcc + swapLast)

                state = u32(state * keyA + x)
                state = u32(rol16(state) * 0xCF98B111L)
                state = u32(rol16(state) * 0x87085B9FL)
                state = u32(rol16(state) * 0x12CEB96DL)
                state = u32(rol16(state) * 0x257E1D83L)
                acc += state

                t = u32(state * keyB + y)
                t = u32(rol16(t) * 0xA27416F5L)
                t = u32(rol16(t) * 0xD38396FFL)
                state = u32(rol16(u32(rol16(t) * 0x7C932B89L)) * 0xBFA49F69L)
                acc = u32(acc + state)

                i += 2
            }
            return longArrayOf(u32(swapLast xor state), u32(swapAcc xor acc))
        }

        /** 移植自 generate_sec_ms_gec_v2 */
        fun generateSecMsGecV2(unixTimestamp: Double? = null): String {
            var ticks = unixTimestamp ?: (System.currentTimeMillis() / 1000.0)
            ticks += WIN_EPOCH.toDouble()
            ticks -= ticks % 300.0
            ticks *= S_TO_NS / 100.0
            val strToHash =
                String.format(Locale.US, "%.0f%s", ticks, TRUSTED_CLIENT_TOKEN)
                    .toByteArray(Charsets.US_ASCII)

            val digest = sha256DigestCustom(strToHash)
            val cs64 = cs64Digest(strToHash, digest)
            val cs64Hex = bytesToHexUpper(
                byteArrayOf(
                    (cs64[0] and 0xFF).toByte(),
                    ((cs64[0] ushr 8) and 0xFF).toByte(),
                    ((cs64[0] ushr 16) and 0xFF).toByte(),
                    ((cs64[0] ushr 24) and 0xFF).toByte(),
                    (cs64[1] and 0xFF).toByte(),
                    ((cs64[1] ushr 8) and 0xFF).toByte(),
                    ((cs64[1] ushr 16) and 0xFF).toByte(),
                    ((cs64[1] ushr 24) and 0xFF).toByte()
                )
            )
            return "$cs64Hex-${bytesToHexUpper(digest)}"
        }

        // ================= 其它工具 =================
        fun generateMuid(): String {
            val bytes = ByteArray(16)
            java.security.SecureRandom().nextBytes(bytes)
            return bytesToHexUpper(bytes)
        }

        fun headersWithMuid(base: Map<String, String>): Map<String, String> {
            val m = HashMap(base)
            m["Cookie"] = "muid=${generateMuid()};"
            return m
        }

        fun connectId(): String = UUID.randomUUID().toString().replace("-", "")

        fun dateToString(): String {
            val sdf = SimpleDateFormat(
                "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'",
                Locale.US
            )
            sdf.timeZone = TimeZone.getTimeZone("UTC")
            return sdf.format(Date())
        }

        fun removeIncompatibleCharacters(text: String): String {
            val sb = StringBuilder(text.length)
            for (ch in text) {
                val c = ch.code
                if ((c in 0..8) || (c in 11..12) || (c in 14..31)) sb.append(' ')
                else sb.append(ch)
            }
            return sb.toString()
        }

        fun xmlEscape(s: String): String {
            val sb = StringBuilder(s.length)
            for (ch in s) {
                when (ch) {
                    '&' -> sb.append("&amp;")
                    '<' -> sb.append("&lt;")
                    '>' -> sb.append("&gt;")
                    else -> sb.append(ch)
                }
            }
            return sb.toString()
        }
    }

    // ================= 实例部分 =================
    private var audioOutputStream = PipedOutputStream()
    private var audioInputStream = PipedInputStream(audioOutputStream, 8192)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var currentWss: WebSocket? = null

    private fun initStream() {
        try {
            audioOutputStream = PipedOutputStream()
            audioInputStream = PipedInputStream(audioOutputStream, 8192)
        } catch (e: Exception) {
            Log.e(TAG, "初始化管道流失败", e)
        }
    }

    fun synthesizeText(
        speakText: String,
        rate: Int,
        voice: String = DEFAULT_VOICE
    ): InputStream {
        initStream()
        val cleaned = xmlEscape(removeIncompatibleCharacters(speakText))
        val ssml = mkSSML(cleaned, voice, processRate(rate))
        openWssAndSend(ssml)
        return audioInputStream
    }

    private fun openWssAndSend(ssml: String) {
        currentWss?.let {
            try { it.close(1000, "normal"); it.cancel() } catch (_: Exception) {}
        }

        val secMsGec = generateSecMsGecV2()
        val connectionId = connectId()

        val wsUrl = buildString {
            append("wss://").append(BASE_URL).append(WSS_PATH)
            append("?TrustedClientToken=").append(TRUSTED_CLIENT_TOKEN)
            append("&ConnectionId=").append(connectionId)
            append("&Sec-MS-GEC=").append(secMsGec)
            append("&Sec-MS-GEC-Version=").append(SEC_MS_GEC_VERSION)
        }

        val reqBuilder = Request.Builder().url(wsUrl)
        headersWithMuid(WSS_HEADERS).forEach { (k, v) -> reqBuilder.header(k, v) }
        val request = reqBuilder.build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket onOpen")
                currentWss = webSocket
                sendSpeechConfig(webSocket)
                sendSSMLMessage(webSocket, ssml)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:turn.end")) {
                    try { audioOutputStream.close() } catch (_: IOException) {}
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    val message = bytes.toByteArray()
                    if (message.size < 2) return
                    val headerLength =
                        ((message[0].toInt() and 0xFF) shl 8) or
                                (message[1].toInt() and 0xFF)
                    if (headerLength > message.size) return
                    val audioData = message.copyOfRange(headerLength + 2, message.size)
                    if (audioData.isNotEmpty()) {
                        audioOutputStream.write(audioData)
                        audioOutputStream.flush()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "onMessage(binary) error", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket onFailure: $t, response=$response")
            }
        }

        client.newWebSocket(request, listener)
    }

    private fun mkSSML(
        escapedText: String, voice: String, rate: String,
        pitch: String = "+0Hz", volume: String = "+0%"
    ): String =
        "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
                "<voice name='$voice'><prosody pitch='$pitch' rate='$rate' volume='$volume'>" +
                escapedText + "</prosody></voice></speak>"

    private fun sendSpeechConfig(ws: WebSocket) {
        val speechConfig =
            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{" +
                    "\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"true\"" +
                    "},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}"
        ws.send(
            "X-Timestamp:${dateToString()}\r\n" +
                    "Content-Type:application/json; charset=utf-8\r\n" +
                    "Path:speech.config\r\n\r\n" + speechConfig + "\r\n"
        )
    }

    private fun sendSSMLMessage(ws: WebSocket, ssml: String) {
        ws.send(
            "X-RequestId:${connectId()}\r\n" +
                    "Content-Type:application/ssml+xml\r\n" +
                    "X-Timestamp:${dateToString()}Z\r\n" +
                    "Path:ssml\r\n\r\n" + ssml
        )
    }

    private fun processRate(rate: Int): String {
        val offset = rate - 12
        return if (offset > 0) "+$offset%" else "$offset%"
    }

    fun release() {
        currentWss?.cancel()
        try { audioOutputStream.close() } catch (_: IOException) {}
    }
}