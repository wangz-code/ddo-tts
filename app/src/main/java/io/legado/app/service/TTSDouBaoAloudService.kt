package io.legado.app.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.ReadBook
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.ArrayList
import java.util.Timer
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.collections.set
import kotlin.concurrent.schedule

/**
 * 豆包
 */
@SuppressLint("UnsafeOptInUsageError")
class TTSDouBaoAloudService : BaseReadAloudService(), Player.Listener {

    private val exoPlayer: ExoPlayer by lazy {
        val dataSourceFactory = ByteArrayDataSourceFactory(audioCache)
        val mediaSourceFactory = ProgressiveMediaSource.Factory(dataSourceFactory)
        ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
    }
    private val tag = "TTSDouBaoService"
    private var speechRate: Int = AppConfig.speechRatePlay + 5
    private var downloadTask: Coroutine<*>? = null
    private val downloadTaskActiveLock = Mutex()
    private var preDownloadTask: Coroutine<*>? = null
    private val preDownloadTaskActiveLock = Mutex()

    private var playIndexJob: Job? = null
    private var playErrorNo = 0
    private var isReloadAudio = 0
    private val doubaoFetch = DouBaoFetch()
    private val audioCache = HashMap<String, ByteArray>()
    private val audioCacheList = arrayListOf<String>()
    private var previousMediaId = ""
    private val requestTime = 0.05  // 请求时间
    private val readTime = 0.2   // 每秒朗读字数,
    private var readTextSize = 301  // 起步文字数
    private val maxText = 700  // 最大文字数

    private val cacheKey = "tts_doubao_cookie" // 自定义缓存key，用于唯一标识这个数据
    private var doubaoCookie = ""// 读取缓存，默认值"豆包"
    private val silentBytes: ByteArray by lazy {
        resources.openRawResource(R.raw.silent_sound).readBytes()
    }

    // 核心方法2：从SharedPreferences读取数据
    private fun getSharedPrefValue(context: Context?, defaultValue: String = ""): String {
        if (context == null) {
            return defaultValue
        }
        val sp = context.getSharedPreferences("TTS_CONFIG", Context.MODE_PRIVATE)
        return sp.getString(cacheKey, defaultValue) ?: defaultValue // 防止null
    }

    override fun onCreate() {
        super.onCreate()
        doubaoCookie = getSharedPrefValue(this)
        Log.d(tag, "doubaoCookie= $doubaoCookie")
        exoPlayer.addListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadTask?.cancel()
        exoPlayer.release()
        doubaoFetch.release()
        removeAllCache()
    }

    override fun play() {
        pageChanged = false
        exoPlayer.stop()
        if (!requestFocus()) return
        Log.i(tag, "playSize===> ${contentList.size} ")

        if (contentList.isEmpty()) {
            AppLog.putDebug("朗读列表为空")
            ReadBook.readAloud()
        } else {
            downloadAndPlayAudios()
            super.play()

        }
    }

    override fun playStop() {
        exoPlayer.stop()
        playIndexJob?.cancel()
    }

    private fun updateNextPos() {
        readAloudNumber += contentList[nowSpeak].length + 1 - paragraphStartPos
        paragraphStartPos = 0
        if (nowSpeak < contentList.lastIndex) {
            nowSpeak++
        } else {
            nextChapter()
        }
    }

    /**
     * 字符串扩展方法：统计仅汉字+英文字母的数量
     */
    private fun countChineseAndEnglish(input: String): Int {
        if (input.isBlank()) return 0

        var count = 0
        // 遍历每个字符，判断是否为汉字或英文字母
        for (char in input) {
            // 1. 汉字：Unicode 范围 0x4E00 ~ 0x9FA5（覆盖常用简体/繁体汉字）
            // 2. 英文字母：a-z 或 A-Z
            val isChinese = char in '\u4E00'..'\u9FA5'
            val isEnglish = char in 'a'..'z' || char in 'A'..'Z'

            if (isChinese || isEnglish) {
                count++
            }
        }
        return count
    }


