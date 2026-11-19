package io.github.neojozapk.trackers

import java.net.HttpURLConnection
import java.net.URI

/** Exodus-style code signatures: 'a.b.|c.d.' alternatives, treated as regex. */
internal class Signatures(mapping: Map<String, List<String>>) {
    private val items = ArrayList<Pair<Regex, String>>()
    private val prefixIndex = HashMap<String, MutableList<Pair<String, String>>>()
    private val slow = ArrayList<Pair<Regex, String>>()
    val size = mapping.size

    init {
        val plain = Regex("^[A-Za-z0-9_\$]+(?:\\.[A-Za-z0-9_\$]+)*\\.?\$")
        for ((name, sigs) in mapping) {
            for (raw in sigs) {
                val sig = raw.trim()
                if (sig.replace("\\", "").length < 5) continue // ignore absurdly short ones
                val rx = try { Regex(sig) } catch (e: Exception) { Regex(Regex.escape(sig)) }
                items.add(rx to name)
                if (plain.matches(sig) && sig.count { it == '.' } >= 2) {
                    val key = sig.split('.').take(2).joinToString(".")
                    prefixIndex.getOrPut(key) { ArrayList() }.add(sig to name)
                } else {
                    slow.add(rx to name)
                }
            }
        }
    }

    /** Full regex search, like Exodus does. Used for manifest entries. */
    fun match(fqcn: String?): String? {
        if (fqcn.isNullOrEmpty()) return null
        for ((rx, name) in items) if (rx.containsMatchIn(fqcn)) return name
        return null
    }

    /** Prefix-index lookup for scanning tens of thousands of classes. */
    fun matchFast(fqcn: String): String? {
        val key = fqcn.split('.').take(2).joinToString(".")
        prefixIndex[key]?.let { list ->
            for ((prefix, name) in list) if (fqcn.startsWith(prefix)) return name
        }
        for ((rx, name) in slow) if (rx.containsMatchIn(fqcn)) return name
        return null
    }
}

internal object TrackerList {
    private const val URL_EXODUS = "https://reports.exodus-privacy.eu.org/api/trackers"

    /**
     * Returns signatures and the source tag for the log: "exodus-live" or "snapshot".
     * The built-in list is always merged in: Exodus entries come first (their names win when
     * several trackers match), and for a tracker present in both, the signatures are combined.
     */
    fun load(): Pair<Signatures, String> {
        try {
            val live = parseExodus(download())
            if (live.size >= 50) return Signatures(merge(live, SNAPSHOT)) to "exodus-live"
        } catch (e: Exception) {
            // fall through to the snapshot
        }
        return Signatures(SNAPSHOT) to "snapshot"
    }

