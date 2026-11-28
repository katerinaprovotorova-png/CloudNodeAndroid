package io.nekohasekai.sagernet.ui

import android.content.Context
import android.provider.Settings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * CloudNode app API client.
 *
 * Session limits and accounting are intentionally NOT stored on the device.
 * The only persisted value is the bearer credential used to authenticate this
 * installation with the CloudNode API.
 */
internal object CloudNodeApi {
    private const val BASE_URL = "https://cloudnodes.ru/api"
    private const val PREFS = "cloudnode_api_auth"
    private const val TOKEN_KEY = "access_token"
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun deviceId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: throw IOException("Не удалось определить устройство")

    @Synchronized
    private fun accessToken(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(TOKEN_KEY, null)?.takeIf { it.isNotBlank() }?.let { return it }

        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
        val payload = JSONObject()
            .put("device_id", deviceId(context))
            .put("platform", "android")
            .put("app_version", version)

        val request = Request.Builder()
            .url("$BASE_URL/app/register")
            .post(payload.toString().toRequestBody(JSON))
            .build()

        val result = executeRequest(request)
        val token = result.optString("access_token")
        if (token.isBlank()) throw IOException("Сервер не выдал токен устройства")
        prefs.edit().putString(TOKEN_KEY, token).apply()
        return token
    }

    private fun executeRequest(request: Request): JSONObject {
        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("CloudNode API: HTTP ${response.code}")
            }
            return if (raw.isBlank()) JSONObject() else JSONObject(raw)
        }
    }

    private fun call(
        context: Context,
        method: String,
        path: String,
        payload: JSONObject? = null
    ): JSONObject {
        val token = accessToken(context)
        val builder = Request.Builder()
            .url("$BASE_URL$path")
            .header("Authorization", "Bearer $token")
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post((payload ?: JSONObject()).toString().toRequestBody(JSON))
            else -> throw IllegalArgumentException("Unsupported API method")
        }
        return executeRequest(builder.build())
    }

    fun isPaid(context: Context): Boolean =
        call(context, "GET", "/app/paid").optBoolean("is_paid", false)

    fun startSession(context: Context): JSONObject =
        call(context, "POST", "/app/session/start")

    fun sessionStatus(context: Context): JSONObject =
        call(context, "GET", "/app/session/status")

    fun claimReward(context: Context, rewardId: String, rewardType: String): JSONObject {
        val payload = JSONObject()
            .put("reward_id", rewardId)
            .put("reward_type", rewardType)
            .put("ad_network", "yandex")
            .put("sdk_rewarded", true)
        return call(context, "POST", "/app/session/reward", payload)
    }

    fun stopSession(context: Context): JSONObject =
        call(context, "POST", "/app/session/stop")

    fun createBridge(context: Context): JSONObject =
        call(context, "POST", "/app/bridge")
}