    private fun downloadAndPlayAudios() {
        if (doubaoCookie.isEmpty()) {
            pauseReadAloud(true)
            Toast.makeText(this, "兄弟没有Cookie让我很难办啊,先添加 cookie", Toast.LENGTH_LONG)
                .show()
            throw IllegalArgumentException("没找到cookie")
        }

        downloadTask?.cancel()
        removeUnUseCache()
        exoPlayer.clearMediaItems()
        Log.d(tag, "clearMediaItems audioCache Size= ${audioCache.size}")
        Log.d(tag, "nowSpeak  $nowSpeak")


        downloadTask = execute {
            downloadTaskActiveLock.withLock {
                var readText = ""
                var delayTime = 0
                // 初始化线程安全的动态列表（支持动态添加/删除）
                val safeList = CopyOnWriteArrayList<String>()
                Log.i(tag, "普通下载contentList size===> ${contentList.size}")
                for (index in contentList.indices) {
                    ensureActive()
                    var content = contentList[index]
                    if (index < nowSpeak) {
                        continue
                    }
                    if (paragraphStartPos > 0 && index == nowSpeak) {
                        content = content.substring(paragraphStartPos)
                    }
                    val fileName = md5SpeakFileName(content)

                    readText += content
                    if (index < contentList.lastIndex && readText.length < readTextSize) {
                        safeList.add(content)
                        continue
                    }

                    val speakText = readText.replace(AppPattern.notReadAloudRegex, "")
                    if (!isCached(fileName)) {
                        Log.i(
                            tag,
                            "无缓存开始下载===>字数 $readTextSize   MD5:$fileName $speakText "
                        )
                        runCatching {
                            getSpeakStream(speakText, fileName)
                        }.onFailure {
                            Log.e(tag, "downloadAndPlayAudios runCatch onFailure")
                            AppLog.put("朗读下载出错\n${it.localizedMessage}", it, true)
                            when (it) {
                                is CancellationException -> Unit
                                else -> pauseReadAloud()
                            }
                            return@execute
                        }
                        Log.i(tag, "下载完毕===>  MD5:$fileName $speakText ")
                        val textCount = readText.length
                        // 朗读速度约0.2 秒每个字, 0.03 是请求时间, 留足请求时间,保持连贯
                        delayTime = (textCount * readTime - textCount * requestTime).toInt()
                        Log.d(tag, "已添加音频[$fileName]，暂停 $delayTime 秒后继续下一个...")
                        if (readTextSize < maxText) readTextSize += 200
                    } else {
                        delayTime = 0;
                        Log.i(tag, "有缓存跳过===> MD5:$fileName $speakText ")
                    }
                    readText = ""
                    val mediaItem = MediaItem.Builder()
                        .setUri("memory://media/$fileName".toUri())
                        .setMediaId(fileName)
                        .build()
                    launch(Main) {
                        exoPlayer.addMediaItem(mediaItem)
                    }

                    // 延迟更新朗读进度文字背景色
                    lifecycleScope.launch {
                        // 关键：先获取遍历瞬间的快照，避免遍历原列表时修改导致的异常/错位
                        val listSnapshot = safeList.toList()
                        for (elem in listSnapshot) {
                            safeList.remove(elem)
                            delay(((elem.length * readTime) * 1000).toLong())
                            updateNextPos()
                            upPlayPos()
                            Log.i(tag, "延迟更新===> 字数:${elem.length}  内容:$elem ")
                        }
                    }

                    // 判断是否快要读完本章, 启动预下载
                    if (contentList.lastIndex == index) {
                        Log.d(tag, "即将读完, ${(delayTime / 4)}秒后启动预下载")
                        lifecycleScope.launch {
                            delay((delayTime / 4 * 1000).toLong())
                            preDownloadAudios()
                        }
                    }
                    delay((delayTime * 1000).toLong()) // 协程挂起秒（非阻塞，不卡线程）
                }
            }

        }.onError {
            Log.d(tag, "朗读下载出错")
            AppLog.put("朗读下载出错\n${it.localizedMessage}", it, true)
        }
    }