    internal fun merge(primary: Map<String, List<String>>, extra: Map<String, List<String>>): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        for ((name, sigs) in primary) out[name] = sigs.toMutableList()
        for ((name, sigs) in extra) {
            val list = out.getOrPut(name) { ArrayList() }
            for (s in sigs) if (s !in list) list.add(s)
        }
        return out
    }

    private fun download(): String {
        val c = URI(URL_EXODUS).toURL().openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 20000
        c.setRequestProperty("User-Agent", "tracker-patches")
        try {
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    // Tracker objects in the Exodus API are flat (no nested objects), so a regex is enough.
    private val objRx = Regex("\\{[^{}]*\"code_signature\"[^{}]*\\}")
    private val nameRx = Regex("\"name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
    private val sigRx = Regex("\"code_signature\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

    internal fun parseExodus(json: String): Map<String, List<String>> {
        val out = LinkedHashMap<String, List<String>>()
        for (m in objRx.findAll(json)) {
            val block = m.value
            val name = nameRx.find(block)?.groupValues?.get(1)?.let(::unescape) ?: continue
            val sig = sigRx.find(block)?.groupValues?.get(1)?.let(::unescape)?.trim().orEmpty()
            if (sig.isNotEmpty()) out[name] = sig.split('|')
        }
        return out
    }

    private fun unescape(s: String): String {
        if ('\\' !in s) return s
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) { sb.append(c); i++; continue }
            when (val n = s[i + 1]) {
                'n' -> sb.append('\n')
                't' -> sb.append('\t')
                'r' -> sb.append('\r')
                'b' -> sb.append('\b')
                'f' -> sb.append('\u000c')
                'u' -> {
                    if (s.length >= i + 6) {
                        sb.append(s.substring(i + 2, i + 6).toInt(16).toChar())
                        i += 4
                    } else sb.append(n)
                }
                else -> sb.append(n) // \\ \" \/
            }
            i += 2
        }
        return sb.toString()
    }

    /** Built-in snapshot (very common SDK packages). Replaced by the live list when reachable. */
    private val SNAPSHOT: Map<String, List<String>> = mapOf(
        "Google Firebase Analytics" to listOf("com.google.firebase.analytics.", "com.google.android.gms.measurement."),
        "Google CrashLytics" to listOf("com.crashlytics.", "com.google.firebase.crashlytics."),
        "Google Firebase Performance" to listOf("com.google.firebase.perf."),
        "Google AdMob" to listOf("com.google.android.gms.ads."),
        "Google Analytics" to listOf("com.google.android.gms.analytics."),
        "Google Tag Manager" to listOf("com.google.android.gms.tagmanager.", "com.google.tagmanager."),
        "Google IMA" to listOf("com.google.ads.interactivemedia."),
        "Facebook Analytics" to listOf("com.facebook.appevents.", "com.facebook.marketing.", "com.facebook.CampaignTrackingReceiver"),
        "Facebook Ads" to listOf("com.facebook.ads."),
        "Facebook SDK init" to listOf("com.facebook.internal.FacebookInitProvider"),
        "AppsFlyer" to listOf("com.appsflyer."),
        "Adjust" to listOf("com.adjust.sdk."),
        "Branch" to listOf("io.branch."),
        "Mixpanel" to listOf("com.mixpanel."),
        "Amplitude" to listOf("com.amplitude."),
        "Flurry" to listOf("com.flurry."),
        "Segment" to listOf("com.segment.analytics."),
        "Braze (Appboy)" to listOf("com.appboy.", "com.braze."),
        "OneSignal" to listOf("com.onesignal."),
        "CleverTap" to listOf("com.clevertap."),
        "MoEngage" to listOf("com.moengage."),
        "Singular" to listOf("com.singular.sdk."),
        "Kochava" to listOf("com.kochava."),
        "Tenjin" to listOf("com.tenjin."),
        "Sentry" to listOf("io.sentry."),
        "Bugsnag" to listOf("com.bugsnag."),
        "Instabug" to listOf("com.instabug."),
        "New Relic" to listOf("com.newrelic.agent."),
        "Datadog" to listOf("com.datadog."),
        "Microsoft App Center" to listOf("com.microsoft.appcenter.analytics.", "com.microsoft.appcenter.crashes."),
        "Microsoft Clarity" to listOf("com.microsoft.clarity."),
        "Huawei Analytics" to listOf("com.huawei.hms.analytics."),
        "Yandex AppMetrica" to listOf("com.yandex.metrica.", "io.appmetrica.analytics."),
        "MyTracker" to listOf("com.my.tracker."),
        "Unity Ads" to listOf("com.unity3d.ads.", "com.unity3d.services."),
        "AppLovin" to listOf("com.applovin."),
        "ironSource" to listOf("com.ironsource."),
        "InMobi" to listOf("com.inmobi."),
        "Vungle / Liftoff" to listOf("com.vungle."),
        "Chartboost" to listOf("com.chartboost.sdk."),
        "StartApp" to listOf("com.startapp."),
        "Pangle (ByteDance)" to listOf("com.bytedance.sdk.openadsdk."),
        "Mintegral" to listOf("com.mbridge.msdk."),
        // Added after the mydealz test (Phase 0): SDKs the live Exodus list did not match.
        "Moloco" to listOf("com.moloco.sdk."),
        "Display.io" to listOf("com.brandio.ads."),
        "Confiant" to listOf("com.confiant."),
        "Iterable" to listOf("com.iterable.iterableapi."),
        "Google Mobile Ads init" to listOf(
            "com.google.android.gms.ads.MobileAdsInitProvider",
            "com.google.android.gms.ads.AdService",
        ),
    )
}
