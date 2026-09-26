import com.android.build.api.artifact.SingleArtifact
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "io.github.tuthan.steamoscompanion"
    // compileSdk 37 serves both flavors; runtime behavior follows each flavor's targetSdk.
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.tuthan.steamoscompanion"
        minSdk = 26
        versionCode = 1
        versionName = "0.0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    testBuildType = "debug"

    flavorDimensions += "target"
    productFlavors {
        // Release target until the Gate A0.2 target-37 migration passes.
        create("baseline") {
            dimension = "target"
            targetSdk = 36
        }
        // Permission-test configuration; adds ACCESS_LOCAL_NETWORK from src/target37.
        create("target37") {
            dimension = "target"
            targetSdk = 37
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // Release-configured APK with local test signing; use the normal debug AndroidTest APK separately.
        create("r8Probe") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles("proguard-r8-probe-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("test").resources.directories.add("src/test/fixtures")
        // The fake host serves the exact vendored bundle; nothing is copied or edited.
        getByName("androidTest").assets.directories += rootProject.file("protocol").path
    }

    packaging {
        resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        // Every BouncyCastle jar carries the same licence text; keep one copy.
        resources.pickFirsts += "META-INF/LICENSE.md"
    }

    testOptions {
        managedDevices {
            localDevices {
                create("api27") { device = "Pixel 2"; apiLevel = 27; systemImageSource = "aosp"; require64Bit = true }
                create("api28") { device = "Pixel 2"; apiLevel = 28; systemImageSource = "google"; require64Bit = true }
                create("api35") { device = "Pixel 6"; apiLevel = 35; systemImageSource = "google" }
                create("api36") { device = "Pixel 6"; apiLevel = 36; systemImageSource = "google" }
                create("api37") { device = "Pixel 6"; apiLevel = 37; systemImageSource = "google" }
            }
            groups {
                create("phase01") {
                    targetDevices.addAll(
                        listOf("api27", "api28", "api35", "api36", "api37").map { localDevices[it] },
                    )
                }
            }
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.bouncycastle.tls)
    androidTestImplementation(libs.bouncycastle.prov)
    androidTestImplementation(libs.bouncycastle.util)
}

dependencyLocking {
    lockAllConfigurations()
}

/** Asserts the security and permission shape of one variant's merged manifest (Gate A0.2). */
abstract class CheckMergedManifest : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val manifest: RegularFileProperty

    @get:Input
    abstract val expectedTargetSdk: Property<Int>

    @get:Input
    abstract val requiresLocalNetwork: Property<Boolean>

    @TaskAction
    fun check() {
        val ns = "http://schemas.android.com/apk/res/android"
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifest.get().asFile)
        fun elements(tag: String) = doc.getElementsByTagName(tag).let { list ->
            (0 until list.length).map { list.item(it) as Element }
        }
        val problems = mutableListOf<String>()

        val sdk = elements("uses-sdk").single()
        if (sdk.getAttributeNS(ns, "minSdkVersion") != "26") problems += "minSdkVersion is not 26"
        if (sdk.getAttributeNS(ns, "targetSdkVersion") != expectedTargetSdk.get().toString()) {
            problems += "targetSdkVersion is ${sdk.getAttributeNS(ns, "targetSdkVersion")}, expected ${expectedTargetSdk.get()}"
        }

        val permissions = elements("uses-permission").associateBy { it.getAttributeNS(ns, "name") }
        val required = mutableSetOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.CAMERA",
            "android.permission.NEARBY_WIFI_DEVICES",
        )
        if (requiresLocalNetwork.get()) required += "android.permission.ACCESS_LOCAL_NETWORK"
        val appPackage = doc.documentElement.getAttribute("package")
        val allowedExtra = setOf("$appPackage.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        (required - permissions.keys).forEach { problems += "missing permission $it" }
        (permissions.keys - required - allowedExtra).forEach { problems += "unexpected permission $it" }
        permissions["android.permission.NEARBY_WIFI_DEVICES"]?.let {
            if (it.getAttributeNS(ns, "maxSdkVersion") != "36") problems += "NEARBY_WIFI_DEVICES maxSdkVersion is not 36"
            if (it.getAttributeNS(ns, "usesPermissionFlags") != "neverForLocation") {
                problems += "NEARBY_WIFI_DEVICES is not neverForLocation"
            }
        }

        val application = elements("application").single()
        if (application.getAttributeNS(ns, "allowBackup") != "false") problems += "allowBackup is not false"
        if (application.getAttributeNS(ns, "dataExtractionRules").isEmpty()) problems += "dataExtractionRules missing"
        if (application.getAttributeNS(ns, "networkSecurityConfig").isEmpty()) problems += "networkSecurityConfig missing"
        if (application.getAttributeNS(ns, "usesCleartextTraffic") == "true") problems += "usesCleartextTraffic is true"
        if (application.getAttributeNS(ns, "debuggable") == "true" && name.contains("Release")) {
            problems += "release variant is debuggable"
        }

        if (problems.isNotEmpty()) {
            throw GradleException("${manifest.get().asFile}:\n" + problems.joinToString("\n") { "  - $it" })
        }
        logger.lifecycle("Merged manifest OK: target ${expectedTargetSdk.get()}, permissions ${permissions.keys.sorted()}")
    }
}

val manifestChecks = mutableListOf<TaskProvider<CheckMergedManifest>>()
androidComponents {
    onVariants { variant ->
        val isTarget37 = variant.productFlavors.any { it.second == "target37" }
        manifestChecks += tasks.register<CheckMergedManifest>("check${variant.name.replaceFirstChar(Char::uppercase)}MergedManifest") {
            group = "verification"
            manifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            expectedTargetSdk.set(if (isTarget37) 37 else 36)
            requiresLocalNetwork.set(isTarget37)
        }
    }
}

val checkMergedManifests = tasks.register("checkMergedManifests") {
    group = "verification"
    description = "Checks every variant's merged manifest against the Gate A0.2 permission shape."
    dependsOn(manifestChecks)
}

tasks.named("check") {
    dependsOn(checkMergedManifests)
}