    private fun preDownloadAudios() {
        Log.i(tag, "准备预下载音频===> ${ReadBook.nextTextChapter}")
        val textChapter = ReadBook.nextTextChapter ?: return
        val preContentList =
            textChapter.getNeedReadAloud(0, readAloudByPage, 0, 1).splitToSequence("\n")
                .filter { it.isNotEmpty() }.toList()
//        val preContentList = textChapter.getNeedReadAloud(0, readAloudByPage, 0)
//            .split("\n")
//            .filter { it.isNotEmpty() }
        Log.i(tag, "开启预下载任务===> ${preContentList.size}")
        preDownloadTask?.cancel()
        preDownloadTask = execute {
            preDownloadTaskActiveLock.withLock {
                var readText = ""
                for (index in preContentList.indices) {
                    coroutineContext.ensureActive()
                    val content = preContentList[index]
                    val fileName = md5SpeakFileName(content)
                    readText += content
                    if (index < preContentList.lastIndex && readText.length < readTextSize) continue
                    val speakText = readText.replace(AppPattern.notReadAloudRegex, "")
                    Log.i(tag, "预下载字数:$readTextSize MD5:$fileName $speakText")
                    Log.i(
                        tag,
                        "实际字数:${readText.length}, 重置 readTextSize = ${readText.length - 1} "
                    )
                    if (readText.length < readTextSize) readTextSize = readText.length - 1
                    runCatching {
                        getSpeakStream(speakText, fileName)
                        Log.d(tag, "预下载 已添加音频 $fileName ，结束预下载  $speakText")
                    }.onFailure {
                        Log.e(tag, "预下载 runCatch onFailure")
                        AppLog.put("预下载下载出错\n${it.localizedMessage}", it, true)
                    }
                    Log.d(tag, "预下载完毕")
                    preDownloadTask?.cancel()
                }
            }
        }.onError {
            Log.d(tag, "预下载出错")
        }
    }

    private suspend fun getSpeakStream(speakText: String, fileName: String): String {
        if (speakText.isEmpty()) {
            cacheAudio(fileName, silentBytes)
            return "fail"
        }

        val audioFailureCallback: DouBaoFetch.AudioGenFailureCallback = object : DouBaoFetch.AudioGenFailureCallback {
            override fun onFailure(error: Throwable, message: String) {
                Log.e(tag, "外部感知到音频生成失败：$message", error)
                pauseReadAloud()
            }
        }

        try {

            return withContext(Dispatchers.IO) {
                val inputStream = doubaoFetch.genAudio(audioFailureCallback, doubaoCookie, speakText)
                cacheAudio(fileName, inputStream)
                "success"
            }
        } catch (e: Exception) {
            Log.i(tag, "edgeSpeakFetch失败: $e")
            cacheAudio(fileName, silentBytes)
        }
        return "fail"
    }

    private fun md5SpeakFileName(content: String): String {
        return MD5Utils.md5Encode16(MD5Utils.md5Encode16("$speechRate|$content"))
    }

    override fun pauseReadAloud(abandonFocus: Boolean) {
        super.pauseReadAloud(abandonFocus)
        Log.i(tag, "pauseReadAloud")
        kotlin.runCatching {
            playIndexJob?.cancel()
            exoPlayer.pause()
        }

    }

    override fun resumeReadAloud() {
        super.resumeReadAloud()
        kotlin.runCatching {
            if (pageChanged) {
                play()
            } else {
                exoPlayer.play()
                upPlayPos()
            }
        }
    }

    private fun upPlayPos() {
        playIndexJob?.cancel()
        val textChapter = textChapter ?: return
        playIndexJob = lifecycleScope.launch {
            upTtsProgress(readAloudNumber + 1)
            if (exoPlayer.duration <= 0) {
                return@launch
            }
            val speakTextLength = if (nowSpeak in contentList.indices) {
                contentList[nowSpeak].length
            } else {
                Log.e(
                    tag,
                    "nowSpeak 越界: nowSpeak=$nowSpeak, contentList.size=${contentList.size}"
                )
                contentList.size - 1
            }
            if (speakTextLength <= 0) {
                return@launch
            }
            val sleep = exoPlayer.duration / speakTextLength
            val start = speakTextLength * exoPlayer.currentPosition / exoPlayer.duration
            for (i in start..contentList[nowSpeak].length) {
                if (pageIndex + 1 < textChapter.pageSize && readAloudNumber + i > textChapter.getReadLength(
                        pageIndex + 1
                    )
                ) {
                    pageIndex++
                    ReadBook.moveToNextPage()
                    upTtsProgress(readAloudNumber + i.toInt())
                }
                delay(sleep)
            }
        }
    }

    /**
     * 更新朗读速度
     */
    override fun upSpeechRate(reset: Boolean) {
        downloadTask?.cancel()
        exoPlayer.stop()
        speechRate = AppConfig.speechRatePlay + 5
        downloadAndPlayAudios()
    }

