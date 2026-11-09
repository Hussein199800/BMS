package batari.app.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import batari.core.CloudCrypto
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class HttpResult(val code: Int, val body: String) {
    val json: JSONObject? get() = runCatching { JSONObject(body) }.getOrNull()
}

class Captcha(val uuid: String, val png: ByteArray)

/** عميل خادم JBD السحابي: دخول وقراءة فقط، وكل طلب يمر على قائمة المسارات المسموحة. */
object JbdCloud {
    const val BASE = "https://cloud-sea.jiabaida.com"

    fun request(method: String, path: String, token: String?, body: JSONObject? = null): HttpResult {
        require(CloudCrypto.isAllowed(method, path)) { "مسار غير مسموح: $method $path" }
        val c = URL("$BASE/$path").openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.setRequestProperty("content-type", "application/json;charset=utf8")
            c.setRequestProperty("Accept-Language", "en-US")
            c.setRequestProperty("appVersion", "Li-ion:1.11.4")
            if (!token.isNullOrEmpty()) c.setRequestProperty("Authorization", token)
            if (body != null) {
                c.doOutput = true
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            return HttpResult(code, text)
        } finally {
            c.disconnect()
        }
    }

    fun captcha(): Captcha {
        val j = request("GET", "api/auth/code", null).json ?: error("رد غير مفهوم من الخادم")
        val img = j.getString("img").substringAfter("base64,")
        return Captcha(j.getString("uuid"), Base64.decode(img, Base64.DEFAULT))
    }

    /** @return الرمز (token) عند النجاح، أو رسالة الخادم عند الفشل. كلمة المرور تُشفَّر ولا تُحفظ. */
    fun login(username: String, password: String, code: String, uuid: String): Result<String> {
        val body = JSONObject()
            .put("username", username.trim())
            .put("password", CloudCrypto.encryptPassword(password))
            .put("code", code.trim())
            .put("uuid", uuid)
            .put("loginType", 1)
        val r = request("POST", "api/auth/appLogin", null, body)
        val token = r.json?.optString("token").orEmpty()
        return if (r.code == 200 && token.isNotEmpty()) Result.success(token)
        else Result.failure(Exception(r.json?.optString("message")?.takeIf { it.isNotBlank() } ?: "فشل الدخول (HTTP ${r.code})"))
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    /** يستخرج قائمة الأجهزة سواء كان الرد مصفوفة مباشرة أو داخل data. */
    fun deviceArray(body: String): JSONArray? =
        runCatching { JSONArray(body) }.getOrNull() ?: runCatching { JSONObject(body).optJSONArray("data") }.getOrNull()
}

/** يحفظ رمز الدخول مشفّرًا بمفتاح في Android Keystore. كلمة المرور لا تُحفظ إطلاقًا. */
class TokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("cloud", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build()
        )
        return g.generateKey()
    }

    fun save(token: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = c.iv + c.doFinal(token.toByteArray())
        prefs.edit().putString("token", Base64.encodeToString(data, Base64.NO_WRAP)).apply()
    }

    fun load(): String? = runCatching {
        val data = Base64.decode(prefs.getString("token", null) ?: return null, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12)) }
        String(c.doFinal(data, 12, data.size - 12))
    }.getOrNull()

    fun clear() = prefs.edit().remove("token").apply()

    private companion object { const val ALIAS = "batari_cloud_token" }
}
