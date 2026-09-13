package net.chaosengine.linkrouter

import android.content.Intent
import android.net.Uri

object LinkRouter {
    const val EXTRA_HANDLED = "net.chaosengine.linkrouter.extra.HANDLED"
    const val LOOP_GUARD_PARAM = "__lr"

    /** Carries the URL to open through the in-app [BrowserChooserActivity]. */
    const val EXTRA_URI = "net.chaosengine.linkrouter.extra.URI"

    /** The share string-array extra, `text/uri-list` / multi-share (API 31+). */
    const val EXTRA_STRINGS = "android.intent.extra.STRINGS"

    /**
     * Collect the candidate string(s) an "Share link" (ACTION_SEND, "text"
     * mime-type wildcard) intent may carry, in precedence order:
     *   1. `android.intent.extra.TEXT` (text/plain) — as a String; some
     *      senders store a `Uri` here instead (e.g. `am start` with a
     *      text/plain `-t`), or a `CharSequence[]` array
     *   2. `android.intent.extra.STRINGS` (text/uri-list / multiple links)
     *   3. the intent's `data` Uri (some senders attach the link as data)
     *
     * Each raw candidate is then handed to
     * [net.chaosengine.linkrouter.rules.RuleEngine.firstLinkIn] to recover the
     * actual http(s) link (the share text may wrap the URL in prose).
     */
    fun sharedTextCandidates(intent: Intent?): List<String> {
        val out = mutableListOf<String>()
        val i = intent ?: return out

        // EXTRA_TEXT is usually a String, but some senders store a Uri here
        // (e.g. an emulator `am start` sharing a text/plain URL) or a
        // CharSequence[] array. Probing it with the typed getters
        // (`getStringExtra`, `getParcelableExtra`, `getCharSequenceArrayExtra`)
        // makes AOSP's `Bundle` validate the stored type before handing it
        // back; a mismatch triggers a `W Bundle: java.lang.ClassCastException`
        // warning logged by `Bundle` *itself*, so a try/catch on our side can
        // never silence it.
        //
        // Instead we fetch the raw stored value once via `Bundle.get(name)` —
        // which returns it as `Any?` with *no* type validation/cast (and also
        // unpacks any lazy-parcel wrapper from a freshly delivered IPC intent)
        // — and then match it against each possible type with a silent `as?`
        // safe-cast. This preserves the exact candidate order/precedence while
        // emitting no warnings at all.
        val text = i.extras?.get(Intent.EXTRA_TEXT)
        (text as? String)?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        (text as? Uri)?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        (text as? Array<out CharSequence>)?.forEach { addIfPresent(out, it) }

        // EXTRA_STRINGS is the string-array extra used for multi-link shares.
        (i.extras?.get(EXTRA_STRINGS) as? Array<out CharSequence>)?.forEach { addIfPresent(out, it) }

        i.data?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        return out
    }

    private fun addIfPresent(out: MutableList<String>, value: CharSequence?) {
        value?.takeIf { it.isNotBlank() }?.let { out.add(it.toString()) }
    }
}