    /**
     * 重新下载本章节
     */
    private fun reloadAudio() {
        removeAllCache()
        previousMediaId = ""
        downloadTask?.cancel()
        exoPlayer.stop()
        downloadAndPlayAudios()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        super.onPlaybackStateChanged(playbackState)
        when (playbackState) {
            Player.STATE_IDLE -> {
                // 空闲
            }

            Player.STATE_BUFFERING -> {
                // 缓冲中
            }

            Player.STATE_READY -> {
                // 准备好
                if (pause) return
                exoPlayer.play()
                upPlayPos()
            }

            Player.STATE_ENDED -> {
                // 结束
                playErrorNo = 0
                isReloadAudio = 0
                updateNextPos()
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
                Log.d(tag, "播放完毕==> 更新 updateNextPos")
            }
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        when (reason) {
            Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED -> {
                if (!timeline.isEmpty && exoPlayer.playbackState == Player.STATE_IDLE) {
                    exoPlayer.prepare()
                }
            }

            else -> {}
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        Log.d(tag, "onMediaItemTransition $reason")
        if (mediaItem?.mediaId.toString().isNotEmpty()) {
            previousMediaId = mediaItem?.mediaId.toString()
        }
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            playErrorNo = 0
            isReloadAudio = 0

        }
        updateNextPos()
        upPlayPos()
    }

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)
        // 打印详细错误信息（日志中搜索 "Source error detail"）
        Log.e(tag, "Source error detail: ${error.cause?.message}", error)
        // 错误类型判断（如格式不支持、IO 错误等）
        Log.e(tag, "playErrorNo errorCode ${error.errorCode}")
        Log.e(tag, "playErrorNo: $playErrorNo")
        AppLog.put("朗读错误\n${contentList[nowSpeak]}", error)
        playErrorNo++
        if (playErrorNo >= 5) {
            toastOnUi("朗读连续5次错误, 最后一次错误代码(${error.localizedMessage})")
            AppLog.put("朗读连续5次错误, 最后一次错误代码(${error.localizedMessage})", error)
            // 把这一章节的音频重新加载
            if (isReloadAudio == 0) {
                playErrorNo = 0
                isReloadAudio++
                Timer().schedule(2000) {
                    reloadAudio()
                    Log.e(tag, "重试本章节")
                }
            } else {
                pauseReadAloud()
            }
        } else {
            if (exoPlayer.hasNextMediaItem()) {
                exoPlayer.seekToNextMediaItem()
                exoPlayer.prepare()
            } else {
                exoPlayer.clearMediaItems()
                updateNextPos()
            }
        }
    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<TTSEdgeAloudService>(actionStr)
    }

    private fun cacheAudio(key: String, inputStream: InputStream): Boolean {
        return try {
            audioCache[key] = inputStream.toByteArray()
            audioCacheList.add(key)
            Log.d(tag, "成功缓存 cacheAudio: $key")
            true
        } catch (e: Exception) {
            Log.d(tag, "缓存失败 cacheAudio: $key")
            e.printStackTrace()
            false
        }
    }

    private fun cacheAudio(key: String, byteArray: ByteArray): Boolean {
        audioCache[key] = byteArray
        audioCacheList.add(key)
        return true
    }

    private fun removeCache(key: String) {
        audioCache.remove(key)
    }

    // 移除不会再使用的缓存, 0 ~ 上次朗读的文件下标
    private fun removeUnUseCache() {
        Log.d(tag, "removeUnUseCache previousMediaId: $previousMediaId")
        if (previousMediaId.isEmpty()) return
        val targetIndex = audioCacheList.indexOf(previousMediaId)
        if (targetIndex <= 0) return // 索引为0或-1时无需处理（-1：未找到；0：无前置元素）

        Log.d(tag, "removeUnUseCache targetIndex: $targetIndex")

        val itemsToRemove = audioCacheList.subList(0, targetIndex)
        itemsToRemove.forEach {
            Log.d(tag, "批量移除: $it")
            removeCache(it)
        }
        itemsToRemove.clear()
        Log.d(tag, "removeUnUseCache: ${audioCacheList.size}")

    }

    private fun removeAllCache() {
        audioCache.clear()
        audioCacheList.clear()
    }

    private fun isCached(key: String) = audioCache.containsKey(key)

    private fun InputStream.toByteArray(): ByteArray {
        val output = ByteArrayOutputStream()
        // 使用use自动关闭流
        this.use { input ->
            output.use { out ->
                input.copyTo(out) // 复制数据到输出流
            }
        }
        return output.toByteArray()
    }
}

