# Releasing Prox5

CI is defined in [`.github/workflows/release.yml`](../.github/workflows/release.yml). It builds an
installable APK and, on a version tag, publishes it as a GitHub Release asset.

## How a release happens

1. Push a tag that starts with `v` (e.g. `v0.1.0`), **or** run the workflow manually from the
   Actions tab ("Release APK" → "Run workflow").
2. The workflow builds the APK on `ubuntu-latest` (Temurin JDK 17, Android SDK, Gradle 9 wrapper).
3. The APK is uploaded as a **workflow artifact** on every run, and on a `v*` tag it is also
   attached to a **GitHub Release** named after the tag, as `Prox5-<tag>.apk`.

```bash
# cut a release from the default branch
git tag v0.1.0
git push origin v0.1.0
```

## Two signing paths

The build's signing comes from the repo's existing config in `app/build.gradle.kts`, which reads a
git-ignored `keystore.properties` at the repo root. CI mirrors that behavior:

| GitHub secrets set?           | Gradle task run        | Output APK                | Installable? | In-place update? |
|-------------------------------|------------------------|---------------------------|--------------|------------------|
| **Yes** (all 4 below)         | `:app:assembleRelease` | signed `app-release.apk`  | Yes          | Yes — stable signature across builds |
| **No**                        | `:app:assembleDebug`   | `app-debug.apk` (debug key)| Yes         | No — debug signature differs per build, so users must uninstall first to update |

So releases work immediately with **no secrets** (debug-signed, fine for sideloading). Add the four
secrets below to upgrade to **stable release signing** with zero workflow changes.

## One-time: create a release keystore and set the secrets

Run these locally. **Never commit the keystore, `keystore.properties`, or any password** — they are
already covered by `.gitignore`.

### 1. Generate a keystore with `keytool`

```bash
keytool -genkeypair -v \
  -keystore prox5-release.jks \
  -alias prox5 \
  -keyalg RSA -keysize 2048 \
  -validity 10000 \
  -storepass 'CHOOSE_A_STORE_PASSWORD' \
  -keypass  'CHOOSE_A_KEY_PASSWORD' \
  -dname "CN=Prox5, O=DFTZ, C=US"
```

Keep `prox5-release.jks` and both passwords somewhere safe (a password manager). If you lose them you
can no longer ship in-place updates to anyone who installed a previous signed build.

### 2. Base64-encode the keystore

```bash
# macOS / Linux
base64 -i prox5-release.jks -o prox5-release.jks.b64    # macOS
base64    prox5-release.jks  > prox5-release.jks.b64     # Linux
```

### 3. Set the four GitHub secrets with `gh`

```bash
gh secret set KEYSTORE_BASE64   --repo nathanabrewer/Prox5 < prox5-release.jks.b64
gh secret set KEYSTORE_PASSWORD --repo nathanabrewer/Prox5 --body 'CHOOSE_A_STORE_PASSWORD'
gh secret set KEY_ALIAS         --repo nathanabrewer/Prox5 --body 'prox5'
gh secret set KEY_PASSWORD      --repo nathanabrewer/Prox5 --body 'CHOOSE_A_KEY_PASSWORD'
```

After these are set, every tagged release (and manual run) produces a **stably signed** APK. You can
then delete the local `.b64` file:

```bash
rm prox5-release.jks.b64
```

## Building locally (optional)

```bash
./gradlew :app:assembleDebug      # app/build/outputs/apk/debug/app-debug.apk  (debug-signed)
./gradlew :app:assembleRelease    # needs keystore.properties + keystore for a *signed* APK;
                                  # without them this produces an UNSIGNED, non-installable APK
```

To sign locally, create `keystore.properties` at the repo root (git-ignored):

```properties
storeFile=prox5-release.jks
storePassword=CHOOSE_A_STORE_PASSWORD
keyAlias=prox5
keyPassword=CHOOSE_A_KEY_PASSWORD
```
