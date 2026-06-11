package me.unknkriod.ang.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.net.VpnService
import java.net.HttpURLConnection
import java.net.URL
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import me.unknkriod.ang.R
import me.unknkriod.ang.core.CoreServiceManager
import me.unknkriod.ang.databinding.ActivityMainBinding
import me.unknkriod.ang.dto.ServersCache
import me.unknkriod.ang.extension.toast
import me.unknkriod.ang.handler.MmkvManager
import me.unknkriod.ang.handler.SettingsManager
import me.unknkriod.ang.handler.SubscriptionUpdater
import me.unknkriod.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.unknkriod.ang.AppConfig
import me.unknkriod.ang.dto.SubscriptionItem
import me.unknkriod.ang.dto.ProfileItem
import me.unknkriod.ang.util.LicenseProvider
import me.unknkriod.ang.util.MessageUtil
import me.unknkriod.ang.util.RemoteSubscription

import me.unknkriod.ang.handler.DiagnosticsManager
import me.unknkriod.ang.dto.DiagnosticService
import me.unknkriod.ang.handler.UpdateCheckerManager
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

class MainActivity : BaseActivity() {
    private val binding by lazy { ActivityMainBinding.inflate(layoutInflater) }
    private val mainViewModel: MainViewModel by viewModels()
    private val topServersAdapter by lazy { ServersAdapter(false) }
    private var selectionFromRecent: Boolean = false
    private var remoteSubscriptions: List<RemoteSubscription> = emptyList()
    private val expandedSubscriptions = mutableSetOf<String>()
    private var isFetchingRemote = false
    private var isSubscriptionUpdating = false
    private var isBatchTesting = false
    private var isSingleTesting = false
    private var lastSelectedServer: String? = null
    private var lastIsPremium = false
    private var isPostUpdatePingInProgress = false
    private var hasSeenTestResult = false
    private var lastTestStartTime = 0L
    private var isLicenseAuthInProgress = false
    private var isSwitchingServer = false
    private var isConnecting = false
    private var isDisconnecting = false
    private var switchingCountdown = 0
    private var switchingTotalDelay = 0L
    private var switchingStartTime = 0L

    private val diagnosticResults = mutableMapOf<String, Boolean?>()
    private val diagnosticLoading = mutableMapOf<String, Boolean>()
    private val diagnosticViews = mutableMapOf<String, View>()

    private val licenseBridge by lazy { LicenseProvider.get() }
    private val isExtensionAvailable get() = licenseBridge.isExtensionAvailable

    private var autoModeJob: kotlinx.coroutines.Job? = null
    private var healthCheckFailCount = 0
    private var testResultResetJob: kotlinx.coroutines.Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentViewWithToolbar(binding.root, showHomeAsUp = false, title = getString(R.string.app_name))

        binding.fab.setOnClickListener { handleFabAction() }
        binding.layoutTestStatusContainer.setOnClickListener { handleTestStatusClick() }
        binding.rvTopServers.adapter = topServersAdapter

        binding.switchAutoMode.isChecked = MmkvManager.decodeSettingsBool(MmkvManager.KEY_AUTO_MODE, false)
        binding.switchAutoMode.setOnCheckedChangeListener { _, isChecked ->
            MmkvManager.encodeSettings(MmkvManager.KEY_AUTO_MODE, isChecked)
            updateUIStates()
            refreshSelectedServer()
            if (isChecked) {
                startAutoModeTimer()
                switchToNextBestServer(forceBest = true)
            } else {
                stopAutoModeTimer()
            }
        }

        initDiagnostics()
        binding.btnRunAllTests.setOnClickListener { runAllDiagnostics() }

        binding.btnAutoSwitchServer.setOnClickListener {
            switchToNextBestServer()
        }

        binding.btnPingAllEmpty.setOnClickListener {
            val isPremium = isExtensionAvailable && MmkvManager.decodeSettingsBool(AppConfig.PREF_IS_PREMIUM_MODE, false)
            val allServers = if (isPremium) getPremiumServerGuids() else getStandardServerGuids()
            if (allServers.isEmpty()) {
                importConfigViaSub(triggerPing = true)
            } else {
                isBatchTesting = true
                hasSeenTestResult = false
                lastTestStartTime = System.currentTimeMillis()
                updateUIStates()
                if (isPremium) {
                    if (mainViewModel.subscriptionId.isEmpty()) {
                        mainViewModel.testAllRealPing(allServers)
                    } else {
                        mainViewModel.testAllRealPing()
                    }
                } else {
                    mainViewModel.testAllRealPing(allServers)
                }
            }
        }

        setupViewModel()
        setupPluginUI()
        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
        SubscriptionUpdater.sync()
        
        checkConnectivityAndSwitchTab()
        refreshModeUI()
        
