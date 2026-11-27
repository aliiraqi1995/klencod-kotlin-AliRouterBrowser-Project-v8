package com.ali.anbaki.routerbrowser

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.WebView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

class CredentialVaultManager(
    private val context: Context,
    private val getActiveWebView: () -> WebView?
) {

    data class DeviceCredential(
        val deviceName: String,
        val url: String,
        val username: String,
        val password: String,
        val updatedAt: Long
    )

    private val prefs by lazy {
        context.getSharedPreferences("AliRouterVaultPrefs", Context.MODE_PRIVATE)
    }

    fun attachAutoFillAndCaptureHook(webView: WebView) {
        val pageUrl = webView.url ?: return
        val matched = findCredentialForUrl(pageUrl)
        val uJson = JSONObject.quote(matched?.username ?: "")
        val pJson = JSONObject.quote(matched?.password ?: "")

        val js = "(function(){" +
            "var savedUser=" + uJson + ";" +
            "var savedPass=" + pJson + ";" +
            "function isVisible(el){if(!el||el.disabled)return false;var r=el.getBoundingClientRect();var s=window.getComputedStyle(el);return s.display!=='none'&&s.visibility!=='hidden'&&r.width>5&&r.height>5;}" +
            "function setVal(el,v){if(!el||!v)return;el.focus();el.value=v;el.dispatchEvent(new Event('input',{bubbles:true}));el.dispatchEvent(new Event('change',{bubbles:true}));}" +
            "var inputs=Array.from(document.querySelectorAll('input')).filter(isVisible);" +
            "var passField=inputs.find(function(i){return (i.type||'').toLowerCase()==='password';});" +
            "var userField=inputs.find(function(i){var t=(i.type||'').toLowerCase();return t==='text'||t==='email';});" +
            "if(savedPass&&passField&&!passField.value){if(savedUser&&userField&&!userField.value){setVal(userField,savedUser);}setVal(passField,savedPass);}" +
            "if(!window.__aliVaultHooked&&passField){window.__aliVaultHooked=true;function captureNow(){var u=userField?(userField.value||''):'';var p=passField?(passField.value||''):'';if(p&&p.length>0&&window.AliVaultBridge){window.AliVaultBridge.onCredentialsSubmitted(window.location.href,u,p);}}" +
            "document.addEventListener('submit',captureNow,true);" +
            "var btns=Array.from(document.querySelectorAll('button,a'));" +
            "btns.forEach(function(b){b.addEventListener('click',captureNow,true);});}" +
            "})();"

        webView.evaluateJavascript(js, null)
    }

    fun getAllCredentials(): MutableList<DeviceCredential> {
        val raw = prefs.getString("device_credentials", "[]") ?: "[]"
        val arr = JSONArray(raw)
        val list = mutableListOf<DeviceCredential>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            list.add(
                DeviceCredential(
                    deviceName = obj.optString("deviceName", "جهاز شبكة"),
                    url = obj.optString("url", ""),
                    username = obj.optString("username", ""),
                    password = obj.optString("password", ""),
                    updatedAt = obj.optLong("updatedAt", 0L)
                )
            )
        }
        return list
    }

    private fun saveAllCredentials(list: List<DeviceCredential>) {
        val arr = JSONArray()
        list.forEach { item ->
            arr.put(JSONObject().apply {
                put("deviceName", item.deviceName)
                put("url", item.url)
                put("username", item.username)
                put("password", item.password)
                put("updatedAt", item.updatedAt)
            })
        }
        prefs.edit().putString("device_credentials", arr.toString()).apply()
    }

    private fun extractHost(url: String): String {
        return url.substringAfter("://", url).substringBefore("/").substringBefore("?")
    }

    fun findCredentialForUrl(url: String): DeviceCredential? {
        val host = extractHost(url)
        if (host.isEmpty()) return null
        return getAllCredentials().firstOrNull { extractHost(it.url) == host }
    }

    fun upsertCredential(deviceName: String, url: String, username: String, password: String) {
        if (password.isBlank()) return
        val host = extractHost(url)
        val list = getAllCredentials()
        val existing = list.firstOrNull { extractHost(it.url) == host }
        val finalName = if (deviceName.isNotBlank()) deviceName else (existing?.deviceName ?: "جهاز ($host)")
        list.removeAll { extractHost(it.url) == host }
        list.add(
            0,
            DeviceCredential(
                deviceName = finalName,
                url = url,
                username = username.trim(),
                password = password.trim(),
                updatedAt = System.currentTimeMillis()
            )
        )
        saveAllCredentials(list)
    }

    fun showAddOrEditCurrentCredentialDialog() {
        val currentUrl = getActiveWebView()?.url ?: "http://192.168.0.1"
        val existing = findCredentialForUrl(currentUrl)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(44, 24, 44, 12)
        }

        val etDeviceName = EditText(context).apply {
            hint = "اسم الجهاز (مثال: راوتر البيت أو نانو السطح)"
            setText(existing?.deviceName ?: "")
        }
        val etUrl = EditText(context).apply {
            hint = "عنوان IP الجهاز"
            setText(existing?.url ?: currentUrl)
        }
        val etUser = EditText(context).apply {
            hint = "اسم المستخدم (اختياري مثل admin)"
            setText(existing?.username ?: "")
        }
        val etPass = EditText(context).apply {
            hint = "كلمة المرور (Password)"
            setText(existing?.password ?: "")
        }

        container.addView(etDeviceName)
        container.addView(etUrl)
        container.addView(etUser)
        container.addView(etPass)

        AlertDialog.Builder(context)
            .setTitle("حفظ بيانات دخول الجهاز")
            .setView(container)
            .setPositiveButton("حفظ") { _, _ ->
                val dName = etDeviceName.text.toString().trim()
                val uLink = etUrl.text.toString().trim().ifEmpty { currentUrl }
                val user = etUser.text.toString().trim()
                val pass = etPass.text.toString().trim()
                if (pass.isEmpty()) {
                    Toast.makeText(context, "أدخل كلمة المرور أولاً", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                upsertCredential(dName, uLink, user, pass)
                Toast.makeText(context, "تم حفظ بيانات الدخول للجهاز", Toast.LENGTH_SHORT).show()
                getActiveWebView()?.let { attachAutoFillAndCaptureHook(it) }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    fun showSavedCredentialsListDialog() {
        val list = getAllCredentials()
        if (list.isEmpty()) {
            AlertDialog.Builder(context)
                .setTitle("كلمات الدخول المحفوظة")
                .setMessage("لا توجد بيانات دخول محفوظة بعد. يمكنك حفظ بيانات أي راوتر أو جهاز من هنا أو تلقائياً عند تسجيل الدخول.")
                .setPositiveButton("+ إضافة بيانات جهاز") { _, _ ->
                    showAddOrEditCurrentCredentialDialog()
                }
                .setNegativeButton("إغلاق", null)
                .show()
            return
        }

        val items = list.map { c ->
            val uStr = if (c.username.isNotBlank()) "المستخدم: ${c.username} | " else ""
            c.deviceName + " (" + extractHost(c.url) + ")\n" + uStr + "كلمة المرور: " + c.password
        }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle("كلمات الدخول المحفوظة (${list.size})")
            .setItems(items) { _, idx ->
                showSingleCredentialOptions(list[idx], idx)
            }
            .setPositiveButton("+ إضافة جديد") { _, _ ->
                showAddOrEditCurrentCredentialDialog()
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun showSingleCredentialOptions(cred: DeviceCredential, index: Int) {
        val opts = arrayOf(
            "فتح الجهاز وتعبئة الدخول تلقائياً",
            "تعبئة في الصفحة الحالية الآن",
            "نسخ كلمة المرور",
            "نسخ اسم المستخدم وكلمة المرور",
            "حذف هذا السجل"
        )
        AlertDialog.Builder(context)
            .setTitle(cred.deviceName)
            .setItems(opts) { _, which ->
                when (which) {
                    0 -> {
                        val wv = getActiveWebView()
                        if (wv != null) {
                            wv.loadUrl(cred.url)
                        }
                    }
                    1 -> {
                        val wv = getActiveWebView()
                        if (wv != null) {
                            attachAutoFillAndCaptureHook(wv)
                            Toast.makeText(context, "تمت التعبئة", Toast.LENGTH_SHORT).show()
                        }
                    }
                    2 -> copyText("Password", cred.password)
                    3 -> {
                        val full = if (cred.username.isNotBlank()) "${cred.username}:${cred.password}" else cred.password
                        copyText("Credentials", full)
                    }
                    4 -> {
                        val updated = getAllCredentials()
                        if (index in updated.indices) {
                            updated.removeAt(index)
                            saveAllCredentials(updated)
                            Toast.makeText(context, "تم حذف السجل", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("رجوع", null)
            .show()
    }

    private fun copyText(label: String, value: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(context, "تم النسخ إلى الحافظة", Toast.LENGTH_SHORT).show()
    }
}
