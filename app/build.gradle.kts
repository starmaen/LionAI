plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.lion.ai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lion.ai"
        minSdk = 26
        targetSdk = 35
        // رقم إصدار متزايد دائماً (حتى في البناء المحلي) حتى يُقبل التحديث فوق أي نسخة سابقة
        // دون حذف التطبيق، فتبقى المفاتيح والمحادثات محفوظة.
        versionCode = (System.currentTimeMillis() / 1000L).toInt()
        versionName = "1.6.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

    // توقيع ثابت واحد لكل النسخ: بدونه يولّد كل بناء مفتاحاً جديداً ويُجبرك على حذف التطبيق القديم
    signingConfigs {
        create("lion") {
            storeFile = rootProject.file("keystore/lionai-release.jks")
            storePassword = System.getenv("LION_KEYSTORE_PASSWORD") ?: "LionAI2026!Release"
            keyAlias = "lionai"
            keyPassword = System.getenv("LION_KEY_PASSWORD") ?: "LionAI2026!Release"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("lion")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            signingConfig = signingConfigs.getByName("lion")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    testImplementation("junit:junit:4.13.2")
}
