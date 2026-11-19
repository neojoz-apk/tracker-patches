package io.github.neojozapk.trackers

import app.revanced.patcher.patch.ResourcePatchContext
import app.revanced.patcher.patch.stringOption
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

// ---------------------------------------------------------------------------------------------
// Options
// ---------------------------------------------------------------------------------------------

private val PKG_RX = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

internal val optNewPackage = stringOption(
    name = "New package name",
    default = null,
    description = "Install the patched app as a clone under this package name, next to the original " +
        "(e.g. com.example.app.clone). Empty = keep the original package.",
    validator = { it.isNullOrBlank() || PKG_RX.matches(it.trim()) },
)

internal val optAppName = stringOption(
    name = "App name",
    default = null,
    description = "Name shown under the launcher icon. Empty = unchanged.",
)

// ---------------------------------------------------------------------------------------------
// Clone: package rename + app name (manifest and res/ parts; the dex part follows in build 2)
// ---------------------------------------------------------------------------------------------

internal object Clone {
    /** Old -> new name of every renamed authority and permission. Shared with the res pass (and the dex pass). */
    val renames = LinkedHashMap<String, String>()
    var realPkg: String? = null
    var newPkg: String? = null
    var refs = 0          // manifest attributes that pointed at a renamed name
    var qualified = 0     // class names made fully qualified
    var resFiles = 0
    var resStrings = 0

    fun reset() {
        renames.clear(); realPkg = null; newPkg = null
        refs = 0; qualified = 0; resFiles = 0; resStrings = 0
    }

    private val PERMISSION_TAGS = setOf("permission", "permission-group", "permission-tree")
    private val COMPONENT_TAGS = setOf("activity", "activity-alias", "service", "receiver", "provider")
    private val CLASS_ATTRS = listOf(
        "android:name", "android:targetActivity", "android:parentActivityName", "android:backupAgent",
        "android:manageSpaceActivity", "android:appComponentFactory", "android:zygotePreloadName",
    )
    private val SHARED_USER_ATTRS = listOf(
        "android:sharedUserId", "android:sharedUserLabel", "android:sharedUserMaxSdkVersion",
    )

    /** apply_clone rule: old -> new, old.x -> new.x, anything else gets ".clone"; already renamed stays. */
    fun renameValue(v: String, old: String, new: String): String = when {
        v == new || v.startsWith("$new.") -> v
        v == old -> new
        v.startsWith("$old.") -> new + v.substring(old.length)
        else -> "$v.clone"
    }

    /**
     * Runs inside the manifest pass, after the tracker rules.
     * [decodedPkg] is the `package` attribute apktool left in the decoded manifest. It can differ from the real
     * package: when resources.arsc carries another name (the build's namespace), apktool puts that one there.
     */
    fun manifest(ctx: ResourcePatchContext, root: Element, decodedPkg: String, dry: Boolean) {
        reset()
        val wanted = optNewPackage.value?.trim()?.ifEmpty { null }
        val appName = optAppName.value?.trim()?.ifEmpty { null }
        val binaryPkg = readRealPackage(ctx)
        val real = binaryPkg ?: decodedPkg
        realPkg = real
        val renaming = wanted != null && wanted != real
        if (renaming) newPkg = wanted
        val app = root.elements().firstOrNull { it.tagName == "application" }

        if (renaming) {
            val new = wanted

            // 1. class names: fully qualified, so they keep pointing at the original classes
            if (!dry) {
                val holders = listOfNotNull(app) + app?.elements().orEmpty().filter { it.tagName in COMPONENT_TAGS }
                for (el in holders) for (attr in CLASS_ATTRS) {
                    val v = el.getAttribute(attr)
                    if (v.isEmpty() || v.startsWith("@")) continue
                    val full = resolve(v, real)!!
                    if (full != v) { el.setAttribute(attr, full); qualified++ }
                }
            }

            // 2. shared user id would tie the clone to the original's signature and UID
            for (attr in SHARED_USER_ATTRS) {
                if (!root.hasAttribute(attr)) continue
                Report.chg("remove-attr", attr, root.getAttribute(attr), dry)
                if (!dry) root.removeAttribute(attr)
            }

            // 3. the app's own permissions
            for (el in root.elements()) {
                if (el.tagName !in PERMISSION_TAGS) continue
                val n = el.getAttribute("android:name")
                if (n.isEmpty() || n in renames) continue
                val to = renameValue(n, real, new)
                if (to == n) continue
                renames[n] = to
                Report.chg("rename", n, to, dry)
            }

            // 4. provider authorities (literal or @string/ reference; ';'-separated lists)
            val strings by lazy { readStrings(ctx) }
            for (el in app?.elements().orEmpty()) {
                if (el.tagName != "provider") continue
                val raw = el.getAttribute("android:authorities")
                if (raw.isEmpty()) continue
                val out = raw.split(';').map { part ->
                    val lit = if (part.startsWith("@string/")) strings[part.removePrefix("@string/")] ?: part else part
                    if (lit.startsWith("@")) return@map part  // unresolved reference: leave it
                    val to = renames[lit] ?: renameValue(lit, real, new)
                    if (to != lit && lit !in renames) {
                        renames[lit] = to
                        Report.chg("rename", lit, to, dry)
                    }
                    to
                }.joinToString(";")
                if (!dry && out != raw) el.setAttribute("android:authorities", out)
            }

            // 5. every other attribute that points at a renamed name (uses-permission, android:permission,
            //    readPermission, path-permission, queries/provider, meta-data values ...)
            walk(root) { el ->
                val attrs = el.attributes
                for (i in 0 until attrs.length) {
                    val a = attrs.item(i)
                    if (a.nodeName == "package") continue
                    val v = a.nodeValue
                    val to = if (a.nodeName == "android:authorities")
                        v.split(';').joinToString(";") { renames[it] ?: it }
                    else renames[v] ?: v
                    if (to != v && !(el.tagName == "provider" && el.parentNode === app && a.nodeName == "android:authorities")) {
                        refs++
                        if (!dry) a.nodeValue = to
                    }
                }
            }

            Report.chg("rename", real, new, dry)
        }

        // 6. package attribute + aapt2's --rename-manifest-package flag
        fixPackage(ctx, root, decodedPkg, binaryPkg, real, if (renaming && !dry) wanted else real, wanted, dry)

        // 7. app name
        if (appName != null && app != null) {
            val targets = listOf(app) + app.elements().filter {
                (it.tagName == "activity" || it.tagName == "activity-alias") && it.hasAttribute("android:label") && isLauncher(it)
            }
            for (el in targets) {
                val old = el.getAttribute("android:label")
                if (old == appName) continue
                val who = if (el === app) "application" else resolve(el.getAttribute("android:name"), real) ?: el.tagName
                Report.chg("label", who, "$appName (was ${old.ifEmpty { "none" }})", dry)
                if (!dry) el.setAttribute("android:label", appName)
            }
        }
    }

