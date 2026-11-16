plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.gitlab.arturbosch.detekt")
    id("org.jetbrains.kotlinx.kover")
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
        // نبدأ من 2,000,000,000 وهو أكبر من أي رقم إصدار قديم (كان ثواني Unix ≈ 1.79 مليار)،
        // ثم نزيد دقيقة لكل بناء. بذلك يبقى التحديث فوق النسخ القديمة ممكناً حتى بعد 2038.
        val versionBase = 2_000_000_000L
        val minutesSince2026 = ((System.currentTimeMillis() - 1_767_225_600_000L) / 60_000L)
            .coerceAtLeast(0L)
        versionCode = (versionBase + minutesSince2026).toInt()
        versionName = "1.6.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

    // توقيع ثابت واحد لكل النسخ: بدونه يولّد كل بناء مفتاحاً جديداً ويُجبرك على حذف التطبيق القديم
    // بيانات التوقيع تأتي من GitHub Secrets في CI، أو من keystore/keystore.properties محلياً
    // (الملف مستثنى من الرفع). لا كلمة سر احتياطية في الكود: غياب الاثنين يعني توقيعاً
    // بـdebug keystore للبناء المحلي فقط، وCI يبقى موقّعاً بالمفتاح الرسمي.
    val keystoreProps = java.util.Properties().apply {
        val f = rootProject.file("keystore/keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val releaseStorePassword = System.getenv("LION_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
        ?: keystoreProps.getProperty("storePassword")
    val releaseKeyPassword = System.getenv("LION_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
        ?: keystoreProps.getProperty("keyPassword")
    val hasReleaseCredentials = releaseStorePassword != null && releaseKeyPassword != null

    signingConfigs {
        create("lion") {
            storeFile = rootProject.file("keystore/lionai-release.jks")
            storePassword = releaseStorePassword
            keyAlias = "lionai"
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {
        release {
            signingConfig = if (hasReleaseCredentials) signingConfigs.getByName("lion")
                            else signingConfigs.getByName("debug")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            signingConfig = if (hasReleaseCredentials) signingConfigs.getByName("lion")
                            else signingConfigs.getByName("debug")
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
    // proot يُشغَّل من nativeLibraryDir: يجب استخراج المكتبات إلى القرص وعدم تجريدها.
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/libproot*.so"
        }
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    source.setFrom(files("src/main/java", "src/test/java"))
    reportsDir = file("$rootDir/build/reports/detekt")
}

// تقارير تغطية اختبارات الوحدة (تبقى قراءةً فقط في CI: لا بوابة نسبة دنيا بعد —
// تُشدّد لاحقاً بـkoverVerify عند استقرار الرقم الأول من التقرير الفعلي).
// أداة القياس الافتراضية في Kover 0.9 تكفي بلا أي إعداد إضافي.

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("org.apache.commons:commons-compress:1.28.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

