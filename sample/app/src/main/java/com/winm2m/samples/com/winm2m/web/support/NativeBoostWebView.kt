package com.winm2m.web.support

import android.content.Context
import android.util.AttributeSet
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.io.inputStream

data class UrlInfo(val hash: String, val alias: List<String>)

class NativeBoostWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    private var versionCheckUrl: String? = null
    private val localCacheDir: File = File(context.cacheDir, "webview_cache")
    private var onPageLoadTimeCallback: ((Long) -> Unit)? = null
    private val cacheMap: MutableMap<String, ByteArray> = mutableMapOf()
    private val urlInfoMap = mutableMapOf<String, UrlInfo>()
    private val urlInfoMapFile = File(localCacheDir, "urlInfoMap.json")
    private val urlOrAliasToFilenameMap = mutableMapOf<String, String>()
    private var useMemoryCache: Boolean = false

    init {
        if (!localCacheDir.exists()) {
            localCacheDir.mkdir()
        }
        loadUrlInfoMap()
        loadCacheToMemory()
        webViewClient = CachingWebViewClient()
    }

    fun setVersionCheckUrl(url: String) {
        versionCheckUrl = url
    }

    fun setUseMemoryCache(useMemoryCache: Boolean) {
        this.useMemoryCache = useMemoryCache
    }

    fun setOnPageLoadTimeCallback(callback: (Long) -> Unit) {
        onPageLoadTimeCallback = callback
    }

    fun checkForUpdates() {
        versionCheckUrl?.let { url ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val connection = URL(url).openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connect()

                    if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                        return@launch
                    }

                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    processJsonArray(JSONArray(response))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } ?: throw IllegalStateException("Version check URL is not set.")
        populateUrlOrAliasToFileMap()
        loadCacheToMemory()
    }

    private fun processJsonArray(jsonArray: JSONArray) {
        for (i in 0 until jsonArray.length()) {
            val jsonObject = jsonArray.getJSONObject(i)
            val url = jsonObject.getString("url")
            val hash = jsonObject.getString("hash")
            val alias = if (jsonObject.has("alias")) {
                val aliasArray = jsonObject.getJSONArray("alias")
                List(aliasArray.length()) { aliasArray.getString(it) }
            } else {
                emptyList<String>()
            }

            val file = File(localCacheDir, fileNameFromUrl(url))
            if (!file.exists() || !urlInfoMap[url]?.hash.equals(hash)) {
                downloadAndSaveFile(url, file)
                urlInfoMap[url] = UrlInfo(hash, alias)
            }
        }
        saveUrlInfoMap()
    }

    private fun fileNameFromUrl(url: String): String {
        val fileName = url.removePrefix("http")
            .replace(":", "_")
            .replace("/", "_")
            .replace(".", "_")
        return fileName
    }

    private fun populateUrlOrAliasToFileMap() {
        urlOrAliasToFilenameMap.clear()
        urlInfoMap.forEach { (url, urlInfo) ->
            val fileName = fileNameFromUrl(url)
            urlOrAliasToFilenameMap[url] = fileName
            urlInfo.alias.forEach { alias ->
                urlOrAliasToFilenameMap[alias] = fileName
            }
        }
    }

    private fun loadUrlInfoMap() {
        if (!urlInfoMapFile.exists()) {
            urlInfoMapFile.writeText("[]")
        }

        val jsonArray = JSONArray(urlInfoMapFile.readText())
        for (i in 0 until jsonArray.length()) {
            val jsonObject = jsonArray.getJSONObject(i)
            val url = jsonObject.getString("url")
            val hash = jsonObject.getString("hash")
            val alias = if (jsonObject.has("alias")) {
                val aliasArray = jsonObject.getJSONArray("alias")
                List(aliasArray.length()) { aliasArray.getString(it) }
            } else {
                emptyList<String>()
            }
            urlInfoMap[url] = UrlInfo(hash, alias)
        }
    }

    private fun saveUrlInfoMap() {
        val jsonArray = JSONArray()
        urlInfoMap.forEach { (url, urlInfo) ->
            val jsonObject = org.json.JSONObject()
            jsonObject.put("url", url)
            jsonObject.put("hash", urlInfo.hash)
            jsonObject.put("alias", JSONArray(urlInfo.alias))
            jsonArray.put(jsonObject)
        }
        urlInfoMapFile.writeText(jsonArray.toString())
    }

    private fun loadCacheToMemory() {
        if (!useMemoryCache) {
            return
        }
        cacheMap.clear()
        localCacheDir.listFiles()?.forEach { file ->
            val fileBytes = file.readBytes()
            cacheMap[file.name] = fileBytes
        }
    }

    private fun downloadAndSaveFile(url: String, file: File) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connect()

        if (connection.responseCode == HttpURLConnection.HTTP_OK) {
            connection.inputStream.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    fun getFileNameForUrl(url: String): String? {
        urlOrAliasToFilenameMap.forEach { (key, fileName) ->
            if (key == url || Regex(key).matches(url)) {
                return fileName
            }
        }
        return null
    }
    
    private inner class CachingWebViewClient : WebViewClient() {
        private var startTime: Long = 0

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url.toString()
            val fileName = getFileNameForUrl(url)
            if (fileName?.isNotEmpty() == true) {
                when {
                    useMemoryCache && cacheMap[fileName] != null -> {
                        cacheMap[fileName]?.inputStream()
                    }
                    else -> {
                        val file = File(localCacheDir, fileName)
                        if (file.exists()) file.inputStream() else null
                    }
                }?.let {
                    return WebResourceResponse(mimeTypeOf(fileName), "UTF-8", it)
                }
            }
            return super.shouldInterceptRequest(view, request)
        }

        private fun mimeTypeOf(fileName: String): String {
            return when {
                fileName.endsWith("_js") -> "application/javascript"
                fileName.endsWith("_css") -> "text/css"
                fileName.endsWith("_html") -> "text/html"
                fileName.endsWith("woff2") -> "font/woff2"
                fileName.endsWith("woff") -> "font/woff"
                fileName.endsWith("ttf") -> "font/ttf"
                fileName.endsWith("otf") -> "font/otf"
                fileName.endsWith("ico") -> "image/x-icon"
                fileName.endsWith("png") -> "image/png"
                fileName.endsWith("jpg") -> "image/jpeg"
                fileName.endsWith("jpeg") -> "image/jpeg"
                fileName.endsWith("gif") -> "image/gif"
                else -> "text/html"
            }
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            startTime = System.currentTimeMillis()
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            val endTime = System.currentTimeMillis()
            val loadTime = endTime - startTime
            onPageLoadTimeCallback?.invoke(loadTime)
        }
    }
}