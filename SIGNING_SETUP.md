# Release signing

## How the project actually signs

This project signs **every** build — debug and release — with one fixed PKCS12
keystore that is committed to the repository at `app/keystore/liquidframe-release.p12`.

The password and alias are hardcoded as fallback defaults in `app/build.gradle`:

| Property | Default value |
|----------|---------------|
| store    | `keystore/liquidframe-release.p12` |
| store password | `liquidframe2026` |
| key alias | `liquidframe` |
| key password | `liquidframe2026` |

This is intentional: CI builds produce APKs that are signed with the same key
as previously installed versions, so users can update in place without an
uninstall. The certificate fingerprint (SHA-256) is
`6E:85:BC:51:CD:EB:78:0E:CD:88:A6:6F:8E:F6:A4:0C:F8:35:AC:5C:E5:14:D4:F9:03:A8:97:4F:32:B5:B3:EA`.

## Overriding the key

Developers who want to sign with their own key can set `LIQUIDFRAME_KEYSTORE`,
`LIQUIDFRAME_KEYSTORE_PASSWORD`, `LIQUIDFRAME_KEY_ALIAS`, and
`LIQUIDFRAME_KEY_PASSWORD` as Gradle properties or environment variables.
When all four are present and the keystore file exists, the external key
replaces the committed one for both build types.