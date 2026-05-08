package io.legado.app.service

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Random
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.math.min

/**
 * 豆包TTS音频合成类
 * 移除了WSS复用和监听服务相关逻辑，只保留核心的音频合成功能
 */
class DouBaoFetch {
    // 初始化OkHttpClient，设置超时时间
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    private val deviceId: String
    private val webId: String
    private val DEFAULT_VOICE = "taozi"
    private val DEFAULT_FORMAT = "aac"
    private val DEFAULT_PITCH = 0
    private val DEFAULT_RATE = 0

    // 音频流相关
    private var audioOutputStream = PipedOutputStream()
    private var audioInputStream = PipedInputStream(audioOutputStream, 8192)
    init {
        // 生成设备ID和WebID
        this.deviceId = generateDeviceId()
        this.webId = generateWebId()


        // 初始化音频管道流
        initAudioStream()
    }

    /**
     * 生成设备ID
     */
    private fun generateDeviceId(): String {
        val random = Random()
        val min = 7400000000000000000L
        val max = 7499999999999999999L
        return (min + (random.nextDouble() * (max - min)).toLong()).toString()
    }

    /**
     * 生成Web ID
     */
    private fun generateWebId(): String {
        val random = Random()
        val min = 7400000000000000000L
        val max = 7499999999999999999L
        return (min + (random.nextDouble() * (max - min)).toLong()).toString()
    }

    /**
     * 初始化音频管道流
     */
    private fun initAudioStream() {
        try {
            audioOutputStream = PipedOutputStream()
            audioInputStream = PipedInputStream(audioOutputStream, 8192) // 8KB缓冲区
        } catch (e: IOException) {
            Log.e(TAG, "初始化音频管道流失败", e)
        }
    }

    /**
     * 移除文本中的特殊字符
     */
    fun removeSpecialCharacters(text: String?): String {
        if (text == null || text.isEmpty()) {
            return ""
        }
        // 解码URL编码的字符
        val decodedText = URLDecoder.decode(text, StandardCharsets.UTF_8)
        // 保留中文、常用标点、字母数字和空格
        val pattern = Pattern.compile("[^\\w\\s\u4e00-\u9fff，。！？；：、（）《》【】“”‘’]")
        return pattern.matcher(decodedText).replaceAll("")
    }

    /**
     * 构建WebSocket URL
     */
    private fun buildWsUrl(speaker: String, format: String, speed: Int, pitch: Int): String {
        // 解析音色（支持简称或完整ID）
        val speakerId = SPEAKERS.getOrDefault(speaker, speaker)
        // 构建查询参数
        val params = StringBuilder()
        params.append("speaker=").append(speakerId)
            .append("&format=").append(format)
            .append("&speech_rate=").append((speed * 100))
            .append("&pitch=").append((pitch * 100))
            .append("&version_code=20800")
            .append("&language=zh")
            .append("&device_platform=web")
            .append("&aid=497858")
            .append("&real_aid=497858")
            .append("&pkg_type=release_version")
            .append("&device_id=").append(deviceId)
            .append("&pc_version=2.50.6")
            .append("&web_id=").append(webId)
            .append("&tea_uuid=").append(webId)
            .append("&region=CN")
            .append("&sys_region=CN")
            .append("&samantha_web=1")
            .append("&use-olympus-account=1")
            .append("&web_tab_id=").append(UUID.randomUUID().toString())

        return WS_URL + "?" + params.toString()
    }



