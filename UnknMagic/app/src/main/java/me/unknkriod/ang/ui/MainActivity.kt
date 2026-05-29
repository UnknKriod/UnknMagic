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
import me.unknkriod.ang.util.RemoteSubscription

import me.unknkriod.ang.handler.UpdateCheckerManager
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

    private val licenseBridge by lazy { LicenseProvider.get() }
    private val isExtensionAvailable get() = licenseBridge.isExtensionAvailable

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentViewWithToolbar(binding.root, showHomeAsUp = false, title = getString(R.string.app_name))

        binding.fab.setOnClickListener { handleFabAction() }
        binding.tvTestState.setOnClickListener { handleLayoutTestClick() }
        binding.btnStopTest.setOnClickListener { mainViewModel.stopTest() }
        binding.rvTopServers.adapter = topServersAdapter

        binding.btnPingAllEmpty.setOnClickListener {
            val isPremium = isExtensionAvailable && MmkvManager.decodeSettingsBool(PREF_IS_PREMIUM_MODE, false)
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
        val lastCheck = MmkvManager.decodeSettingsLong(PREF_LAST_UPDATE_CHECK, 0L)
        val now = System.currentTimeMillis()
        
        if (now - lastCheck >= TimeUnit.DAYS.toMillis(1)) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val result = UpdateCheckerManager.checkForUpdate()
                    if (result.hasUpdate) {
                        withContext(Dispatchers.Main) {
                            startActivity(Intent(this@MainActivity, CheckUpdateActivity::class.java))
                        }
                    }
                    MmkvManager.encodeSettings(PREF_LAST_UPDATE_CHECK, now)
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
                MmkvManager.encodeSettings(PREF_IS_PREMIUM_MODE, isPremium)
                refreshModeUI()
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun refreshModeUI() {
        val isPremium = isExtensionAvailable && MmkvManager.decodeSettingsBool(PREF_IS_PREMIUM_MODE, false)

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
                        
                        if (!hasPremium && MmkvManager.decodeSettingsBool(PREF_IS_PREMIUM_MODE, false)) {
                            MmkvManager.encodeSettings(PREF_IS_PREMIUM_MODE, false)
                            binding.tabMode.getTabAt(0)?.select()
                        }

                        syncAndAutoImport(subs)
                        refreshSelectedServer()
                    }.onFailure {
                        Log.e("Unknown Magic", "Failed to fetch subscriptions", it)
                        val hasPremium = getPremiumSubIds().isNotEmpty()
                        binding.tabMode.visibility = if (hasPremium) View.VISIBLE else View.GONE
                        
                        toast(it.message ?: "Error fetching subscriptions")
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

            MmkvManager.encodeSettings(PREF_PREMIUM_SUBS_LIST, newPremiumIds.joinToString(","))

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
        mainViewModel.isRunning.observe(this) {
            updateUIStates()
        }
        mainViewModel.updateTestResultAction.observe(this) { result ->
            val testingText = getString(R.string.connection_test_testing)
            val now = System.currentTimeMillis()

            if (result != null) {
                if (result != testingText && result != getString(R.string.connection_test_stopping)) {
                    hasSeenTestResult = true
                }
                if (isSingleTesting && result != testingText) {
                    isSingleTesting = false
                }
            } else {
                if ((isBatchTesting || isPostUpdatePingInProgress) && !hasSeenTestResult && (now - lastTestStartTime < 1000)) {
                    return@observe
                }
                isBatchTesting = false
                isSingleTesting = false
                isPostUpdatePingInProgress = false
            }

            if (result != null && (result == "EOF_DETECTED" || result.contains("EOF", ignoreCase = true))) {
                val lastAutoUpdate = MmkvManager.decodeSettingsLong("last_auto_update_sub", 0L)
                val isStandardTab = binding.tabMode.selectedTabPosition == 0
                val canAutoUpdate = isStandardTab && (now - lastAutoUpdate > 60_000L)

                if (canAutoUpdate) {
                    MmkvManager.encodeSettings("last_auto_update_sub", now)
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
        if (mainViewModel.isRunning.value == true) {
            CoreServiceManager.stopVService(this)
        } else {
            val allServers = MmkvManager.decodeAllServerList()
            if (allServers.isEmpty()) {
                importConfigViaSub()
                return
            }

            if (MmkvManager.getSelectServer().isNullOrEmpty()) {
                val isPremium = isExtensionAvailable && MmkvManager.decodeSettingsBool(PREF_IS_PREMIUM_MODE, false)
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
                    startV2Ray()
                } else {
                    requestVpnPermission.launch(intent)
                }
            } else {
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

    private fun startV2Ray() {
        val guid = MmkvManager.getSelectServer()
        if (guid.isNullOrEmpty()) {
            toast("No server selected. Updating subscription...")
            importConfigViaSub()
            return
        }
        MmkvManager.addRecentServer(guid)
        refreshSelectedServer()
        CoreServiceManager.startVService(this)
    }

    fun restartV2Ray() {
        if (mainViewModel.isRunning.value == true) {
            CoreServiceManager.stopVService(this)
        }
        lifecycleScope.launch {
            delay(500)
            startV2Ray()
        }
    }



    private fun refreshSelectedServer() {
        val isPremium = isExtensionAvailable && MmkvManager.decodeSettingsBool(PREF_IS_PREMIUM_MODE, false)
        
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
        binding.layoutRecentServers.removeAllViews()
        if (filteredRecent.isEmpty()) {
            binding.cardRecent.visibility = View.GONE
            binding.tvRecentLabel.visibility = View.GONE
        } else {
            binding.cardRecent.visibility = View.VISIBLE
            binding.tvRecentLabel.visibility = View.VISIBLE
            filteredRecent.forEach { (guid, profile) ->
                addServerToLayout(guid, profile, currentGuid, fromRecent = true)
            }
        }

        if (currentGuid != null) {
            if (selectionFromRecent) {
                binding.cardRecent.alpha = 1.0f
                binding.rvTopServers.alpha = 0.5f
            } else {
                binding.cardRecent.alpha = 0.5f
                binding.rvTopServers.alpha = 1.0f
            }
        } else {
            binding.cardRecent.alpha = 1.0f
            binding.rvTopServers.alpha = 1.0f
        }
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
        val premiumSubsStr = MmkvManager.decodeSettingsString(PREF_PREMIUM_SUBS_LIST, "")
        return premiumSubsStr?.split(",")?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
    }

    private fun updateTop10List() {
        val isPremium = isExtensionAvailable && MmkvManager.decodeSettingsBool(PREF_IS_PREMIUM_MODE, false)
        if (isPremium) {
            updateUIStates()
            return
        }

        val premiumIds = getPremiumSubIds()
        val allServers = mainViewModel.serversCache
        val displayServers = allServers.filter { item ->
            val delay = MmkvManager.decodeServerAffiliationInfo(item.guid)?.testDelayMillis ?: 0L
            delay > 0 && !premiumIds.contains(item.profile.subscriptionId)
        }.sortedBy { item ->
            MmkvManager.decodeServerAffiliationInfo(item.guid)?.testDelayMillis ?: Long.MAX_VALUE
        }.take(10)

        if (displayServers.isEmpty()) {
            binding.rvTopServers.visibility = View.GONE
            binding.tvTopLabel.visibility = View.GONE
            binding.layoutEmptyTop.visibility = View.VISIBLE
        } else {
            binding.rvTopServers.visibility = View.VISIBLE
            binding.tvTopLabel.visibility = View.VISIBLE
            binding.layoutEmptyTop.visibility = View.GONE
            topServersAdapter.submitList(displayServers)
        }

        updateUIStates()
    }

    companion object {
        private const val PREF_IS_PREMIUM_MODE = "is_premium_mode"
        private const val PREF_PREMIUM_SUB_ID = "premium_sub_id"
        private const val PREF_PREMIUM_SUBS_LIST = "premium_subs_list"
        private const val PREF_LAST_UPDATE_CHECK = "last_update_check_time"
    }

    private fun updateUIStates() {
        val isRunning = mainViewModel.isRunning.value == true
        val testResult = mainViewModel.updateTestResultAction.value

        val isBatch = isBatchTesting || isPostUpdatePingInProgress
        val isUpdating = isFetchingRemote || isSubscriptionUpdating
        val isTestingText = testResult == getString(R.string.connection_test_testing)
        val isStoppingText = testResult == getString(R.string.connection_test_stopping)

        val isHeavyProcess = isBatch || isUpdating

        // 1. FAB (Stop/Play)
        val currentGuid = MmkvManager.getSelectServer()
        val isPremiumMode = isExtensionAvailable && MmkvManager.decodeSettingsBool(PREF_IS_PREMIUM_MODE, false)
        val isServerSelected = if (currentGuid.isNullOrEmpty()) {
            false
        } else {
            val profile = MmkvManager.decodeServerConfig(currentGuid)
            profile != null && (getPremiumSubIds().contains(profile.subscriptionId) == isPremiumMode)
        }

        val fabEnabled = (isRunning || isServerSelected) && !isHeavyProcess
        binding.fab.isEnabled = fabEnabled
        binding.fab.alpha = if (fabEnabled) 1.0f else 0.6f

        val iconRes = if (isRunning) R.drawable.ic_stop_24dp else R.drawable.ic_play_24dp
        val colorRes = if (isRunning) R.color.color_fab_active else R.color.color_fab_inactive
        binding.fab.setIconResource(iconRes)
        binding.fab.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))

        // 2. Test State Label
        when {
            testResult != null -> binding.tvTestState.text = testResult
            isBatch || isSingleTesting || isUpdating -> binding.tvTestState.text = getString(R.string.connection_test_testing)
            else -> binding.tvTestState.text = getString(if (isRunning) R.string.connection_connected else R.string.connection_not_connected)
        }

        val isAnyTesting = isBatch || isSingleTesting || isTestingText || isStoppingText
        val testClickable = (isRunning || isServerSelected) && !isAnyTesting && !isUpdating
        binding.tvTestState.isEnabled = testClickable
        binding.tvTestState.alpha = if (isRunning || isServerSelected) 1.0f else 0.6f

        // 3. Stop Button
        binding.btnStopTest.visibility = if (isBatch) View.VISIBLE else View.GONE
        binding.btnStopTest.isEnabled = !isStoppingText
        binding.btnStopTest.alpha = if (isStoppingText) 0.5f else 1.0f

        // 4. Ping All Empty Button
        val pingAllEnabled = !isHeavyProcess
        binding.btnPingAllEmpty.isEnabled = pingAllEnabled
        binding.btnPingAllEmpty.alpha = if (pingAllEnabled) 1.0f else 0.6f

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
            val isPremium = isExtensionAvailable && MmkvManager.decodeSettingsBool(PREF_IS_PREMIUM_MODE, false)
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
            if (isExtensionAvailable && MmkvManager.decodeSettingsBool(PREF_IS_PREMIUM_MODE, false)) {
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
        R.id.settings -> {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    fun importConfigViaSub(triggerPing: Boolean = false, forceSubIds: List<String>? = null): Boolean {
        CoreServiceManager.stopVService(this)
        isSubscriptionUpdating = true
        updateUIStates()
        showLoading()
        lifecycleScope.launch(Dispatchers.IO) {
            val subIds = if (forceSubIds != null) {
                forceSubIds
            } else {
                val isPremium = isExtensionAvailable && MmkvManager.decodeSettingsBool(PREF_IS_PREMIUM_MODE, false)
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
                    refreshSelectedServer()
                    toast(getString(R.string.title_update_subscription_result,  result.failureCount))
                    if (triggerPing) {
                        if (MmkvManager.decodeSettingsBool(PREF_IS_PREMIUM_MODE, false)) {
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
        checkLicenseAuth()
    }

    private val requestVpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) {
            startV2Ray()
        }
    }
}
