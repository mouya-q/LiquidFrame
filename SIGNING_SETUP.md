# Release signing

## How the project actually signs

This project signs **every** build — release and debug — with one fixed keystore
that is committed to the repository at `app/keystore/liquidframe-release.p12`:

```
store      app/keystore/liquidframe-release.p12   (PKCS12)
store pass liquidframe2026
alias      liquidframe
key pass   liquidframe2026
```

The keystore is committed **on purpose**. LiquidFrame is an open-source LSPosed
module that users install from CI artifacts. Signing every build with the same
key is what makes a new APK update over an installed one without an uninstall;
a private or rotating key would force users to wipe the module (and its
settings) on every release. This matches the project's original design.

CI (`.github/workflows/build.yml`) builds `:app:assembleRelease` and then
**fails the run if the produced APK is not signed**, so a green run always
means an installable-over artifact.

## Signing with your own key (optional)

The committed keystore is the default, not a requirement. To sign with your
own key, set these in `~/.gradle/gradle.properties` or the environment before
building — the values override the in-tree defaults:

```properties
LIQUIDFRAME_KEYSTORE=/absolute/path/to/your-release.p12
LIQUIDFRAME_KEYSTORE_PASSWORD=your-store-password
LIQUIDFRAME_KEY_ALIAS=your-key-alias
LIQUIDFRAME_KEY_PASSWORD=your-key-password
```

Keep in mind that installing your build over an install signed with the project
key will require an uninstall — that is Android's signature check, not this
build system.

## Why this file was rewritten

An earlier revision of the project removed the keystore from the signing
path and documented it as a secret ("Never commit a private keystore…"),
while the file itself stayed in the repository. The result was the worst of
both: CI's release builds came out **unsigned** while the workflow comment
still claimed they were signed, and users had to uninstall to update. This
file now documents the real policy instead of the aspirational one.