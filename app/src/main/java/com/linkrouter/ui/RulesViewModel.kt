package com.linkrouter.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.linkrouter.AppContainer
import com.linkrouter.browsers.BrowserInfo
import com.linkrouter.rules.RedirectFormat
import com.linkrouter.rules.RedirectFormatValidator
import com.linkrouter.rules.Rule
import com.linkrouter.settings.FallbackMode
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RuleRow(
    val rule: Rule,
    val browser: BrowserInfo?,
    val isPrivateCapable: Boolean,
)

data class RedirectFormatRow(
    val format: RedirectFormat,
    val preview: String?,
)

class RulesViewModel(app: Application) : AndroidViewModel(app) {

    private val container = AppContainer.get(app)
    private val repo = container.ruleRepository
    private val registry = container.browserRegistry
    private val settings = container.settings
    private val fmtRepo = container.redirectFormatRepository

    val browsers by lazy { registry.browsers }

    /**
     * All selectable rule targets: the built-in in-app WebView first, followed
     * by the installed browsers. The WebView is a synthetic entry — it is not
     * an installed app, so it is not part of [browsers].
     */
    val targets: kotlinx.coroutines.flow.StateFlow<List<BrowserInfo>> by lazy {
        registry.browsers
            .map { installed: List<BrowserInfo> ->
                listOf(com.linkrouter.browsers.WebViewTarget.browserInfo) + installed
            }
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())
    }

    val fallbackMode by lazy { settings.fallbackMode }
    val fallbackBrowser by lazy { settings.fallbackBrowser }
    val rememberedPackage by lazy { settings.rememberedPackage }
    val warnPrivate by lazy { settings.warnPrivate }

    private val _rows = mutableStateOf<List<RuleRow>>(emptyList())
    val rows: List<RuleRow> get() = _rows.value

    private val _formats = mutableStateOf<List<RedirectFormatRow>>(emptyList())
    val formats: List<RedirectFormatRow> get() = _formats.value

    init {
        viewModelScope.launch {
            // Combine both flows so rows rebuild when the async browser discovery
            // completes — otherwise a fresh process renders every rule "uninstalled"
            // because the rules snapshot is read before browsers is populated.
            container.ruleRepository.observeOrdered()
                .combine(registry.browsers) { rules, browsers ->
                    // Include the synthetic WebView entry so its rules don't
                    // show as "uninstalled" in the rule list.
                    val installed = (listOf(com.linkrouter.browsers.WebViewTarget.browserInfo) + browsers)
                        .associateBy { it.packageName }
                    rules.map { r ->
                        RuleRow(
                            rule = r,
                            browser = installed[r.targetPackage],
                            isPrivateCapable = com.linkrouter.browsers.StrategyTable.isPrivateCapable(r.targetPackage),
                        )
                    }
                }
                .collect { _rows.value = it }
        }
        viewModelScope.launch {
            container.redirectFormatRepository.observeAll().collect { list ->
                _formats.value = list.map { fmt ->
                    RedirectFormatRow(fmt, RedirectFormatValidator.preview(fmt))
                }
            }
        }
    }

    fun addRule(
        pattern: String,
        matchType: com.linkrouter.rules.MatchType,
        targetPackage: String,
        targetActivity: String?,
        openMode: com.linkrouter.rules.OpenMode,
    ) {
        viewModelScope.launch {
            val rule = Rule(
                id = 0,
                pattern = pattern,
                matchType = matchType,
                targetPackage = targetPackage,
                targetActivity = targetActivity,
                openMode = openMode,
                enabled = true,
                priority = 0,
            )
            repo.insert(rule)
        }
    }

    fun updateRule(rule: Rule) {
        viewModelScope.launch { repo.update(rule) }
    }

    fun setEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch { repo.setEnabled(id, enabled) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repo.delete(id) }
    }

    fun duplicate(id: Long) {
        viewModelScope.launch { repo.duplicate(id) }
    }

    fun reorder(newTopFirstOrder: List<Long>) {
        viewModelScope.launch { repo.reorder(newTopFirstOrder) }
    }

    fun setFallbackMode(mode: FallbackMode) {
        settings.setFallbackMode(mode)
    }

    fun setFallbackBrowser(pkg: String?) {
        settings.setFallbackBrowser(pkg)
    }

    fun refreshBrowsers() {
        registry.refresh()
    }

    fun importRules(rules: List<Rule>) {
        viewModelScope.launch { repo.importAll(rules) }
    }

    /** Parse a JSON backup; returns the rules, or null on invalid JSON. */
    fun parseJson(json: String): List<Rule>? {
        return try {
            com.linkrouter.importexport.RuleSerializer.fromJson(json)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun exportRules(): List<Rule> = repo.all()

    fun addFormat(
        name: String,
        pattern: String,
        matchType: com.linkrouter.rules.MatchType,
        extractType: com.linkrouter.rules.ExtractType,
        extractTarget: String,
        openRealDestination: Boolean,
    ) {
        viewModelScope.launch {
            val fmt = RedirectFormat(
                id = 0,
                name = name.trim(),
                pattern = pattern.trim(),
                matchType = matchType,
                extractType = extractType,
                extractTarget = extractTarget.trim(),
                enabled = true,
                priority = 0,
                isBuiltIn = false,
                openRealDestination = openRealDestination,
            )
            fmtRepo.insert(fmt)
        }
    }

    fun updateFormat(fmt: RedirectFormat) {
        viewModelScope.launch { fmtRepo.update(fmt) }
    }

    fun setFormatEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch { fmtRepo.setEnabled(id, enabled) }
    }

    fun deleteFormat(id: Long) {
        viewModelScope.launch { fmtRepo.delete(id) }
    }

    fun resetFormatBuiltIn() {
        viewModelScope.launch { fmtRepo.resetBuiltIn() }
    }

    fun importFormats(formats: List<RedirectFormat>) {
        viewModelScope.launch { fmtRepo.importAllFormats(formats) }
    }

    fun parseFormatJson(json: String): List<RedirectFormat>? {
        return try {
            com.linkrouter.importexport.RuleSerializer.fromFormatJson(json)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun exportFormats(): List<RedirectFormat> = fmtRepo.all()

    fun setWarnPrivate(enabled: Boolean) {
        settings.setWarnPrivate(enabled)
    }

    fun resetPrivateWarnings() {
        settings.resetPrivateWarnings()
    }
}
