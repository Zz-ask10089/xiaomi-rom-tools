import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 签名信息放在项目根目录 keystore.properties（不入库）。
// 没有该文件时 release 不签名，克隆本仓库后可用你自己的密钥库。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.diankuang.xiaomirom"
    compileSdk {
        version =
            release(37) {
                minorApiLevel = 0
            }
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.diankuang.xiaomirom"
        minSdk = 24
        targetSdk = 37
        versionCode = 12
        versionName = "2.9"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("xrom") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("xrom")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.4")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(files("libs/jsoup-1.17.2.jar"))
    implementation("org.tukaani:xz:1.10")
    implementation("org.apache.commons:commons-compress:1.27.1")
    // commons-compress 1.27 的 BZip2 流运行时依赖 commons-io（pom 里标 optional，需显式声明）
    implementation("commons-io:commons-io:2.16.1")
}
