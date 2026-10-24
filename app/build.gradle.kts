plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "dev.hoshino.app"
    compileSdk { version = release(37) { minorApiLevel = 0 } }
    defaultConfig {
        applicationId = "dev.hoshino.app"; minSdk = 29; targetSdk = 37
        versionCode = 1; versionName = "0.0.1-m0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    flavorDimensions += "abi"
    productFlavors {
        create("arm64") { dimension="abi";ndk { abiFilters += "arm64-v8a" } }
        create("x86") { dimension="abi";ndk { abiFilters += "x86_64" } }
    }
    sourceSets {
        getByName("main").assets.setSrcDirs(emptyList<String>())
        getByName("arm64").assets.srcDir(layout.buildDirectory.dir("generated/runtimeAssets/arm64-v8a").get().asFile)
        getByName("x86").assets.srcDir(layout.buildDirectory.dir("generated/runtimeAssets/x86_64").get().asFile)
        getByName("androidTestArm64").assets.srcDir(layout.buildDirectory.dir("generated/benchmarkAssets/arm64-v8a").get().asFile)
        getByName("androidTestX86").assets.srcDir(layout.buildDirectory.dir("generated/benchmarkAssets/x86_64").get().asFile)
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
    outputs.dir(layout.buildDirectory.dir("generated/runtimeAssets"));outputs.dir(layout.buildDirectory.dir("generated/benchmarkAssets"))
    doLast {
        for(abi in listOf("arm64-v8a","x86_64")) {
            copy { from("src/main/assets/kernel/$abi.txz","src/main/assets/kernel/manifest.json");into(layout.buildDirectory.dir("generated/runtimeAssets/$abi/kernel")) }
            copy { from("src/main/assets/licenses/$abi");into(layout.buildDirectory.dir("generated/runtimeAssets/$abi/licenses")) }
            copy { from(rootProject.file(".research/runtime/$abi.tgz"));rename { "baseline.tgz" };into(layout.buildDirectory.dir("generated/benchmarkAssets/$abi")) }
        }
    }
}
tasks.named("preBuild") { dependsOn(stageRuntimeAssets) }
