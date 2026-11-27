package com.ali.anbaki.routerbrowser

import android.app.AlertDialog
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.net.Inet4Address
import java.net.InetAddress

object NetworkToolsHelper {

    fun getCurrentGatewayUrl(context: Context): String? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return null
            val linkProps: LinkProperties = cm.getLinkProperties(network) ?: return null
            for (route in linkProps.routes) {
                val gw = route.gateway
                if (route.isDefaultRoute && gw is Inet4Address && !gw.isAnyLocalAddress) {
                    val ip = gw.hostAddress
                    if (!ip.isNullOrBlank() && ip != "0.0.0.0") {
                        return "http://$ip"
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    fun showNetworkDiagnosticsDialog(
        context: Context,
        onOpenUrl: (String) -> Unit
    ) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(network)
        val linkProps = cm.getLinkProperties(network)

        val isConnected = caps != null && (
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        )

        val connType = when {
            caps == null -> "غير متصل"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "شبكة لاسلكية (Wi-Fi)"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "كيبل شبكة (Ethernet)"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "بيانات الهاتف (Cellular)"
            else -> "متصل"
        }

        var localIp = "غير متوفر"
        var gatewayIp = "غير متوفر"
        val dnsList = mutableListOf<String>()

        linkProps?.linkAddresses?.forEach { la ->
            val addr = la.address
            if (addr is Inet4Address) {
                localIp = "${addr.hostAddress}/${la.prefixLength}"
            }
        }

        linkProps?.routes?.forEach { route ->
            val gw = route.gateway
            if (route.isDefaultRoute && gw is Inet4Address && !gw.isAnyLocalAddress) {
                gatewayIp = gw.hostAddress ?: "غير متوفر"
            }
        }

        linkProps?.dnsServers?.forEach { dns ->
            dns.hostAddress?.let { dnsList.add(it) }
        }

        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val linkSpeed = try {
            @Suppress("DEPRECATION")
            val info = wifiManager?.connectionInfo
            if (info != null && info.linkSpeed > 0) "${info.linkSpeed} Mbps" else "غير متاح"
        } catch (_: Exception) {
            "غير متاح"
        }

        val report = buildString {
            appendLine("• حالة الاتصال: ${if (isConnected) "متصل بنجاح" else "غير متصل"}")
            appendLine("• نوع الاتصال: $connType")
            appendLine("• عنوان الجهاز المحلي (IP): $localIp")
            appendLine("• عنوان الراوتر / البوابة (Gateway): $gatewayIp")
            appendLine("• سرعة الربط (Link Speed): $linkSpeed")
            appendLine("• خوادم DNS: ${if (dnsList.isEmpty()) "غير متاح" else dnsList.joinToString(", ")}")
        }

        val builder = AlertDialog.Builder(context)
            .setTitle("خصائص ومعلومات الشبكة")
            .setMessage(report)
            .setNegativeButton("إغلاق", null)

        if (gatewayIp != "غير متوفر" && gatewayIp != "0.0.0.0") {
            builder.setPositiveButton("فتح بوابة الراوتر ($gatewayIp)") { _, _ ->
                onOpenUrl("http://$gatewayIp")
            }
        }

        builder.show()
    }

    fun runQuickPingCheck(context: Context, hostOrUrl: String) {
        val host = hostOrUrl.substringAfter("://", hostOrUrl).substringBefore("/").substringBefore(":")
        if (host.isBlank()) {
            Toast.makeText(context, "لا يوجد عنوان لفحصه", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(context, "جاري فحص الاستجابة مع $host ...", Toast.LENGTH_SHORT).show()
        Thread {
            val start = System.currentTimeMillis()
            val reachable = try {
                InetAddress.getByName(host).isReachable(2000)
            } catch (_: Exception) {
                false
            }
            val elapsed = System.currentTimeMillis() - start
            Handler(Looper.getMainLooper()).post {
                val msg = if (reachable) {
                    "الجهاز ($host) متصل ويستجيب خلال ${elapsed}ms"
                } else {
                    "تعذر الوصول إلى ($host) عبر Ping — تأكد من الاتصال بنفس الشبكة"
                }
                AlertDialog.Builder(context)
                    .setTitle("نتيجة فحص الاتصال (Ping)")
                    .setMessage(msg)
                    .setPositiveButton("موافق", null)
                    .show()
            }
        }.start()
    }
}
