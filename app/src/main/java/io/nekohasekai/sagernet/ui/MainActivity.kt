package io.nekohasekai.sagernet.ui

import android.Manifest.permission.POST_NOTIFICATIONS
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.provider.Settings
import android.util.Base64
import android.view.KeyEvent
import android.view.MenuItem
import androidx.activity.addCallback
import androidx.annotation.IdRes
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceDataStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import com.yandex.mobile.ads.banner.BannerAdEventListener
import com.yandex.mobile.ads.banner.BannerAdSize
import com.yandex.mobile.ads.common.AdError
import com.yandex.mobile.ads.common.AdRequest
import com.yandex.mobile.ads.common.AdRequestConfiguration
import com.yandex.mobile.ads.common.AdRequestError
import com.yandex.mobile.ads.common.ImpressionData
import com.yandex.mobile.ads.common.MobileAds
import com.yandex.mobile.ads.rewarded.Reward
import com.yandex.mobile.ads.rewarded.RewardedAd
import com.yandex.mobile.ads.rewarded.RewardedAdEventListener
import com.yandex.mobile.ads.rewarded.RewardedAdLoader
import com.yandex.mobile.ads.rewarded.RewardedAdLoadListener
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.databinding.LayoutMainBinding
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.PluginEntry
import io.nekohasekai.sagernet.group.GroupInterfaceAdapter
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.alert
import io.nekohasekai.sagernet.ktx.isPlay
import io.nekohasekai.sagernet.ktx.isPreview
import io.nekohasekai.sagernet.ktx.launchCustomTab
import io.nekohasekai.sagernet.ktx.parseProxies
import io.nekohasekai.sagernet.ktx.readableMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.matsuri.nb4a.utils.Util
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class MainActivity : ThemedActivity(),
    SagerConnection.Callback,
    OnPreferenceDataStoreChangeListener,
    NavigationView.OnNavigationItemSelectedListener {

    lateinit var binding: LayoutMainBinding
    lateinit var navigation: NavigationView

    private var sessionTimerJob: Job? = null
    private var rewardedAdLoader: RewardedAdLoader? = null
    private var rewardedAd: RewardedAd? = null

    private val FREE_SESSION_DURATION_MS = 30 * 60 * 1000L
    private val FREE_TRAFFIC_LIMIT_BYTES = 250L * 1024L * 1024L
    private val sessionPrefs by lazy {
        getSharedPreferences("cloudnode_free_session", MODE_PRIVATE)
    }
    private var lastReportedTrafficBytes = 0L
    private var trafficLimitReached = false
    private var sessionEndReason: String? = null

    // ✅ URL API через Nginx прокси (HTTPS 443)
    private val API_BASE_URL = "https://cloudnodes.ru/api"
    private val API_KEY = "SuperSecretKey123XYZ"

    // ✅ Боевые ID рекламы
    private val BANNER_AD_UNIT_ID = "R-M-20422645-1"
    private val REWARDED_AD_UNIT_ID = "R-M-20422645-2"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = LayoutMainBinding.inflate(layoutInflater)
        binding.fab.initProgress(binding.fabProgress)
        if (themeResId !in intArrayOf(R.style.Theme_SagerNet_Black)) {
            navigation = binding.navView
            binding.drawerLayout.removeView(binding.navViewBlack)
        } else {
            navigation = binding.navViewBlack
            binding.drawerLayout.removeView(binding.navView)
        }
        navigation.setNavigationItemSelectedListener(this)

        if (savedInstanceState == null) {
            displayFragmentWithId(R.id.nav_configuration)
        }
        onBackPressedDispatcher.addCallback {
            if (supportFragmentManager.findFragmentById(R.id.fragment_holder) is ConfigurationFragment) {
                moveTaskToBack(true)
            } else {
                displayFragmentWithId(R.id.nav_configuration)
            }
        }

        // ✅ Логика кнопки самолётика с проверкой платного статуса
        binding.fab.setOnClickListener {
            if (DataStore.serviceState.canStop) {
                clearFreeSession()
                deleteUserFromServer { SagerNet.stopService() }
            } else {
                checkPaidStatus { isPaid ->
                    if (isPaid) {
                        runOnUiThread {
                            snackbar("Подключение...").show()
                            connect.launch(null)
                        }
                    } else {
                        showRewardedAd {
                            startNewFreeSession()
                            fetchAndConnectTrial()
                        }
                    }
                }
            }
        }

        binding.stats.setOnClickListener { if (DataStore.serviceState.connected) binding.stats.testConnection() }

        setContentView(binding.root)

        // ✅ Инициализация рекламы с естественным размером баннера
        MobileAds.initialize(this) {
            binding.bannerAdView.setAdUnitId(BANNER_AD_UNIT_ID)
            val adWidthPx = resources.displayMetrics.widthPixels
            binding.bannerAdView.setAdSize(BannerAdSize.stickySize(this, adWidthPx))

            binding.bannerAdView.setBannerAdEventListener(object : BannerAdEventListener {
                override fun onAdLoaded() {
                    binding.bannerAdView.post {
                        binding.bannerAdView.post {
                            val bannerHeight = binding.bannerAdView.height
                            val extraMargin = (8 * resources.displayMetrics.density).toInt()
                            val lp = binding.stats.layoutParams as CoordinatorLayout.LayoutParams
                            lp.bottomMargin = bannerHeight + extraMargin
                            binding.stats.layoutParams = lp
                        }
                    }
                }
                override fun onAdFailedToLoad(error: AdRequestError) {
                    runOnUiThread { snackbar("Баннер: код ${error.code} — ${error.description}").show() }
                }
                override fun onAdClicked() {}
                override fun onLeftApplication() {}
                override fun onReturnedToApplication() {}
                override fun onImpression(impressionData: ImpressionData?) {}
            })
            binding.bannerAdView.loadAd(AdRequest.Builder().build())
            loadRewardedAd()
        }

        changeState(BaseService.State.Idle)
        connection.connect(this, this)
        DataStore.configurationStore.registerChangeListener(this)
        GroupManager.userInterface = GroupInterfaceAdapter(this)

        if (intent?.action == Intent.ACTION_VIEW) {
            onNewIntent(intent)
        }

        refreshNavMenu(DataStore.enableClashAPI)

        if (Build.VERSION.SDK_INT >= 33) {
            val checkPermission = ContextCompat.checkSelfPermission(this@MainActivity, POST_NOTIFICATIONS)
            if (checkPermission != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this@MainActivity, arrayOf(POST_NOTIFICATIONS), 0)
            }
        }

        if (isPreview) {
            MaterialAlertDialogBuilder(this)
                .setTitle(BuildConfig.PRE_VERSION_NAME)
                .setMessage(R.string.preview_version_hint)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    // ================= ПРОВЕРКА ПЛАТНОГО СТАТУСА (БЕЗОПАСНАЯ) =================
    private fun checkPaidStatus(onResult: (Boolean) -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            val isPaid = try {
                val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                val username = "tg_$deviceId"
                val client = OkHttpClient.Builder()
                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val req = Request.Builder()
                    .url("$API_BASE_URL/check_paid?username=$username")
                    .header("X-API-Key", API_KEY)
                    .get()
                    .build()

                client.newCall(req).execute().use { response ->
                    val body = response.body?.string() ?: "{}"
                    if (response.isSuccessful) {
                        JSONObject(body).optBoolean("is_paid", false)
                    } else {
                        android.util.Log.e("CloudNode", "CHECK_PAID HTTP ERROR: ${response.code}")
                        false
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("CloudNode", "CHECK_PAID EXCEPTION", e)
                false
            }

            runOnUiThread {
                if (!isFinishing && !isDestroyed) onResult(isPaid)
            }
        }
    }

    // ================= СОЗДАНИЕ МОСТА И ОТКРЫТИЕ БОТА =================
    private fun createBridgeAndOpenBot() {
        snackbar("Создаём временный доступ...").show()
        binding.fab.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                val username = "bridge_$deviceId"
                val client = OkHttpClient.Builder()
                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                val req = Request.Builder()
                    .url("$API_BASE_URL/create_bridge")
                    .header("X-API-Key", API_KEY)
                    .post("""{"username":"$username"}""".toRequestBody("application/json".toMediaType()))
                    .build()

                val resp = client.newCall(req).execute()
                val json = JSONObject(resp.body?.string() ?: throw Exception("Пустой ответ"))

                if (!json.optBoolean("success", false)) {
                    throw Exception("Не удалось создать временный доступ")
                }

                val link = json.optString("direct_link", null)
                if (link.isNullOrEmpty()) {
                    throw Exception("Сервер не вернул ссылку")
                }

                val beans = parseProxies(link)
                if (beans.isEmpty()) throw Exception("Не удалось распарсить ссылку")
                val bean = beans.first()
                val targetGroupId = DataStore.selectedGroup.takeIf { it > 0 } ?: 1L
                GroupManager.clearGroup(targetGroupId)
                val entity = ProfileManager.createProfile(targetGroupId, bean)
                DataStore.selectedGroup = targetGroupId
                DataStore.selectedProxy = entity.id
                DataStore.currentProfile = entity.id

                runOnUiThread {
                    binding.fab.isEnabled = true
                    snackbar("✅ Временный доступ активирован! Открываем бота...").show()
                    launchCustomTab("https://t.me/CloudNodeBot?start=from_app_android_$deviceId")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.fab.isEnabled = true
                    snackbar("Ошибка: ${e.readableMessage}").show()
                    launchCustomTab("https://t.me/CloudNodeBot?start=from_app")
                }
            }
        }
    }

    // ================= РЕКЛАМА =================

    private fun loadRewardedAd() {
        if (rewardedAdLoader == null) {
            rewardedAdLoader = RewardedAdLoader(this).apply {
                setAdLoadListener(object : RewardedAdLoadListener {
                    override fun onAdLoaded(ad: RewardedAd) { rewardedAd = ad }
                    override fun onAdFailedToLoad(adRequestError: AdRequestError) {}
                })
            }
        }
        val config = AdRequestConfiguration.Builder(REWARDED_AD_UNIT_ID).build()
        rewardedAdLoader?.loadAd(config)
    }

    private fun showRewardedAd(afterReward: () -> Unit) {
        val ad = rewardedAd
        if (ad == null) {
            snackbar("Реклама ещё загружается. Доступ не начислен. Попробуйте через несколько секунд.").show()
            loadRewardedAd()
            return
        }

        var rewardEarned = false
        var actionCompleted = false

        ad.setAdEventListener(object : RewardedAdEventListener {
            override fun onAdShown() {}
            override fun onAdClicked() {}
            override fun onAdImpression(impressionData: ImpressionData?) {}

            // SDK подтверждает награду. Само закрытие рекламы наградой не считается.
            override fun onRewarded(reward: Reward) {
                rewardEarned = true
            }

            override fun onAdFailedToShow(adError: AdError) {
                ad.setAdEventListener(null)
                rewardedAd = null
                snackbar("Не удалось показать рекламу. Доступ не начислен. Попробуйте позже.").show()
                loadRewardedAd()
            }

            override fun onAdDismissed() {
                ad.setAdEventListener(null)
                rewardedAd = null

                if (rewardEarned && !actionCompleted) {
                    actionCompleted = true
                    afterReward()
                } else if (!rewardEarned) {
                    snackbar("Награда не подтверждена. Доступ не начислен.").show()
                }

                loadRewardedAd()
            }
        })
        ad.show(this)
    }

    // ================= УДАЛЕНИЕ ПОЛЬЗОВАТЕЛЯ ПРИ ОТКЛЮЧЕНИИ =================

    private fun deleteUserFromServer(afterDelete: () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                val username = "android_$deviceId"
                val client = OkHttpClient.Builder().connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS).build()
                val deleteReq = Request.Builder()
                    .url("$API_BASE_URL/delete_user?username=$username")
                    .header("X-API-Key", API_KEY)
                    .delete()
                    .build()

                client.newCall(deleteReq).enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                        runOnUiThread { afterDelete() }
                    }
                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        response.close()
                        runOnUiThread { afterDelete() }
                    }
                })
            } catch (e: Exception) {
                runOnUiThread { afterDelete() }
            }
        }
    }

    // ================= ПОЛУЧЕНИЕ КОНФИГУРАЦИИ С ЛОГИРОВАНИЕМ =================

    private fun fetchAndConnectTrial() {
        snackbar("Проверка доступа...").show()
        binding.fab.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                val username = "android_$deviceId"
                
                android.util.Log.d("CloudNode", "API_BASE_URL: $API_BASE_URL")
                android.util.Log.d("CloudNode", "Username: $username")

                val client = OkHttpClient.Builder()
                    .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                // 1. Проверяем существующего пользователя
                val checkUrl = "$API_BASE_URL/check_user?username=$username"
                android.util.Log.d("CloudNode", "CHECK URL: $checkUrl")
                
                val checkReq = Request.Builder()
                    .url(checkUrl)
                    .header("X-API-Key", API_KEY)
                    .get()
                    .build()

                val checkResp = client.newCall(checkReq).execute()
                val checkBody = checkResp.body?.string() ?: throw Exception("Пустой ответ от check_user")
                android.util.Log.d("CloudNode", "CHECK RESPONSE: $checkBody")

                val checkJson = JSONObject(checkBody)
                val exists = checkJson.optBoolean("exists", false)
                val serverLink = checkJson.optString("direct_link", null)
                val targetGroupId = DataStore.selectedGroup.takeIf { it > 0 } ?: 1L

                if (exists && !serverLink.isNullOrEmpty() && DataStore.selectedProxy > 0) {
                    runOnUiThread {
                        binding.fab.isEnabled = true
                        snackbar("Подключение...").show()
                        connect.launch(null)
                    }
                    return@launch
                }

                // 2. Создаем нового пользователя
                GroupManager.clearGroup(targetGroupId)

                val createUrl = "$API_BASE_URL/create_user"
                android.util.Log.d("CloudNode", "CREATE URL: $createUrl")

                val createReq = Request.Builder()
                    .url(createUrl)
                    .header("X-API-Key", API_KEY)
                    .post("""{"username":"$username"}""".toRequestBody("application/json".toMediaType()))
                    .build()

                val createResp = client.newCall(createReq).execute()
                val createBody = createResp.body?.string() ?: throw Exception("Пустой ответ от create_user")
                android.util.Log.d("CloudNode", "CREATE RESPONSE: $createBody")

                val createJson = JSONObject(createBody)

                if (!createJson.optBoolean("success", false)) {
                    val details = createJson.optString("details", createJson.optString("error", "Неизвестная ошибка"))
                    throw Exception("Сервер не создал пользователя: $details")
                }

                val newLink = createJson.optString("direct_link", null)
                if (newLink.isNullOrEmpty()) {
                    throw Exception("Сервер вернул success=true, но не дал ссылку")
                }

                val beans = parseProxies(newLink)
                if (beans.isEmpty()) {
                    throw Exception("Не удалось распарсить конфигурацию")
                }
                val bean = beans.first()

                val entity: ProxyEntity = ProfileManager.createProfile(targetGroupId, bean)

                DataStore.selectedGroup = targetGroupId
                DataStore.selectedProxy = entity.id
                DataStore.currentProfile = entity.id

                runOnUiThread {
                    binding.fab.isEnabled = true
                    snackbar("✅ Доступ получен! Подключаем...").show()
                    connect.launch(null)
                }
            } catch (e: Exception) {
                android.util.Log.e("CloudNode", "FATAL ERROR", e)
                runOnUiThread {
                    binding.fab.isEnabled = true
                    snackbar("Ошибка: ${e.readableMessage}").show()
                }
            }
        }
    }

    // ================= БЕСПЛАТНАЯ СЕССИЯ: 30 МИНУТ / 250 МБ =================

    private fun showSessionLimitDialog() {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setTitle("⏱ Бесплатные 30 минут истекли")
            .setMessage("Чтобы продолжить пользоваться VPN, посмотрите рекламу и получите новые 30 минут и дополнительный трафик. Либо перейдите к покупке Premium.")
            .setPositiveButton("🎬 Ещё 30 минут за рекламу") { _, _ ->
                showRewardedAd {
                    startNewFreeSession()
                    fetchAndConnectTrial()
                }
            }
            .setNeutralButton("💳 Купить Premium") { _, _ ->
                createBridgeAndOpenBot()
            }
            .setNegativeButton("❌ Завершить и удалить доступ") { _, _ ->
                clearFreeSession()
                deleteUserFromServer { SagerNet.stopService() }
            }
            .setCancelable(false)
            .show()
    }

    private fun showTrafficLimitDialog() {
        if (isFinishing || isDestroyed) return
        MaterialAlertDialogBuilder(this)
            .setTitle("📶 Пакет трафика израсходован")
            .setMessage("VPN отключён. Посмотрите рекламу, чтобы получить дополнительный трафик. Оставшееся время текущей сессии сохранится.")
            .setPositiveButton("🎬 Получить дополнительный трафик") { _, _ ->
                showRewardedAd {
                    val currentLimit = sessionPrefs.getLong("traffic_limit_bytes", FREE_TRAFFIC_LIMIT_BYTES)
                    sessionPrefs.edit()
                        .putLong("traffic_limit_bytes", currentLimit + FREE_TRAFFIC_LIMIT_BYTES)
                        .apply()
                    trafficLimitReached = false
                    sessionEndReason = null
                    lastReportedTrafficBytes = 0L

                    if (sessionPrefs.getLong("deadline_at", 0L) > System.currentTimeMillis()) {
                        snackbar("Награда подтверждена: дополнительный трафик начислен. Подключаем...").show()
                        connect.launch(null)
                    } else {
                        showSessionLimitDialog()
                    }
                }
            }
            .setNeutralButton("💳 Купить Premium") { _, _ ->
                createBridgeAndOpenBot()
            }
            .setNegativeButton("❌ Завершить и удалить доступ") { _, _ ->
                clearFreeSession()
                deleteUserFromServer { SagerNet.stopService() }
            }
            .setCancelable(false)
            .show()
    }

    private fun startNewFreeSession() {
        val now = System.currentTimeMillis()
        sessionPrefs.edit()
            .putLong("deadline_at", now + FREE_SESSION_DURATION_MS)
            .putLong("traffic_used_bytes", 0L)
            .putLong("traffic_limit_bytes", FREE_TRAFFIC_LIMIT_BYTES)
            .apply()
        lastReportedTrafficBytes = 0L
        trafficLimitReached = false
        sessionEndReason = null
    }

    private fun clearFreeSession() {
        sessionTimerJob?.cancel()
        sessionPrefs.edit().clear().apply()
        lastReportedTrafficBytes = 0L
        trafficLimitReached = false
        sessionEndReason = null
    }

    private fun handleFreeTrafficUpdate(stats: SpeedDisplayData) {
        if (!DataStore.serviceState.connected || trafficLimitReached) return

        val deadline = sessionPrefs.getLong("deadline_at", 0L)
        if (deadline <= 0L) return

        val totalBytes = (stats.txTotal + stats.rxTotal).coerceAtLeast(0L)
        val deltaBytes = if (totalBytes >= lastReportedTrafficBytes) {
            totalBytes - lastReportedTrafficBytes
        } else {
            // Счётчик Xray мог обнулиться после переподключения.
            totalBytes
        }
        lastReportedTrafficBytes = totalBytes

        if (deltaBytes > 0L) {
            val used = sessionPrefs.getLong("traffic_used_bytes", 0L) + deltaBytes
            sessionPrefs.edit().putLong("traffic_used_bytes", used).apply()
        }

        val usedBytes = sessionPrefs.getLong("traffic_used_bytes", 0L)
        val trafficLimit = sessionPrefs.getLong("traffic_limit_bytes", FREE_TRAFFIC_LIMIT_BYTES)
        val remainingTime = (deadline - System.currentTimeMillis()).coerceAtLeast(0L)
        binding.stats.updateCloudNodeStatus(usedBytes, remainingTime)

        if (usedBytes >= trafficLimit) {
            trafficLimitReached = true
            sessionEndReason = "traffic"
            runOnUiThread {
                SagerNet.stopService()
                showTrafficLimitDialog()
            }
        }
    }

    private fun startSessionTimer() {
        val deadline = sessionPrefs.getLong("deadline_at", 0L)
        if (deadline <= 0L) return

        sessionTimerJob?.cancel()
        sessionTimerJob = MainScope().launch {
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) {
                    sessionEndReason = "time"
                    SagerNet.stopService()
                    sessionTimerJob = null
                    showSessionLimitDialog()
                    break
                }

                if (DataStore.serviceState.connected) {
                    val used = sessionPrefs.getLong("traffic_used_bytes", 0L)
                    binding.stats.updateCloudNodeStatus(used, remaining)
                }
                delay(minOf(1000L, remaining))
            }
        }
    }

    // ===============================================================

    fun refreshNavMenu(clashApi: Boolean) {
        if (::navigation.isInitialized) {
            navigation.menu.findItem(R.id.nav_traffic)?.isVisible = clashApi
        }
    }

    // ✅ ОБРАБОТКА АВТОМАТИЧЕСКОГО ИМПОРТА ПОСЛЕ ОПЛАТЫ
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val uri = intent.data ?: return
        
        // Обработка автоматического импорта после оплаты через бота
        if (uri.scheme == "https" && uri.host == "t.me" && uri.path?.startsWith("/CloudNodeBot") == true) {
            val startParam = uri.getQueryParameter("start") ?: return
            if (startParam.startsWith("import_")) {
                val encodedLink = startParam.removePrefix("import_")
                try {
                    val linkBytes = Base64.decode(encodedLink, Base64.DEFAULT)
                    val link = String(linkBytes)
                    lifecycleScope.launch(Dispatchers.IO) {
                        importProfile(Uri.parse(link))
                    }
                } catch (e: Exception) {
                    runOnUiThread { snackbar("Ошибка импорта: ${e.readableMessage}").show() }
                }
                return
            }
        }
        
        // Стандартная обработка подписок и профилей
        lifecycleScope.launch(Dispatchers.IO) {
            if (uri.scheme == "sn" && uri.host == "subscription" || uri.scheme == "clash") {
                importSubscription(uri)
            } else {
                importProfile(uri)
            }
        }
    }

    fun urlTest(): Int {
        if (!DataStore.serviceState.connected || connection.service == null) {
            error("not started")
        }
        return connection.service!!.urlTest()
    }

    suspend fun importSubscription(uri: Uri) {
        val group: ProxyGroup
        val url = uri.getQueryParameter("url")
        if (!url.isNullOrBlank()) {
            group = ProxyGroup(type = GroupType.SUBSCRIPTION)
            val subscription = SubscriptionBean()
            group.subscription = subscription
            subscription.link = url
            group.name = uri.getQueryParameter("name")
        } else {
            val data = uri.encodedQuery.takeIf { !it.isNullOrBlank() } ?: return
            try {
                group = KryoConverters.deserialize(
                    ProxyGroup().apply { export = true }, Util.zlibDecompress(Util.b64Decode(data))
                ).apply { export = false }
            } catch (e: Exception) {
                runOnUiThread { alert(e.readableMessage).show() }
                return
            }
        }
        val name = group.name.takeIf { !it.isNullOrBlank() } ?: group.subscription?.link ?: group.subscription?.token
        if (name.isNullOrBlank()) return
        group.name = group.name.takeIf { !it.isNullOrBlank() } ?: ("Subscription #" + System.currentTimeMillis())

        runOnUiThread {
            displayFragmentWithId(R.id.nav_group)
            MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.subscription_import)
                .setMessage(getString(R.string.subscription_import_message, name))
                .setPositiveButton(R.string.yes) { _, _ -> lifecycleScope.launch(Dispatchers.IO) { finishImportSubscription(group) } }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private suspend fun finishImportSubscription(subscription: ProxyGroup) {
        GroupManager.createGroup(subscription)
        GroupUpdater.startUpdate(subscription, true)
    }

    suspend fun importProfile(uri: Uri) {
        val profile = try {
            parseProxies(uri.toString()).getOrNull(0) ?: error(getString(R.string.no_proxies_found))
        } catch (e: Exception) {
            runOnUiThread { alert(e.readableMessage).show() }
            return
        }
        runOnUiThread {
            MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.profile_import)
                .setMessage(getString(R.string.profile_import_message, profile.displayName()))
                .setPositiveButton(R.string.yes) { _, _ -> lifecycleScope.launch(Dispatchers.IO) { finishImportProfile(profile) } }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private suspend fun finishImportProfile(profile: AbstractBean) {
        val targetId = DataStore.selectedGroupForImport()
        ProfileManager.createProfile(targetId, profile)
        runOnUiThread {
            displayFragmentWithId(R.id.nav_configuration)
            snackbar(resources.getQuantityString(R.plurals.added, 1, 1)).show()
        }
    }

    override fun missingPlugin(profileName: String, pluginName: String) {
        val pluginEntity = PluginEntry.find(pluginName)
        if (pluginEntity == null) {
            snackbar(getString(R.string.plugin_unknown, pluginName)).show()
            return
        }
        MaterialAlertDialogBuilder(this).setTitle(R.string.missing_plugin)
            .setMessage(getString(R.string.profile_requiring_plugin, profileName, pluginEntity.displayName))
            .setPositiveButton(R.string.action_download) { _, _ -> showDownloadDialog(pluginEntity) }
            .setNeutralButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.action_learn_more) { _, _ -> launchCustomTab("https://matsuridayo.github.io/nb4a-plugin/") }
            .show()
    }

    private fun showDownloadDialog(pluginEntry: PluginEntry) {
        var index = 0; var playIndex = -1; var fdroidIndex = -1
        val items = mutableListOf<String>()
        if (pluginEntry.downloadSource.playStore) { items.add(getString(R.string.install_from_play_store)); playIndex = index++ }
        if (pluginEntry.downloadSource.fdroid) { items.add(getString(R.string.install_from_fdroid)); fdroidIndex = index++ }
        items.add(getString(R.string.download)); val downloadIndex = index

        MaterialAlertDialogBuilder(this).setTitle(pluginEntry.name)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    playIndex -> launchCustomTab("https://play.google.com/store/apps/details?id=${pluginEntry.packageName}")
                    fdroidIndex -> launchCustomTab("https://f-droid.org/packages/${pluginEntry.packageName}/")
                    downloadIndex -> launchCustomTab(pluginEntry.downloadSource.downloadLink)
                }
            }.show()
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        if (item.isChecked) binding.drawerLayout.closeDrawers() else {
            return displayFragmentWithId(item.itemId)
        }
        return true
    }

    @SuppressLint("CommitTransaction")
    fun displayFragment(fragment: ToolbarFragment) {
        binding.stats.allowShow = true
        binding.fab.show()
        supportFragmentManager.beginTransaction().replace(R.id.fragment_holder, fragment).commitAllowingStateLoss()
        binding.drawerLayout.closeDrawers()
    }

    fun displayFragmentWithId(@IdRes id: Int): Boolean {
        when (id) {
            R.id.nav_configuration -> displayFragment(ConfigurationFragment())
            R.id.nav_group -> displayFragment(GroupFragment())
            R.id.nav_route -> displayFragment(RouteFragment())
            R.id.nav_settings -> displayFragment(SettingsFragment())
            R.id.nav_traffic -> displayFragment(WebviewFragment())
            R.id.nav_tools -> displayFragment(ToolsFragment())
            R.id.nav_logcat -> displayFragment(LogcatFragment())
            R.id.nav_donate -> { launchCustomTab("https://yoomoney.ru/to/4100119515794399"); return false }
            R.id.nav_about -> displayFragment(AboutFragment())
            else -> return false
        }
        navigation.menu.findItem(id).isChecked = true
        return true
    }

    private fun changeState(state: BaseService.State, msg: String? = null, animate: Boolean = false) {
        DataStore.serviceState = state
        binding.fab.changeState(state, DataStore.serviceState, animate)
        binding.stats.changeState(state)

        if (state == BaseService.State.Connected) {
            checkPaidStatus { isPaid ->
                if (isPaid) {
                    clearFreeSession()
                } else {
                    val deadline = sessionPrefs.getLong("deadline_at", 0L)
                    if (deadline > System.currentTimeMillis()) {
                        startSessionTimer()
                    } else {
                        sessionEndReason = "no_session"
                        SagerNet.stopService()
                        snackbar("Для бесплатного подключения сначала получите доступ за рекламу.").show()
                    }
                }
            }
        } else if (sessionEndReason != "traffic") {
            sessionTimerJob?.cancel()
        }

        if (msg != null) snackbar(getString(R.string.vpn_error, msg)).show()
    }

    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(binding.coordinator, text, Snackbar.LENGTH_LONG).apply {
            if (binding.fab.isShown) anchorView = binding.fab
        }
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        changeState(state, msg, true)
    }

    val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND, true)
    override fun onServiceConnected(service: ISagerNetService) = changeState(
        try { BaseService.State.values()[service.state] } catch (_: RemoteException) { BaseService.State.Idle }
    )

    override fun onServiceDisconnected() = changeState(BaseService.State.Idle)
    override fun onBinderDied() {
        connection.disconnect(this)
        connection.connect(this, this)
    }

    private val connect = registerForActivityResult(VpnRequestActivity.StartService()) {
        if (it) snackbar(R.string.vpn_permission_denied).show()
    }

    override fun cbSpeedUpdate(stats: SpeedDisplayData) {
        binding.stats.updateSpeed(stats.txRateProxy, stats.rxRateProxy)
        handleFreeTrafficUpdate(stats)
    }

    override fun cbTrafficUpdate(data: TrafficData) {
        lifecycleScope.launch(Dispatchers.IO) { ProfileManager.postUpdate(data) }
    }

    override fun cbSelectorUpdate(id: Long) {
        val old = DataStore.selectedProxy
        DataStore.selectedProxy = id
        DataStore.currentProfile = id
        lifecycleScope.launch(Dispatchers.IO) {
            ProfileManager.postUpdate(old, true)
            ProfileManager.postUpdate(id, true)
        }
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        when (key) {
            Key.SERVICE_MODE -> onBinderDied()
            Key.PROXY_APPS, Key.BYPASS_MODE, Key.INDIVIDUAL -> {
                if (DataStore.serviceState.canStop) {
                    snackbar(getString(R.string.need_reload)).setAction(R.string.apply) { SagerNet.reloadService() }.show()
                }
            }
        }
    }

    override fun onStart() {
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
        super.onStart()
    }

    override fun onStop() {
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND)
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        GroupManager.userInterface = null
        DataStore.configurationStore.unregisterChangeListener(this)
        connection.disconnect(this)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (super.onKeyDown(keyCode, event)) return true
                binding.drawerLayout.open()
                navigation.requestFocus()
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (binding.drawerLayout.isOpen) {
                    binding.drawerLayout.close()
                    return true
                }
            }
        }
        if (super.onKeyDown(keyCode, event)) return true
        if (binding.drawerLayout.isOpen) return false
        val fragment = supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
        return fragment != null && fragment.onKeyDown(keyCode, event)
    }
}
