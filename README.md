# SariTunnel Africa — Multi-engine Android source

مشروع Android متعدد البروتوكولات يضم ثلاثة مسارات منفصلة. هذه الحزمة **مصدر المشروع**؛ يُبنى APK عبر GitHub Actions ولم يُختبر على جهاز في هذه الجلسة.

## المحركات

- **VLESS / Xray داخل التطبيق:** يقرأ روابط `vless://` المنشورة في [صفحة Blogger](https://connection7876.blogspot.com/?m=1)، ويبني إعداد Xray ويبدأ نفق Android `VpnService`. مشروع Go bridge في `xray-go/` يُحوّل إلى `app/libs/xray.aar` آلياً عبر `scripts/build-xray-aar.sh`.
- **Tor VPN:** يفتح التطبيق الرسمي المستقل Tor VPN for Android (الحزمة `org.torproject.vpn`). بسبب قيود Android، لا يتحكم SariTunnelAfrica مباشرة في تشغيل/إيقاف VPN خارجي؛ يفتح التطبيق الرسمي أو إعدادات VPN لإدارة الاتصال.
- **OpenVPN:** يستكشف التطبيق ملفات `.ovpn` من IPSpeed ثم يرجع إلى `servers.json` عند فشل المصدر أو إرجاعه 404/صفحة حماية. يرسل الملف إلى **OpenVPN for Android** عبر واجهة AIDL الرسمية؛ يلزم تثبيت التطبيق المرافق والموافقة على الوصول أول مرة. محرك OpenVPN نفسه ليس مضمناً داخل APK.

## مهم عن عناوين الخوادم

الرابط الكامل `vless://UUID@HOST:PORT?...` يحمل البروتوكول والمنفذ وبيانات الاتصال المطلوبة. أما `Germany 149.102.146.143` فهو عنوان مرجعي فقط؛ لا يتضمن البروتوكول أو المنفذ أو بيانات المصادقة، ولذلك يعرضه التطبيق كـ IP-only ولا يسمح بالاتصال به. لكي يعمل الخادم، أضف رابط VLESS كاملاً إلى صفحة Blogger، أو ملف `.ovpn` كاملاً إلى `servers.json`، أو إعداد WireGuard كاملاً. لا تُستخدم روابط ملفات تجريبية أو منافذ مخمّنة.

## قاعدة مهمة

Android يسمح باتصال VPN نشط واحد فقط في الوقت نفسه. افصل المحرك الحالي قبل بدء محرك آخر. ظهور حالة «بدأ المحرك» لا يثبت وحده أن الخادم يعمل أو أن حركة الإنترنت تمر عبر النفق؛ يجب اختبار الاتصال على جهاز حقيقي وخادم صالح.

## البناء بواسطة GitHub Actions

ملف `.github/workflows/build.yml` يبني مصدر Xray ثم APK من خلال GitHub Actions. بعد رفع المشروع إلى مستودعك، افتح **Actions → Build Sari VPN APK → Run workflow** أو ادفع commit إلى `main`. بعد نجاح المهمة نزّل artifact باسم `SariVPN-MultiEngine-APK`؛ مدة الاحتفاظ 14 يوماً.

خطوات البناء الآلية:

1. Java 17 وGo 1.26.
2. Android SDK 35 وNDK `26.1.10909125`.
3. Gradle 8.13.
4. Gradle يشغّل `scripts/build-xray-aar.sh` تلقائياً أثناء `preBuild` ثم ينفذ `assembleDebug`.

للبناء المحلي، ثبّت JDK 17 وAndroid SDK/NDK وGo 1.26، واضبط `ANDROID_HOME` و`ANDROID_NDK_HOME`، ثم شغّل `gradle assembleDebug`. البناء الأصلي لـXray يحتاج إلى تنزيل تبعيات Go من الإنترنت.

## قيود وحماية

- روابط VLESS يجب أن تكون كاملة ومتوافقة مع النقل/الأمان المدعومين في `XrayConfigBuilder.kt`. الروابط القديمة أو المنتهية أو التي لا تطابق إعداد الخادم قد تفشل.
- قائمة Blogger يتم جلبها وقت التحديث؛ لا يضمن التطبيق بقاء خادم عام متاحاً.
- لا يضمن VPN اتصالاً مجانياً بالبيانات الخلوية، ولا يُعفي من رسوم مزود الشبكة.
- تجنب إدخال بيانات حساسة عبر خوادم عامة مجهولة.
- يحتوي المصدر على إشعارات تراخيص ومصادر المكونات في `THIRD-PARTY-NOTICES.md`.

## ملفات رئيسية

- `app/src/main/java/africa/saritunnel/MainActivity.kt` — الواجهة الرئيسية (Home / Location / Settings) لمحرك VLESS: الاتصال، اختيار الخادم، الفرز، اختبار الـ ping.
- `app/src/main/java/africa/saritunnel/VlessSubscriptionManager.kt` — جلب وتحليل روابط `vless://` من Blogger، التخزين المحلي، والتحديث كل 24 ساعة.
- `app/src/main/java/africa/saritunnel/LatencyTester.kt` — قياس زمن اتصال TCP لكل خادم (لا يُشغَّل أثناء اتصال VPN).
- `app/src/main/java/africa/saritunnel/OpenVpnActivity.kt` — شاشة OpenVPN وTor السابقة (من Settings ← OpenVPN & Tor engines).
- `app/src/main/java/africa/saritunnel/XrayConfigBuilder.kt` — التحويل من رابط VLESS إلى إعداد Xray.
- `app/src/main/java/africa/saritunnel/XrayVpnService.kt` — دورة حياة نفق Xray/TUN.
- `xray-go/xray_bridge.go` — جسر Xray الأصلي وحماية مقابس الاتصال من التوجيه داخل النفق نفسه.
- `app/src/main/java/africa/saritunnel/OpenVpnClient.kt` — التحكم بمحرك OpenVPN المرافق.

## إعداد قائمة الخوادم الاحتياطية

`servers.json` ملف JSON عام يمكن تحديثه من المستودع أو استضافته على موقعك. أضف إلى `servers` العناصر التي تحتوي على `name`, `country`, `ip`, و`url` يشير إلى ملف HTTPS حقيقي ينتهي بـ`.ovpn`. يمكن استخدام `config` بدلاً من `url` لتضمين ملف إعداد كامل إذا كنت تتحكم في الوصول إلى المستودع؛ لا تنشر بيانات دخول خاصة في مستودع عام. عناصر `addresses` تعرض عناوين IP فقط ولا تتيح الاتصال. لا تضف عنواناً إلى `servers` ما لم تتحقق أن رابط الإعداد يُرجع ملف OpenVPN صالحاً.

## حدود الاختبار

هذه حزمة مصدر كاملة وليست APK مختبراً. لا يتوفر في بيئة إعداد الملف اتصال إنترنت لتنزيل مستودعات Gradle/Go أو اختبار خوادم عامة. يجب تشغيل GitHub Actions والتحقق من سجل البناء، ثم اختبار النفق على هاتف فعلي. كما أن نجاح بدء Xray وحده ليس دليلاً كافياً على مرور حركة الإنترنت؛ اختبر عنوان IP الخارجي وDNS بعد الاتصال.


## Performance tuning notes

- Xray TUN MTU is set to 1280 as a conservative mobile-network value; a larger MTU may perform better on some networks, so this is not a universal speed guarantee.
- Xray does not enable payload compression by default in this configuration.
- BBR is a server-side Linux TCP congestion-control setting. It can only be enabled on a VPS the operator controls; the Android app cannot enable it on a third-party server.
- OpenVPN automatic selection prioritizes the lowest published ping among complete profiles. Published ping can be stale; existing connection verification and failover still determine whether a profile actually works.


## الإصدار 2.2.0 — واجهة جديدة وتغذية Blogger فقط

- **المصدر الوحيد لخوادم VLESS:** `https://connection7876.blogspot.com/2026/10/blog-post.html`. أُزيلت مصادر GitHub غير المرتبطة، ويُحذف أي كاش قديم منها عند أول تشغيل.
- **التحليل:** الصفحة تضع اسم الدولة في السطر الذي يسبق كل رابط؛ يُستخدم هذا الاسم أولاً، ثم رمز الدولة داخل اسم الخادم (مثل `vless-au_xxxxx`)، ثم نطاق الدولة في اسم المضيف، وأخيراً استعلام اختياري إلى `ipwho.is` (HTTPS، حتى 30 استعلاماً لكل تحديث) للخوادم التي يتعذر تحديد دولتها. الروابط المقتطعة (`...`) تُتجاهل.
- **الكاش:** `files/vless_servers.json`. يُعرض فوراً عند فتح التطبيق، ويُحدَّث تلقائياً كل 24 ساعة (WorkManager + فحص عند الفتح). عند فشل الشبكة يبقى الكاش كما هو.
- **الاختبار:** زمن اتصال TCP يُقاس لكل منفذ فريد (6 اختبارات متوازية). هذا يثبت أن المنفذ يستجيب فقط. حالة «Connected» لا تظهر إلا بعد نجاح طلب HTTPS فعلي عبر النفق؛ وإلا يُغلق النفق ويُعرض الفشل. «Fastest» لا يُعرض إلا لخادم تم اختباره.
- **الفرز:** Fastest (أقل ping)، Best (ping مع تعديل بحسب نتائج الاتصال الفعلية السابقة)، Country (أبجدياً). التجميع حسب الدولة دائماً، ويمكن طيّ كل دولة.
- **إصلاحات المحرك:** دعم `type=raw` (مرادف TCP في Xray) الذي تستخدمه الصفحة، وحلّ اسم المضيف قبل إنشاء النفق مع الحفاظ على SNI وHost، وإعادة تشغيل النفق عند تبديل الخادم، ومعالجة `onRevoke`.
- **سير البناء:** أُعيد إنشاء `.github/workflows/build.yml` (كان مذكوراً في README لكنه غير موجود في الـ ZIP).
