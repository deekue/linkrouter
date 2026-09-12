package net.chaosengine.linkrouter.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import net.chaosengine.linkrouter.AppContainer
import net.chaosengine.linkrouter.browsers.BrowserInfo
import net.chaosengine.linkrouter.rules.RedirectFormat
import net.chaosengine.linkrouter.rules.RedirectFormatValidator
import net.chaosengine.linkrouter.rules.Rule
import net.chaosengine.linkrouter.settings.FallbackMode
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

data class ShortenerHostRow(val host: net.chaosengine.linkrouter.rules.ShortenerHost)

data class QueryParamFilterRow(val filter: net.chaosengine.linkrouter.rules.QueryParamFilter)

data class HostRewriteRow(val rewrite: net.chaosengine.linkrouter.rules.HostRewrite)

class RulesViewModel(app: Application) : AndroidViewModel(app) {

    private val container = AppContainer.get(app)
    private val repo = container.ruleRepository
    private val registry = container.browserRegistry
    private val settings = container.settings
    private val fmtRepo = container.redirectFormatRepository
    private val shortenerRepo = container.shortenerHostRepository
    private val paramFilterRepo = container.queryParamFilterRepository
    private val hostRewriteRepo = container.hostRewriteRepository

    val browsers by lazy { registry.browsers }

