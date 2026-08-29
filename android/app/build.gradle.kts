plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.nytweetdeck.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.nytweetdeck.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    sourceSets["main"].assets.directories.add(
        rootProject.layout.projectDirectory.dir("../src/main/resources/x-api").asFile.absolutePath,
    )
    sourceSets["test"].resources.directories.add(
        rootProject.layout.projectDirectory.dir("../src/main/resources/x-api").asFile.absolutePath,
    )
    sourceSets["test"].resources.directories.add(
        rootProject.layout.projectDirectory.dir("../src/test/resources").asFile.absolutePath,
    )

    lint {
        // API 37 is not yet offered by the installed official Android CLI repository.
        // Compose BOM 2026.06 and these compatible stable versions remain on API 36.
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    val okHttpBom = platform("com.squareup.okhttp3:okhttp-bom:5.3.0")

    implementation(composeBom)
    implementation(okHttpBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.webkit:webkit:1.17.0")
    implementation("com.squareup.okhttp3:okhttp")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    // Coil 3.5 ships Kotlin 2.4 metadata; AGP 9.0's built-in Kotlin supports through 2.3.
    implementation("io.coil-kt.coil3:coil-compose:3.4.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.4.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation(okHttpBom)
    testImplementation("com.squareup.okhttp3:mockwebserver3")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
