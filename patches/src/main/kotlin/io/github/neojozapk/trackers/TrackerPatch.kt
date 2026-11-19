package io.github.neojozapk.trackers

import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.booleanOption
import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import app.revanced.patcher.patch.stringsOption
import org.w3c.dom.Element
import org.w3c.dom.Node

// ---------------------------------------------------------------------------------------------
// Options (shared by the manifest pass, which reads them, and the visible patch, which lists them)
// ---------------------------------------------------------------------------------------------

private fun validRegexes(list: List<String>?): Boolean =
    list.orEmpty().all { r -> r.isBlank() || runCatching { Regex(r) }.isSuccess }

private val optReportOnly = booleanOption(
    name = "Report only",
    default = false,
    description = "Change nothing. Log what would be changed (chg lines say \"dry\":true).",
)
private val optDisableTrackers = booleanOption(
    name = "Disable tracker components",
    default = true,
    description = "Set android:enabled=\"false\" on tracker services, receivers and providers.",
)
private val optIncludeActivities = booleanOption(
    name = "Include tracker activities",
    default = false,
    description = "Also disable tracker activities and aliases. More likely to crash the app.",
)
private val optRemoveInits = booleanOption(
    name = "Remove tracker startup initialisers",
    default = true,
    description = "Remove tracker <meta-data> entries from androidx.startup.InitializationProvider.",
)
private val optKeep = stringsOption(
    name = "Keep list",
    default = emptyList(),
    description = "Regexes on full class names. Matching tracker components and initialisers are left untouched.",
    validator = { validRegexes(it) },
)
private val optCustom = stringsOption(
    name = "Custom components to disable",
    default = emptyList(),
    description = "Regexes on full class names. Any matching component (activities too) is disabled, tracker or not.",
    validator = { validRegexes(it) },
)
private val optOptOut = booleanOption(
    name = "Add tracker opt-out flags",
    default = true,
    description = "Add documented SDK opt-out <meta-data> flags (Firebase, Facebook, Sentry).",
)
private val optStripAdPerms = booleanOption(
    name = "Remove advertising-ID permissions",
    default = true,
    description = "Remove AD_ID and AdServices permissions.",
)

// ---------------------------------------------------------------------------------------------
// Rules (ported from tracker_patch.py)
// ---------------------------------------------------------------------------------------------

private val COMPONENT_TAGS = setOf("activity", "activity-alias", "service", "receiver", "provider")

/** Documented SDK switches that turn collection off while the SDK still initialises normally. */
internal val OPTOUT_FLAGS = listOf(
    "firebase_analytics_collection_enabled" to "false",
    "firebase_analytics_collection_deactivated" to "true",
    "google_analytics_adid_collection_enabled" to "false",
    "google_analytics_ssaid_collection_enabled" to "false",
    "google_analytics_default_allow_ad_personalization_signals" to "false",
    "google_analytics_automatic_screen_reporting_enabled" to "false",
    "firebase_crashlytics_collection_enabled" to "false",
    "firebase_performance_collection_enabled" to "false",
    "firebase_performance_collection_deactivated" to "true",
    "com.facebook.sdk.AutoLogAppEventsEnabled" to "false",
    "com.facebook.sdk.AdvertiserIDCollectionEnabled" to "false",
    "com.facebook.sdk.AutoInitEnabled" to "false",
    "io.sentry.auto-init" to "false",
)

private val AD_PERMISSIONS = setOf(
    "com.google.android.gms.permission.AD_ID",
    "android.permission.ACCESS_ADSERVICES_AD_ID",
    "android.permission.ACCESS_ADSERVICES_ATTRIBUTION",
    "android.permission.ACCESS_ADSERVICES_TOPICS",
    "android.permission.ACCESS_ADSERVICES_CUSTOM_AUDIENCE",
)

/** SDKs that tend to break after re-signing: name to class prefixes. */
private val RISKY_SDKS = mapOf(
    "Google Sign-In" to listOf("com.google.android.gms.auth.api.signin.", "com.google.android.libraries.identity.googleid."),
    "Play Integrity" to listOf("com.google.android.play.core.integrity.", "com.google.android.play.integrity."),
    "PairIP" to listOf("com.pairip."),
    "Facebook login" to listOf("com.facebook.login."),
    "Microsoft login" to listOf("com.microsoft.identity.client.", "com.microsoft.aad.adal."),
)

/** State shared between the manifest pass and the code pass of one patching run. */
private object Report {
    var sigs: Signatures? = null
    val manifestTrackers = HashSet<String>()
    var comps = 0
    var trackerComps = 0
    var inits = 0
    var perms = 0
    var chg = 0
    var skip = 0