    /**
     * All selectable rule targets: the built-in in-app WebView first, followed
     * by the installed browsers. The WebView is a synthetic entry — it is not
     * an installed app, so it is not part of [browsers].
     */
    val targets: kotlinx.coroutines.flow.StateFlow<List<BrowserInfo>> by lazy {
        registry.browsers
            .map { installed: List<BrowserInfo> ->
                listOf(net.chaosengine.linkrouter.browsers.WebViewTarget.browserInfo) + installed
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

    private val _shortenerHosts = mutableStateOf<List<ShortenerHostRow>>(emptyList())
    val shortenerHosts: List<ShortenerHostRow> get() = _shortenerHosts.value

    private val _queryParamFilters = mutableStateOf<List<QueryParamFilterRow>>(emptyList())
    val queryParamFilters: List<QueryParamFilterRow> get() = _queryParamFilters.value

    private val _hostRewrites = mutableStateOf<List<HostRewriteRow>>(emptyList())
    val hostRewrites: List<HostRewriteRow> get() = _hostRewrites.value

    init {
        viewModelScope.launch {
            // Combine both flows so rows rebuild when the async browser discovery
            // completes — otherwise a fresh process renders every rule "uninstalled"
            // because the rules snapshot is read before browsers is populated.
            container.ruleRepository.observeOrdered()
                .combine(registry.browsers) { rules, browsers ->
                    // Include the synthetic WebView entry so its rules don't
                    // show as "uninstalled" in the rule list.
                    val installed = (listOf(net.chaosengine.linkrouter.browsers.WebViewTarget.browserInfo) + browsers)
                        .associateBy { it.packageName }
                    rules.map { r ->
                        RuleRow(
                            rule = r,
                            browser = installed[r.targetPackage],
                            isPrivateCapable = net.chaosengine.linkrouter.browsers.StrategyTable.isPrivateCapable(r.targetPackage),
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
        viewModelScope.launch {
            container.shortenerHostRepository.observeAll().collect { list ->
                _shortenerHosts.value = list.map { ShortenerHostRow(it) }
            }
        }
        viewModelScope.launch {
            container.queryParamFilterRepository.observeAll().collect { list ->
                _queryParamFilters.value = list.map { QueryParamFilterRow(it) }
            }
        }
        viewModelScope.launch {
            container.hostRewriteRepository.observeAll().collect { list ->
                _hostRewrites.value = list.map { HostRewriteRow(it) }
            }
        }
    }

    fun addRule(
        pattern: String,
        matchType: net.chaosengine.linkrouter.rules.MatchType,
        targetPackage: String,
        targetActivity: String?,
        openMode: net.chaosengine.linkrouter.rules.OpenMode,
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
            net.chaosengine.linkrouter.importexport.RuleSerializer.fromJson(json)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun exportRules(): List<Rule> = repo.all()

    fun addFormat(
        name: String,
        pattern: String,
        matchType: net.chaosengine.linkrouter.rules.MatchType,
        extractType: net.chaosengine.linkrouter.rules.ExtractType,
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

    fun addShortenerHost(name: String, host: String, pathPrefix: String? = null) {
        viewModelScope.launch {
            val prefix = pathPrefix?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            shortenerRepo.insert(
                net.chaosengine.linkrouter.rules.ShortenerHost(
                    id = 0,
                    name = name.trim(),
                    host = host.trim().lowercase(),
                    pathPrefix = prefix,
                    enabled = true,
                    isBuiltIn = false,
                )
            )
        }
    }

    fun setShortenerHostEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch { shortenerRepo.setEnabled(id, enabled) }
    }

    fun deleteShortenerHost(id: Long) {
        viewModelScope.launch { shortenerRepo.delete(id) }
    }

    fun addQueryParamFilter(param: String, host: String? = null) {
        viewModelScope.launch {
            val h = host?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            paramFilterRepo.insert(
                net.chaosengine.linkrouter.rules.QueryParamFilter(
                    id = 0,
                    name = param.trim(),
                    host = h,
                    param = param.trim().lowercase(),
                    enabled = true,
                    isBuiltIn = false,
                )
            )
        }
    }

    fun updateQueryParamFilter(filter: net.chaosengine.linkrouter.rules.QueryParamFilter) {
        viewModelScope.launch { paramFilterRepo.update(filter) }
    }

    fun setQueryParamFilterEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch { paramFilterRepo.setEnabled(id, enabled) }
    }

    fun deleteQueryParamFilter(id: Long) {
        viewModelScope.launch { paramFilterRepo.delete(id) }
    }

    fun importFormats(formats: List<RedirectFormat>) {
        viewModelScope.launch { fmtRepo.importAllFormats(formats) }
    }

    fun parseFormatJson(json: String): List<RedirectFormat>? {
        return try {
            net.chaosengine.linkrouter.importexport.RuleSerializer.fromFormatJson(json)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun exportFormats(): List<RedirectFormat> = fmtRepo.all()

    fun importQueryParamFilters(filters: List<net.chaosengine.linkrouter.rules.QueryParamFilter>) {
        viewModelScope.launch { paramFilterRepo.importAllFilters(filters) }
    }

    fun parseFilterJson(json: String): List<net.chaosengine.linkrouter.rules.QueryParamFilter>? {
        return try {
            net.chaosengine.linkrouter.importexport.RuleSerializer.fromFilterJson(json)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun exportQueryParamFilters(): List<net.chaosengine.linkrouter.rules.QueryParamFilter> = paramFilterRepo.all()

    fun importShortenerHosts(hosts: List<net.chaosengine.linkrouter.rules.ShortenerHost>) {
        viewModelScope.launch { shortenerRepo.importAllHosts(hosts) }
    }

    fun parseShortenerHostJson(json: String): List<net.chaosengine.linkrouter.rules.ShortenerHost>? {
        return try {
            net.chaosengine.linkrouter.importexport.RuleSerializer.fromShortenerHostJson(json)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun exportShortenerHosts(): List<net.chaosengine.linkrouter.rules.ShortenerHost> = shortenerRepo.all()

    fun importHostRewrites(rewrites: List<net.chaosengine.linkrouter.rules.HostRewrite>) {
        viewModelScope.launch { hostRewriteRepo.importAll(rewrites) }
    }

    fun parseHostRewriteJson(json: String): List<net.chaosengine.linkrouter.rules.HostRewrite>? {
        return try {
            net.chaosengine.linkrouter.importexport.RuleSerializer.fromHostRewriteJson(json)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun exportHostRewrites(): List<net.chaosengine.linkrouter.rules.HostRewrite> = hostRewriteRepo.all()

    /**
     * Validate a host-rewrite rule's fields up front (pure, no save). Returns
     * [net.chaosengine.linkrouter.rules.HostRewriteValidator.Result.Valid]
     * (with optional non-blocking [net.chaosengine.linkrouter.rules.HostRewriteValidator.Result.Valid.warnings])
     * or [net.chaosengine.linkrouter.rules.HostRewriteValidator.Result.Invalid].
     * The UI dialog can render [reason]/[warnings] and only call [add]/[edit]
     * on a [net.chaosengine.linkrouter.rules.HostRewriteValidator.Result.Valid] outcome.
     */
    fun validateHostRewrite(
        matchHost: String,
        matchType: net.chaosengine.linkrouter.rules.RewriteMatchType,
        kind: net.chaosengine.linkrouter.rules.RewriteKind,
        targetHost: String,
        preserveHostInPath: Boolean,
    ): net.chaosengine.linkrouter.rules.HostRewriteValidator.Result =
        net.chaosengine.linkrouter.rules.HostRewriteValidator.validate(
            matchHost, matchType, kind, targetHost, preserveHostInPath,
        )

    /**
     * Create a new rewrite. The fields are validated first; a
     * [net.chaosengine.linkrouter.rules.HostRewriteValidator.Result.Invalid] result is returned
     * (and nothing is saved) so the UI can surface the error. On success the
     * normalized hosts are stored and the result returned (warnings, if any,
     * are non-blocking and already surfaced in the dialog).
     */
    fun add(
        matchHost: String,
        matchType: net.chaosengine.linkrouter.rules.RewriteMatchType,
        kind: net.chaosengine.linkrouter.rules.RewriteKind,
        targetHost: String,
        preserveHostInPath: Boolean,
    ): net.chaosengine.linkrouter.rules.HostRewriteValidator.Result? {
        val result = net.chaosengine.linkrouter.rules.HostRewriteValidator.validate(
            matchHost, matchType, kind, targetHost, preserveHostInPath,
        )
        if (result is net.chaosengine.linkrouter.rules.HostRewriteValidator.Result.Invalid) return result
        val valid = result as net.chaosengine.linkrouter.rules.HostRewriteValidator.Result.Valid
        viewModelScope.launch {
            hostRewriteRepo.insert(
                net.chaosengine.linkrouter.rules.HostRewrite(
                    id = 0,
                    matchHost = valid.normalizedMatchHost,
                    matchType = matchType,
                    kind = kind,
                    targetHost = valid.normalizedTargetHost,
                    preserveHostInPath = preserveHostInPath,
                    enabled = true,
                    priority = 0,
                    isBuiltIn = false,
                )
            )
        }
        return result
    }

    /**
     * Update an existing rewrite (built-in rows included: re-targeting is
     * allowed, only the `isBuiltIn` flag is locked by the repository). Same
     * validation/gating as [add]: `Invalid` is returned and nothing saved.
     */
    fun edit(
        rewrite: net.chaosengine.linkrouter.rules.HostRewrite,
    ): net.chaosengine.linkrouter.rules.HostRewriteValidator.Result? {
        val result = net.chaosengine.linkrouter.rules.HostRewriteValidator.validate(
            rewrite.matchHost,
            rewrite.matchType,
            rewrite.kind,
            rewrite.targetHost,
            rewrite.preserveHostInPath,
        )
        if (result is net.chaosengine.linkrouter.rules.HostRewriteValidator.Result.Invalid) return result
        val valid = result as net.chaosengine.linkrouter.rules.HostRewriteValidator.Result.Valid
        viewModelScope.launch {
            hostRewriteRepo.update(rewrite.copy(matchHost = valid.normalizedMatchHost, targetHost = valid.normalizedTargetHost))
        }
        return result
    }

    /** Toggle a rewrite's enabled flag. Built-in rows can be disabled. */
    fun setHostRewriteEnabled(id: Long, enabled: Boolean) {
        viewModelScope.launch { hostRewriteRepo.setEnabled(id, enabled) }
    }

    /**
     * Delete a rewrite. Built-in rows are refused by the repository (deleting
     * is redirected to disabling), so this is a no-op for those.
     */
    fun deleteHostRewrite(id: Long) {
        viewModelScope.launch { hostRewriteRepo.delete(id) }
    }

    /** Reorder rewrites to a new top-first id order (two-phase temp-priority). */
    fun reorderHostRewrites(newTopFirstOrder: List<Long>) {
        viewModelScope.launch { hostRewriteRepo.reorder(newTopFirstOrder) }
    }

    /**
     * Live preview: [sampleUrl] with [rule] applied, or [sampleUrl] unchanged when
     * the rule does not match (delegates to [net.chaosengine.linkrouter.rules.HostRewriter.preview]).
     */
    fun hostRewritePreview(
        rule: net.chaosengine.linkrouter.rules.HostRewrite,
        sampleUrl: String,
    ): String = net.chaosengine.linkrouter.rules.HostRewriter.preview(rule, sampleUrl)

    fun setWarnPrivate(enabled: Boolean) {
        settings.setWarnPrivate(enabled)
    }

    fun resetPrivateWarnings() {
        settings.resetPrivateWarnings()
    }
}
