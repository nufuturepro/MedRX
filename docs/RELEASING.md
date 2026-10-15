# Releasing Med RX

Releases are built by GitHub Actions whenever a tag `v*` is pushed. The
workflow signs both APKs (phone + Wear) with the release keystore stored in
repo secrets and publishes them to GitHub Releases — the exact feed the
in-app updater polls (`nufuturepro/MedRX`).

## One-time setup: upload signing secrets

The keystore lives **outside the repo** (never commit it). From the repo
root (`Med/`), with the keystore at `../medrx-keystore/medrx-release.jks`:

```bash
# 1. Base64-encode the keystore (Git Bash / WSL / macOS / Linux)
base64 -w0 ../medrx-keystore/medrx-release.jks > ks.b64

# 2. Upload it as the KEY_STORE_FILE secret (needs GitHub CLI)
gh secret set KEY_STORE_FILE < ks.b64
rm ks.b64

# 3. Upload the credentials — take the password from
#    ../medrx-keystore/CREDENTIALS.txt (never paste it in chat/issues)
gh secret set KEY_STORE_PASSWORD
gh secret set ALIAS          # value: medrx
gh secret set KEY_PASSWORD   # same password as KEY_STORE_PASSWORD
```

No GitHub CLI? Repo → Settings → Secrets and variables → Actions →
"New repository secret", paste the base64 text for `KEY_STORE_FILE`.

> When uploading base64 by hand, make sure the editor doesn't wrap lines —
> the CI decodes the secret exactly as stored.

## Version numbers

Med RX carries **two numbers with two jobs**:

- **`versionCode`** — what Android and CI care about. A strictly increasing
  integer that is **Med RX's own counter**: start wherever it must to be
  above any build already shipped under this app id (now `26`, past
  upstream's last `20`), increment by exactly 1 per release, never
  reuse, never sync it back to upstream's counter.
- **`versionName`** — what humans and the in-app updater compare. Format:
  **`X.Y.Z`**, Med Rx's own line since `2.2.4`.

  - `Z` for fixes and small batches, `Y` for feature releases, `X` when the
    app changes shape. The `-fork.N` suffix used before `2.2.4` is gone: it
    framed the app as *based on* upstream rather than as its own product, and
    it broke comparisons silently — `isNewer()` splits on `.` and turns any
    non-numeric segment into `0`, so `2.2.3-fork.1` compares as
    `[2, 2, 0, 1]` and the patch digit disappears.

  Example lineage: `2.2.3-fork.1` → `2.2.4` → `2.3.0`.

**The tag must keep the same shape as `versionName`** (`v2.2.4` for
`2.2.4`). The updater compares the release tag against the installed
`versionName`, so a mismatch (`v2.2.4-fork.1` against `2.2.4`) makes every
installed build look newer than the release and the app stops offering
updates entirely.

## Cutting a release

1. Bump `versionCode` (+1, never reuse) and `versionName` in **both**
   `app/build.gradle.kts` and `wear/build.gradle.kts` (keep them in sync).
2. Commit to `main` and push.
3. Tag and push the tag:

   ```bash
   git tag v2.2.4 && git push origin v2.2.4
   ```

4. Watch the Actions tab; when green, the Release appears with
   `MedRx-…-phone.apk` and `MedRx-…-wear.apk`.
5. Sanity-check: the updater compares the release tag against the installed
   `versionName`, so the new version must compare strictly greater and the
   tag must match `versionName` in shape (`v2.2.4` ↔ `2.2.4`).

## Signing-key rules

- **One key forever.** Same package (`com.nukirk.medrx`) + new key = update
  refused on every user device. Losing this key ends update-in-place for
  Med RX; keep `medrx-keystore/` backed up somewhere safe (password
  manager + offline copy).
- Never commit `*.jks`, `*.keystore`, or `keystore.properties` (gitignored —
  verify with `git status` after creating them locally).
- For local signed builds, create `keystore.properties` in the repo root
  (gitignored):

  ```properties
  KEY_STORE_FILE=../medrx-keystore/medrx-release.jks
  KEY_STORE_PASSWORD=...
  KEY_ALIAS=medrx
  KEY_PASSWORD=...
  ```

  then `./gradlew :app:assembleRelease :wear:assembleRelease`.

## Signing certificate fingerprint

```
SHA-256: 6A:CE:BC:23:0B:15:C4:E6:16:11:A5:AC:57:37:EE:A5:09:90:88:F6:8C:13:72:FA:65:61:4A:BF:3B:A6:3A:AD
```

Publish this in the README so users can verify downloaded APKs
(`keytool -printcert -jarfile MedRx-2.2.4-phone.apk`).

## Migrating users from the original Med

The package id differs, so both apps install side by side. Data moves via
the app's own tools: original Med → Settings → export backup (JSON) and/or
CSV → Med RX → import. The reader ignores unknown keys, so exports from
either app load cleanly.
