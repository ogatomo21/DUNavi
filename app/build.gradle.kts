import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.license.scribe)
}

android {
    namespace = "net.ogatomo.dunavi"
    compileSdk = 37

    defaultConfig {
        applicationId = "net.ogatomo.dunavi"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            storeFile = providers.environmentVariable("KEYSTORE_PATH").orNull?.let(::file)
            storePassword = providers.environmentVariable("KEYSTORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("KEY_ALIAS").orNull
            keyPassword = providers.environmentVariable("KEY_PASSWORD").orNull
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging.resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    implementation(libs.license.scribe.runtime)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

licenseScribe {
    baseDir.set(layout.projectDirectory.dir("licenses"))
    generatedPackageName.set("net.ogatomo.dunavi")
    generatedClassName.set("AppLicenses")
}

androidComponents.beforeVariants(androidComponents.selector().withBuildType("release")) { variant ->
    variant.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
}

val verifyReleaseSigning = tasks.register("verifyReleaseSigning") {
    doLast {
        val required = listOf("KEYSTORE_PATH", "KEYSTORE_PASSWORD", "KEY_ALIAS", "KEY_PASSWORD")
        val missing = required.filter { providers.environmentVariable(it).orNull.isNullOrBlank() }
        check(missing.isEmpty()) { "Release signing requires: ${missing.joinToString()}. See docs/releasing.md." }
        check(file(providers.environmentVariable("KEYSTORE_PATH").get()).isFile) { "KEYSTORE_PATH must point to an existing keystore." }
    }
}

tasks.matching { it.name == "validateSigningRelease" }.configureEach {
    dependsOn(verifyReleaseSigning)
}

tasks.register("printVersionName") {
    doLast { println(android.defaultConfig.versionName) }
}

@CacheableTask
abstract class PrepareLicenseAssets : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val mitFile: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE)
    abstract val noticesFile: RegularFileProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction fun prepare() {
        val licenses = outputDirectory.get().asFile.resolve("licenses").apply { mkdirs() }
        Files.copy(mitFile.get().asFile.toPath(), licenses.resolve("MIT.txt").toPath(), StandardCopyOption.REPLACE_EXISTING)
        Files.copy(noticesFile.get().asFile.toPath(), licenses.resolve("THIRD_PARTY_NOTICES.md").toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

val prepareLicenseAssets = tasks.register<PrepareLicenseAssets>("prepareLicenseAssets") {
    mitFile.set(rootProject.layout.projectDirectory.file("LICENSE"))
    noticesFile.set(rootProject.layout.projectDirectory.file("THIRD_PARTY_NOTICES.md"))
    outputDirectory.set(layout.buildDirectory.dir("generated/licenseAssets"))
}
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(prepareLicenseAssets, PrepareLicenseAssets::outputDirectory)
}
