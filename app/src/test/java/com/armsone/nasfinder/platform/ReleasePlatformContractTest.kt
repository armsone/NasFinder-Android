package com.armsone.nasfinder.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ReleasePlatformContractTest {
    @Test
    fun `manifest keeps launcher aliases restorable and exported surface minimal`() {
        val manifest = xml(sourceFile("app/src/main/AndroidManifest.xml"))
        val application = manifest.getElementsByTagName("application").item(0) as Element
        assertEquals("false", application.android("allowBackup"))
        assertTrue(application.android("debuggable").isBlank())

        val permissions = manifest.getElementsByTagName("uses-permission").elements()
            .mapTo(mutableSetOf()) { it.android("name") }
        assertTrue("android.permission.REQUEST_INSTALL_PACKAGES" in permissions)
        assertFalse("android.permission.MANAGE_EXTERNAL_STORAGE" in permissions)
        assertFalse("android.permission.READ_EXTERNAL_STORAGE" in permissions)
        assertFalse("android.permission.WRITE_EXTERNAL_STORAGE" in permissions)
        assertFalse("android.permission.RECEIVE_BOOT_COMPLETED" in permissions)

        val aliases = manifest.getElementsByTagName("activity-alias").elements()
            .associateBy { it.android("name") }
        val launcherAliases = aliases.filterValues { alias ->
            alias.getElementsByTagName("category").elements()
                .any { it.android("name") == "android.intent.category.LAUNCHER" }
        }
        assertEquals(
            setOf(
                ".DefaultLauncherAlias",
                ".CyberVaultLauncherAlias",
                ".DigitalRainLauncherAlias",
                ".PurpleNasLauncherAlias",
                ".NasRadarLauncherAlias",
                ".EnamelLauncherAlias",
            ),
            launcherAliases.keys,
        )
        assertEquals(1, launcherAliases.values.count { it.android("enabled") == "true" })
        val expectedLauncherIcons = mapOf(
            ".DefaultLauncherAlias" to "@mipmap/ic_launcher_default",
            ".PurpleNasLauncherAlias" to "@mipmap/ic_launcher_purple_nas",
            ".DigitalRainLauncherAlias" to "@mipmap/ic_launcher_vibe_coder",
            ".CyberVaultLauncherAlias" to "@mipmap/ic_launcher_cyber_vault",
            ".NasRadarLauncherAlias" to "@mipmap/ic_launcher_nas_radar",
            ".EnamelLauncherAlias" to "@mipmap/ic_launcher_enamel",
        )
        launcherAliases.forEach { (name, alias) ->
            assertEquals(".MainActivity", alias.android("targetActivity"))
            assertEquals("true", alias.android("exported"))
            assertEquals(expectedLauncherIcons[name], alias.android("icon"))
            assertEquals(alias.android("icon"), alias.android("roundIcon"))
            assertEquals("@string/app_name", alias.android("label"))
        }
        val shareAlias = requireNotNull(aliases[".ShareReceiverAlias"])
        assertEquals("true", shareAlias.android("exported"))
        assertEquals("@string/share_save_label", shareAlias.android("label"))
        assertEquals(".MainActivity", shareAlias.android("targetActivity"))
        assertEquals(
            setOf("android.intent.action.SEND", "android.intent.action.SEND_MULTIPLE"),
            shareAlias.getElementsByTagName("action").elements()
                .mapTo(mutableSetOf()) { it.android("name") },
        )

        val restoreReceiver = manifest.getElementsByTagName("receiver").elements()
            .firstOrNull { it.android("name") == ".platform.AppIconRestoreReceiver" }
        assertNotNull(restoreReceiver)
        val restore = requireNotNull(restoreReceiver)
        assertEquals("false", restore.android("exported"))
        val restoreActions = restore.getElementsByTagName("action").elements()
            .mapTo(mutableSetOf()) { it.android("name") }
        assertEquals(setOf("android.intent.action.MY_PACKAGE_REPLACED"), restoreActions)

        val providers = manifest.getElementsByTagName("provider").elements()
        val shareProvider = providers.firstOrNull { it.android("name") == ".platform.NasFinderShareFileProvider" }
        assertNotNull(shareProvider)
        val share = requireNotNull(shareProvider)
        assertEquals("false", share.android("exported"))
        assertEquals("true", share.android("grantUriPermissions"))
        assertEquals("\${applicationId}.sharefiles", share.android("authorities"))
    }

    @Test
    fun `FileProvider exposes only finalized products and never updater partials`() {
        val paths = xml(sourceFile("app/src/main/res/xml/provider_paths.xml"))
            .documentElement.childNodes.elements()
            // FileProvider's <paths> attributes are intentionally unqualified,
            // unlike AndroidManifest attributes in the android namespace.
            .map { Triple(it.tagName, it.getAttribute("name"), it.getAttribute("path")) }
            .toSet()
        assertEquals(
            setOf(
                Triple("cache-path", "prepared_shares", "shares/"),
                Triple("cache-path", "verified_updates", "updates/"),
                Triple("files-path", "shared_inbox", "SharedInbox/"),
            ),
            paths,
        )
        assertFalse(paths.any { (_, _, path) -> path.isBlank() || path == "." || "partial" in path })
    }

    @Test
    fun `documents provider widget and external entry metadata remain scoped`() {
        val manifest = xml(sourceFile("app/src/main/AndroidManifest.xml"))
        val providers = manifest.getElementsByTagName("provider").elements()
        val documents = requireNotNull(
            providers.firstOrNull { it.android("name") == ".platform.NasFinderDocumentsProvider" },
        )
        assertEquals("true", documents.android("exported"))
        assertEquals("true", documents.android("grantUriPermissions"))
        assertEquals("android.permission.MANAGE_DOCUMENTS", documents.android("permission"))
        assertEquals("\${applicationId}.documents", documents.android("authorities"))
        assertEquals(
            setOf("android.content.action.DOCUMENTS_PROVIDER"),
            documents.getElementsByTagName("action").elements()
                .mapTo(mutableSetOf()) { it.android("name") },
        )

        val widget = requireNotNull(
            manifest.getElementsByTagName("receiver").elements()
                .firstOrNull { it.android("name") == ".platform.NasFinderAppWidgetProvider" },
        )
        assertEquals("false", widget.android("exported"))
        assertEquals("@string/widget_picker_title", widget.android("label"))
        assertEquals(
            setOf("android.appwidget.action.APPWIDGET_UPDATE"),
            widget.getElementsByTagName("action").elements()
                .mapTo(mutableSetOf()) { it.android("name") },
        )

        val main = requireNotNull(
            manifest.getElementsByTagName("activity").elements()
                .firstOrNull { it.android("name") == ".MainActivity" },
        )
        assertEquals("true", main.android("exported"))
        assertEquals("singleTask", main.android("launchMode"))
        val filters = main.getElementsByTagName("intent-filter").elements()
        assertTrue(filters.all { it.android("priority").isBlank() })
        val nasFinderHosts = filters.flatMap { filter ->
            filter.getElementsByTagName("data").elements()
                .filter { it.android("scheme") == "nasfinder" }
                .map { it.android("host") }
        }.toSet()
        assertEquals(setOf("inbox", "webhard", "browser"), nasFinderHosts)
    }

    @Test
    fun `widget mirrors the iOS single glyph open action without dashboard summaries`() {
        val layout = xml(sourceFile("app/src/main/res/layout/nasfinder_widget.xml"))
        val textViews = layout.getElementsByTagName("TextView").elements()
        assertTrue(textViews.isEmpty())
        val images = layout.getElementsByTagName("ImageView").elements()
        assertEquals(1, images.size)
        assertEquals("@drawable/ic_nasfinder_accessory", images.single().android("src"))
        assertEquals("@string/widget_open_label", layout.documentElement.android("contentDescription"))

        val metadata = xml(sourceFile("app/src/main/res/xml/nasfinder_widget_info.xml"))
            .documentElement
        assertEquals("110dp", metadata.android("minWidth"))
        assertEquals("110dp", metadata.android("minHeight"))
        assertEquals("none", metadata.android("resizeMode"))
    }

    private fun sourceFile(relativePath: String): File {
        val working = File(System.getProperty("user.dir")).canonicalFile
        return generateSequence(working) { it.parentFile }
            .map { File(it, relativePath) }
            .firstOrNull(File::isFile)
            ?: error("Cannot locate $relativePath from $working")
    }

    private fun xml(file: File) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    }.newDocumentBuilder().parse(file)

    private fun Element.android(name: String): String = getAttributeNS(ANDROID_NAMESPACE, name)

    private fun org.w3c.dom.NodeList.elements(): List<Element> = buildList {
        for (index in 0 until length) (item(index) as? Element)?.let(::add)
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
