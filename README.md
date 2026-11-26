# Jujutsu Controller (Android)

An Android client for managing Jujutsu (`jj`) and Git workflows on mobile via **Termux** integration, built with Kotlin and Jetpack Compose.

## Features

- **Termux & `jj` Integration**: Executes `jj` and repository commands through Termux (`com.termux.permission.RUN_COMMAND`).
- **Repository Setup & Cloning**: Register existing local workspaces or clone repositories via HTTPS / SSH key with per-repository author settings (`user.name` / `user.email`).
- **Workspace & History Inspection**: Browse and edit repository files in-app, inspect working-copy status, and review commit logs.
- **Bilingual UI (English / Japanese)**: Switch display language dynamically (`System`, `日本語`, `English`).

## Prerequisites

1. **Android Device / Emulator**: Android 8.0 (API 26) or higher.
2. **Termux**: Install Termux (F-Droid or GitHub release build recommended) and enable external command execution (`allow-external-apps = true` in `~/.termux/termux.properties`).
3. **Jujutsu (`jj`) & Git in Termux**: Install `git` and `jj` inside Termux (e.g., via `pkg install git jujutsu` or Cargo at `~/.cargo/bin/jj`).
4. **App Permissions**: Grant Termux `RUN_COMMAND` permission (`com.termux.permission.RUN_COMMAND`) and storage access when prompted.

## Usage

1. **Prepare Termux**: Launch Termux once and verify that `jj --version` runs without errors.
2. **Register or Clone a Repository**: Open the repository registration dialog in Jujutsu Controller to select an existing workspace folder or clone a remote repository via HTTPS / SSH.
3. **Inspect & Edit**: View current repository status, browse and edit files inside the workspace, and run `jj` operations directly from the app.

## Build

- **Debug Build** (`com.example.jujutsucontroller.debug` / `JujutsuController (Debug)`):
  - macOS / Linux: `./gradlew assembleDebug`
  - Windows (PowerShell): `.\gradlew.bat assembleDebug`
- **Release Build** (`com.example.jujutsucontroller`):
  - `./gradlew assembleRelease` (requires signing credentials via `keystore.properties` or `ANDROID_KEYSTORE_PATH` environment variables)

## License

This project is licensed under the MIT License - see the `LICENSE` file for details.
