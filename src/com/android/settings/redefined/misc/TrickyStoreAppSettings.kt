package com.android.settings.redefined.misc

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import androidx.appcompat.widget.SearchView
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import com.android.internal.logging.nano.MetricsProto
import com.android.settings.R
import com.android.settings.SettingsPreferenceFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class TargetMode(val symbol: String) {
    AUTO(""),
    LEAF_HACK("?"),
    CERT_GEN("!"),
}

class TrickyStoreAppSettings : SettingsPreferenceFragment() {

    companion object {
        const val TARGET_KEY = "spoof_trickystore_target"
        val DEFAULT_TARGETS = setOf(
            "com.google.android.gms",
            "com.android.vending",
        )
        val DEFAULT_TARGET_MODES = mapOf(
            "com.revolut.revolut" to TargetMode.CERT_GEN,
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var allApps = mutableListOf<AppEntry>()
    private var showSystemApps = false
    private var searchQuery = ""

    data class AppEntry(
        val packageName: String,
        val label: String,
        val icon: android.graphics.drawable.Drawable?,
        val isSystem: Boolean,
        var isSelected: Boolean,
        var mode: TargetMode
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireActivity().title = getString(R.string.ts_manage_target_apps)
        
        val screen = preferenceManager.createPreferenceScreen(requireContext())
        preferenceScreen = screen
        setHasOptionsMenu(true)
        
        loadApps()
    }

    override fun getMetricsCategory() = MetricsProto.MetricsEvent.VIEW_UNKNOWN

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun loadApps() {
        scope.launch {
            val pm = requireContext().packageManager
            val targetMap = withContext(Dispatchers.IO) { readTargets() }
            
            val installed = withContext(Dispatchers.IO) {
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { app ->
                        val isSystem = app.flags and ApplicationInfo.FLAG_SYSTEM != 0
                        !(isSystem && listOf("overlay", "theme", "icon").any { app.packageName.contains(it) })
                    }
                    .map { app ->
                        AppEntry(
                            packageName = app.packageName,
                            label = pm.getApplicationLabel(app).toString(),
                            icon = runCatching { com.android.settingslib.Utils.getBadgedIcon(requireContext(), app) }.getOrNull(),
                            isSystem = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                            isSelected = targetMap.containsKey(app.packageName),
                            mode = targetMap[app.packageName] ?: TargetMode.AUTO
                        )
                    }
                    .sortedWith(compareByDescending<AppEntry> { it.isSelected }.thenBy { it.label.lowercase() })
            }
            
            allApps.clear()
            allApps.addAll(installed)
            refreshUi()
        }
    }

    private fun refreshUi() {
        preferenceScreen.removeAll()
        
        val category = PreferenceCategory(requireContext())
        category.title = getString(R.string.all_apps)
        preferenceScreen.addPreference(category)
        
        val query = searchQuery.lowercase()
        val filtered = allApps.filter { 
            (showSystemApps || !it.isSystem || it.isSelected) &&
            (query.isEmpty() || it.label.lowercase().contains(query) || it.packageName.lowercase().contains(query))
        }
        
        filtered.forEach { app ->
            val pref = AppSpoofPreference(requireContext()).apply {
                key = app.packageName
                icon = app.icon
                title = app.label
                summary = app.packageName + if (app.isSystem) " (System)" else ""
                isChecked = app.isSelected
                currentMode = app.mode
                
                setOnPreferenceChangeListener { _, newValue ->
                    val checked = newValue as Boolean
                    app.isSelected = checked
                    scope.launch(Dispatchers.IO) { saveTargets() }
                    true
                }
                onModeChangeListener = { mode ->
                    app.mode = mode
                    scope.launch(Dispatchers.IO) { saveTargets() }
                }
            }
            category.addPreference(pref)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.tricky_store_manage_apps, menu)
        val searchItem = menu.findItem(R.id.search_app_list_menu)
        val tintColor = com.android.settingslib.Utils.getColorAttrDefaultColor(requireContext(), android.R.attr.textColorPrimary)
        searchItem?.icon?.setTint(tintColor)
        menu.findItem(R.id.show_system)?.icon?.setTint(tintColor)
        menu.findItem(R.id.hide_system)?.icon?.setTint(tintColor)
        val searchView = searchItem?.actionView as? SearchView
        searchView?.maxWidth = Integer.MAX_VALUE
        searchView?.queryHint = getString(R.string.search_settings)
        searchItem?.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                val appBar = activity?.findViewById<com.google.android.material.appbar.AppBarLayout>(R.id.app_bar)
                appBar?.setExpanded(false, false)
                val lv = listView
                if (lv != null) androidx.core.view.ViewCompat.setNestedScrollingEnabled(lv, false)
                return true
            }
            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                val appBar = activity?.findViewById<com.google.android.material.appbar.AppBarLayout>(R.id.app_bar)
                appBar?.setExpanded(false, false)
                val lv = listView
                if (lv != null) androidx.core.view.ViewCompat.setNestedScrollingEnabled(lv, true)
                return true
            }
        })
        searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean = false
            override fun onQueryTextChange(newText: String?): Boolean {
                searchQuery = newText ?: ""
                refreshUi()
                return true
            }
        })
        menu.findItem(R.id.show_system)?.isVisible = !showSystemApps
        menu.findItem(R.id.hide_system)?.isVisible = showSystemApps
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.show_system, R.id.hide_system -> {
                showSystemApps = !showSystemApps
                activity?.invalidateOptionsMenu()
                refreshUi()
                return true
            }
            R.id.menu_select_all -> {
                allApps.forEach { it.isSelected = true }
                scope.launch(Dispatchers.IO) { saveTargets() }
                refreshUi()
                return true
            }
            R.id.menu_auto_select -> {
                allApps.forEach { 
                    it.isSelected = it.packageName in DEFAULT_TARGETS || it.packageName in DEFAULT_TARGET_MODES.keys
                    it.mode = DEFAULT_TARGET_MODES[it.packageName] ?: TargetMode.AUTO
                }
                scope.launch(Dispatchers.IO) { saveTargets() }
                refreshUi()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun readTargets(): MutableMap<String, TargetMode> {
        val result = mutableMapOf<String, TargetMode>()
        val content = Settings.Secure.getString(requireContext().contentResolver, TARGET_KEY) ?: return result
        content.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotBlank()) {
                when {
                    trimmed.endsWith("?") -> result[trimmed.dropLast(1)] = TargetMode.LEAF_HACK
                    trimmed.endsWith("!") -> result[trimmed.dropLast(1)] = TargetMode.CERT_GEN
                    else -> result[trimmed] = TargetMode.AUTO
                }
            }
        }
        return result
    }

    private fun saveTargets() {
        val lines = allApps.filter { it.isSelected }.map { it.packageName + it.mode.symbol }
        Settings.Secure.putString(requireContext().contentResolver, TARGET_KEY, lines.joinToString("\n"))
    }
}
