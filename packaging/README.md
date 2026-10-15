# Native installers

The build creates independent **Point of Sale** and **Point of Sale Demo** applications, each with its own bundled Java runtime. Installing and running them requires neither Java nor Maven and does not require network access. These are unsigned private-testing packages, not public releases.

## Supported build targets

| Build machine | CPU | Installer |
|---|---|---|
| Windows 11 | x64 | `.exe`, per-user, Start menu and optional desktop shortcut |
| macOS 14 or newer | Intel x64 or Apple Silicon arm64 | `.dmg` containing an application to drag into Applications |
| Ubuntu 24.04 | x64 | `.deb` with an application-menu entry |

Run the build on the target operating system and architecture. This repository does not cross-compile installers or publish releases. Mac Intel and Apple Silicon require separate native builds. The Linux format is Debian packaging, not RPM.

## Prerequisites

All platforms need **Eclipse Temurin 26.0.2.1+1**, **Apache Maven 3.9.11**, and **JavaFX 26.0.2 JMODs** matching the machine's OS and CPU. Set `JAVA_HOME` to the Temurin installation and put Maven on `PATH`. The driver checks the vendor, versions and JavaFX native binary architecture before building. The JavaFX SDK is not a substitute for the JMOD archive.

- [Temurin pinned release](https://github.com/adoptium/temurin26-binaries/releases/tag/jdk-26.0.2.1%2B1): verify the archive using the release's SHA-256 file before extracting.
- [JavaFX downloads](https://gluonhq.com/products/javafx/): select the 26.0.2 JMOD archive for Windows x64, macOS x64, macOS aarch64, or Linux x64. Extract it and use the directory containing `javafx.controls.jmod` as `JAVAFX_JMODS`.
- [Maven 3.9.11 archive](https://archive.apache.org/dist/maven/maven-3/3.9.11/binaries/): verify the published checksum.

Windows additionally needs WiX **5.0.2**, .NET for the WiX tool, and its **globally installed** UI and Util extensions. A local WiX extension cache is insufficient because jpackage runs WiX from the application image directory. One setup option:

```powershell
dotnet tool install wix --version 5.0.2 --tool-path target/installer/tooling/wix
$env:PATH = (Resolve-Path target/installer/tooling/wix).Path + ';' + $env:PATH
wix extension add -g WixToolset.UI.wixext/5.0.2
wix extension add -g WixToolset.Util.wixext/5.0.2
```

Ubuntu needs `fakeroot`, `dpkg-dev` and `binutils`; GUI tests also need a graphical session or Xvfb. Install the system GTK/font libraries required by JavaFX. macOS needs the normal `hdiutil` tooling and a graphical desktop session.

Versions and package identities live in `versions.properties`. JavaFX and H2 must agree with `pom.xml`. Installer versions use three numeric components; Windows limits them to `255.255.65535`. Keep upgrade UUIDs and package identifiers unchanged between releases.

## Build commands

Windows:

```powershell
$env:JAVA_HOME = 'C:\Tools\jdk-26.0.2.1+1'
$env:PATH = "$env:JAVA_HOME\bin;C:\Tools\apache-maven-3.9.11\bin;$env:PATH"
./packaging/build.ps1 -Version 1.0.0 -JavaFxJmods 'C:\Tools\javafx-jmods-26.0.2'
```

macOS or Ubuntu:

```bash
export JAVA_HOME='/path/to/temurin-26.0.2.1'
export JAVAFX_JMODS='/path/to/javafx-jmods-26.0.2'
export PATH="$JAVA_HOME/bin:/path/to/apache-maven-3.9.11/bin:$PATH"
bash packaging/build.sh --version 1.0.0
```

Both variants are built by default. Use `-Variant app` or `--variant app` to build one; `demo` is also accepted. Use `-Check`/`--check` for prerequisite validation, or `-ImageOnly`/`--image-only` to test the self-contained application images without a native installer compiler. `-OutputDirectory`/`--output` selects a subdirectory of `target/installer`; outputs outside that tree are rejected.

The scripts run `mvn verify` before packaging and fail on test/build errors. Source-file Java drives the shared packaging logic, so no Python, Gradle or third-party packaging plugin is required. Maven dependencies must be available locally or downloadable during the build; the resulting installer works offline.

Each build gets a fresh timestamped staging directory. It does not clean the workspace, delete earlier builds, or inspect the user's data directory. JavaFX and JDK modules are linked separately from the application/H2 classpath jars. Runtime smoke checks use only in-memory H2 databases.

## Artifacts

Outputs are under `target/installer/artifacts/` by default:

```text
point-of-sale-1.0.0-windows-x64.exe
point-of-sale-demo-1.0.0-windows-x64.exe
point-of-sale-1.0.0-macos-arm64.dmg
point-of-sale-1.0.0-macos-x64.dmg
point-of-sale-1.0.0-linux-x64.deb
```

Each installer has a `.sha256` sidecar and a `.build.properties` manifest with the release/dependency versions, architecture, runtime module list, jar checksums, image location and verification status. The corresponding demo artifacts follow the same naming convention. Images, generated icons and customized installer sources remain under `target/installer/staging/` for inspection. Installers include H2's licence, dependency notices and the runtime's `legal` directory. No JUnit dependencies or local database files are shipped.

## Installation, upgrades and data

On Windows run the EXE, select the shortcut options, then launch from the Start menu. The install belongs to the current Windows user. On macOS drag the app from the DMG into Applications. On Ubuntu run `sudo apt install ./point-of-sale-1.0.0-linux-x64.deb`; normal and demo are installed under separate `/opt/point-of-sale` and `/opt/point-of-sale-demo` roots.

The normal app asks you to create an owner account on first launch. The demo is labelled `[DEMO]` and accepts `owner` / `owner-pass-1` (manager: `maria` / `maria-pass-1`; cashier: `sam` / `sam-pass-1`). Demo data is seeded only into an empty demo store.

All writable data retains its existing location:

- Normal: `~/.cashier-app/` (`%USERPROFILE%\.cashier-app` on Windows).
- Demo: `~/.cashier-app-demo/` (`%USERPROFILE%\.cashier-app-demo` on Windows).

Databases, preferences, logs and backups belong to the OS user who runs the app, not the install directory. Upgrading or uninstalling does not remove those folders. Back up from Settings before upgrading and keep that backup. The application retains its automatic backup-before-migration behavior. There is no automatic updater or data-path migration.

Close the affected application normally before installing a newer package or uninstalling. Windows and Debian installers check for the packaged process and abort instead of terminating it; the other variant can remain open. On macOS quit the affected app before Finder replacement or removal. Downgrades are unsupported; the Windows installer blocks them. Other platforms must not replace a newer app with an older build.

Unsigned private packages can trigger Windows SmartScreen and macOS Gatekeeper. Distribute them only to testers who expect the package and can verify its checksum. Document any platform approval used during testing; signing and notarization are outside this release.

## Acceptance checklist

Run on clean native machines without Java/Maven before calling a platform verified:

1. Install both variants; check icons, shortcuts, Add/Remove Programs or package metadata, and absence of a console window.
2. Start normal owner setup and sign into the demo. Check artwork, calendar pickers, checkout, receipt saving, CSV export and backups.
3. Save a sale and preferences in a temporary test store, exit and reopen. Repeat with an install/user path containing spaces and non-ASCII characters.
4. With one variant open, attempt an upgrade and removal; they must fail without closing the app, removing the old installation or changing its database.
5. Close it, upgrade from `1.0.0` to `1.0.1`, then verify saved sales/preferences and both independent installations.
6. Uninstall one variant and reinstall; its data must remain, and the other variant must still launch with its own data.
7. Record each platform's result separately. Runtime smoke checks are not substitutes for installed GUI/upgrade tests.

See `VERIFICATION.md` for the checks actually performed in this workspace.