    private fun fixPackage(
        ctx: ResourcePatchContext, root: Element, decoded: String, binary: String?, real: String,
        target: String, wanted: String?, dry: Boolean,
    ) {
        // Same package in resources.arsc and manifest: rename via the manifest attribute, so aapt2 also names
        // the resource table after the new package (getIdentifier(.., getPackageName()) keeps working). Flag off.
        // Different (namespace) package in resources.arsc: keep it there like the original build did and let
        // aapt2 rename only the manifest, via the flag.
        val wantFlag: String? = if (decoded == real) null else target
        if (decoded == real && target != decoded) root.setAttribute("package", target)

        val was: String?
        var fix: String
        try {
            val pkgInfo = packageInfo(ctx)
            val f = pkgInfo.javaClass.getField("renameManifestPackage")
            was = f.get(pkgInfo) as String?
            fix = if (was == wantFlag) "none" else {
                f.set(pkgInfo, wantFlag)
                if (wantFlag == null) "cleared" else "set"
            }
        } catch (e: Throwable) {
            // Fallback: put the target into the manifest attribute. Lost again if the flag is set.
            if (target != decoded) root.setAttribute("package", target)
            TprLog.emit("t" to "pkg", "manifest" to decoded, "real" to binary, "new" to wanted, "aaptWas" to null,
                "aapt" to null, "fix" to "failed", "err" to "${e.javaClass.simpleName}: ${e.message}", "dry" to dry)
            return
        }
        TprLog.emit("t" to "pkg", "manifest" to decoded, "real" to binary, "new" to wanted, "aaptWas" to was,
            "aapt" to wantFlag, "fix" to fix, "dry" to dry)
    }

    /** Rewrites old authority/permission names in the XML files under res/ (strings, xml/ files, searchable, sync adapters ...). */
    fun resources(ctx: ResourcePatchContext, dry: Boolean) {
        val new = newPkg ?: return
        val old = realPkg ?: return
        val res = ctx["res"]
        if (!res.isDirectory) return
        val rx = if (renames.isEmpty()) null else Regex(
            "(?<![A-Za-z0-9_.])(" +
                renames.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } +
                ")(?![A-Za-z0-9_])",
        )
        // static shortcuts must target the app's own package
        val targetPkgRx = Regex("(android:targetPackage\\s*=\\s*\")" + Regex.escape(old) + "\"")
        res.walkTopDown().filter { it.isFile && it.name.endsWith(".xml") }.sortedBy { it.path }.forEach { f ->
            val text = f.readText()
            var n = 0
            var out = rx?.replace(text) { m -> n++; renames.getValue(m.value) } ?: text
            if (f.parentFile.name.startsWith("xml")) out = targetPkgRx.replace(out) { m -> n++; m.groupValues[1] + new + "\"" }
            if (n == 0) return@forEach
            resFiles++
            resStrings += n
            Report.chg("rename-res", "res/" + f.relativeTo(res).invariantSeparatorsPath, "$n", dry)
            if (!dry) f.writeText(out)
        }
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun Element.elements(): List<Element> {
        val out = ArrayList<Element>()
        var n: Node? = firstChild
        while (n != null) { if (n is Element) out.add(n); n = n.nextSibling }
        return out
    }

