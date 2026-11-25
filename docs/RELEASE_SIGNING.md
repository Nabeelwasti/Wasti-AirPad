# Wasti AirPad — Release Signing

This project can build a signed release APK in GitHub Actions without storing signing material in the repository.

## Important security rules

- Create the keystore on a device you control.
- Keep the `.jks` file and both passwords private.
- Never commit the keystore, paste it into chat, upload it to a public issue, or put passwords in source code.
- Keep at least two encrypted backups of the keystore in separate secure locations.
- Losing a self-managed signing key can prevent future APK updates from installing over an existing installation.
- Use the same keystore and alias for every future release of this app.

## 1. Generate the keystore

Install a Java JDK that includes `keytool`, then run:

```bash
keytool -genkeypair -v \
  -keystore airpad-release.jks -storetype JKS \
  -keyalg RSA -keysize 4096 -validity 10000 -alias airpad
```

Choose strong, unique passwords when prompted. Keep the alias as `airpad` unless you intentionally choose another value. The certificate identity fields are descriptive; use the publisher identity you want associated with the app.

## 2. Encode the keystore for GitHub

On Linux or Termux:

```bash
base64 -w 0 airpad-release.jks
```

On Windows PowerShell:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("airpad-release.jks"))
```

Copy the resulting Base64 text privately. It is still sensitive: anyone with it and the passwords can use the signing key.

## 3. Add four GitHub Actions secrets

Open the repository on GitHub:

**Settings → Secrets and variables → Actions → New repository secret**

Create these four secrets:

| Secret name | Value |
| --- | --- |
| `AIRPAD_KEYSTORE_BASE64` | The complete Base64 text from step 2 |
| `AIRPAD_KEYSTORE_PASSWORD` | The keystore password |
| `AIRPAD_KEY_ALIAS` | `airpad` (or the alias you selected) |
| `AIRPAD_KEY_PASSWORD` | The key password |

Do not put these values in repository variables, source files, workflow YAML, pull-request comments, or this chat. GitHub Actions exposes the values to the build only as secrets. The workflow writes the keystore into the runner's temporary directory with restrictive file permissions. The signing job removes whitespace from copied Base64, accepts URL-safe Base64, restores omitted padding, and then validates the JKS format, store password, and alias before Gradle receives the signing configuration. The normal test/lint/debug/unsigned-release job runs separately, so a malformed signing secret cannot prevent the app's automated checks from running.

## 4. Verify the signed build

The Gradle release signing configuration explicitly declares `storeType = "JKS"` to match the keystore format generated above. After all four secrets are saved, trigger a new workflow run (for example, by pushing a commit to the pull-request branch). That run should upload:

- `AirPad-apk` — debug APK for device testing.
- `AirPad-release-signed` — signed release APK for distribution.

The workflow verifies that the signed APK exists and prints its SHA-256 checksum. If signing secrets are absent, the signing job reports an unsigned-only configuration and the verified unsigned release artifact remains available; it does not silently use the Android debug key as a release key. If the signing job reports invalid Base64, recreate `AIRPAD_KEYSTORE_BASE64` from the complete output of `base64 -w 0 airpad-release.jks` (or the PowerShell command above). Do not include shell prompts, command text, quotes, or explanatory text in the secret. If decoding succeeds but JKS validation fails, verify the existing store password and alias locally with `keytool -list -v -keystore airpad-release.jks -storetype JKS -alias airpad`; never share the key or passwords.

## 5. Protect and back up the key

Keep the original `airpad-release.jks` and passwords in an encrypted password manager or encrypted offline backup. Store the key and passwords separately where practical. Do not delete the original after adding the GitHub secrets. The GitHub secrets are build credentials, not a replacement for your own backup.

## Google Play note

For Google Play distribution, use Play App Signing. Google manages the app-signing key, while you keep an upload key and use it to authenticate uploads. For direct APK distribution outside Google Play, the same privately controlled signing key must be preserved for future updates. Read the official [Android app-signing guide](https://developer.android.com/studio/publish/app-signing).
