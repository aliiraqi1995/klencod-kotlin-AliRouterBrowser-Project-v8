package com.ali.anbaki.routerbrowser

import android.webkit.WebView
import android.widget.Toast
import org.json.JSONArray

object ArabicPageTranslator {

    private val pairs: List<Pair<String, String>> = listOf(
        "登录" to "تسجيل الدخول",
        "登入" to "تسجيل الدخول",
        "用户名" to "اسم المستخدم",
        "账号" to "الحساب",
        "密码" to "كلمة المرور",
        "登录密码" to "كلمة مرور الدخول",
        "请输入密码" to "أدخل كلمة المرور",
        "请输入登录密码" to "أدخل كلمة مرور الدخول",
        "无线设置" to "إعدادات الواي فاي",
        "无线网络" to "الشبكة اللاسلكية",
        "无线名称" to "اسم الشبكة (SSID)",
        "无线密码" to "كلمة مرور الواي فاي",
        "网络设置" to "إعدادات الشبكة",
        "上网设置" to "إعدادات الإنترنت",
        "外网设置" to "إعدادات WAN",
        "局域网设置" to "إعدادات الشبكة المحلية (LAN)",
        "系统管理" to "إدارة النظام",
        "系统工具" to "أدوات النظام",
        "系统状态" to "حالة النظام",
        "运行状态" to "حالة التشغيل",
        "重启路由器" to "إعادة تشغيل الراوتر",
        "重启设备" to "إعادة تشغيل الجهاز",
        "恢复出厂设置" to "استعادة ضبط المصنع",
        "固件升级" to "تحديث السوفتوير",
        "软件升级" to "تحديث البرنامج",
        "修改密码" to "تغيير كلمة المرور",
        "管理员密码" to "كلمة مرور المدير",
        "保存" to "حفظ",
        "确定" to "موافق",
        "取消" to "إلغاء",
        "应用" to "تطبيق",
        "退出" to "خروج",
        "注销" to "تسجيل الخروج",
        "连接状态" to "حالة الاتصال",
        "已连接" to "متصل",
        "未连接" to "غير متصل",
        "信道" to "القناة (Channel)",
        "频段" to "التردد",
        "信号强度" to "قوة الإشارة",
        "高级设置" to "إعدادات متقدمة",
        "家长控制" to "الرقابة الأبوية",
        "访客网络" to "شبكة الضيوف",
        "工作模式" to "وضع التشغيل",
        "中继模式" to "وضع المقوي (Repeater)",
        "桥接模式" to "وضع الجسر (Bridge)",
        "路由模式" to "وضع الراوتر",
        "宽带拨号" to "اتصال PPPoE",
        "动态IP" to "IP تلقائي (DHCP)",
        "静态IP" to "IP ثابت (Static)",
        "Login Password" to "كلمة مرور الدخول",
        "User Name" to "اسم المستخدم",
        "Username" to "اسم المستخدم",
        "Password" to "كلمة المرور",
        "Sign In" to "تسجيل الدخول",
        "Log In" to "تسجيل الدخول",
        "Login" to "دخول",
        "Log Out" to "تسجيل الخروج",
        "Logout" to "خروج",
        "Quick Setup" to "الإعداد السريع",
        "Setup Wizard" to "معالج الإعداد",
        "Network Map" to "خريطة الشبكة",
        "Internet Settings" to "إعدادات الإنترنت",
        "Wireless Settings" to "إعدادات الواي فاي",
        "Wireless Security" to "حماية الواي فاي",
        "Wireless Mode" to "وضع الواي فاي",
        "Access Point" to "نقطة وصول (AP)",
        "Station" to "مستقبل (Station)",
        "Repeater" to "مقوي إشارة (Repeater)",
        "Range Extender" to "موسع النطاق",
        "Channel Width" to "عرض القناة",
        "Frequency" to "التردد",
        "Output Power" to "طاقة البث",
        "Signal Strength" to "قوة الإشارة",
        "Noise Floor" to "مستوى الضوضاء",
        "Transmit CCQ" to "جودة الإرسال CCQ",
        "AirMax Quality" to "جودة AirMax",
        "AirMax Capacity" to "سعة AirMax",
        "Network" to "الشبكة",
        "Wireless" to "اللاسلكي",
        "Advanced" to "متقدم",
        "Services" to "الخدمات",
        "System" to "النظام",
        "Status" to "الحالة",
        "Main" to "الرئيسية",
        "Dashboard" to "لوحة التحكم",
        "Device Name" to "اسم الجهاز",
        "Firmware Version" to "إصدار النظام",
        "Uptime" to "مدة التشغيل",
        "MAC Address" to "عنوان MAC",
        "IP Address" to "عنوان IP",
        "Subnet Mask" to "قناع الشبكة",
        "Default Gateway" to "البوابة الافتراضية",
        "Primary DNS" to "DNS الأساسي",
        "Secondary DNS" to "DNS الثانوي",
        "DHCP Server" to "خادم DHCP",
        "Connected Devices" to "الأجهزة المتصلة",
        "Attached Devices" to "الأجهزة المرتبطة",
        "Client List" to "قائمة المتصلين",
        "Bandwidth Control" to "تحديد السرعة",
        "Port Forwarding" to "توجيه المنافذ",
        "Firewall" to "جدار الحماية",
        "Security" to "الأمان",
        "System Tools" to "أدوات النظام",
        "Administration" to "الإدارة",
        "Reboot" to "إعادة التشغيل",
        "Restart" to "إعادة تشغيل",
        "Factory Defaults" to "ضبط المصنع",
        "Restore Factory" to "استعادة المصنع",
        "Backup" to "نسخ احتياطي",
        "Restore" to "استعادة",
        "Firmware Upgrade" to "تحديث السوفتوير",
        "Change Password" to "تغيير كلمة المرور",
        "Save" to "حفظ",
        "Apply" to "تطبيق",
        "Cancel" to "إلغاء",
        "Connect" to "اتصال",
        "Disconnect" to "قطع الاتصال",
        "Scan" to "بحث عن الشبكات",
        "Site Survey" to "مسح الشبكات"
    )

    fun translateCurrentPage(webView: WebView) {
        val dictArray = JSONArray()
        for ((k, v) in pairs) {
            dictArray.put(JSONArray().put(k).put(v))
        }
        val dictJson = dictArray.toString()

        val script = "(function(){" +
            "var dict=" + dictJson + ";" +
            "function tr(s){if(!s)return s;var o=s;for(var i=0;i<dict.length;i++){var a=dict[i][0],b=dict[i][1];if(o.indexOf(a)!==-1){o=o.split(a).join(b);}}return o;}" +
            "function walk(n){if(!n)return;if(n.nodeType===3){var x=n.nodeValue,y=tr(x);if(y!==x)n.nodeValue=y;}else if(n.nodeType===1){var t=n.tagName;if(t==='SCRIPT'||t==='STYLE')return;if(n.placeholder)n.placeholder=tr(n.placeholder);if(n.title)n.title=tr(n.title);if((t==='INPUT'||t==='BUTTON')&&n.value&&n.type!=='password'&&n.type!=='text')n.value=tr(n.value);for(var c=0;c<n.childNodes.length;c++)walk(n.childNodes[c]);}}" +
            "walk(document.body);if(document.title)document.title=tr(document.title);return 'OK';" +
            "})();"

        webView.evaluateJavascript(script) {
            Toast.makeText(webView.context, "تمت ترجمة عناصر الصفحة إلى العربية", Toast.LENGTH_SHORT).show()
        }
    }
}
