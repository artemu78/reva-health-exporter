package dev.reva.healthexporter

import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.readText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationContractTest {
    private val projectDirectory: Path = Path.of(System.getProperty("user.dir"))

    @Test
    fun `manifest and resources expose the stable application identity`() {
        val manifest = projectDirectory.resolve("src/main/AndroidManifest.xml")
        val strings = projectDirectory.resolve("src/main/res/values/strings.xml")

        assertTrue("AndroidManifest.xml must exist", Files.isRegularFile(manifest))
        assertTrue("strings.xml must exist", Files.isRegularFile(strings))

        val manifestDocument = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest.toFile())
        val application = manifestDocument.getElementsByTagName("application").item(0)
        val label = application.attributes.getNamedItem("android:label")?.nodeValue
        assertEquals("@string/app_name", label)

        val stringDocument = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(strings.toFile())
        val values = (0 until stringDocument.getElementsByTagName("string").length)
            .map { stringDocument.getElementsByTagName("string").item(it) }
            .associate { it.attributes.getNamedItem("name").nodeValue to it.textContent }

        assertEquals("Reva Health Exporter", values["app_name"])
        assertEquals("Reva Health Exporter", values["first_screen_title"])
        assertTrue(manifest.readText().contains("android.intent.category.LAUNCHER"))
    }

    @Test
    fun `continuous integration verifies the fast build and API 30 launch test`() {
        val workflow = projectDirectory.resolve("../.github/workflows/android.yml").normalize()

        assertTrue("Android CI workflow must exist", Files.isRegularFile(workflow))

        val configuration = workflow.readText()
        assertTrue(configuration.contains("./gradlew test lintDebug assembleDebug"))
        assertTrue(configuration.contains("api-level: 30"))
        assertTrue(configuration.contains("KERNEL==\"kvm\""))
        assertTrue(configuration.contains("./gradlew connectedDebugAndroidTest"))
    }

    @Test
    fun `manifest exposes Health Connect and declares only selected read permissions`() {
        val manifest = projectDirectory.resolve("src/main/AndroidManifest.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest.toFile())
        val permissionNodes = document.getElementsByTagName("uses-permission")
        val permissions = (0 until permissionNodes.length)
            .map { permissionNodes.item(it).attributes.getNamedItem("android:name").nodeValue }
            .toSet()

        assertEquals(
            setOf(
                "android.permission.health.READ_STEPS",
                "android.permission.health.READ_HEART_RATE",
                "android.permission.health.READ_RESTING_HEART_RATE",
                "android.permission.health.READ_SLEEP",
                "android.permission.health.READ_DISTANCE",
                "android.permission.health.READ_TOTAL_CALORIES_BURNED",
                "android.permission.health.READ_EXERCISE",
                "android.permission.health.READ_OXYGEN_SATURATION",
            ),
            permissions,
        )

        val packages = document.getElementsByTagName("package")
        assertEquals(1, packages.length)
        assertEquals(
            "com.google.android.apps.healthdata",
            packages.item(0).attributes.getNamedItem("android:name").nodeValue,
        )
    }
}