    fun reset() {
        sigs = null
        manifestTrackers.clear()
        comps = 0; trackerComps = 0; inits = 0; perms = 0; chg = 0; skip = 0
    }

    fun chg(action: String, target: String, detail: String, dry: Boolean) {
        chg++
        TprLog.emit("t" to "chg", "a" to action, "target" to target, "detail" to detail, "dry" to dry)
    }

    fun skip(target: String, why: String) {
        skip++
        TprLog.emit("t" to "skip", "target" to target, "why" to why)
    }
}

internal fun resolve(name: String?, pkg: String): String? = when {
    name.isNullOrEmpty() -> name
    name.startsWith(".") -> pkg + name
    !name.contains('.') -> "$pkg.$name"
    else -> name
}

private fun Element.childElements(): List<Element> {
    val out = ArrayList<Element>()
    var n: Node? = firstChild
    while (n != null) {
        if (n is Element) out.add(n)
        n = n.nextSibling
    }
    return out
}

private fun compile(list: List<String>?): List<Regex> =
    list.orEmpty().filter { it.isNotBlank() }.map {
        try { Regex(it.trim()) } catch (e: Exception) { throw PatchException("Invalid regex: $it") }
    }

/** Removes [el] from its parent together with the whitespace text node in front of it. */
private fun removeWithIndent(parent: Node, el: Node) {
    val prev = el.previousSibling
    if (prev != null && prev.nodeType == Node.TEXT_NODE && prev.textContent.isNullOrBlank()) parent.removeChild(prev)
    parent.removeChild(el)
}

/** Reads the manifest, logs it, and (unless report-only) applies the manifest rules. */
private val trackerManifestPatch = resourcePatch {
    apply {
        Report.reset()
        val reportOnly = optReportOnly.value ?: false
        val disableTrackers = optDisableTrackers.value ?: true
        val includeActivities = optIncludeActivities.value ?: false
        val removeInits = optRemoveInits.value ?: true
        val optOut = optOptOut.value ?: true
        val stripAdPerms = optStripAdPerms.value ?: true
        val keepRx = compile(optKeep.value)
        val customRx = compile(optCustom.value)
        fun kept(n: String) = keepRx.any { it.containsMatchIn(n) }

        val (sigs, src) = TrackerList.load()
        Report.sigs = sigs

        document("AndroidManifest.xml").use { document ->
            val root = document.documentElement
            val pkg = root.getAttribute("package")
            // apktool usually strips these from the decoded manifest -> null (the manager's log header has them).
            val vcRaw = root.getAttribute("android:versionCode")
            TprLog.emit(
                "t" to "meta",
                "pkg" to pkg,
                "ver" to root.getAttribute("android:versionName").ifEmpty { null },
                "vc" to (vcRaw.toLongOrNull() ?: vcRaw.ifEmpty { null }),
                "src" to src,
                "trackers" to sigs.size,
                "mode" to if (reportOnly) "report" else "patch",
            )
            TprLog.emit(
                "t" to "opt",
                "disable" to disableTrackers,
                "activities" to includeActivities,
                "inits" to removeInits,
                "optout" to optOut,
                "adperms" to stripAdPerms,
                "keep" to keepRx.size,
                "custom" to customRx.size,
            )

            // --- permissions -------------------------------------------------------------------
            for (el in root.childElements()) {
                if (el.tagName != "uses-permission" && el.tagName != "uses-permission-sdk-23") continue
                val n = el.getAttribute("android:name")
                val ad = n in AD_PERMISSIONS
                Report.perms++
                TprLog.emit("t" to "perm", "n" to n, "ad" to ad)
                if (ad && stripAdPerms) {
                    Report.chg("remove-perm", n, el.tagName, reportOnly)
                    if (!reportOnly) removeWithIndent(root, el)
                }
            }

            val app = root.childElements().firstOrNull { it.tagName == "application" } ?: return@use

            // --- components --------------------------------------------------------------------
            for (el in app.childElements()) {
                if (el.tagName !in COMPONENT_TAGS) continue
                val name = resolve(el.getAttribute("android:name"), pkg) ?: ""
                val target = if (el.tagName == "activity-alias")
                    resolve(el.getAttribute("android:targetActivity"), pkg) else null
                val tracker = sigs.match(name) ?: sigs.match(target)
                val wasEnabled = el.getAttribute("android:enabled") != "false"
                Report.comps++
                if (tracker != null) {
                    Report.trackerComps++
                    Report.manifestTrackers.add(tracker)
                }
                TprLog.emit("t" to "comp", "k" to el.tagName, "n" to name, "tr" to tracker, "en" to wasEnabled)

                val isActivity = el.tagName == "activity" || el.tagName == "activity-alias"
                val custom = customRx.any { it.containsMatchIn(name) }
                when {
                    // explicit request wins over the keep list and the activity rule
                    custom -> disable(el, name, "custom", wasEnabled, reportOnly)
                    tracker == null || !disableTrackers -> {}
                    kept(name) -> Report.skip(name, "keep")
                    isActivity && !includeActivities -> Report.skip(name, "activity")
                    else -> disable(el, name, "tracker:$tracker", wasEnabled, reportOnly)
                }

                // androidx.startup initialisers are <meta-data> inside InitializationProvider
                if (el.tagName == "provider" && name == "androidx.startup.InitializationProvider") {
                    for (md in el.childElements()) {
                        if (md.tagName != "meta-data") continue
                        val initName = md.getAttribute("android:name")
                        val t = sigs.match(initName)
                        if (t != null) Report.manifestTrackers.add(t)
                        Report.inits++
                        TprLog.emit("t" to "init", "n" to initName, "tr" to t)
                        if (t == null || !removeInits) continue
                        if (kept(initName)) {
                            Report.skip(initName, "keep")
                        } else {
                            Report.chg("remove-init", initName, "tracker:$t", reportOnly)
                            if (!reportOnly) removeWithIndent(el, md)
                        }
                    }
                }
            }

            // --- opt-out flags -----------------------------------------------------------------
            if (optOut) {
                val existing = app.childElements()
                    .filter { it.tagName == "meta-data" }
                    .associateBy { it.getAttribute("android:name") }
                for ((key, value) in OPTOUT_FLAGS) {
                    val md = existing[key]
                    val old = md?.getAttribute("android:value")?.ifEmpty { null }
                    if (md != null && old == value) {
                        Report.skip(key, "already")
                        continue
                    }
                    Report.chg("add-meta", key, if (old == null) "$key=$value" else "$key=$value (was $old)", reportOnly)
                    if (reportOnly) continue
                    val target = md ?: document.createElement("meta-data").also {
                        it.setAttribute("android:name", key)
                        app.appendChild(document.createTextNode("\n        "))
                        app.appendChild(it)
                    }
                    target.setAttribute("android:value", value)
                }
            }
        }
    }
}

