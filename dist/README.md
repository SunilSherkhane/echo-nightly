# dist/

Manually built artifacts — **not** produced by CI.

| File | Variant | versionCode | Signed with |
|---|---|---|---|
| `echo-nightly-3.0.726-debug.apk` | `nightly` | 726 | debug keystore (`~/.android/debug.keystore`) |

Built from branch `fix/extension-verification` (commit `1e7191f4`) with
`./gradlew assembleNightly`, then zipaligned and signed for local install.

To rebuild: see `echo-nightly-BUILD.md` in the repository root.
