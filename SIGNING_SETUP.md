# Release signing

Gradle reads these values from project properties or environment variables:

- `LIQUIDFRAME_KEYSTORE`: PKCS#12 keystore path
- `LIQUIDFRAME_KEYSTORE_PASSWORD`
- `LIQUIDFRAME_KEY_ALIAS`
- `LIQUIDFRAME_KEY_PASSWORD`

The default keystore path is `app/keystore/liquidframe-release.p12`. A signing config is added only when the file and credentials are available. Without it, APKs are unsigned.

The GitHub Actions workflow also checks the release certificate fingerprint. Use the project release key for that workflow; a locally generated key will fail the check.

Do not commit private keystores or passwords.
