// AGP 9 自带 Kotlin（built-in Kotlin·默认绑 KGP 2.3.10）：不再 apply org.jetbrains.kotlin.android。
// 用官方升级机制（buildscript classpath）把 built-in Kotlin 的 KGP 抬到 catalog 锁定的 kotlin 版本；
// 版本从 gradle/libs.versions.toml（libs.versions.kotlin）单一事实源读取，绝不写字面量——
// 否则与 compose/serialization 编译器插件（plugins{} 经同一 catalog 应用）版本漂移。
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baselineprofile) apply false
}
