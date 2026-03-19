## 1. Implementation

- [x] 1.1 Add DTS-family payload classification fields and enums to the transport validation model
- [x] 1.2 Extend DTS-HD/DTS:X burst parsing to classify wrapped payloads as HD, likely-core-only, or unknown
- [x] 1.3 Update the DTS-family comparator so payload-classifier results supplement existing type-IV and wrapper validation
- [x] 1.4 Extend diagnostics export to include DTS payload classifier metadata in burst records and comparison output
- [x] 1.5 Add targeted DTS-family tests covering wrapped HD payload, wrapped likely-core-only payload, and wrapped unknown payload cases
- [x] 1.6 Validate with `./gradlew --no-daemon :app:testDebugUnitTest --tests "com.nexio.tv.debug.passthrough.*" :app:compileDebugKotlin`
- [x] 1.7 Validate with `openspec validate add-dtshd-payload-classifier --strict`
