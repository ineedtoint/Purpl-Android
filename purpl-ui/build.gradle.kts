plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
    id("maven-publish")
    id("org.jetbrains.dokka")
}

android {
    namespace = "sh.purpl.ui"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    buildFeatures { compose = true }
    publishing { singleVariant("release") { withSourcesJar() } }
}

dependencies {
    api(project(":purpl"))
    implementation(platform("androidx.compose:compose-bom:2026.02.01"))
    api("androidx.compose.ui:ui")
    api("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    testImplementation("junit:junit:4.13.2")
}

// API Reference의 모듈과 패키지 설명
dokka {
    moduleName.set("purpl-ui")
    dokkaSourceSets.configureEach { includes.from("Module.md") }
}

afterEvaluate {
    publishing { publications { create<MavenPublication>("release") { from(components["release"]); artifactId = "purpl-ui" } } }
}
