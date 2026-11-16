import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.vanniktech.maven.publish")
}

group = "com.qwryai"
version = "0.1.0"

android {
    namespace = "com.qwryai.sdk"
    compileSdk = 35
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.json:json:20240303")
}

mavenPublishing {
    configure(AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = true))
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
    signAllPublications()
    coordinates("com.qwryai", "qwryai-sdk", version.toString())
    pom {
        name.set("QwryAI Android SDK")
        description.set("Put your QwryAI agent inside an Android app.")
        url.set("https://github.com/qwryai/qwryai-android")
        licenses { license { name.set("MIT License"); url.set("https://opensource.org/licenses/MIT") } }
        developers { developer { id.set("qwryai"); name.set("QwryAI"); url.set("https://www.qwryai.com") } }
        scm {
            url.set("https://github.com/qwryai/qwryai-android")
            connection.set("scm:git:https://github.com/qwryai/qwryai-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/qwryai/qwryai-android.git")
        }
    }
}
