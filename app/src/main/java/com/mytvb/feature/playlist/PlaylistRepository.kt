package com.mytvb.feature.playlist

import android.content.Context
import com.google.gson.reflect.TypeToken
import com.google.gson.JsonParser
import com.mytvb.core.common.json.GsonHolder
import com.mytvb.core.common.log.AppLog
import com.mytvb.feature.marmot.domain.MarmotModels.Live
import com.mytvb.feature.marmot.domain.MarmotModels.Vod
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 节目单仓库：启动时从网络下载 tv.json，成功则缓存到 filesDir/playlist/tv.json，
 * 失败则沿用缓存；无缓存时空列表（不兜底内置数据）。
 *
 * 数据源（gh-proxy 优先，直连 raw 兜底；TV 国内网络直连 GitHub 大概率超时）。
 * 解析支持两种格式：
 * 1. 标准结构 `{ data: [ { tag, name, vods: [ { name, url } ] } ] }`（与 MarmotModels 对齐）
 * 2. 扁平结构 `{ "节目名": "URL" }`（宽松解析，容忍尾逗号，合并为单分组）
 */
object PlaylistRepository {

    private const val TAG = "PlaylistRepo"
    private const val RELATIVE_PATH = "playlist/tv.json"
    private const val TIMEOUT_MS = 10_000L

    private val SOURCES = listOf(
        "https://gh-proxy.com/https://raw.githubusercontent.com/ai-wen/tvb/main/tv.json",
        "https://raw.githubusercontent.com/ai-wen/tvb/main/tv.json"
    )

    private val playlistState = MutableStateFlow<List<Live>>(emptyList())

    /** 节目单数据（空列表 = 无缓存且下载失败）。 */
    val playlist: StateFlow<List<Live>> = playlistState.asStateFlow()

    private val downloadInFlight = AtomicBoolean(false)

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            .callTimeout(TIMEOUT_MS * 2, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
    }

    /**
     * 确保节目单可用：先加载本地缓存，再尝试下载最新版（成功后覆盖缓存并更新流）。
     * 重复调用只触发一次下载，后续调用幂等。
     */
    suspend fun ensureFresh(context: Context) {
        val appContext = context.applicationContext
        withContext(Dispatchers.IO) {
            loadCachedIntoState(appContext)
            if (!downloadInFlight.compareAndSet(false, true)) {
                return@withContext
            }
            try {
                val body = downloadAny()
                if (body == null) {
                    AppLog.w(TAG, "节目单下载失败（${SOURCES.size} 个源均不可达），沿用缓存 ${playlistState.value.sumOf { it.vods.size }} 条")
                    return@withContext
                }
                val parsed = parse(body)
                if (parsed.isEmpty()) {
                    AppLog.w(TAG, "节目单解析失败（无可显示条目），沿用缓存")
                    return@withContext
                }
                saveFile(appContext, body)
                playlistState.value = parsed
                AppLog.i(TAG, "节目单下载成功：${parsed.size} 个分组，${parsed.sumOf { it.vods.size }} 条节目")
            } finally {
                downloadInFlight.set(false)
            }
        }
    }

    private fun loadCachedIntoState(context: Context) {
        if (playlistState.value.isNotEmpty()) return
        val cached = parse(readFile(context))
        if (cached.isNotEmpty()) {
            playlistState.value = cached
            AppLog.i(TAG, "使用缓存节目单：${cached.size} 个分组")
        }
    }

    private fun readFile(context: Context): String? {
        return runCatching {
            File(context.filesDir, RELATIVE_PATH)
                .takeIf { it.exists() && it.length() > 0L }
                ?.readText()
        }.getOrNull()
    }

    private fun saveFile(context: Context, body: String) {
        runCatching {
            val target = File(context.filesDir, RELATIVE_PATH)
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".part")
            tmp.writeText(body)
            if (target.exists()) target.delete()
            tmp.renameTo(target)
        }.onFailure {
            AppLog.w(TAG, "节目单缓存写入失败: ${it.message}")
        }
    }

    private fun downloadAny(): String? {
        for (url in SOURCES) {
            val body = runCatching {
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android TV) MyTVB")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        AppLog.w(TAG, "节目单源失败 code=${response.code} url=$url")
                        null
                    } else {
                        response.body?.string()
                    }
                }
            }.getOrElse {
                AppLog.w(TAG, "节目单源异常 ${it.javaClass.simpleName}: ${it.message} url=$url")
                null
            }
            if (!body.isNullOrBlank()) {
                return body
            }
        }
        return null
    }

    /** 解析节目单 JSON：优先标准结构，回退宽松扁平结构。返回空列表表示不可用。 */
    private fun parse(json: String?): List<Live> {
        if (json.isNullOrBlank()) return emptyList()
        // 1) 标准结构 {data:[{tag,name,vods:[{name,url}]}]}
        runCatching {
            val type = object : TypeToken<com.mytvb.feature.marmot.domain.MarmotModels.DataWrapper<Live>>() {}.type
            GsonHolder.DEFAULT.fromJson<List<Live>>(json, type)
        }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        // 2) 扁平结构 {"名称":"URL"}（JsonParser 宽松模式，容忍尾逗号）
        return runCatching {
            val obj = JsonParser.parseString(json).asJsonObject
            val vods = obj.entrySet().mapNotNull { entry ->
                val value = entry.value
                if (value != null && value.isJsonPrimitive) Vod(name = entry.key, url = value.asString) else null
            }
            if (vods.isEmpty()) {
                emptyList()
            } else {
                listOf(Live(tag = "playlist", name = "节目单", vods = vods.toMutableList()))
            }
        }.getOrDefault(emptyList())
    }
}
