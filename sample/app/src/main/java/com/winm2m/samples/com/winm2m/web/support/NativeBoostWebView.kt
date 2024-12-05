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
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

class NativeBoostWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    private var versionCheckUrl: String? = null
    private var interceptDomainPrefix: String? = null
    private val localCacheDir: File = File(context.cacheDir, "webview_cache")
    private var onPageLoadTimeCallback: ((Long) -> Unit)? = null
    private val cacheMap: MutableMap<String, ByteArray> = mutableMapOf()

    init {
        if (!localCacheDir.exists()) {
            localCacheDir.mkdir()
        } else {
            loadCacheToMemory()
        }

        webViewClient = CachingWebViewClient()
    }

    fun setVersionCheckUrl(url: String) {
        versionCheckUrl = url
    }

    fun setInterceptDomainPrefix(prefix: String) {
        interceptDomainPrefix = prefix
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

                    if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                        val zipFile = File(localCacheDir, "cache_update.zip")
                        FileOutputStream(zipFile).use { fos ->
                            connection.inputStream.copyTo(fos)
                        }

                        // Unzip the file
                        unzipFile(zipFile, localCacheDir)
                        zipFile.delete() // Clean up
                        loadCacheToMemory() // Reload cache to memory
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } ?: throw IllegalStateException("Version check URL is not set.")
    }

    private fun unzipFile(zipFile: File, targetDirectory: File) {
        ZipInputStream(FileInputStream(zipFile)).use { zis ->
            var entry = zis.nextEntry
            val buffer = ByteArray(1024)
            while (entry != null) {
                val newFile = File(targetDirectory, entry.name)
                FileOutputStream(newFile).use { fos ->
                    var len: Int
                    while (zis.read(buffer).also { len = it } > 0) {
                        fos.write(buffer, 0, len)
                    }
                }
                entry = zis.nextEntry
            }
        }
    }

    private fun loadCacheToMemory() {
        localCacheDir.listFiles()?.forEach { file ->
            val fileBytes = file.readBytes()
            cacheMap[file.name] = fileBytes
        }
    }

    private inner class CachingWebViewClient : WebViewClient() {
        private var startTime: Long = 0

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url.toString()
            interceptDomainPrefix?.let { prefix ->
                if (url.startsWith(prefix)) {
                    val fileName = url.removePrefix(prefix).substringBefore("?").substringAfterLast("/")
                    cacheMap[fileName]?.let { fileBytes ->
                        return WebResourceResponse(
                            "text/html",
                            "UTF-8",
                            fileBytes.inputStream()
                        )
                    }
                }
            }
            return super.shouldInterceptRequest(view, request)
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