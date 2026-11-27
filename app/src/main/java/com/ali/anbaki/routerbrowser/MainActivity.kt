package com.ali.anbaki.routerbrowser

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.http.SslError
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    data class RouterDeviceItem(
        val category: String,
        val name: String,
        val url: String
    )

    companion object {
        private const val HOME_URL = "http://192.168.0.1"
    }

    private lateinit var webContainer: FrameLayout
    private lateinit var etUrl: AutoCompleteTextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvTabCount: TextView
    private lateinit var tvNetworkBanner: TextView
    private lateinit var vaultManager: CredentialVaultManager

    private val tabs = mutableListOf<WebView>()
    private var activeTabIndex = -1
    private var isDesktopMode = false
    private var zoomPercent = 100
    private var wasDisconnected = false
    private var lastPageFailed = false

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    inner class WebVaultJsBridge {
        @JavascriptInterface
        fun onCredentialsSubmitted(url: String, username: String, password: String) {
            runOnUiThread {
                if (password.isNotBlank()) {
                    vaultManager.upsertCredential("", url, username, password)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        ensureDefaultDevicesInitialized()
        setupUrlSuggestions()
        setupBackNavigation()
        registerNetworkAutoRefresh()

        createNewTab(HOME_URL)
    }

    private fun initViews() {
        webContainer = findViewById(R.id.webContainer)
        etUrl = findViewById(R.id.etUrl)
        progressBar = findViewById(R.id.progressBar)
        tvTabCount = findViewById(R.id.tvTabCount)
        tvNetworkBanner = findViewById(R.id.tvNetworkBanner)

        vaultManager = CredentialVaultManager(
            context = this,
            getActiveWebView = { getCurrentWebView() }
        )

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            handleNavigateBack()
        }

        findViewById<Button>(R.id.btnGo).setOnClickListener {
            loadInputUrl()
        }

        etUrl.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                loadInputUrl()
                true
            } else {
                false
            }
        }

        etUrl.setOnItemClickListener { _, _, _, _ ->
            loadInputUrl()
        }

        etUrl.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                etUrl.post {
                    if (etUrl.adapter != null && etUrl.adapter.count > 0) {
                        etUrl.showDropDown()
                    }
                }
            }
        }

        findViewById<LinearLayout>(R.id.btnDevices).setOnClickListener {
            showDevicesCatalogDialog()
        }

        findViewById<LinearLayout>(R.id.btnTabs).setOnClickListener {
            showTabsManagerDialog()
        }

        findViewById<LinearLayout>(R.id.btnTranslate).setOnClickListener {
            val wv = getCurrentWebView()
            if (wv != null) {
                ArabicPageTranslator.translateCurrentPage(wv)
            }
        }

        findViewById<LinearLayout>(R.id.btnOptions).setOnClickListener { anchor ->
            showOptionsMenu(anchor)
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleNavigateBack()
            }
        })
    }

    private fun handleNavigateBack() {
        val wv = getCurrentWebView()
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else if (tabs.size > 1) {
            closeCurrentTab()
        } else {
            Toast.makeText(this, "أنت في الصفحة الرئيسية", Toast.LENGTH_SHORT).show()
        }
    }

    private fun registerNetworkAutoRefresh() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val activeNet = connectivityManager?.activeNetwork
        val activeCaps = connectivityManager?.getNetworkCapabilities(activeNet)
        val currentlyOnline = activeCaps != null && (
            activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        )
        wasDisconnected = !currentlyOnline
        tvNetworkBanner.visibility = if (currentlyOnline) View.GONE else View.VISIBLE

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    tvNetworkBanner.visibility = View.GONE
                    if (wasDisconnected || lastPageFailed) {
                        wasDisconnected = false
                        lastPageFailed = false
                        Toast.makeText(
                            this@MainActivity,
                            "تم الاتصال بالشبكة — جاري تحديث الصفحة...",
                            Toast.LENGTH_SHORT
                        ).show()
                        getCurrentWebView()?.reload()
                    }
                }
            }

            override fun onLost(network: Network) {
                runOnUiThread {
                    wasDisconnected = true
                    tvNetworkBanner.visibility = View.VISIBLE
                }
            }
        }

        try {
            connectivityManager?.registerNetworkCallback(request, networkCallback!!)
        } catch (_: Exception) {
        }
    }

    private fun setupUrlSuggestions() {
        val commonIps = listOf(
            "http://192.168.0.1",
            "http://192.168.1.1",
            "http://192.168.1.20",
            "http://192.168.1.254",
            "http://192.168.0.254",
            "http://192.168.10.1",
            "http://192.168.10.10",
            "http://192.168.8.1",
            "http://192.168.31.1",
            "http://192.168.88.1",
            "http://192.168.100.1",
            "http://192.168.2.1",
            "http://192.168.9.1",
            "http://10.0.0.1",
            "http://tplinkwifi.net",
            "http://tplinkrepeater.net",
            "http://tendawifi.com",
            "http://miwifi.com",
            "http://myrepeater.net"
        )
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, commonIps)
        etUrl.setAdapter(adapter)
    }

    private fun showOptionsMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        val desktopLabel = if (isDesktopMode) "وضع الكمبيوتر: مفعّل ✓" else "وضع الكمبيوتر (Desktop)"

        popup.menu.add(0, 1, 1, "🔍 البحث في الصفحة الحالية")
        popup.menu.add(0, 2, 2, "🔑 كلمات الدخول المحفوظة (الأدمن واليوزر)")
        popup.menu.add(0, 3, 3, "➕ حفظ بيانات دخول الصفحة الحالية")
        popup.menu.add(0, 4, 4, "🕒 سجل التصفح الكامل")
        popup.menu.add(0, 5, 5, "💻 $desktopLabel")
        popup.menu.add(0, 10, 5, "🔎 تكبير وتصغير الصفحة (الحالي: $zoomPercent%)")
        popup.menu.add(0, 6, 6, "📡 خصائص الشبكة والبوابة (Gateway)")
        popup.menu.add(0, 7, 7, "📶 فحص الاستجابة السريع (Ping)")
        popup.menu.add(0, 8, 8, "🔄 تحديث الصفحة")
        popup.menu.add(0, 9, 9, "ℹ️ حول التطبيق والمطور")

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> showFindInPageDialog()
                2 -> vaultManager.showSavedCredentialsListDialog()
                3 -> vaultManager.showAddOrEditCurrentCredentialDialog()
                4 -> showFullHistoryDialog()
                5 -> toggleDesktopMode()
                10 -> showZoomScaleDialog()
                6 -> NetworkToolsHelper.showNetworkDiagnosticsDialog(this) { gwUrl ->
                    etUrl.setText(gwUrl)
                    getCurrentWebView()?.loadUrl(gwUrl)
                }
                7 -> {
                    val target = getCurrentWebView()?.url ?: etUrl.text.toString()
                    NetworkToolsHelper.runQuickPingCheck(this, target)
                }
                8 -> getCurrentWebView()?.reload()
                9 -> showAboutDeveloperDialog()
            }
            true
        }
        popup.show()
    }

    private fun showFindInPageDialog() {
        val wv = getCurrentWebView() ?: return
        val input = EditText(this).apply {
            hint = "اكتب الكلمة للبحث عنها في الصفحة..."
            isSingleLine = true
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(44, 24, 44, 12)
            addView(input)
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString() ?: ""
                if (query.isNotEmpty()) {
                    wv.findAllAsync(query)
                } else {
                    wv.clearMatches()
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        AlertDialog.Builder(this)
            .setTitle("البحث داخل الصفحة")
            .setView(box)
            .setPositiveButton("التالي") { _, _ ->
                wv.findNext(true)
            }
            .setNeutralButton("مسح التحديد") { _, _ ->
                wv.clearMatches()
            }
            .setNegativeButton("إغلاق") { _, _ ->
                wv.clearMatches()
            }
            .show()
    }

    private fun showAboutDeveloperDialog() {
        val message = "تم بناء هذا التطبيق بواسطة:\n" +
            "علي زيد العنبكي\n\n" +
            "تطبيق متخصص وسريع للدخول إلى إعدادات الراوترات، أجهزة البث والاستقبال (Ubiquiti)، مقويات الإشارة (Extender)، وأجهزة الشبكات اللاسلكية وإدارتها بسهولة وباللغة العربية."

        AlertDialog.Builder(this)
            .setTitle("حول التطبيق")
            .setMessage(message)
            .setPositiveButton("موافق", null)
            .show()
    }

    private fun ensureDefaultDevicesInitialized() {
        val prefs = getSharedPreferences("AliRouterPrefs", Context.MODE_PRIVATE)
        if (prefs.contains("catalog_devices")) return

        val defaults = listOf(
            RouterDeviceItem("راوترات", "Tenda (الرئيسي الافتراضي)", "http://192.168.0.1"),
            RouterDeviceItem("راوترات", "TP-Link (الافتراضي)", "http://192.168.1.1"),
            RouterDeviceItem("راوترات", "TP-Link (نطاق 0.1)", "http://192.168.0.1"),
            RouterDeviceItem("راوترات", "TP-Link رابط سريع", "http://tplinkwifi.net"),
            RouterDeviceItem("راوترات", "Tenda رابط سريع", "http://tendawifi.com"),
            RouterDeviceItem("راوترات", "Xiaomi Mi Router", "http://192.168.31.1"),
            RouterDeviceItem("راوترات", "Huawei / Honor", "http://192.168.8.1"),
            RouterDeviceItem("راوترات", "Huawei Fiber / ONT", "http://192.168.100.1"),
            RouterDeviceItem("راوترات", "MikroTik RouterOS", "http://192.168.88.1"),
            RouterDeviceItem("راوترات", "D-Link / Netgear", "http://192.168.0.1"),
            RouterDeviceItem("راوترات", "Asus Router", "http://192.168.1.1"),
            RouterDeviceItem("راوترات", "Mercusys / Netis", "http://192.168.1.1"),
            RouterDeviceItem("يوبيكويتي (Ubiquiti)", "NanoStation / PowerBeam / LiteBeam", "http://192.168.1.20"),
            RouterDeviceItem("يوبيكويتي (Ubiquiti)", "Ubiquiti AirOS (نطاق 1.21)", "http://192.168.1.21"),
            RouterDeviceItem("يوبيكويتي (Ubiquiti)", "Ubiquiti Router / EdgeOS", "http://192.168.1.1"),
            RouterDeviceItem("يوبيكويتي (Ubiquiti)", "شبكة فرعية (192.168.9.1)", "http://192.168.9.1"),
            RouterDeviceItem("اكستيندر (Extender)", "TP-Link Extender (192.168.0.254)", "http://192.168.0.254"),
            RouterDeviceItem("اكستيندر (Extender)", "TP-Link Extender (192.168.1.254)", "http://192.168.1.254"),
            RouterDeviceItem("اكستيندر (Extender)", "TP-Link Repeater رابط", "http://tplinkrepeater.net"),
            RouterDeviceItem("اكستيندر (Extender)", "Tenda Extender / A9 / A18", "http://192.168.0.254"),
            RouterDeviceItem("اكستيندر (Extender)", "Xiaomi Wi-Fi Extender", "http://192.168.31.1"),
            RouterDeviceItem("اكستيندر (Extender)", "Generic Repeater (192.168.10.1)", "http://192.168.10.1"),
            RouterDeviceItem("اكستيندر (Extender)", "Generic Repeater (192.168.10.10)", "http://192.168.10.10")
        )
        saveDevicesList(defaults)
    }

    private fun getDevicesList(): MutableList<RouterDeviceItem> {
        val prefs = getSharedPreferences("AliRouterPrefs", Context.MODE_PRIVATE)
        val raw = prefs.getString("catalog_devices", "[]") ?: "[]"
        val arr = JSONArray(raw)
        val list = mutableListOf<RouterDeviceItem>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            list.add(
                RouterDeviceItem(
                    category = obj.optString("category", "أجهزة"),
                    name = obj.optString("name", ""),
                    url = obj.optString("url", "")
                )
            )
        }
        return list
    }

    private fun saveDevicesList(list: List<RouterDeviceItem>) {
        val arr = JSONArray()
        list.forEach { d ->
            arr.put(JSONObject().apply {
                put("category", d.category)
                put("name", d.name)
                put("url", d.url)
            })
        }
        getSharedPreferences("AliRouterPrefs", Context.MODE_PRIVATE)
            .edit()
            .putString("catalog_devices", arr.toString())
            .apply()
    }

    private fun showDevicesCatalogDialog() {
        val devices = getDevicesList()
        val labels = devices.map { d ->
            "[" + d.category + "] " + d.name + "\n" + d.url
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("أجهزة الراوتر واليوبيكويتي والاكستيندر (${devices.size})")
            .setItems(labels) { _, which ->
                val chosen = devices[which]
                etUrl.setText(chosen.url)
                getCurrentWebView()?.loadUrl(chosen.url)
            }
            .setPositiveButton("+ إضافة جهاز") { _, _ ->
                showAddCustomDeviceDialog()
            }
            .setNeutralButton("فتح البوابة التلقائية") { _, _ ->
                val gw = NetworkToolsHelper.getCurrentGatewayUrl(this)
                if (gw != null) {
                    etUrl.setText(gw)
                    getCurrentWebView()?.loadUrl(gw)
                } else {
                    Toast.makeText(this, "لم يتم اكتشاف بوابة افتراضية حالياً", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun showAddCustomDeviceDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(44, 24, 44, 12)
        }
        val etCat = EditText(this).apply {
            hint = "التصنيف (مثال: راوترات / يوبيكويتي / اكستيندر)"
            setText("أجهزتي")
        }
        val etName = EditText(this).apply {
            hint = "اسم الجهاز (مثال: راوتر الصالة أو نانو السطح)"
        }
        val etIp = EditText(this).apply {
            hint = "عنوان IP أو الرابط"
            setText(getCurrentWebView()?.url ?: "http://192.168.0.1")
        }

        box.addView(etCat)
        box.addView(etName)
        box.addView(etIp)

        AlertDialog.Builder(this)
            .setTitle("إضافة جهاز جديد")
            .setView(box)
            .setPositiveButton("حفظ") { _, _ ->
                val cat = etCat.text.toString().trim().ifEmpty { "أجهزتي" }
                val name = etName.text.toString().trim().ifEmpty { "جهاز شبكة" }
                var url = etIp.text.toString().trim()
                if (url.isEmpty()) return@setPositiveButton
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    url = "http://$url"
                }
                val list = getDevicesList()
                list.add(0, RouterDeviceItem(cat, name, url))
                saveDevicesList(list)
                Toast.makeText(this, "تمت إضافة ($name)", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showTabsManagerDialog() {
        val labels = tabs.mapIndexed { idx, wv ->
            val activeMark = if (idx == activeTabIndex) "✓ " else ""
            val title = wv.title?.takeIf { it.isNotBlank() } ?: (wv.url ?: "صفحة ${idx + 1}")
            activeMark + title + "\n" + (wv.url ?: "")
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("التبديل بين الصفحات (${tabs.size})")
            .setItems(labels) { _, which ->
                switchToTab(which)
            }
            .setPositiveButton("+ صفحة جديدة") { _, _ ->
                createNewTab(HOME_URL)
            }
            .setNeutralButton("إغلاق الحالية") { _, _ ->
                closeCurrentTab()
            }
            .setNegativeButton("رجوع", null)
            .show()
    }

    private fun loadInputUrl() {
        var raw = etUrl.text.toString().trim()
        if (raw.isEmpty()) return
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
            raw = "http://$raw"
        }
        etUrl.setText(raw)
        etUrl.dismissDropDown()
        getCurrentWebView()?.loadUrl(raw)
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun createNewTab(url: String) {
        val webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )

            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.textZoom = zoomPercent
            setInitialScale(zoomPercent)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)

            applyUserAgent(this, isDesktopMode)
            addJavascriptInterface(WebVaultJsBridge(), "AliVaultBridge")

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    return false
                }

                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    super.onPageFinished(view, loadedUrl)
                    lastPageFailed = false
                    if (view != null && !loadedUrl.isNullOrEmpty() && view == getCurrentWebView()) {
                        etUrl.setText(loadedUrl)
                        saveHistoryEntry(view.title ?: "", loadedUrl)
                    }
                    if (view != null) {
                        unlockPageViewportZoom(view)
                        vaultManager.attachAutoFillAndCaptureHook(view)
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true) {
                        lastPageFailed = true
                    }
                }

                @SuppressLint("WebViewClientOnReceivedSslError")
                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?
                ) {
                    handler?.proceed()
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (view == getCurrentWebView()) {
                        if (newProgress in 1..99) {
                            progressBar.visibility = View.VISIBLE
                            progressBar.progress = newProgress
                        } else {
                            progressBar.visibility = View.GONE
                        }
                    }
                }
            }
        }

        tabs.add(webView)
        updateTabBadge()
        switchToTab(tabs.lastIndex)
        webView.loadUrl(url)
    }

    private fun switchToTab(index: Int) {
        if (index !in tabs.indices) return
        activeTabIndex = index
        webContainer.removeAllViews()
        val current = tabs[activeTabIndex]
        webContainer.addView(current)
        etUrl.setText(current.url ?: HOME_URL)
        updateTabBadge()
    }

    private fun closeCurrentTab() {
        if (activeTabIndex !in tabs.indices) return
        val toRemove = tabs.removeAt(activeTabIndex)
        webContainer.removeView(toRemove)
        toRemove.destroy()
        if (tabs.isEmpty()) {
            createNewTab(HOME_URL)
        } else {
            switchToTab((activeTabIndex - 1).coerceAtLeast(0))
        }
        updateTabBadge()
    }

    private fun updateTabBadge() {
        tvTabCount.text = tabs.size.toString()
    }

    private fun getCurrentWebView(): WebView? {
        return if (activeTabIndex in tabs.indices) tabs[activeTabIndex] else null
    }

    private fun toggleDesktopMode() {
        isDesktopMode = !isDesktopMode
        tabs.forEach { applyUserAgent(it, isDesktopMode) }
        getCurrentWebView()?.reload()
        val msg = if (isDesktopMode) "تم تفعيل وضع الكمبيوتر (Desktop)" else "تم الرجوع لوضع الهاتف"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun applyUserAgent(webView: WebView, desktop: Boolean) {
        val defaultUa = WebSettings.getDefaultUserAgent(this)
        webView.settings.userAgentString = if (desktop) {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        } else {
            defaultUa
        }
    }

    private fun saveHistoryEntry(title: String, url: String) {
        val prefs = getSharedPreferences("AliRouterPrefs", Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString("full_history", "[]") ?: "[]")
        val updated = JSONArray()
        updated.put(JSONObject().apply {
            put("title", title.ifBlank { url })
            put("url", url)
            put("time", System.currentTimeMillis())
        })
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("url") != url && updated.length() < 150) {
                updated.put(obj)
            }
        }
        prefs.edit().putString("full_history", updated.toString()).apply()
    }

    private fun showFullHistoryDialog() {
        val prefs = getSharedPreferences("AliRouterPrefs", Context.MODE_PRIVATE)
        val arr = JSONArray(prefs.getString("full_history", "[]") ?: "[]")
        if (arr.length() == 0) {
            Toast.makeText(this, "سجل التصفح فارغ حالياً", Toast.LENGTH_SHORT).show()
            return
        }

        val labels = Array(arr.length()) { idx ->
            val obj = arr.optJSONObject(idx)
            val t = obj?.optString("title", "") ?: ""
            val u = obj?.optString("url", "") ?: ""
            t + "\n" + u
        }

        AlertDialog.Builder(this)
            .setTitle("سجل التصفح الكامل (${labels.size})")
            .setItems(labels) { _, which ->
                val targetUrl = arr.optJSONObject(which)?.optString("url") ?: return@setItems
                etUrl.setText(targetUrl)
                getCurrentWebView()?.loadUrl(targetUrl)
            }
            .setNeutralButton("مسح السجل") { _, _ ->
                prefs.edit().remove("full_history").apply()
                Toast.makeText(this, "تم مسح سجل التصفح", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun unlockPageViewportZoom(webView: WebView) {
        val js = """
            (function(){
                var meta=document.querySelector('meta[name="viewport"]');
                if(meta){
                    var c=meta.getAttribute('content')||'';
                    c=c.replace(/user-scalable\s*=\s*no/ig,'user-scalable=yes');
                    c=c.replace(/maximum-scale\s*=\s*[0-9.]+/ig,'maximum-scale=5.0');
                    c=c.replace(/minimum-scale\s*=\s*[0-9.]+/ig,'minimum-scale=0.25');
                    if(c.indexOf('user-scalable')===-1){c=c+', user-scalable=yes';}
                    if(c.indexOf('maximum-scale')===-1){c=c+', maximum-scale=5.0';}
                    meta.setAttribute('content',c);
                }else{
                    var m=document.createElement('meta');
                    m.name='viewport';
                    m.content='width=device-width, initial-scale=1.0, maximum-scale=5.0, user-scalable=yes';
                    document.head&&document.head.appendChild(m);
                }
                document.documentElement.style.touchAction='manipulation';
                if(document.body){document.body.style.touchAction='manipulation';}
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun applyZoomToWebView(webView: WebView, percent: Int) {
        webView.settings.setSupportZoom(true)
        webView.settings.builtInZoomControls = true
        webView.settings.displayZoomControls = false
        webView.settings.textZoom = percent
        webView.setInitialScale(percent)
        unlockPageViewportZoom(webView)
    }

    private fun showZoomScaleDialog() {
        val scales = intArrayOf(75, 100, 125, 150, 175)
        val labels = scales.map { s ->
            val mark = if (s == zoomPercent) " ✓" else ""
            "$s%$mark"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("مستوى تكبير الصفحة (الحالي: $zoomPercent%)")
            .setItems(labels) { _, which ->
                zoomPercent = scales[which]
                tabs.forEach { applyZoomToWebView(it, zoomPercent) }
                getCurrentWebView()?.reload()
                Toast.makeText(this, "تم ضبط التكبير على $zoomPercent%", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    override fun onDestroy() {
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (_: Exception) {
        }
        tabs.forEach { it.destroy() }
        super.onDestroy()
    }
}