        if (binding.switchAutoMode.isChecked) {
            startAutoModeTimer()
        }
    }

    override fun onStart() {
        super.onStart()
        checkLicenseAuth()
    }

    private fun checkLicenseAuth() {
        if (!isExtensionAvailable) return
        
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val isValid = licenseBridge.isLicenseValid(this@MainActivity)

                withContext(Dispatchers.Main) {
                    if (!isValid) {
                        try {
                            licenseBridge.getLicenseActivityIntent(this@MainActivity)?.let {
                                isLicenseAuthInProgress = true
                                requestLicenseAuth.launch(it)
                            }
                        } catch (e: Exception) {
                            Log.e("Unknown Magic", "Failed to start license activity", e)
                        }
                    } else {
                        fetchRemoteSubscriptions()
                    }
                }
            } catch (e: Exception) {
                Log.e("Unknown Magic", "Exception in checkLicenseAuth", e)
            }
        }
    }

    private fun checkConnectivityAndSwitchTab() {
        lifecycleScope.launch(Dispatchers.IO) {
            val isConnected = try {
                val url = URL(AppConfig.DELAY_TEST_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.instanceFollowRedirects = false
                val responseCode = connection.responseCode
                connection.disconnect()
                responseCode == 204
            } catch (e: Exception) {
                false
            }

            withContext(Dispatchers.Main) {
                val targetTab = if (isConnected && isExtensionAvailable && getPremiumSubIds().isNotEmpty()) 1 else 0
                if (binding.tabMode.selectedTabPosition != targetTab) {
                    binding.tabMode.post {
                        binding.tabMode.getTabAt(targetTab)?.select()
                    }
                } else {
                    refreshModeUI()
                }
                
                if (isConnected) {
                    autoCheckForUpdates()
                }
            }
        }
    }

    private fun autoCheckForUpdates() {
        val lastCheck = MmkvManager.decodeSettingsLong(AppConfig.PREF_LAST_UPDATE_CHECK, 0L)
        val now = System.currentTimeMillis()

        if (now - lastCheck >= TimeUnit.DAYS.toMillis(1)) {
            lifecycleScope.launch(Dispatchers.IO) {
                delay(2000)

                var retryCount = 0
                while (isLicenseAuthInProgress && retryCount < 30) {
                    delay(1000)
                    retryCount++
                }

                delay(AppConfig.AUTO_UPDATE_CHECK_DELAY)

                try {
                    val result = UpdateCheckerManager.checkForUpdate()
                    if (result.hasUpdate) {
                        withContext(Dispatchers.Main) {
                            if (!isFinishing && !isDestroyed && !isLicenseAuthInProgress) {
                                showUpdateDialog(result)
                            }
                        }
                    }
                    MmkvManager.encodeSettings(AppConfig.PREF_LAST_UPDATE_CHECK, now)
                } catch (e: Exception) {
                    Log.e("Unknown Magic", "Auto update check failed", e)
                }
            }
        }
    }

    private fun setupPluginUI() {
        if (!isExtensionAvailable) return

        if (getPremiumSubIds().isNotEmpty()) {
            binding.tabMode.visibility = View.VISIBLE
        }
        findViewById<View>(R.id.license_handle)?.visibility = View.VISIBLE
        
        // Add a small gap between tabs
        val tabContainer = binding.tabMode.getChildAt(0) as? ViewGroup
        if (tabContainer != null && tabContainer.childCount > 1) {
            val premiumTab = tabContainer.getChildAt(1)
            val params = premiumTab.layoutParams as? LinearLayout.LayoutParams
            if (params != null) {
                params.marginStart = (8 * resources.displayMetrics.density).toInt()
                premiumTab.layoutParams = params
            }
        }

        binding.tabMode.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                val isPremium = tab?.position == 1
                MmkvManager.encodeSettings(AppConfig.PREF_IS_PREMIUM_MODE, isPremium)
                refreshModeUI()
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun refreshModeUI() {
        val isPremium = isExtensionAvailable && MmkvManager.decodeSettingsBool(AppConfig.PREF_IS_PREMIUM_MODE, false)

        binding.switchAutoMode.visibility = if (isPremium) View.GONE else View.VISIBLE
        binding.cardAutoDashboard.visibility = View.GONE // Reset dashboard visibility
        binding.layoutRecentServers.removeAllViews()
        premiumAdapters.clear()
        binding.tvNoPremiumSubs.visibility = View.GONE

        val standardVisibility = if (isPremium) View.GONE else View.VISIBLE
        binding.tvRecentLabel.visibility = standardVisibility
        binding.tvTopLabel.visibility = standardVisibility
        binding.rvTopServers.visibility = standardVisibility

        if (isPremium) {
            binding.cardRecent.visibility = View.VISIBLE
            binding.layoutEmptyTop.visibility = View.GONE

            if (mainViewModel.subscriptionId.isNotEmpty()) {
                val premiumIds = getPremiumSubIds()
                if (!premiumIds.contains(mainViewModel.subscriptionId)) {
                    mainViewModel.subscriptionIdChanged("")
                }
            }

            if (getPremiumSubIds().isEmpty() || remoteSubscriptions.isEmpty()) {
                fetchRemoteSubscriptions()
            }
        } else {
            binding.cardRecent.visibility = View.VISIBLE // Will be hidden in refreshSelectedServer if empty
            binding.layoutEmptyTop.visibility = View.GONE // Will be shown in updateTop10List if empty
            binding.tvRecentLabel.text = getString(R.string.recent_servers)
            mainViewModel.subscriptionIdChanged("")
        }
        refreshSelectedServer()
    }

    private fun fetchRemoteSubscriptions() {
        if (isFetchingRemote || !isExtensionAvailable) return
        isFetchingRemote = true
        updateUIStates()
        lifecycleScope.launch(Dispatchers.Default) {
            try {
                val result = licenseBridge.getRemoteSubscriptions(this@MainActivity)
                
                withContext(Dispatchers.Main) {
                    result.onSuccess { subs ->
                        remoteSubscriptions = subs
                        
                        val hasPremium = subs.isNotEmpty() || getPremiumSubIds().isNotEmpty()
                        binding.tabMode.visibility = if (hasPremium) View.VISIBLE else View.GONE
                        
                        if (!hasPremium && MmkvManager.decodeSettingsBool(AppConfig.PREF_IS_PREMIUM_MODE, false)) {
                            MmkvManager.encodeSettings(AppConfig.PREF_IS_PREMIUM_MODE, false)
                            binding.tabMode.getTabAt(0)?.select()
                        }

                        syncAndAutoImport(subs)
                        refreshSelectedServer()
                    }.onFailure {
                        Log.e("Unknown Magic", "Failed to fetch subscriptions", it)
                        val hasPremium = getPremiumSubIds().isNotEmpty()
                        binding.tabMode.visibility = if (hasPremium) View.VISIBLE else View.GONE
                        
                        toast(it.message ?: getString(R.string.msg_error_fetching_subscriptions))
                        refreshSelectedServer()
                    }
                }
            } catch (e: Exception) {
                Log.e("Unknown Magic", "Exception in fetchRemoteSubscriptions", e)
            } finally {
                withContext(Dispatchers.Main) {
                    isFetchingRemote = false
                    updateUIStates()
                }
            }
        }
    }

    private fun syncAndAutoImport(subs: List<RemoteSubscription>) {
        lifecycleScope.launch(Dispatchers.Default) {
            var anyUpdate = false
            val currentSubs = MmkvManager.decodeSubscriptions()
            val oldPremiumIds = getPremiumSubIds()
            val newPremiumIds = mutableSetOf<String>()
            val remoteUrls = subs.map { it.url }.toSet()

            currentSubs.forEach { sub ->
                if (oldPremiumIds.contains(sub.guid)) {
                    if (!remoteUrls.contains(sub.subscription.url)) {
                        MmkvManager.removeSubscription(sub.guid)
                        anyUpdate = true
                    } else {
                        // Эта подписка остается в списке премиальных
                        newPremiumIds.add(sub.guid)
                    }
                }
            }

            subs.forEach { remote ->
                val url = remote.url
                if (url.isEmpty()) return@forEach

                val existing = currentSubs.find { it.subscription.url == url }
                if (existing == null) {
                    // Импорт новой подписки
                    val subItem = SubscriptionItem(remarks = remote.remarks, url = url, enabled = true)
                    val guid = me.unknkriod.ang.util.Utils.getUuid()
                    MmkvManager.encodeSubscription(guid, subItem)
                    newPremiumIds.add(guid)
                    anyUpdate = true
                } else {
                    // Если подписка была, но не числилась премиальной
                    if (!newPremiumIds.contains(existing.guid)) {
                        newPremiumIds.add(existing.guid)
                        anyUpdate = true
                    }
                    // Если серверов еще нет, нужно обновить
                    if (MmkvManager.decodeServerList(existing.guid).isEmpty()) {
                        anyUpdate = true
                    }
                }
            }

            MmkvManager.encodeSettings(AppConfig.PREF_PREMIUM_SUBS_LIST, newPremiumIds.joinToString(","))

            if (anyUpdate) {
                withContext(Dispatchers.Main) {
                    if (mainViewModel.subscriptionId.isNotEmpty() && !newPremiumIds.contains(mainViewModel.subscriptionId)) {
                        mainViewModel.subscriptionIdChanged("")
                    }
                    importConfigViaSub(triggerPing = false, forceSubIds = newPremiumIds.toList())
                }
            }
        }
    }

    private var lastUpdateActionTime = 0L
    private var lastAutoUpdateTime = 0L

    private fun setupViewModel() {
        mainViewModel.isRunning.observe(this) { isRunning ->
            isConnecting = false
            isDisconnecting = false
            
            if (!isRunning) {
                mainViewModel.stopTest()
                mainViewModel.updateTestResultAction.value = null
                isBatchTesting = false
                isSingleTesting = false
                isPostUpdatePingInProgress = false

                diagnosticResults.clear()
                diagnosticLoading.clear()
                binding.btnAutoSwitchServer.visibility = View.GONE
            }
            updateUIStates()
        }
        mainViewModel.isPaused.observe(this) {
            updateUIStates()
        }
        mainViewModel.updateTestResultAction.observe(this) { result ->
            val testingText = getString(R.string.connection_test_testing)
            val stoppingText = getString(R.string.connection_test_stopping)
            val now = System.currentTimeMillis()

            if (result != null) {
                if (result != testingText && result != stoppingText) {
                    hasSeenTestResult = true
                    if (!isBatchTesting && !isPostUpdatePingInProgress) {
                        testResultResetJob?.cancel()
                        testResultResetJob = lifecycleScope.launch {
                            delay(2000)
                            mainViewModel.updateTestResultAction.value = null
                        }
                    } else {
                        testResultResetJob?.cancel()
                    }
                } else if (result == testingText) {
                    testResultResetJob?.cancel()
                    testResultResetJob = lifecycleScope.launch {
                        delay(15000)
                        if (mainViewModel.updateTestResultAction.value == testingText) {
                            mainViewModel.updateTestResultAction.value = null
                        }
                    }
                }

                if (isSingleTesting && result != testingText) {
                    isSingleTesting = false
                }
            } else {
                testResultResetJob?.cancel()
                if ((isBatchTesting || isPostUpdatePingInProgress) && !hasSeenTestResult && (now - lastTestStartTime < 1000)) {
                    return@observe
                }
                if (isPostUpdatePingInProgress && MmkvManager.decodeSettingsBool(MmkvManager.KEY_AUTO_MODE, false)) {
                    switchToNextBestServer(forceBest = true)
                }
                isBatchTesting = false
                isSingleTesting = false
                isPostUpdatePingInProgress = false
            }

            if (result != null && (result == "EOF_DETECTED" || result.contains("EOF", ignoreCase = true))) {
                val lastAutoUpdate = MmkvManager.decodeSettingsLong(AppConfig.PREF_LAST_AUTO_UPDATE_SUB, 0L)
                val isStandardTab = binding.tabMode.selectedTabPosition == 0
                val canAutoUpdate = isStandardTab && (now - lastAutoUpdate > 60_000L)

                if (canAutoUpdate) {
                    MmkvManager.encodeSettings(AppConfig.PREF_LAST_AUTO_UPDATE_SUB, now)
                    toast(R.string.msg_subscription_auto_update_warning)
                    importConfigViaSub(triggerPing = true)
                }
            }

            updateUIStates()

            if (result != null) {
                if (now - lastUpdateActionTime > 1000) {
                    lastUpdateActionTime = now
                    refreshSelectedServer()
                }
            }
        }
        mainViewModel.updateListAction.observe(this) {
            refreshSelectedServer()
        }
    }

    private fun handleFabAction() {
        if (mainViewModel.isPaused.value == true) {
            MessageUtil.sendMsg2Service(this, AppConfig.MSG_STATE_RESUME, "")
            return
        }
        if (mainViewModel.isRunning.value == true) {
            isDisconnecting = true
            updateUIStates()
            CoreServiceManager.stopVService(this)
        } else {
            val allServers = MmkvManager.decodeAllServerList()
            if (allServers.isEmpty()) {
                importConfigViaSub()
                return
            }

            if (MmkvManager.getSelectServer().isNullOrEmpty()) {
                val isPremium = isExtensionAvailable && MmkvManager.decodeSettingsBool(AppConfig.PREF_IS_PREMIUM_MODE, false)
                val premiumIds = getPremiumSubIds()
                
                val filteredServers = if (isPremium) {
                    allServers.filter { guid -> 
                        val profile = MmkvManager.decodeServerConfig(guid)
                        profile != null && premiumIds.contains(profile.subscriptionId)
                    }
                } else {
                    allServers.filter { guid ->
                        val profile = MmkvManager.decodeServerConfig(guid)
                        profile != null && !premiumIds.contains(profile.subscriptionId)
                    }
                }

                val targetList = if (filteredServers.isNotEmpty()) filteredServers else allServers

                val bestServer = targetList.minByOrNull { guid ->
                    val delay = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis ?: 0L
                    if (delay <= 0L) Long.MAX_VALUE else delay
                }
                
                if (bestServer != null && (MmkvManager.decodeServerAffiliationInfo(bestServer)?.testDelayMillis ?: 0L) > 0) {
                    MmkvManager.setSelectServer(bestServer)
                } else {
                    MmkvManager.setSelectServer(targetList[0])
                }
            }

            if (SettingsManager.isVpnMode()) {
                val intent = VpnService.prepare(this)
                if (intent == null) {
                    isConnecting = true
                    updateUIStates()
                    startV2Ray()
                } else {
                    requestVpnPermission.launch(intent)
                }
            } else {
                isConnecting = true
                updateUIStates()
                startV2Ray()
            }
        }
    }

    private fun handleLayoutTestClick() {
        if (mainViewModel.isRunning.value == true) {
            isSingleTesting = true
            hasSeenTestResult = false
            lastTestStartTime = System.currentTimeMillis()
            updateUIStates()
            mainViewModel.testCurrentServerRealPing()
        } else {
            toast(R.string.connection_test_fail)
        }
    }

    private fun handleTestStatusClick() {
        val testResult = mainViewModel.updateTestResultAction.value
        val isStoppingText = testResult == getString(R.string.connection_test_stopping)
        val isBatch = isBatchTesting || isPostUpdatePingInProgress

        if (isBatch) {
            if (!isStoppingText) {
                mainViewModel.stopTest()
            }
        } else if (!isSingleTesting) {
            handleLayoutTestClick()
        }
    }

    private fun startV2Ray() {
        val guid = MmkvManager.getSelectServer()
        if (guid.isNullOrEmpty()) {
            toast(R.string.msg_no_server_selected_updating)
            importConfigViaSub()
            return
        }
        MmkvManager.addRecentServer(guid)
        refreshSelectedServer()
        CoreServiceManager.startVService(this)
    }

    fun restartV2Ray() {
        lifecycleScope.launch {
            isConnecting = true
            updateUIStates()
            if (mainViewModel.isRunning.value == true) {
                CoreServiceManager.stopVService(this@MainActivity)
                var retry = 0
                while (mainViewModel.isRunning.value == true && retry < 30) {
                    delay(100)
                    retry++
                }
            }
            delay(300)
            startV2Ray()
        }
    }



    private fun refreshSelectedServer() {
        val isPremium = isExtensionAvailable && MmkvManager.decodeSettingsBool(AppConfig.PREF_IS_PREMIUM_MODE, false)
        
        lifecycleScope.launch(Dispatchers.Default) {
            val currentGuid = MmkvManager.getSelectServer()
            val premiumIds = getPremiumSubIds()
            
            if (isPremium) {
                val allServers = MmkvManager.decodeAllServerList().mapNotNull { guid ->
                    val profile = MmkvManager.decodeServerConfig(guid)
                    if (profile != null) ServersCache(guid, profile) else null
                }
                val currentSubs = MmkvManager.decodeSubscriptions()
                
                if (currentGuid != lastSelectedServer || !lastIsPremium) {
                    if (!currentGuid.isNullOrEmpty()) {
                        MmkvManager.decodeServerConfig(currentGuid)?.subscriptionId?.let { subId ->
                            currentSubs.find { it.guid == subId }?.subscription?.url?.let { expandedSubscriptions.add(it) }
                        }
                    }
                }

                val displayList = if (remoteSubscriptions.isNotEmpty()) {
                    remoteSubscriptions.map { remote ->
                        val url = remote.url
                        val remarks = remote.remarks
                        val importedSub = currentSubs.find { it.subscription.url == url }
                        Triple(remarks, url, importedSub?.guid ?: "")
                    }
                } else {
                    currentSubs.filter { premiumIds.contains(it.guid) }.map {
                        Triple(it.subscription.remarks, it.subscription.url, it.guid)
                    }
                }

                if (displayList.size == 1) {
                    expandedSubscriptions.add(displayList[0].second)
                }

                withContext(Dispatchers.Main) {
                    updatePremiumLayout(displayList, allServers)
                }
            } else {
                val recentGuids = MmkvManager.decodeRecentServers()
                val filteredRecent = recentGuids.filter { guid ->
                    val profile = MmkvManager.decodeServerConfig(guid)
                    profile != null && !premiumIds.contains(profile.subscriptionId)
                }.mapNotNull { guid ->
                    val profile = MmkvManager.decodeServerConfig(guid)
                    if (profile != null) guid to profile else null
                }

                withContext(Dispatchers.Main) {
                    updateStandardLayout(filteredRecent, currentGuid)
                }
            }
            
            lastSelectedServer = currentGuid
            lastIsPremium = isPremium

            withContext(Dispatchers.Main) {
                updateTop10List()
            }
        }
    }

    private val premiumAdapters = mutableMapOf<String, ServersAdapter>()

    private fun updatePremiumLayout(displayList: List<Triple<String, String, String>>, allServers: List<ServersCache>) {
        if (displayList.isEmpty()) {
            binding.layoutRecentServers.removeAllViews()
            binding.tvNoPremiumSubs.visibility = View.VISIBLE
            premiumAdapters.clear()
            return
        }

        binding.tvNoPremiumSubs.visibility = View.GONE
        val isSingleSub = displayList.size == 1
        
        // Remove views that are no longer in displayList
        val currentUrls = displayList.map { it.second }.toSet()
        val toRemove = mutableListOf<View>()
        for (i in 0 until binding.layoutRecentServers.childCount) {
            val child = binding.layoutRecentServers.getChildAt(i)
            val url = child.tag as? String
            if (url == null || !currentUrls.contains(url)) {
                toRemove.add(child)
            }
        }
        toRemove.forEach { 
            binding.layoutRecentServers.removeView(it)
            (it.tag as? String)?.let { url -> premiumAdapters.remove(url) }
        }

        displayList.forEachIndexed { index, (remarks, url, subId) ->
            var subBlock = binding.layoutRecentServers.findViewWithTag<View>(url)
            val isNew = subBlock == null
            
            if (isNew) {
                subBlock = layoutInflater.inflate(R.layout.item_premium_subscription, binding.layoutRecentServers, false)
                subBlock.tag = url
            }

            val tvSubName = subBlock!!.findViewById<TextView>(R.id.tv_sub_name)
            val ivExpand = subBlock.findViewById<ImageView>(R.id.iv_expand)
            val rvServers = subBlock.findViewById<RecyclerView>(R.id.rv_servers)
            val viewSelect = subBlock.findViewById<View>(R.id.view_select_area)
            val viewExpand = subBlock.findViewById<View>(R.id.view_expand_area)
            val ivIndicator = subBlock.findViewById<ImageView>(R.id.iv_selected_indicator)

            tvSubName.text = remarks
            val isSelected = mainViewModel.subscriptionId == subId
            subBlock.alpha = if (mainViewModel.subscriptionId.isEmpty() || isSelected) 1.0f else 0.5f
            ivIndicator.visibility = if (isSelected && subId.isNotEmpty()) View.VISIBLE else View.GONE

            val isExpanded = expandedSubscriptions.contains(url)
            rvServers.visibility = if (isExpanded) View.VISIBLE else View.GONE
            ivExpand.rotation = if (isExpanded) 180f else 0f
            viewExpand.visibility = if (isSingleSub) View.GONE else View.VISIBLE

            viewSelect.setOnClickListener {
                if (isBatchTesting || isPostUpdatePingInProgress) return@setOnClickListener
                if (subId.isEmpty()) return@setOnClickListener
                mainViewModel.subscriptionIdChanged(if (mainViewModel.subscriptionId == subId) "" else subId)
                if (subId.isNotEmpty()) expandedSubscriptions.add(url)
                // We don't call refreshSelectedServer() here anymore because subscriptionId is Observed? 
                // Wait, mainViewModel.subscriptionId is not observed, it's just a property.
                // But subscriptionIdChanged might trigger something.
                refreshSelectedServer()
            }

            viewExpand.setOnClickListener {
                if (expandedSubscriptions.contains(url)) expandedSubscriptions.remove(url) else expandedSubscriptions.add(url)
                refreshSelectedServer()
            }

            if (subId.isNotEmpty() && isExpanded) {
                val serversInSub = allServers.filter { it.profile.subscriptionId == subId }
                if (serversInSub.isNotEmpty()) {
                    val adapter = premiumAdapters.getOrPut(url) { 
                        ServersAdapter(false).also { rvServers.adapter = it }
                    }
                    if (rvServers.adapter != adapter) {
                        rvServers.adapter = adapter
                    }
                    adapter.submitList(serversInSub)
                }
            }

            if (isNew) {
                binding.layoutRecentServers.addView(subBlock, index)
            } else {
                // Ensure correct order if it somehow changed
                val currentIndex = binding.layoutRecentServers.indexOfChild(subBlock)
                if (currentIndex != index) {
                    binding.layoutRecentServers.removeView(subBlock)
                    binding.layoutRecentServers.addView(subBlock, index)
                }
            }
        }
        binding.cardRecent.alpha = 1.0f
    }

    private fun updateStandardLayout(filteredRecent: List<Pair<String, ProfileItem>>, currentGuid: String?) {
        val isPremium = isExtensionAvailable && MmkvManager.decodeSettingsBool(AppConfig.PREF_IS_PREMIUM_MODE, false)
        val isAutoMode = MmkvManager.decodeSettingsBool(MmkvManager.KEY_AUTO_MODE, false)
        binding.layoutRecentServers.removeAllViews()
        
        binding.cardAutoDashboard.visibility = if (isAutoMode && !isPremium) View.VISIBLE else View.GONE

        if (filteredRecent.isEmpty()) {
            binding.cardRecent.visibility = View.GONE
            binding.tvRecentLabel.visibility = View.GONE
        } else {
            binding.cardRecent.visibility = if (isAutoMode) View.GONE else View.VISIBLE
            binding.tvRecentLabel.visibility = if (isAutoMode) View.GONE else View.VISIBLE
            filteredRecent.forEach { (guid, profile) ->
                addServerToLayout(guid, profile, currentGuid, fromRecent = true)
            }
        }

        if (currentGuid != null) {
            if (selectionFromRecent) {
                binding.cardRecent.alpha = 1.0f
                binding.rvTopServers.alpha = 0.3f
            } else {
                binding.cardRecent.alpha = 0.3f
                binding.rvTopServers.alpha = 1.0f
            }
        } else {
            binding.cardRecent.alpha = 1.0f
            binding.rvTopServers.alpha = 1.0f
        }
        
        // Hide/Show Top 10 based on Auto Mode
        binding.tvTopLabel.visibility = if (isAutoMode) View.GONE else View.VISIBLE
        binding.rvTopServers.visibility = if (isAutoMode) View.GONE else View.VISIBLE
    }

    private fun addServerToLayout(guid: String, profile: ProfileItem, currentGuid: String?, fromRecent: Boolean) {
        val itemView = layoutInflater.inflate(R.layout.item_server_top, binding.layoutRecentServers, false)
        val tvName: TextView = itemView.findViewById(R.id.tv_name)
        val tvPing: TextView = itemView.findViewById(R.id.tv_ping)
        val ivCurrent: ImageView = itemView.findViewById(R.id.iv_current)
        val layoutContent: View = itemView.findViewById(R.id.layout_content)

        tvName.text = profile.remarks
        val aff = MmkvManager.decodeServerAffiliationInfo(guid)
        val delay = aff?.testDelayMillis ?: 0L
        if (delay > 0) {
            tvPing.text = "${delay}ms"
            tvPing.setTextColor(getPingColor(delay))
        } else if (delay < 0) {
            tvPing.text = "-1"
            tvPing.setTextColor(0xFFFF0000.toInt())
        }

        if (guid == currentGuid) {
            ivCurrent.visibility = View.VISIBLE
            layoutContent.setBackgroundResource(R.drawable.ic_rounded_corner_active)
            layoutContent.backgroundTintList = ColorStateList.valueOf(0x1A000000)
        } else {
            ivCurrent.visibility = View.GONE
            layoutContent.background = null
        }
        val p = (16 * resources.displayMetrics.density).toInt()
        layoutContent.setPadding(p, p, p, p)

        itemView.setOnClickListener {
            if (isBatchTesting || isPostUpdatePingInProgress) return@setOnClickListener
            if (MmkvManager.decodeSettingsBool(MmkvManager.KEY_AUTO_MODE, false)) return@setOnClickListener
            selectionFromRecent = fromRecent
            val currentSelect = MmkvManager.getSelectServer()
            if (currentSelect == guid) {
                MmkvManager.setSelectServer("")
            } else {
                MmkvManager.setSelectServer(guid)
            }
            refreshSelectedServer()
            if (mainViewModel.isRunning.value == true) {
                restartV2Ray()
            }
        }
        binding.layoutRecentServers.addView(itemView)
    }

    private fun getPremiumServerGuids(): List<String> {
        val premiumIds = getPremiumSubIds()
        return MmkvManager.decodeAllServerList().filter { guid ->
            val profile = MmkvManager.decodeServerConfig(guid)
            profile != null && premiumIds.contains(profile.subscriptionId)
        }
    }

    private fun getStandardServerGuids(): List<String> {
        val premiumIds = getPremiumSubIds()
        return MmkvManager.decodeAllServerList().filter { guid ->
            val profile = MmkvManager.decodeServerConfig(guid)
            profile != null && !premiumIds.contains(profile.subscriptionId)
        }
    }

    private fun getPingColor(delay: Long): Int {
        return when {
            delay <= 180 -> 0xFF009966.toInt()
            delay <= 250 -> 0xFFF97910.toInt()
            else -> 0xFFFF0000.toInt()
        }
    }

    private fun getPremiumSubIds(): Set<String> {
        val premiumSubsStr = MmkvManager.decodeSettingsString(AppConfig.PREF_PREMIUM_SUBS_LIST, "")
        return premiumSubsStr?.split(",")?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
    }

    private fun startAutoModeTimer() {
        autoModeJob?.cancel()
        autoModeJob = lifecycleScope.launch(Dispatchers.Default) {
            while (true) {
                delay(60_000L) // Once a minute
                
                val isRunning = mainViewModel.isRunning.value == true
                val isPaused = mainViewModel.isPaused.value == true
                
                if (isRunning && !isPaused) {
                    performHealthCheck()
                }
            }
        }
    }

    private fun initDiagnostics() {
        val services = DiagnosticsManager.getServices(this)
        binding.layoutDiagnosticsContainer.removeAllViews()
        diagnosticViews.clear()

        services.forEach { service ->
            val itemView = layoutInflater.inflate(R.layout.item_diagnostic_service, binding.layoutDiagnosticsContainer, false)
            val tvTitle: TextView = itemView.findViewById(R.id.tv_title)
            val btnTest: View = itemView.findViewById(R.id.btn_test)

            tvTitle.text = service.title
            btnTest.setOnClickListener { runDiagnostic(service) }

            diagnosticViews[service.id] = itemView
            binding.layoutDiagnosticsContainer.addView(itemView)

            // Sync initial state
            updateDiagnosticView(service.id)
        }
    }

    private fun runDiagnostic(service: DiagnosticService) {
        if (diagnosticLoading[service.id] == true) return
        lifecycleScope.launch {
            try {
                diagnosticLoading[service.id] = true
                updateUIStates()

                var allSuccess = true
                for (url in service.urls) {
                    if (!testService(url)) {
                        allSuccess = false
                        break
                    }
                }
                diagnosticResults[service.id] = allSuccess
            } catch (e: Exception) {
                Log.e("UnknMagic", "Diagnostic failed for ${service.id}", e)
                diagnosticResults[service.id] = false
            } finally {
                diagnosticLoading[service.id] = false
                updateUIStates()
                if (checkManualResultsAndSwitch()) {
                    switchToNextBestServer(delayMs = 2000)
                }
            }
        }
    }

    private fun runAllDiagnostics() {
        val services = DiagnosticsManager.getServices(this)
        if (services.any { diagnosticLoading[it.id] == true }) return

        lifecycleScope.launch {
            services.forEach { service ->
                runDiagnostic(service)
                while (diagnosticLoading[service.id] == true) delay(100)
            }
        }
    }

    private fun updateDiagnosticView(serviceId: String) {
        val view = diagnosticViews[serviceId] ?: return
        val ivStatus: ImageView = view.findViewById(R.id.iv_status)
        val pbTest: View = view.findViewById(R.id.pb_test)
        val btnTest: View = view.findViewById(R.id.btn_test)

        val isLoading = diagnosticLoading[serviceId] == true
        val result = diagnosticResults[serviceId]

        pbTest.visibility = if (isLoading) View.VISIBLE else View.GONE
        ivStatus.visibility = if (isLoading) View.GONE else View.VISIBLE

        if (!isLoading) {
            updateIndicator(ivStatus, result)
        }

        val isRunning = mainViewModel.isRunning.value == true
        val isPaused = mainViewModel.isPaused.value == true
        val isBatch = isBatchTesting || isPostUpdatePingInProgress
        val isUpdating = isFetchingRemote || isSubscriptionUpdating
        val isTestingManual = diagnosticLoading.values.any { it }

        btnTest.isEnabled = isRunning && !isPaused && !isBatch && !isUpdating && !isTestingManual
    }

    private fun stopAutoModeTimer() {
        autoModeJob?.cancel()
        autoModeJob = null
        healthCheckFailCount = 0
    }

    private suspend fun testService(urlStr: String): Boolean {
        if (isSwitchingServer) return true
        return withContext(Dispatchers.IO) {
            try {
                val socksPort = SettingsManager.getSocksPort()
                val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
                
                val connection = URL(urlStr).openConnection(proxy) as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                
                val code = connection.responseCode
                connection.disconnect()
                Log.d("UnknMagic", "Manual Test (via Proxy): $urlStr returned $code")
                code == 200 || code in 400..499
            } catch (e: Exception) {
                Log.e("UnknMagic", "Manual Test (via Proxy): $urlStr failed: ${e.message}")
                false
            }
        }
    }

    private fun updateIndicator(view: ImageView, success: Boolean?) {
        val (iconRes, color) = when (success) {
            true -> R.drawable.ic_check_24dp to 0xFF009966.toInt()
            false -> R.drawable.ic_close_24dp to 0xFFFF0000.toInt()
            null -> R.drawable.ic_remove_24dp to ContextCompat.getColor(this, R.color.color_fab_inactive)
        }
        view.setImageResource(iconRes)
        view.imageTintList = ColorStateList.valueOf(color)
    }

    private fun checkManualResultsAndSwitch(): Boolean {
        if (diagnosticLoading.values.any { it }) return false

        val services = DiagnosticsManager.getServices(this)
        val results = diagnosticResults.values.filterNotNull()
        
        // Переключаем только если протестированы ВСЕ сервисы и ВСЕ они провалены
        val isBroken = results.size == services.size && results.all { !it }
        
        if (results.any { !it }) {
            binding.btnAutoSwitchServer.visibility = View.VISIBLE
        } else {
            binding.btnAutoSwitchServer.visibility = View.GONE
        }
        return isBroken
    }

    private suspend fun performHealthCheck() {
        Log.i("UnknMagic", "Auto Mode: Starting background health check...")

        val services = DiagnosticsManager.getServices(this)

        withContext(Dispatchers.Main) {
            services.forEach { service ->
                diagnosticLoading[service.id] = true
            }
            updateUIStates()
        }

        for (service in services) {
            var serviceSuccess = true
            try {
                for (url in service.urls) {
                    if (!testService(url)) {
                        serviceSuccess = false
                        break
                    }
                }
            } catch (e: Exception) {
                serviceSuccess = false
            }

            withContext(Dispatchers.Main) {
                diagnosticResults[service.id] = serviceSuccess
                diagnosticLoading[service.id] = false
                updateUIStates()
            }
        }

        withContext(Dispatchers.Main) {
            val isBroken = checkManualResultsAndSwitch()
            if (!isBroken) {
                Log.i("UnknMagic", "Auto Mode: Health check PASSED")
                healthCheckFailCount = 0
            } else {
                healthCheckFailCount++
                Log.w("UnknMagic", "Auto Mode: Health check FAILED ($healthCheckFailCount/3)")
                if (healthCheckFailCount >= 3) {
                    healthCheckFailCount = 0
                    Log.i("UnknMagic", "Auto Mode: Threshold reached. Switching server...")
                    switchToNextBestServer(delayMs = 5000)
                }
            }
        }
    }

    private fun switchToNextBestServer(forceBest: Boolean = false, forceReconnect: Boolean? = null, delayMs: Long = 0) {
        if (isSwitchingServer) return
        isSwitchingServer = true
        switchingTotalDelay = delayMs
        switchingStartTime = System.currentTimeMillis()

        lifecycleScope.launch(Dispatchers.Default) {
            try {
                if (delayMs > 0) {
                    switchingTotalDelay = delayMs
                    val startTime = System.currentTimeMillis()
                    while (System.currentTimeMillis() - startTime < delayMs) {
                        val elapsed = System.currentTimeMillis() - startTime
                        switchingCountdown = ((delayMs - elapsed + 999) / 1000).toInt()
                        withContext(Dispatchers.Main) { updateUIStates() }
                        delay(100)
                    }
                    switchingCountdown = 0
                }

                val allServers = MmkvManager.decodeAllServerList().mapNotNull { guid ->
                    val profile = MmkvManager.decodeServerConfig(guid)
                    if (profile != null) guid to profile else null
                }

                val premiumIds = getPremiumSubIds()
                val standardServers = allServers.filter { !premiumIds.contains(it.second.subscriptionId) }
                
                if (standardServers.isEmpty()) return@launch

                val currentGuid = MmkvManager.getSelectServer()
                
                val sortedServers = standardServers.map { 
                    it to (MmkvManager.decodeServerAffiliationInfo(it.first)?.testDelayMillis ?: 0L)
                }.sortedBy {
                    val delay = it.second
                    if (delay <= 0) Long.MAX_VALUE else delay
                }.map { it.first }

                val nextServer = if (forceBest) {
                    val candidate = sortedServers.firstOrNull()
                    val candidateDelay = candidate?.let { MmkvManager.decodeServerAffiliationInfo(it.first)?.testDelayMillis } ?: 0L
                    if (candidateDelay > 0) candidate else null
                } else {
                    val currentIdx = sortedServers.indexOfFirst { it.first == currentGuid }
                    if (currentIdx != -1) {
                        val nextIdx = (currentIdx + 1) % sortedServers.size
                        val candidate = sortedServers[nextIdx]
                        val candidateDelay = MmkvManager.decodeServerAffiliationInfo(candidate.first)?.testDelayMillis ?: 0L
                        
                        if (candidateDelay > 0) candidate else sortedServers.firstOrNull()
                    } else {
                        sortedServers.firstOrNull()
                    }
                }

                if (nextServer != null) {
                    val isNewServer = nextServer.first != currentGuid
                    if (isNewServer || forceBest) {
                        withContext(Dispatchers.Main) {
                            if (isNewServer) {
                                MmkvManager.setSelectServer(nextServer.first)
                                
                                diagnosticResults.clear()
                                diagnosticLoading.clear()
                                DiagnosticsManager.getServices(this@MainActivity).forEach {
                                    updateDiagnosticView(it.id)
                                }
                                binding.btnAutoSwitchServer.visibility = View.GONE

                                refreshSelectedServer()
                            }
                            
                            val shouldReconnect = forceReconnect ?: (mainViewModel.isRunning.value == true)
                            if (shouldReconnect) {
                                // Флаг для updateUIStates, чтобы показать "Перезапуск..."
                                switchingCountdown = -1 
                                updateUIStates()
                                
                                if (mainViewModel.isRunning.value == true) {
                                    CoreServiceManager.stopVService(this@MainActivity)
                                    var retry = 0
                                    while (mainViewModel.isRunning.value == true && retry < 30) {
                                        delay(200)
                                        retry++
                                    }
                                }
                                delay(500)
                                startV2Ray()
                                
                                var retryStart = 0
                                while (mainViewModel.isRunning.value != true && retryStart < 30) {
                                    delay(200)
                                    retryStart++
                                }
                                delay(1000)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("UnknMagic", "AutoSwitch error", e)
            } finally {
                isSwitchingServer = false
                switchingCountdown = 0
                withContext(Dispatchers.Main) { updateUIStates() }
            }
        }
    }

    private fun updateTop10List() {
        val isPremium = isExtensionAvailable && MmkvManager.decodeSettingsBool(AppConfig.PREF_IS_PREMIUM_MODE, false)
        val isAutoMode = MmkvManager.decodeSettingsBool(MmkvManager.KEY_AUTO_MODE, false)
        if (isPremium) {
            updateUIStates()
            return
        }

        val premiumIds = getPremiumSubIds()
        val allServers = ArrayList(mainViewModel.serversCache)
        val displayServers = allServers.filter { item ->
            val delay = MmkvManager.decodeServerAffiliationInfo(item.guid)?.testDelayMillis ?: 0L
            delay > 0 && !premiumIds.contains(item.profile.subscriptionId)
        }.map { item ->
            item to (MmkvManager.decodeServerAffiliationInfo(item.guid)?.testDelayMillis ?: 0L)
        }.sortedBy { it.second }
        .map { it.first }
        .take(10)

        if (displayServers.isEmpty() || isAutoMode) {
            binding.rvTopServers.visibility = View.GONE
            binding.tvTopLabel.visibility = View.GONE
            binding.layoutEmptyTop.visibility = if (isAutoMode) View.GONE else View.VISIBLE
        } else {
            binding.rvTopServers.visibility = View.VISIBLE
            binding.tvTopLabel.visibility = View.VISIBLE
            binding.layoutEmptyTop.visibility = View.GONE
            topServersAdapter.submitList(displayServers)
        }

        updateUIStates()
    }

    companion object {
    }

    private fun updateUIStates() {
        val isRunning = mainViewModel.isRunning.value == true
        val isPaused = mainViewModel.isPaused.value == true
        val testResult = mainViewModel.updateTestResultAction.value

        val isBatch = isBatchTesting || isPostUpdatePingInProgress
        val isUpdating = isFetchingRemote || isSubscriptionUpdating
        val isTestingText = testResult == getString(R.string.connection_test_testing)
        val isStoppingText = testResult == getString(R.string.connection_test_stopping)

        val isServiceTransition = isConnecting || isDisconnecting
        val isHeavyProcess = isBatch || isUpdating || isSwitchingServer || isServiceTransition
        val isAutoMode = MmkvManager.decodeSettingsBool(MmkvManager.KEY_AUTO_MODE, false)

        // 1. FAB (Stop/Play/Resume)
        val currentGuid = MmkvManager.getSelectServer()
        val isPremiumMode = isExtensionAvailable && MmkvManager.decodeSettingsBool(AppConfig.PREF_IS_PREMIUM_MODE, false)
        val isServerSelected = if (!currentGuid.isNullOrEmpty()) {
            val profile = MmkvManager.decodeServerConfig(currentGuid)
            profile != null && (getPremiumSubIds().contains(profile.subscriptionId) == isPremiumMode)
        } else {
            false
        }

        val fabEnabled = (isRunning || isPaused || isServerSelected) && !isHeavyProcess
        binding.fab.isEnabled = fabEnabled
        binding.fab.alpha = if (fabEnabled) 1.0f else 0.6f

        val iconRes = when {
            isPaused -> android.R.drawable.ic_media_pause
            isRunning -> R.drawable.ic_stop_24dp
            else -> R.drawable.ic_play_24dp
        }
        
        val fabColor = when {
            isPaused -> R.color.colorWhite
            isRunning -> R.color.color_fab_active
            else -> R.color.color_fab_inactive
        }

        if (isSwitchingServer) {
            binding.fab.icon = null
        } else {
            binding.fab.setIconResource(iconRes)
        }
        binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, fabColor))
        
        if (isPaused) {
            binding.fab.iconTint = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.color_fab_active))
        } else {
            binding.fab.iconTint = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.colorWhite))
        }

        // 2. Test State Label
        when {
            switchingCountdown > 0 -> binding.tvTestState.text = "${getString(R.string.auto_mode_switching)} (${switchingCountdown}s)"
            switchingCountdown == -1 -> binding.tvTestState.text = getString(R.string.connection_updating_subscription).replace("…", "").replace("...", "")
            isConnecting -> binding.tvTestState.text = getString(R.string.connection_starting)
            isDisconnecting -> binding.tvTestState.text = getString(R.string.connection_stopping)
            isPaused -> binding.tvTestState.text = getString(R.string.connection_paused)
            testResult != null -> binding.tvTestState.text = testResult
            isUpdating -> binding.tvTestState.text = getString(R.string.connection_updating_subscription)
            isBatch || isSingleTesting -> binding.tvTestState.text = getString(R.string.connection_test_testing)
            else -> binding.tvTestState.text = getString(if (isRunning) R.string.connection_connected else R.string.connection_not_connected)
        }

        val isAnyTesting = isBatch || isSingleTesting || isTestingText || isStoppingText
        
        val canStart = (isRunning || isServerSelected) && !isAnyTesting && !isUpdating
        val canStopBatch = isBatch && !isStoppingText
        binding.layoutTestStatusContainer.isEnabled = canStart || canStopBatch
        
        // Устанавливаем полную непрозрачность во время теста или если есть результат
        binding.layoutTestStatusContainer.alpha = if (isAnyTesting || testResult != null || isRunning || isServerSelected) 1.0f else 0.6f

        if (isAutoMode && isRunning && !isPaused && !isAnyTesting && testResult == null) {
            binding.tvTestState.text = getString(R.string.auto_mode_connected)
        }

        // Diagnostics Panel State
        val isTestingManual = diagnosticLoading.values.any { it }
        val canRunDiagnostics = isRunning && !isPaused && !isHeavyProcess && !isTestingManual
        binding.btnRunAllTests.isEnabled = canRunDiagnostics
        binding.cardAutoDashboard.alpha = if (isRunning && !isPaused) 1.0f else 0.6f

        // Update all diagnostic views to reflect enabled/disabled state
        diagnosticViews.keys.forEach { updateDiagnosticView(it) }

        if (isBatch) {
            binding.tvTestState.setBackgroundResource(R.drawable.bg_test_state_tag_top)
            binding.tvTestState.setTextColor(ContextCompat.getColor(this, R.color.md_theme_onSurface))
        } else {
            binding.tvTestState.setBackgroundResource(0)
            binding.tvTestState.setTextColor(ContextCompat.getColor(this, R.color.md_theme_onSurface))
        }

        // 3. Stop Button
        binding.btnStopTest.visibility = if (isBatch) View.VISIBLE else View.GONE
        binding.btnStopTest.isEnabled = !isStoppingText
        binding.btnStopTest.alpha = if (isStoppingText) 0.5f else 1.0f

        // 4. Ping All Empty Button
        val pingAllEnabled = !isHeavyProcess
        binding.btnPingAllEmpty.isEnabled = pingAllEnabled
        binding.btnPingAllEmpty.alpha = if (pingAllEnabled) 1.0f else 0.6f

        if (isBatch) {
            binding.cardRecent.alpha = 0.6f
            binding.rvTopServers.alpha = 0.6f
        }

        // 5. Switching Progress and FAB animation
        if (isSwitchingServer || isServiceTransition || isUpdating) {
            if (binding.pbSwitching.visibility != View.VISIBLE) {
                binding.pbSwitching.alpha = 0f
                binding.pbSwitching.visibility = View.VISIBLE
                binding.pbSwitching.animate().alpha(1f).setDuration(300).start()
            }

            if (isSwitchingServer && switchingCountdown > 0 && switchingTotalDelay > 0) {
                val elapsed = System.currentTimeMillis() - switchingStartTime
                val progress = (elapsed.coerceAtLeast(0L).toFloat() / switchingTotalDelay * 100).toInt()
                binding.pbSwitching.isIndeterminate = false
                binding.pbSwitching.setProgress(progress.coerceIn(0, 100), true)
            } else {
                binding.pbSwitching.isIndeterminate = true
            }
            binding.fab.animate().scaleX(0.85f).scaleY(0.85f).alpha(0.6f).setDuration(300).start()
        } else {
            if (binding.pbSwitching.visibility == View.VISIBLE) {
                binding.pbSwitching.animate().alpha(0f).setDuration(300).withEndAction {
                    binding.pbSwitching.visibility = View.GONE
                }.start()
            }
            binding.fab.animate().scaleX(1.0f).scaleY(1.0f).alpha(1.0f).setDuration(300).start()
        }

        invalidateOptionsMenu()
    }

    private inner class ServersAdapter(private val fromRecent: Boolean) : RecyclerView.Adapter<ServersAdapter.ViewHolder>() {
        private var items = listOf<ServersCache>()
        private var selectedGuid: String? = null

        fun submitList(newItems: List<ServersCache>) {
            val newSelectedGuid = MmkvManager.getSelectServer()
            val oldItems = items
            val oldSelectedGuid = selectedGuid
            
            items = newItems
            selectedGuid = newSelectedGuid
            
            androidx.recyclerview.widget.DiffUtil.calculateDiff(object : androidx.recyclerview.widget.DiffUtil.Callback() {
                override fun getOldListSize(): Int = oldItems.size
                override fun getNewListSize(): Int = newItems.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    return oldItems[oldItemPosition].guid == newItems[newItemPosition].guid
                }
                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    val old = oldItems[oldItemPosition]
                    val new = newItems[newItemPosition]
                    val oldDelay = MmkvManager.decodeServerAffiliationInfo(old.guid)?.testDelayMillis ?: 0L
                    val newDelay = MmkvManager.decodeServerAffiliationInfo(new.guid)?.testDelayMillis ?: 0L
                    
                    val wasSelected = old.guid == oldSelectedGuid
                    val isSelected = new.guid == newSelectedGuid
                    
                    return old.profile.remarks == new.profile.remarks && 
                           oldDelay == newDelay && 
                           wasSelected == isSelected
                }
            }).dispatchUpdatesTo(this)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = layoutInflater.inflate(R.layout.item_server_top, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.tvName.text = item.profile.remarks
            val delay = MmkvManager.decodeServerAffiliationInfo(item.guid)?.testDelayMillis ?: 0L
            if (delay > 0) {
                holder.tvPing.text = "${delay}ms"
                holder.tvPing.setTextColor(getPingColor(delay))
            } else if (delay < 0) {
                holder.tvPing.text = "-1"
                holder.tvPing.setTextColor(0xFFFF0000.toInt())
            }

            val layoutContent: View = holder.itemView.findViewById(R.id.layout_content)
            if (item.guid == selectedGuid) {
                holder.ivCurrent.visibility = View.VISIBLE
                layoutContent.setBackgroundResource(R.drawable.ic_rounded_corner_active)
                layoutContent.backgroundTintList = ColorStateList.valueOf(0x1A000000)
            } else {
                holder.ivCurrent.visibility = View.GONE
                layoutContent.background = null
            }
            val p = (16 * holder.itemView.resources.displayMetrics.density).toInt()
            layoutContent.setPadding(p, p, p, p)

            holder.itemView.setOnClickListener {
                if (isBatchTesting || isPostUpdatePingInProgress) return@setOnClickListener
                if (MmkvManager.decodeSettingsBool(MmkvManager.KEY_AUTO_MODE, false)) return@setOnClickListener
                selectionFromRecent = fromRecent
                val currentSelect = MmkvManager.getSelectServer()
                if (currentSelect == item.guid) {
                    MmkvManager.setSelectServer("")
                } else {
                    MmkvManager.setSelectServer(item.guid)
                }
                refreshSelectedServer()
                if (mainViewModel.isRunning.value == true) {
                    restartV2Ray()
                }
            }
        }

        override fun getItemCount() = items.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tv_name)
            val tvPing: TextView = view.findViewById(R.id.tv_ping)
            val ivCurrent: ImageView = view.findViewById(R.id.iv_current)
        }
    }



    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val isBatch = isBatchTesting || isPostUpdatePingInProgress
        val isUpdating = isFetchingRemote || isSubscriptionUpdating
        val isBlocked = isBatch || isUpdating || isSingleTesting || mainViewModel.updateTestResultAction.value == getString(R.string.connection_test_testing)

        menu.findItem(R.id.all_ping)?.apply {
            isEnabled = !isBlocked
            icon?.alpha = if (isBlocked) 128 else 255
        }
        menu.findItem(R.id.sub_update)?.apply {
            isEnabled = !isBlocked
            icon?.alpha = if (isBlocked) 128 else 255
        }

        return super.onPrepareOptionsMenu(menu)
    }

    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean {
        if (menu.javaClass.simpleName == "MenuBuilder") {
            try {
                val method = menu.javaClass.getDeclaredMethod("setOptionalIconsVisible", java.lang.Boolean.TYPE)
                method.isAccessible = true
                method.invoke(menu, true)
            } catch (e: Exception) {}
        }
        return super.onMenuOpened(featureId, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.all_ping -> {
            val isPremium = isExtensionAvailable && MmkvManager.decodeSettingsBool(AppConfig.PREF_IS_PREMIUM_MODE, false)
            if (isPremium) {
                val servers = if (mainViewModel.subscriptionId.isEmpty()) getPremiumServerGuids() else mainViewModel.serversCache.map { it.guid }
                if (mainViewModel.subscriptionId.isNotEmpty() || servers.isNotEmpty()) {
                    isBatchTesting = true
                    hasSeenTestResult = false
                    lastTestStartTime = System.currentTimeMillis()
                    updateUIStates()
                    if (mainViewModel.subscriptionId.isEmpty()) {
                        mainViewModel.testAllRealPing(servers)
                    } else {
                        mainViewModel.testAllRealPing()
                    }
                }
            } else {
                importConfigViaSub(triggerPing = true)
            }
            true
        }
        R.id.sub_update -> {
            if (isExtensionAvailable && MmkvManager.decodeSettingsBool(AppConfig.PREF_IS_PREMIUM_MODE, false)) {
                fetchRemoteSubscriptions()
            }
            importConfigViaSub()
            true
        }
        R.id.per_app_proxy -> {
            startActivity(Intent(this, PerAppProxyActivity::class.java))
            true
        }
        R.id.routing_settings -> {
            startActivity(Intent(this, RoutingSettingActivity::class.java))
            true
        }
        R.id.asset_settings -> {
            startActivity(Intent(this, UserAssetActivity::class.java))
            true
        }
        R.id.logcat -> {
            startActivity(Intent(this, LogcatActivity::class.java))
            true
        }
        R.id.check_update -> {
            startActivity(Intent(this, CheckUpdateActivity::class.java))
            true
        }
        R.id.settings -> {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    fun importConfigViaSub(triggerPing: Boolean = false, forceSubIds: List<String>? = null): Boolean {
        mainViewModel.stopTest()
        if (mainViewModel.isRunning.value == true) {
            isDisconnecting = true
        }
        CoreServiceManager.stopVService(this)
        isSubscriptionUpdating = true
        isBatchTesting = false
        isSingleTesting = false
        isPostUpdatePingInProgress = false
        MmkvManager.setSelectServer("")
        mainViewModel.updateTestResultAction.value = null
        updateUIStates()
        refreshSelectedServer()
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            val subIds = if (forceSubIds != null) {
                forceSubIds
            } else {
                val isPremium = isExtensionAvailable && MmkvManager.decodeSettingsBool(AppConfig.PREF_IS_PREMIUM_MODE, false)
                if (isPremium) {
                    if (mainViewModel.subscriptionId.isNotEmpty()) {
                        listOf(mainViewModel.subscriptionId)
                    } else {
                        getPremiumSubIds().toList()
                    }
                } else {
                    val premiumIds = getPremiumSubIds()
                    MmkvManager.decodeSubscriptions().map { it.guid }.filter { !premiumIds.contains(it) }
                }
            }

            val result = mainViewModel.updateConfigViaSubAll(subIds)
            delay(500L)
            withContext(Dispatchers.Main) {
                if (triggerPing && (result.successCount > 0 || result.configCount > 0)) {
                    isBatchTesting = true
                    isPostUpdatePingInProgress = true
                    hasSeenTestResult = false
                    lastTestStartTime = System.currentTimeMillis()
                    MmkvManager.setSelectServer("")
                }
                isSubscriptionUpdating = false
                updateUIStates()
                hideLoading()
                if (result.successCount > 0 || result.configCount > 0) {
                    mainViewModel.reloadServerList()
                    MmkvManager.setSelectServer("") // Гарантируем сброс выбора после обновления
                    refreshSelectedServer()
                    toast(getString(R.string.title_update_subscription_result,  result.failureCount))
                    if (triggerPing) {
                        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_IS_PREMIUM_MODE, false)) {
                            if (mainViewModel.subscriptionId.isEmpty()) {
                                mainViewModel.testAllRealPing(getPremiumServerGuids())
                            } else {
                                mainViewModel.testAllRealPing()
                            }
                        } else {
                            mainViewModel.testAllRealPing(getStandardServerGuids())
                        }
                    }
                } else if (result.failureCount > 0) {
                    isBatchTesting = false
                    isPostUpdatePingInProgress = false
                    updateUIStates()
                    toast(R.string.toast_failure)
                } else {
                    isBatchTesting = false
                    isPostUpdatePingInProgress = false
                    updateUIStates()
                    toast(R.string.title_update_subscription_no_subscription)
                }
            }
        }
        return true
    }

    private val requestLicenseAuth = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        isLicenseAuthInProgress = false
        checkLicenseAuth()
    }

    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            isConnecting = true
            updateUIStates()
            startV2Ray()
        }
    }
}
