# Release signing

Release signing is intentionally opt-in. Do not commit a private keystore or passwords.

Set these environment variables in a trusted local shell or CI secret store:

- `LIQUIDFRAME_KEYSTORE`: absolute path to a PKCS#12 keystore
- `LIQUIDFRAME_KEYSTORE_PASSWORD`
- `LIQUIDFRAME_KEY_ALIAS`
- `LIQUIDFRAME_KEY_PASSWORD`

If all values are present and the keystore exists, Gradle signs release and debug builds with it. Without them, release artifacts are unsigned and cannot update an already-installed APK signed by another key. Keep the keystore and its passwords backed up securely and outside the repository.

For GitHub Actions, the workflow expects `LIQUIDFRAME_KEYSTORE_BASE64`, `LIQUIDFRAME_KEYSTORE_PASSWORD`, `LIQUIDFRAME_KEY_ALIAS`, and `LIQUIDFRAME_KEY_PASSWORD` repository or environment secrets. The Base64 secret is decoded into the runner's temporary directory and is not uploaded as an artifact.
