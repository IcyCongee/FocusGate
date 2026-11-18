plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.focusgate.phase0"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "dev.focusgate.phase0"
        minSdk = 30
        targetSdk = 35
        versionCode = 5
        versionName = "0.4.1-trial"
        val buildId = providers.gradleProperty("focusGateBuildId").orElse("trial0.4.1-local").get()
        require(buildId.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "Invalid focusGateBuildId" }
        buildConfigField("String", "BUILD_ID", "\"$buildId\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
