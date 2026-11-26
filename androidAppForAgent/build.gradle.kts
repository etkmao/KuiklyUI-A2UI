import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localConfig = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun configString(key: String): String = "\"" + localConfig.getProperty(key, "")
    .replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""

android {
    namespace = "com.tencent.kuikly.a2ui.agent"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.tencent.kuikly.a2ui.demo.agent"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "AGENT_BASE_URL", "\"\"")
        buildConfigField("String", "AGENT_ROUTES", "\"\"")
    }
    buildTypes {
        getByName("debug") {
            buildConfigField("String", "AGENT_BASE_URL", configString("agent.baseUrl"))
            buildConfigField("String", "AGENT_ROUTES", configString("agent.routes"))
        }
        getByName("release") { isMinifyEnabled = false }
    }
    buildFeatures { viewBinding = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
}

dependencies {
    implementation(A2uiDep.a2ui(project))
    implementation(A2uiDep.a2uiRenderAndroid(project))
    implementation(KuiklyDep.coreRenderAndroid(project))
    implementation("androidx.appcompat:appcompat:1.4.2")
    implementation("androidx.core:core-ktx:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test:core:1.5.0")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}

tasks.withType<Test>().configureEach {
    System.getenv("AGENT_TEST_BASE_URL")?.let {
        systemProperty("agent.test.baseUrl", it)
        systemProperty("agent.test.routes", localConfig.getProperty("agent.routes", ""))
    }
}
