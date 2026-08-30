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
    fun `application declares adaptive launcher icons`() {
        val manifest = projectDirectory.resolve("src/main/AndroidManifest.xml")
        val launcherIcon = projectDirectory.resolve("src/main/res/mipmap-anydpi-v26/ic_launcher.xml")
        val roundLauncherIcon = projectDirectory.resolve("src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml")

        val manifestDocument = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest.toFile())
        val application = manifestDocument.getElementsByTagName("application").item(0)

        assertEquals("@mipmap/ic_launcher", application.attributes.getNamedItem("android:icon")?.nodeValue)
        assertEquals(
            "@mipmap/ic_launcher_round",
            application.attributes.getNamedItem("android:roundIcon")?.nodeValue,
        )
        assertTrue("Adaptive launcher icon must exist", Files.isRegularFile(launcherIcon))
        assertTrue("Round adaptive launcher icon must exist", Files.isRegularFile(roundLauncherIcon))
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
    fun `release acceptance has one executable repository interface`() {
        val acceptanceScript = projectDirectory.resolve("../scripts/verify-release-acceptance.sh").normalize()
        val acceptanceGuide = projectDirectory.resolve("../docs/issue-13-release-acceptance.md").normalize()

        assertTrue("release acceptance script must exist", Files.isRegularFile(acceptanceScript))
        assertTrue("release acceptance script must be executable", Files.isExecutable(acceptanceScript))
        assertTrue("release acceptance guide must exist", Files.isRegularFile(acceptanceGuide))

        val script = acceptanceScript.readText()
        assertTrue(script.contains("./gradlew test lintDebug assembleDebug"))
        assertTrue(script.contains("./scripts/verify-release-build.sh"))
        assertTrue(script.contains("verify-schema-v1-fixtures"))
        assertTrue(script.contains("verify-repository-privacy"))
        assertTrue(script.contains("git grep -nI -E -e"))

        val guide = acceptanceGuide.readText()
        assertTrue(guide.contains("Clean install"))
        assertTrue(guide.contains("Upgrade"))
        assertTrue(guide.contains("Permission revocation"))
        assertTrue(guide.contains("Drive revocation"))
        assertTrue(guide.contains("Offline recovery"))
        assertTrue(guide.contains("Account switch"))
        assertTrue(guide.contains("Corrupt local state"))
    }

    @Test
    fun `release acceptance enforces core line and branch coverage`() {
        val build = projectDirectory.resolve("build.gradle.kts")
        val workflow = projectDirectory.resolve("../.github/workflows/android.yml").normalize()
        val acceptanceScript = projectDirectory.resolve("../scripts/verify-release-acceptance.sh").normalize()

        val buildConfiguration = build.readText()
        assertTrue(buildConfiguration.contains("CoverageUnit.LINE"))
        assertTrue(buildConfiguration.contains("minValue = 90"))
        assertTrue(buildConfiguration.contains("CoverageUnit.BRANCH"))
        assertTrue(buildConfiguration.contains("minValue = 85"))
        assertTrue(workflow.readText().contains("koverVerifyDebug"))
        assertTrue(acceptanceScript.readText().contains("koverVerifyDebug"))
    }

    @Test
    fun `only permanently signed workflow artifacts are presented as installable candidates`() {
        val ciWorkflow = projectDirectory.resolve("../.github/workflows/android.yml").normalize().readText()
        val releaseVerifier = projectDirectory.resolve("../scripts/verify-release-build.sh").normalize().readText()

        assertTrue(ciWorkflow.contains("name: debug-fast-check-results"))
        assertTrue(ciWorkflow.contains("workflow_dispatch:"))
        assertTrue(ciWorkflow.contains("name: signed-release-candidate-"))
        assertTrue(ciWorkflow.contains("ANDROID_KEYSTORE_BASE64"))
        assertTrue(ciWorkflow.contains("github.event.pull_request.head.repo.full_name == github.repository"))
        assertTrue(releaseVerifier.contains("rm -f \"\$apk_path\""))
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
                "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND",
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

    @Test
    fun `Android 13 and earlier providers can discover the rationale and legacy permissions`() {
        val manifest = projectDirectory.resolve("src/main/AndroidManifest.xml")
        val permissionsResource = projectDirectory.resolve("src/main/res/values/health_permissions.xml")
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest.toFile())
        val activities = document.getElementsByTagName("activity")
        val rationaleActivity = (0 until activities.length)
            .map { activities.item(it) }
            .single { it.attributes.getNamedItem("android:name").nodeValue == ".PermissionsRationaleActivity" }
        val rationaleActions = rationaleActivity.childNodes.asSequence()
            .flatMap { it.childNodes.asSequence() }
            .filter { it.nodeName == "action" }
            .map { it.attributes.getNamedItem("android:name").nodeValue }
            .toSet()
        val metadata = rationaleActivity.childNodes.asSequence()
            .first { it.nodeName == "meta-data" }

        assertTrue("legacy Health Connect permissions resource must exist", Files.isRegularFile(permissionsResource))
        assertTrue("androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" in rationaleActions)
        assertEquals("health_permissions", metadata.attributes.getNamedItem("android:name").nodeValue)
        assertEquals("@array/health_permissions", metadata.attributes.getNamedItem("android:resource").nodeValue)

        val permissionsDocument = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(permissionsResource.toFile())
        val items = permissionsDocument.getElementsByTagName("item")
        val legacyPermissions = (0 until items.length).map { items.item(it).textContent }.toSet()
        assertEquals(
            setOf(
                "androidx.health.permission.Steps.READ",
                "androidx.health.permission.HeartRate.READ",
                "androidx.health.permission.RestingHeartRate.READ",
                "androidx.health.permission.SleepSession.READ",
                "androidx.health.permission.SleepStage.READ",
                "androidx.health.permission.Distance.READ",
                "androidx.health.permission.TotalCaloriesBurned.READ",
                "androidx.health.permission.ExerciseSession.READ",
                "androidx.health.permission.OxygenSaturation.READ",
            ),
            legacyPermissions,
        )
    }

    @Test
    fun `main layout and strings provide the version footer at the bottom of the screen`() {
        val layout = projectDirectory.resolve("src/main/res/layout/activity_main.xml")
        val strings = projectDirectory.resolve("src/main/res/values/strings.xml")

        assertTrue("activity_main.xml must exist", Files.isRegularFile(layout))
        assertTrue("strings.xml must exist", Files.isRegularFile(strings))

        val stringDocument = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(strings.toFile())
        val stringNodes = stringDocument.getElementsByTagName("string")
        val values = (0 until stringNodes.length)
            .map { stringNodes.item(it) }
            .associate { it.attributes.getNamedItem("name").nodeValue to it.textContent }

        assertEquals("Version %1\$s", values["app_version"])

        val layoutDocument = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(layout.toFile())
        val linearLayout = layoutDocument.getElementsByTagName("LinearLayout").item(0)
        val childNodes = (0 until linearLayout.childNodes.length)
            .map { linearLayout.childNodes.item(it) }
            .filter { it.nodeType == org.w3c.dom.Node.ELEMENT_NODE }

        val lastChild = childNodes.last()
        assertEquals("TextView", lastChild.nodeName)
        assertEquals("@+id/app_version", lastChild.attributes.getNamedItem("android:id")?.nodeValue)
    }

    private fun org.w3c.dom.NodeList.asSequence(): Sequence<org.w3c.dom.Node> = sequence {
        for (index in 0 until length) yield(item(index))
    }
}
