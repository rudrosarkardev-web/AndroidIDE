/*
 * Codex for Android — standalone 64-bit Android app.
 *
 * The terminal emulator (`com.termux.terminal`, `com.termux.view`) and its native
 * PTY implementation are vendored from AndroidIDE / Termux (GPL-3.0). See NOTICE.md.
 */

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
  }
}

rootProject.name = "CodexAndroid"

include(":app")
