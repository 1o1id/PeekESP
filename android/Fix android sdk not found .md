# Fix: "SDK location not found" in Gradle (Windows)

## The Error

```
PS D:\GITHUB\PeekESP\android> .\gradlew.bat assembleDebug
Starting a Gradle Daemon, 1 incompatible Daemon could not be reused, use --status for details
FAILURE: Build failed with an exception.

* What went wrong:
Could not determine the dependencies of task ':app:compileDebugJavaWithJavac'.

SDK location not found. Define a valid SDK location with an ANDROID_HOME environment
variable or by setting the sdk.dir path in your project's local properties file at
'D:\GITHUB\PeekESP\android\local.properties'.

BUILD FAILED in 14s
```

## What It Means

Gradle can't find the Android SDK. The encouraging part: the project parsed fine, and the only failure is the missing SDK path.

## Fix: Create `local.properties`

### Step 1: Check whether you already have an SDK

In PowerShell:

```powershell
Test-Path "$env:LOCALAPPDATA\Android\Sdk"
```

### Step 2a: If it prints `True`

Create `local.properties` in `D:\GITHUB\PeekESP\android\` with one command:

```powershell
$sdk = "$env:LOCALAPPDATA\Android\Sdk" -replace '\\','/'
Set-Content -Path .\local.properties -Value "sdk.dir=$sdk" -Encoding ascii
```

Verify the file contains a line like:

```
sdk.dir=C:/Users/YOU/AppData/Local/Android/Sdk
```

Forward slashes are fine and avoid escaping problems.

Run the build again:

```powershell
.\gradlew.bat assembleDebug
```

### Step 2b: If it prints `False`

You have no SDK yet. Two options:

**Option 1 (easiest): Android Studio**

Install Android Studio, open the `android` folder once, and let it download the SDK. It writes `local.properties` for you, and the SDK lands in the folder above.

**Option 2: Command line only**

1. Download the **"Command line tools only"** package from [developer.android.com/studio](https://developer.android.com/studio).
2. Unzip it to `C:\Android\Sdk\cmdline-tools\latest\`.
3. Install the required packages:

   ```powershell
   C:\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat "platform-tools" "platforms;android-35" "build-tools;35.0.0"
   ```

4. Accept the licenses when prompted:

   ```powershell
   C:\Android\Sdk\cmdline-tools\latest\bin\sdkmanager.bat --licenses
   ```

5. Set the SDK path in `local.properties`:

   ```
   sdk.dir=C:/Android/Sdk
   ```

## About the "incompatible Daemon" Line

Harmless. An older Gradle daemon was running, so Gradle started a new one. To stop the old daemons:

```powershell
.\gradlew.bat --stop
```

## Housekeeping

Make sure `local.properties` is in your `.gitignore`, because it holds a machine-specific path:

```
local.properties
```

## Quick Reference

| Problem | Solution |
|---|---|
| `SDK location not found` | Create `local.properties` with `sdk.dir=...` |
| SDK not installed | Install Android Studio, or use `sdkmanager` |
| Licenses not accepted | `sdkmanager --licenses` |
| "Incompatible Daemon" message | Ignore, or run `.\gradlew.bat --stop` |
