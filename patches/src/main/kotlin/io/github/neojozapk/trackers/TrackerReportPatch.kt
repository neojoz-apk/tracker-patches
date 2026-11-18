package io.github.neojozapk.trackers

import app.revanced.patcher.patch.bytecodePatch
import app.revanced.patcher.patch.resourcePatch
import org.w3c.dom.Element
import org.w3c.dom.Node

/** State shared between the manifest pass and the code pass of one patching run. */
private object Report {
    var sigs: Signatures? = null
    val manifestTrackers = HashSet<String>()
    var comps = 0
    var trackerComps = 0
    var inits = 0
    var perms = 0

    fun reset() {
        sigs = null
        manifestTrackers.clear()
        comps = 0; trackerComps = 0; inits = 0; perms = 0
    }
}

private val COMPONENT_TAGS = setOf("activity", "activity-alias", "service", "receiver", "provider")

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

private fun resolve(name: String?, pkg: String): String? = when {
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

/** Reads the manifest and logs it. Changes nothing. */
val trackerReportManifestPatch = resourcePatch {
    execute {
        Report.reset()
        val (sigs, src) = TrackerList.load()
        Report.sigs = sigs

        document("AndroidManifest.xml").use { document ->
            val root = document.documentElement
            val pkg = root.getAttribute("package")
            val vcRaw = root.getAttribute("android:versionCode")
            TprLog.emit(
                "t" to "meta",
                "pkg" to pkg,
                "ver" to root.getAttribute("android:versionName"),
                "vc" to (vcRaw.toLongOrNull() ?: vcRaw),
                "src" to src,
                "trackers" to sigs.size,
            )

            for (el in root.childElements()) {
                if (el.tagName == "uses-permission" || el.tagName == "uses-permission-sdk-23") {
                    val n = el.getAttribute("android:name")
                    Report.perms++
                    TprLog.emit("t" to "perm", "n" to n, "ad" to (n in AD_PERMISSIONS))
                }
            }

            val app = root.childElements().firstOrNull { it.tagName == "application" } ?: return@use
            for (el in app.childElements()) {
                if (el.tagName !in COMPONENT_TAGS) continue
                val name = resolve(el.getAttribute("android:name"), pkg) ?: ""
                val target = if (el.tagName == "activity-alias")
                    resolve(el.getAttribute("android:targetActivity"), pkg) else null
                val tracker = sigs.match(name) ?: sigs.match(target)
                Report.comps++
                if (tracker != null) {
                    Report.trackerComps++
                    Report.manifestTrackers.add(tracker)
                }
                TprLog.emit(
                    "t" to "comp",
                    "k" to el.tagName,
                    "n" to name,
                    "tr" to tracker,
                    "en" to (el.getAttribute("android:enabled") != "false"),
                )

                // androidx.startup initialisers are <meta-data> inside InitializationProvider
                if (el.tagName == "provider" && name == "androidx.startup.InitializationProvider") {
                    for (md in el.childElements()) {
                        if (md.tagName != "meta-data") continue
                        val initName = md.getAttribute("android:name")
                        val t = sigs.match(initName)
                        if (t != null) Report.manifestTrackers.add(t)
                        Report.inits++
                        TprLog.emit("t" to "init", "n" to initName, "tr" to t)
                    }
                }
            }
        }
    }
}

/** The one patch the user sees. Scans dex class names, then closes the log. */
@Suppress("unused")
val trackerReportPatch = bytecodePatch(
    name = "Tracker report",
    description = "Changes nothing. Logs the app's components, permissions and tracker code as TPR| lines.",
) {
    dependsOn(trackerReportManifestPatch)

    execute {
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
        )
    }
}