    // 1. 简化回调接口：仅保留onFailure方法
    interface AudioGenFailureCallback {
        fun onFailure(error: Throwable, message: String)
    }
    /**
     * 生成音频，返回音频输入流
     * @param cookie 豆包Cookie
     * @param text 要转换的文本
     * @param voice 音色
     * @param rate 语速 (-1.0 ~ 1.0)
     * @param pitch 音调 (-1.0 ~ 1.0)
     * @param format 音频格式
     * @return 音频输入流
     */
    fun genAudio(
        failureCallback: AudioGenFailureCallback, // 仅失败回调
        cookie: String,
        text: String,
        voice: String = DEFAULT_VOICE,
        rate: Int = DEFAULT_RATE,
        pitch: Int = DEFAULT_PITCH,
        format: String = DEFAULT_FORMAT
    ): InputStream{

        val cleanedText = removeSpecialCharacters(text)
        // 重新初始化音频流
        initAudioStream()


        // 构建WebSocket URL
        val wsUrl = buildWsUrl(voice, format, rate, pitch)
        Log.d(TAG, "WebSocket URL: $wsUrl")


        try {
            // 构建请求
            val request = Request.Builder()
                .url(wsUrl)
                .header("Accept-Language", "en,zh-CN;q=0.9,zh;q=0.8")
                .header("Cache-Control", "no-cache")
                .header("Pragma", "no-cache")
                .header("Origin", "https://www.doubao.com")
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36"
                )
                .header("Cookie", cookie)
                .build()


            // 创建WebSocket监听器
            val listener: WebSocketListener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    super.onOpen(webSocket, response)
                    Log.d(TAG, "WebSocket连接成功")

                    try {
                        // 发送文本消息
                        val textMsg = JSONObject()
                        textMsg.put("event", "text")
                        textMsg.put("text", cleanedText)
                        webSocket.send(textMsg.toString())


                        // 发送结束信号
                        val finishMsg = JSONObject()
                        finishMsg.put("event", "finish")
                        webSocket.send(finishMsg.toString())

                        Log.d(TAG, "已发送文本和结束信号")
                    } catch (e: JSONException) {
                        Log.e(TAG, "构建JSON消息失败", e)
                        webSocket.close(1001, "JSON构建失败")
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    super.onMessage(webSocket, text)
                    try {
                        val data = JSONObject(text)
                        val event = data.optString("event", "")

                        if ("open_success" == event) {
                            Log.d(TAG, "WebSocket连接成功")
                        } else if ("sentence_start" == event) {
                            val readableText = data.optJSONObject("sentence_start_result")
                                .optString("readable_text", "")
                            Log.d(
                                TAG,
                                "开始合成句子: " + readableText.substring(
                                    0,
                                    min(readableText.length, 50)
                                ) + "..."
                            )
                        } else if ("error" == event) {
                            val errorMsg = data.optString("message", "未知错误")
                            Log.e(TAG, "合成错误: $errorMsg")
                            webSocket.close(1001, errorMsg)
                        } else if (data.optInt("code", 0) != 0) {
                            val errorMsg = "错误码 " + data.optInt("code") + ": " + data.optString(
                                "message",
                                "未知错误"
                            )
                            Log.e(TAG, errorMsg)
                            webSocket.close(1001, errorMsg)
                        }
                    } catch (e: JSONException) {
                        Log.e(TAG, "解析JSON消息失败", e)
                    }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    super.onMessage(webSocket, bytes)
                    try {
                        Log.d(TAG, "收到bytes: "+bytes.size.toString())
                        // 写入音频数据到输出流
                        audioOutputStream.write(bytes.toByteArray())
                        audioOutputStream.flush()
                    } catch (e: IOException) {
                        Log.e(TAG, "写入音频数据失败", e)
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    super.onClosing(webSocket, code, reason)
                    Log.d(
                        TAG,
                        "WebSocket正在关闭: $code - $reason"
                    )
                    try {
                        audioOutputStream.close()
                    } catch (e: IOException) {
                        Log.e(TAG, "关闭音频输出流失败", e)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    super.onFailure(webSocket, t, response)
                    Log.e(TAG, "WebSocket连接失败", t)
                    try {
                        audioOutputStream.close()
                    } catch (e: IOException) {
                        Log.e(TAG, "关闭音频输出流失败", e)
                    }
                    // 核心：触发WebSocket失败回调
                    failureCallback.onFailure(t, "WebSocket连接失败")
                }
            }
            // 建立WebSocket连接
            client.newWebSocket(request, listener)
        } catch (e: Exception) {
            Log.e(TAG, "生成音频失败", e)
            failureCallback.onFailure(e, "生成音频失败")
        }
        return audioInputStream
    }

    /**
     * 释放资源
     */
    fun release() {
        try {
            audioOutputStream.close()
            audioInputStream.close()
        } catch (e: IOException) {
            Log.e(TAG, "释放资源失败", e)
        }
    }

    companion object {
        private const val TAG = "TTSDouBaoFetch"
        private const val WS_URL = "wss://ws-samantha.doubao.com/samantha/audio/tts"

        // 常用语音角色映射
        private val SPEAKERS: HashMap<String?, String?> = object : HashMap<String?, String?>() {
            init {
                // 女声
                put("taozi", "zh_female_taozi_conversation_v4_wvae_bigtts")
                put("shuangkuai", "zh_female_shuangkuai_emo_v3_wvae_bigtts")
                put("tianmei", "zh_female_tianmei_conversation_v4_wvae_bigtts")
                put("qingche", "zh_female_qingche_moon_bigtts")


                // 男声
                put("yangguang", "zh_male_yangguang_conversation_v4_wvae_bigtts")
                put("chenwen", "zh_male_chenwen_moon_bigtts")
                put("rap", "zh_male_rap_mars_bigtts")


                // 多语言
                put("en_female", "en_female_sarah_conversation_bigtts")
                put("en_male", "en_male_adam_conversation_bigtts")
            }
        }
    }
}