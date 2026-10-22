# SariTunnel Africa

تطبيق Android خفيف لإنشاء نفق VPN عبر بروتوكول WireGuard (UDP) باستخدام محرك WireGuard الرسمي المفتوح المصدر (wireguard-go عبر مكتبة `com.wireguard.android:tunnel`).

## ما الذي يعمل فعلاً في هذا الإصدار
- اتصال نفق WireGuard حقيقي عبر `VpnService` (توجيه كامل للجهاز IPv4).
- اكتشاف الخوادم من مصدرين فقط: إعدادات WireGuard يدوية (مخصصة للمستخدم) أو ملف JSON عبر HTTPS يحدده المستخدم.
- ترتيب الخوادم حسب زمن حل اسم المضيف (DNS) وعدد الإخفاقات السابقة.
- التحقق من النفق بعد الاتصال: حالة الواجهة + طلب HTTPS عبر شبكة VPN إلى `generate_204` يجب أن يُرجع 204.
- إعادة اتصال تلقائية مع تراجع أُسّي (exponential backoff) وحد أقصى لعدد المحاولات.
- عداد وقت الاتصال، سرعة التحميل والرفع، وإجمالي البيانات المستخدمة في الجلسة.
- سجل تشخيص محدود الحجم (150 سطراً) لا يحتوي على مفاتيح أو إعدادات.
- تخزين الإعدادات المخصصة مشفّراً عبر `EncryptedSharedPreferences`.

## ما الذي لا يُدعم بعد
- بروتوكول HTTP CONNECT وSSH غير مفعّلين في هذا الإصدار. يمكن إضافتهما لاحقاً كوحدات منفصلة.
- لا يُرفق التطبيق أي خادم مجاني. لا توجد هنا عناوين أو بيانات اعتماد مختلقة. عليك توفير إعداد WireGuard مرخّص لك أو رابط ملف خوادم موثوق.
- زمن DNS ليس زمن المصافحة الفعلية. التحقق الحقيقي هو اختبار حركة المرور بعد الاتصال.
- اختبار الاتصال بخادم معيّن لا يثبت أن الإنترنت المجاني متاح عبر شركة الاتصالات، فتوفر الشبكات يختلف من دولة لأخرى ومن شركة لأخرى.

## صيغة ملف الخوادم (HTTPS JSON)
```json
[
  {
    "name": "Server A",
    "country": "SS",
    "config": "[Interface]\nPrivateKey=...\nAddress=10.8.0.2/32\nDNS=1.1.1.1\n\n[Peer]\nPublicKey=...\nAllowedIPs=0.0.0.0/0\nEndpoint=vpn.example.org:51820"
  }
]
```
- يجب أن يبدأ الرابط بـ `https://`.
- الحد الأقصى: 256 KB للملف، و50 خادماً، و8 KB لكل إعداد.

## البناء عبر GitHub Actions
1. أنشئ مستودعاً جديداً على GitHub وارفع محتويات هذا المجلد.
2. ادخل إلى تبويب Actions وشغّل Workflow باسم `Android Build` (أو انتظر الدفع التالي).
3. بعد نجاح البناء، حمّل الملف `SariTunnelAfrica-debug` من قسم Artifacts. بداخله ملف APK.

ملاحظة: الـ Workflow يبني نسخة debug. لنشر التطبيق على Google Play تحتاج إلى:
- مفتاح توقيع (keystore) خاص بك، وبناء `assembleRelease` أو `bundleRelease` مع التوقيع.
- سياسة الخصوصية، والإفصاح عن استخدام VPN في Play Console.

## البناء محلياً
```bash
gradle wrapper --gradle-version 8.7
./gradlew :app:assembleDebug
```

## استكشاف الأخطاء
- **فشل البناء بسبب Gradle أو JDK**: تأكد من استخدام JDK 17 وGradle 8.7.
- **"VPN permission is required"**: وافق على طلب VPN عند أول اتصال.
- **"No supported server is available"**: أضف إعداد WireGuard أو رابط HTTPS صالحاً في الإعدادات المتقدمة.
- **"VPN interface did not come up"**: تحقق من صلاحية الإعداد وأن المنفذ UDP غير محجوب من الشبكة.
- **"Test traffic did not pass through tunnel"**: النفق أُنشئ لكن حركة المرور لا تمر، وهذا غالباً بسبب سياسة الشبكة أو الخادم.

## التراخيص
- WireGuard Android Tunnel library: راجع ملف LICENSE في مستودعها الرسمي.
- AndroidX / Material Components / Kotlin Coroutines: Apache License 2.0.
تأكد من مراجعة تراخيص كل مكتبة قبل النشر.
