plugins { id("com.android.library"); id("maven-publish"); id("org.jetbrains.dokka") }
android {
    namespace = "sh.purpl"
    compileSdk = 37
    defaultConfig { minSdk = 26; consumerProguardFiles("consumer-rules.pro") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    publishing { singleVariant("release") { withSourcesJar() } }
}
dependencies {
    implementation("com.android.billingclient:billing:9.1.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.lifecycle:lifecycle-process:2.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.json:json:20250517")
}
// API Reference의 모듈과 패키지 설명
dokka {
    moduleName.set("purpl")
    dokkaSourceSets.configureEach { includes.from("Module.md") }
}
afterEvaluate {
    publishing { publications { create<MavenPublication>("release") { from(components["release"]); artifactId = "purpl" } } }
}