private fun disable(el: Element, name: String, why: String, wasEnabled: Boolean, dry: Boolean) {
    if (!wasEnabled) {
        Report.skip(name, "already")
        return
    }
    Report.chg("disable", name, why, dry)
    if (!dry) el.setAttribute("android:enabled", "false")
}

/** The one patch the user sees. Runs the manifest rules, scans dex class names, then closes the log. */
@Suppress("unused")
val trackerPatch = bytecodePatch(
    name = "Tracker patches",
    description = "Disables tracker components, startup initialisers, ad-ID permissions and sets opt-out flags. " +
        "Logs every finding and change as TPR| lines.",
) {
    dependsOn(trackerManifestPatch)

    optReportOnly()
    optDisableTrackers()
    optIncludeActivities()
    optRemoveInits()
    optKeep()
    optCustom()
    optOptOut()
    optStripAdPerms()

    apply {
        val sigs = Report.sigs ?: TrackerList.load().first
        val perTracker = HashMap<String, Int>()
        val risky = HashMap<String, Int>()
        var total = 0

        classDefs.forEach { classDef ->
            val type = classDef.type // e.g. Lcom/foo/Bar;
            if (type.length < 3) return@forEach
            val fqcn = type.substring(1, type.length - 1).replace('/', '.')
            total++
            sigs.matchFast(fqcn)?.let { perTracker.merge(it, 1, Int::plus) }
            for ((sdk, prefixes) in RISKY_SDKS) {
                if (prefixes.any { fqcn.startsWith(it) }) risky.merge(sdk, 1, Int::plus)
            }
        }

        for ((tracker, n) in perTracker.entries.sortedByDescending { it.value }) {
            TprLog.emit(
                "t" to "code",
                "tr" to tracker,
                "classes" to n,
                "manifest" to (tracker in Report.manifestTrackers),
            )
        }
        for ((sdk, n) in risky) TprLog.emit("t" to "warn", "sdk" to sdk, "classes" to n)

        // Last line: lets the reader check that no line was lost.
        TprLog.emit(
            "t" to "end",
            "comps" to Report.comps,
            "trackerComps" to Report.trackerComps,
            "inits" to Report.inits,
            "perms" to Report.perms,
            "classes" to total,
            "trackersInCode" to perTracker.size,
            "warns" to risky.size,
            "chg" to Report.chg,
            "skip" to Report.skip,
        )
    }
}
