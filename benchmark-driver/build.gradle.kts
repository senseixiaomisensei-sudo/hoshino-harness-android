plugins { id("com.android.application");id("org.jetbrains.kotlin.android") }
android {
    namespace="dev.hoshino.benchmark"
    compileSdk { version=release(37) {minorApiLevel=0} }
    defaultConfig {applicationId="dev.hoshino.benchmark";minSdk=29;targetSdk=37;testInstrumentationRunner="androidx.test.runner.AndroidJUnitRunner"}
    compileOptions {sourceCompatibility=JavaVersion.VERSION_17;targetCompatibility=JavaVersion.VERSION_17}
    kotlinOptions {jvmTarget="17"}
}
dependencies {
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
