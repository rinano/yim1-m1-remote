# Release signing

The official builds are signed with a dedicated RSA-4096 release key.

* Public certificate: [`release-cert.pem`](release-cert.pem)
* Certificate SHA-256:
  `AB:8D:95:C2:7A:12:2F:1B:F9:CD:94:F7:39:EB:F7:41:91:88:C9:CC:8E:73:80:0E:AA:EE:FA:03:F1:DD:CE:B8`

keystore/debug.keystore is the standard Android debug key committed for reproducible local builds (debug only; never used for releases). The **release private key is not part of this repository**. To build your own release,
generate a keystore and create a `keystore.properties` file in the repository root:

```properties
storeFile=/absolute/path/to/your-release.jks
storePassword=***
keyAlias=yim1remote
keyPassword=***
```

`keystore.properties` and `*.jks` are gitignored. Without the file,
`./gradlew assembleRelease` falls back to the Android debug key (installable,
but not suitable for distribution).

