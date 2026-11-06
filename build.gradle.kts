// Android 내장 Kotlin과 Compose 컴파일러 버전 통일
buildscript { dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20") } }
plugins {
    id("com.android.library") version "9.3.3" apply false
    id("com.android.application") version "9.3.3" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    id("org.jetbrains.dokka") version "2.2.0"
}
allprojects { group = "sh.purpl"; version = "0.1.0" }

// purpl과 purpl-ui의 API Reference를 하나의 사이트로 통합
dependencies {
    dokka(project(":purpl"))
    dokka(project(":purpl-ui"))
}

dokka {
    moduleName.set("Purpl")
    dokkaPublications.html { includes.from("documentation/Purpl.md") }
}
