plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "app.hoshino.harness"
    compileSdk { version = release(37) { minorApiLevel = 0 } }
    defaultConfig {
        applicationId = "dev.hoshino.app"; minSdk = 29; targetSdk = 37
        versionCode = 212; versionName = "0.2.1-m2.8.2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    signingConfigs {
        getByName("debug") {
            storeFile = file(System.getenv("HOSHINO_SIGNING_STORE") ?: "${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android"
        }
    }
    buildTypes {
        // 发给用户的 arm64 安装包：与 debug 同签名、同数据，可直接覆盖安装；关闭 debuggable 让 ART 用 AOT/基线配置，界面明显更流畅。
        create("fast") {
            initWith(getByName("debug"))
            isDebuggable = false
            isJniDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("debug")
        }
    }
    flavorDimensions += "abi"
    productFlavors {
        create("arm64") { dimension="abi";ndk { abiFilters += "arm64-v8a" } }
        create("x86") { dimension="abi";ndk { abiFilters += "x86_64" } }
    }
    sourceSets {
        getByName("main").assets.setSrcDirs(emptyList<String>())
        // 单测与仪器测试共用的测试工具（如升级种子用的 LegacyConstructors），JVM 单测里也能直接验证。
        getByName("test").java.srcDir("src/sharedTest/java")
        getByName("androidTest").java.srcDir("src/sharedTest/java")
        getByName("arm64").assets.srcDir(layout.buildDirectory.dir("generated/runtimeAssets/arm64-v8a").get().asFile)
        getByName("x86").assets.srcDir(layout.buildDirectory.dir("generated/runtimeAssets/x86_64").get().asFile)
        getByName("androidTestArm64").assets.srcDir(layout.buildDirectory.dir("generated/benchmarkAssets/arm64-v8a").get().asFile)
        getByName("androidTestX86").assets.srcDir(layout.buildDirectory.dir("generated/benchmarkAssets/x86_64").get().asFile)
        getByName("androidTest").assets.srcDir(rootProject.file("eval")) // EV2 评测题集（tasks/…），EvalRunTest 从测试包 assets 读
    }
    androidResources { noCompress += listOf("txz","tgz") }
    packaging { jniLibs { useLegacyPackaging = true }; resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":core-bridge"))
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.core:core-splashscreen:1.2.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.navigation:navigation-compose:2.9.5")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // m2.8 phone OCR (PL1, open source, MIT): ONNX Runtime runs PaddleOCR PP-OCRv4 (Apache-2.0) models downloaded on demand.
    // APK cost: ~6.6 MB compressed / 18.3 MB installed for arm64 (see docs/m2.8-phone-acceptance.md). Never ML Kit / GMS.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    testImplementation("junit:junit:4.13.2")
    // 本地单测需要真实的 org.json（android.jar 里是桩）；仅测试依赖，公有领域许可。
    testImplementation("org.json:json:20250517")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
val verifyKernelAssets by tasks.registering {
    doLast {
        check(file("src/main/assets/kernel/manifest.json").isFile) { "Missing runtime manifest: run python rootfs/prepare.py" }
        for (abi in listOf("arm64-v8a", "x86_64")) {
            check(file("src/main/assets/kernel/$abi.txz").isFile) { "Run python rootfs/prepare.py first: missing $abi rootfs" }
            check(rootProject.file(".research/runtime/$abi.tgz").isFile) { "Missing identical gzip benchmark archive for $abi" }
            check(file("src/main/jniLibs/$abi/libproot.so").isFile) { "Missing standalone proot for $abi" }
        }
    }
}
val stageRuntimeAssets by tasks.registering {
    dependsOn(verifyKernelAssets)
    inputs.dir("src/main/assets/kernel");inputs.dir("src/main/assets/licenses")
    inputs.file(rootProject.file("THIRD_PARTY.md"))
    outputs.dir(layout.buildDirectory.dir("generated/runtimeAssets"));outputs.dir(layout.buildDirectory.dir("generated/benchmarkAssets"))
    doLast {
        for(abi in listOf("arm64-v8a","x86_64")) {
            copy { from("src/main/assets/kernel/$abi.txz","src/main/assets/kernel/manifest.json");into(layout.buildDirectory.dir("generated/runtimeAssets/$abi/kernel")) }
            copy { from("src/main/assets/licenses/$abi");into(layout.buildDirectory.dir("generated/runtimeAssets/$abi/licenses")) }
            copy { from(rootProject.file("licenses"));into(layout.buildDirectory.dir("generated/runtimeAssets/$abi/licenses")) }
            copy { from(rootProject.file("THIRD_PARTY.md"));into(layout.buildDirectory.dir("generated/runtimeAssets/$abi/licenses")) }
            copy { from("src/main/assets/codex-models.json");into(layout.buildDirectory.dir("generated/runtimeAssets/$abi")) }
            copy { from("src/main/assets/harness-source.zip");into(layout.buildDirectory.dir("generated/runtimeAssets/$abi")) }
            copy { from(rootProject.file(".research/runtime/$abi.tgz"));rename { "baseline.tgz" };into(layout.buildDirectory.dir("generated/benchmarkAssets/$abi")) }
        }
    }
}
tasks.named("preBuild") { dependsOn(stageRuntimeAssets) }
