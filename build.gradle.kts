// Android 내장 Kotlin과 Compose 컴파일러 버전 통일
buildscript { dependencies { classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20") } }
plugins {
    id("com.android.library") version "9.3.3" apply false
    id("com.android.application") version "9.3.3" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
allprojects { group = "sh.purpl"; version = "0.1.0" }
