pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AI Pocket Chat"
include(":app")
// :baselineprofile 暂停（依赖升级 2026-06·M3）：最新【稳定版】baselineprofile/benchmark 1.4.1 与 AGP 9.2.1
// 不兼容（BaselineProfileAppTargetPlugin → "Module :app is not a supported android module"），唯一支持 AGP 9.x
// 的是 1.5.0-alpha。按「只用最新稳定版」拍板暂停该模块（损失冷启动 baseline profile 优化·非功能性）。
// 待官方稳定版 1.5 发布后恢复：取消下行注释 + 恢复 app/build.gradle.kts 的 baselineprofile 插件与 baselineProfile(project) 依赖。
// include(":baselineprofile")
