package com.winm2m.samples

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebSettings.LOAD_NO_CACHE
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.winm2m.samples.ui.theme.SampleOfUsingNativeboostWebviewTheme
import com.winm2m.web.support.NativeBoostWebView

class MainActivity : ComponentActivity() {
    companion object {
        const val entryUrl = "https://www.hiclass.net/mobile/help/faq"
        const val fileUrl = "https://9200.01.r01.code.0.winm2m.com/hq.zip"
        const val prefix = "https://www.hiclass.net"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val webView1 = WebView(this)
        val webView2 = NativeBoostWebView(this)

        webView2.setVersionCheckUrl(fileUrl)
        webView2.setInterceptDomainPrefix(prefix)
        webView2.checkForUpdates()

        var startTime1 = 0L
        var endTime1 by mutableStateOf(0L)
        var endTime2 by mutableStateOf(0L)

        webView1.settings.apply {
            cacheMode = LOAD_NO_CACHE
            javaScriptEnabled = true
            domStorageEnabled = true
        }

        webView2.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
        }

        val webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                if (view == webView1) {
                    startTime1 = System.currentTimeMillis()
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (view == webView1) {
                    endTime1 = System.currentTimeMillis()
                }
            }
        }

        webView1.webViewClient = webViewClient

        webView2.setOnPageLoadTimeCallback { loadTime ->
            endTime2 = loadTime
        }

        webView1.loadUrl(entryUrl)
        webView2.loadUrl(entryUrl)

        setContent {
            SampleOfUsingNativeboostWebviewTheme {
                var text1 by remember { mutableStateOf("Loading...") }
                var text2 by remember { mutableStateOf("Loading...") }

                LaunchedEffect(endTime1) {
                    if (endTime1 > 0) {
                        text1 = "WebView: ${endTime1 - startTime1} ms"
                    }
                }

                LaunchedEffect(endTime2) {
                    if (endTime2 > 0) {
                        text2 = "Boosted: $endTime2 ms"
                    }
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                            AndroidView(factory = { webView1 }, modifier = Modifier.fillMaxSize())
                            Text(
                                text = text1,
                                color = Color.Red,
                                fontSize = 40.sp,
                                modifier = Modifier.align(Alignment.TopCenter).background(Color.White)
                            )
                        }
                        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                            AndroidView(factory = { webView2 }, modifier = Modifier.fillMaxSize())
                            Text(
                                text = text2,
                                color = Color.Red,
                                fontSize = 40.sp,
                                modifier = Modifier.align(Alignment.TopCenter).background(Color.White)
                            )
                        }
                    }
                }
            }
        }
    }
}