    private fun walk(el: Element, f: (Element) -> Unit) {
        f(el)
        for (c in el.elements()) walk(c, f)
    }

    private fun isLauncher(el: Element): Boolean = el.elements().any { f ->
        f.tagName == "intent-filter" &&
            f.elements().any { it.tagName == "action" && it.getAttribute("android:name") == "android.intent.action.MAIN" } &&
            f.elements().any { it.tagName == "category" && it.getAttribute("android:name") == "android.intent.category.LAUNCHER" }
    }

    private fun readStrings(ctx: ResourcePatchContext): Map<String, String> {
        val f = ctx["res/values/strings.xml"]
        if (!f.isFile) return emptyMap()
        val d = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val nodes = d.getElementsByTagName("string")
        return (0 until nodes.length).associate {
            val e = nodes.item(it) as Element
            e.getAttribute("name") to e.textContent.trim()
        }
    }

    private fun field(obj: Any, name: String): Any? {
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            try {
                return c.getDeclaredField(name).apply { isAccessible = true }.get(obj)
            } catch (_: NoSuchFieldException) {
            }
            c = c.superclass
        }
        throw NoSuchFieldException("${obj.javaClass.name}.$name")
    }

    /** apktool's ApkInfo.packageInfo (patcher field ResourcePatchContext.apkInfo). */
    private fun packageInfo(ctx: ResourcePatchContext): Any {
        val apkInfo = field(ctx, "apkInfo") ?: throw IllegalStateException("apkInfo is null")
        return apkInfo.javaClass.getField("packageInfo").get(apkInfo) ?: throw IllegalStateException("packageInfo is null")
    }

    /** The package from the original APK's binary manifest (what Android installs it as). */
    fun readRealPackage(ctx: ResourcePatchContext): String? = try {
        val apk = field(ctx, "apkFile") as File
        ZipFile(apk).use { z ->
            val e = z.getEntry("AndroidManifest.xml") ?: return null
            Axml.manifestPackage(z.getInputStream(e).readBytes())
        }
    } catch (e: Throwable) {
        null
    }
}

/** Minimal binary-XML reader: just the `package` attribute of the root `<manifest>` element. */
internal object Axml {
    fun manifestPackage(bytes: ByteArray): String? {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (b.getShort(0).toInt() and 0xffff != 0x0003) return null
        var pos = b.getShort(2).toInt() and 0xffff
        var strings: List<String?> = emptyList()
        while (pos + 8 <= bytes.size) {
            val type = b.getShort(pos).toInt() and 0xffff
            val headerSize = b.getShort(pos + 2).toInt() and 0xffff
            val size = b.getInt(pos + 4)
            if (size <= 0) return null
            when (type) {
                0x0001 -> strings = stringPool(b, pos)
                0x0102 -> {
                    val name = strings.getOrNull(b.getInt(pos + 20))
                    if (name != "manifest") return null
                    val attrStart = b.getShort(pos + 24).toInt() and 0xffff
                    val attrSize = b.getShort(pos + 26).toInt() and 0xffff
                    val count = b.getShort(pos + 28).toInt() and 0xffff
                    for (i in 0 until count) {
                        val a = pos + headerSize + attrStart + i * attrSize
                        if (strings.getOrNull(b.getInt(a + 4)) != "package") continue
                        val raw = b.getInt(a + 8)
                        if (raw != -1) return strings.getOrNull(raw)
                        if (b.get(a + 15).toInt() == 0x03) return strings.getOrNull(b.getInt(a + 16))
                        return null
                    }
                    return null
                }
            }
            pos += size
        }
        return null
    }

    private fun stringPool(b: ByteBuffer, pos: Int): List<String?> {
        val count = b.getInt(pos + 8)
        val utf8 = b.getInt(pos + 16) and 0x100 != 0
        val stringsStart = pos + b.getInt(pos + 20)
        val headerSize = b.getShort(pos + 2).toInt() and 0xffff
        return (0 until count).map { i ->
            try {
                var p = stringsStart + b.getInt(pos + headerSize + i * 4)
                if (utf8) {
                    p += if (b.get(p).toInt() and 0x80 != 0) 2 else 1           // utf-16 length
                    var len = b.get(p).toInt() and 0xff
                    if (len and 0x80 != 0) { len = ((len and 0x7f) shl 8) or (b.get(p + 1).toInt() and 0xff); p += 2 } else p += 1
                    String(b.array(), p, len, Charsets.UTF_8)
                } else {
                    var len = b.getShort(p).toInt() and 0xffff
                    if (len and 0x8000 != 0) { len = ((len and 0x7fff) shl 16) or (b.getShort(p + 2).toInt() and 0xffff); p += 4 } else p += 2
                    String(b.array(), p, len * 2, Charsets.UTF_16LE)
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
