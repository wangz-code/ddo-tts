package io.legado.app.service

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import android.util.Base64

class MiMoFetch(

) {
    companion object {
        private const val TAG = "MiMoFetch"
        private const val MIMO_API_URL = "https://api.xiaomimimo.com/v1/chat/completions"
        private const val DEFAULT_VOICE = "Chloe"
        private const val DEFAULT_STYLE = "自然、口语化的对话语调"
        private const val DEFAULT_APIKEY = ""

    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()


    /**
     * 合成语音
     * @param text    要朗读的文本
     * @param rate    语速 0~100（参考 Edge 的习惯，默认 25 为正常）
     * @param voice   音色名称
     * @param style   音色风格描述
     * @return        音频 InputStream（WAV）
     */
    fun synthesizeText(
        text: String,
        rate: Int = 25,
        voice: String = DEFAULT_VOICE,
        style: String = DEFAULT_STYLE,
        apiKey:String = DEFAULT_APIKEY
    ): InputStream {
        val cleanedText = removeSpecialCharacters(text)
        val pace = processRate(rate)
        val curStyle = "$style，$pace"

        Log.i(TAG, "合成请求 → voice=$voice, style=$curStyle, text长度=${cleanedText.length}")
        var isCust = voice =="自定义"
        val payload = JSONObject().apply {

            put("model", if (isCust) "mimo-v2.5-tts-voicedesign" else "mimo-v2.5-tts")

            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", if (isCust) curStyle else pace)
                })
                put(JSONObject().apply {
                    put("role", "assistant")
                    put("content", cleanedText)
                })
            })

            put("audio", JSONObject().apply {
                put("format", "wav")
                if(!isCust)  put("voice", voice)
            })
        }

        val requestBody = payload.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(MIMO_API_URL)
            .addHeader("api-key", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "无错误信息"
                throw RuntimeException("MiMo 请求失败: ${response.code} - $errorBody")
            }

            val result = JSONObject(response.body!!.string())
            val audioB64 = result
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getJSONObject("audio")
                .getString("data")

            val audioBytes = Base64.decode(audioB64, Base64.DEFAULT)
            Log.i(TAG, "合成成功，音频大小: ${audioBytes.size} bytes")
            return ByteArrayInputStream(audioBytes)
        }
    }

    /** 移除特殊字符，保留中文、英文、常用标点 */
    private fun removeSpecialCharacters(text: String): String {
        return text.replace(Regex("[^\\w\\s\u4e00-\u9fff，。！？；：、（）《》【】“”‘’]"), "")
    }

    /**
     * 把 rate（0~100）转换成自然语言语速描述
     * 以 25 为正常语速基准（与 Python 版保持一致）
     */
    private fun processRate(rate: Int): String {
        val rateValue = rate.coerceIn(0, 100)
        val diff = rateValue - 25
        return when {
            diff <= -15 -> "非常慢的语速"
            diff <= -5  -> "偏慢的语速"
            diff >= 15  -> "非常快的语速"
            diff >= 5   -> "偏快的语速"
            else        -> "正常语速"
        }
    }

    fun release() {
        // HTTP 方式无需特殊释放，预留接口保持与 Edge 版一致
        Log.i(TAG, "MiMoFetch 已释放")
    }
}