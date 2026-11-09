package batari.core

import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher

/** ما يخص خادم JBD السحابي ولا يحتاج أندرويد. */
object CloudCrypto {
    /** المفتاح العام المضمَّن في تطبيق JBD الرسمي لتشفير كلمة المرور قبل إرسالها. */
    private const val PUBLIC_KEY =
        "MFwwDQYJKoZIhvcNAQEBBQADSwAwSAJBANL378k3RiZHWx5AfJqdH9xRNBmD9wGD" +
            "2iRe41HdTNF8RUhNnHit5NpMNtGL0NPTSSpPjjI1kJfVorRvaQerUgkCAwEAAQ=="

    /** RSA/PKCS1 ثم Base64 — كما يفعل التطبيق الرسمي (JSEncrypt). */
    fun encryptPassword(password: String): String {
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY)))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return Base64.getEncoder().encodeToString(cipher.doFinal(password.toByteArray(Charsets.UTF_8)))
    }

    /**
     * المسارات الوحيدة التي يُسمح للتطبيق بطلبها من الخادم: دخول وقراءة فقط.
     * مسارات الكتابة والتحكم (writeOneParam، factorySet، ota، unBind…) غير مدرجة عمدًا.
     */
    private val ALLOWED = setOf(
        "GET api/auth/code",
        "POST api/auth/appLogin",
        "GET bluetooth/server/device/myList",
        "GET bluetooth/server/device/realtimeInfo",
        "POST bluetooth/server/ess/readCollectInfo",
        "GET bluetooth/server/ess/collectInfo",
        "POST bluetooth/server/ess/readBaseInfo",
        "GET bluetooth/server/ess/baseInfo",
        "GET bluetooth/server/cmd/execStatus",
    )

    fun isAllowed(method: String, path: String): Boolean = "${method.uppercase()} ${path.substringBefore('?')}" in ALLOWED

    private val SENSITIVE = Regex("(mac|addr|uuid|sn|serial|name|email|mail|phone|mobile|user|token|account|nick|avatar|ssid|pwd|password|\\bid|Id$|ip$)", RegexOption.IGNORE_CASE)

    /** يحجب قيم الحقول الحساسة في نص JSON (للتقارير)، ويُبقي الأرقام والقياسات كما هي. */
    fun maskJson(json: String): String =
        Regex("\"([^\"\\\\]+)\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|-?\\d[\\d.]*)").replace(json) { m ->
            val key = m.groupValues[1]
            val raw = m.groupValues[2]
            if (!SENSITIVE.containsMatchIn(key)) m.value
            else {
                val v = raw.trim('"')
                val masked = if (v.length <= 4) "••" else v.take(2) + "•••" + v.takeLast(2)
                "\"$key\":\"$masked\""
            }
        }
}
