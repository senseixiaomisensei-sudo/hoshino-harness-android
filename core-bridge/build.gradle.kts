plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "dev.hoshino.bridge"
    compileSdk { version = release(37) { minorApiLevel = 0 } }
    defaultConfig { minSdk = 29;consumerProguardFiles("consumer-rules.pro") }
    buildFeatures { aidl = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    // TaskSetTest / EvalProvenanceTest 读仓库根目录的 eval/；架构守卫读 ../app/src
    testOptions { unitTests.all { it.inputs.dir(rootProject.file("eval")).withPropertyName("evalTasks"); it.inputs.dir(rootProject.file("app/src/main")).withPropertyName("appSources") } }
}
dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation("junit:junit:4.13.2")
    // 纯逻辑单测（评测、度量）用真实 org.json：android.jar 里的是桩
    testImplementation("org.json:json:20250517")
}
