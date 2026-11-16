pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // [T-android-vad] RealTimeCutVADLibraryForAndroid ships via JitPack
        // only. Same author and same underlying stack (Silero + ONNX Runtime +
        // WebRTC APM) as the RealTimeCutVADLibrary SPM package iOS already
        // uses, so both platforms segment speech with the same model and the
        // same tunables.
        maven { url = uri("https://jitpack.io") }
        // rclone.aar — the backup feature's remote destinations (SMB / WebDAV /
        // SFTP / S3 / FTP). Not published to any Maven repo: it is built from
        // deps/rclone-mobile by `deps/build_rclone_android.sh`, which is also
        // what produces the iOS XCFramework from the same Go sources and the
        // same trimmed backend list. Treated as a build artifact, not a vendored
        // binary — see docs/backup-restore-design.md §6.2.
        flatDir { dirs("app/libs") }
    }
}

rootProject.name = "Minis"

// [T-minimal] Only the minimal app is built.
//
// `:app` is the original 165 000-line application. It stays in the tree as the
// reference for every feature this one has not adopted yet — the markdown
// renderer, the streaming markdown parser, the session list, the provider
// client — but it is not compiled, because compiling something nobody ships
// costs minutes per build and proves nothing.
//
// Switching back is this one line.
include(":minimal")
// include(":app")
