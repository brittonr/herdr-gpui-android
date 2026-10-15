# Host storage tests

Run the dependency-free command and transport suite with `sh android/tests/run.sh`.
The storage suite additionally needs runnable Android `org.json` classes, such as
an existing Robolectric `android-all` runtime JAR (the SDK `android.jar` is stubs):

```sh
HERDR_ANDROID_TEST_RUNTIME=/path/to/android-all.jar sh android/storage-tests/run.sh
```

This suite checks legacy migration, exact text and pin serialization, malformed
settings, immutable snapshots, and ID/origin/token fencing. It never accesses a
keystore, real saved hosts, or the network. It does not prove Android keystore,
AtomicFile, or Activity lifecycle behavior; those require device tests.
