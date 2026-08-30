import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import java.util.Properties

abstract class PrintApplicationVersion : DefaultTask() {
    @get:Input
    abstract val applicationVersionName: Property<String>

    @get:Input
    abstract val applicationVersionCode: Property<Int>

    @TaskAction
    fun printVersion() {
        println("versionName=${applicationVersionName.get()}")
        println("versionCode=${applicationVersionCode.get()}")
    }
}

abstract class VerifyApplicationReleaseTag : DefaultTask() {
    @get:Input
    abstract val applicationVersionName: Property<String>

    @get:Input
    abstract val releaseTag: Property<String>

    @TaskAction
    fun verifyTag() {
        val expected = "v${applicationVersionName.get()}"
        val actual = releaseTag.get()
        if (actual != expected) {
            throw GradleException("Release tag $actual does not match configured version $expected")
        }
        println("Release tag $actual matches versionName=${applicationVersionName.get()}")
    }
}

plugins {
    id("com.android.application") version "9.3.2" apply false
}

val versionProperties = Properties().apply {
    rootProject.file("version.properties").inputStream().use(::load)
}
val appVersionCode = versionProperties.getProperty("VERSION_CODE")?.toIntOrNull()
    ?: throw GradleException("VERSION_CODE must be a positive integer in version.properties")
val appVersionName = versionProperties.getProperty("VERSION_NAME")
    ?: throw GradleException("VERSION_NAME is required in version.properties")

require(appVersionCode > 0) { "VERSION_CODE must be positive" }
require(Regex("(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)\\.(?:0|[1-9]\\d*)").matches(appVersionName)) {
    "VERSION_NAME must use X.Y.Z semantic versioning"
}

tasks.register<PrintApplicationVersion>("printVersion") {
    group = "release"
    description = "Prints the configured Android application version."
    applicationVersionName.set(appVersionName)
    applicationVersionCode.set(appVersionCode)
}

tasks.register<VerifyApplicationReleaseTag>("verifyReleaseTag") {
    group = "release"
    description = "Checks that -PreleaseTag matches v<VERSION_NAME>."
    applicationVersionName.set(appVersionName)
    releaseTag.set(providers.gradleProperty("releaseTag"))
}
