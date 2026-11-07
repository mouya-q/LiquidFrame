# Release signing setup

Release builds are no longer signed with credentials embedded in `app/build.gradle`.
Set these values in `~/.gradle/gradle.properties` or as environment variables before
building a release:

```properties
LIQUIDFRAME_KEYSTORE=/absolute/path/to/your-release.p12
LIQUIDFRAME_KEYSTORE_PASSWORD=your-store-password
LIQUIDFRAME_KEY_ALIAS=your-key-alias
LIQUIDFRAME_KEY_PASSWORD=your-key-password
```

When the values are absent, the debug build uses the normal Android debug key and the
release build is left unsigned. Keep the keystore and this properties file outside Git
and outside shared source archives.
