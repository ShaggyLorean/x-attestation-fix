# X Attestation Fix

An LSPosed module that fixes X (formerly Twitter) Android app's hardware attestation flow on devices where TEE Remote Key Provisioning is broken.

## What problem does this solve

X v12.7.1 generates Android Key Attestation keys through the TEE by default. On some devices — specifically the OnePlus 13 running the 2026-06-05 security patch — the TEE's CSR v2 / Remote Key Provisioning (RKP) call fails with error `-18`:

```
E/KeymasterUtils: IKMHal_sendCmd failed with rsp_header->status: -18
E/android_keymaster: TA call to generate_csr_v2() failed with error: -18
E/keystore2: IGetKeyCallback failed: ERROR_UNKNOWN: Failure in CSR v2 generation.
```

When this happens, the `GooglePlayAttestationCoordinator` (`com.x.attestation.a1`) cannot generate an attestation token. The app-attestation token cache (`com.twitter.network.appattestation.g`) stays `null`. The signing gate (`com.twitter.network.q.a()`) checks the cache before allowing signed POST requests, and since the cache is empty, it returns `false` for every request:

```
SIGNING_GATE a => false
```

The user sees this as a login rejection: **"Please use official X apps to proceed or try again later."**

The same gate rejects `POST /onboarding/login/actions/username`, so you can't even get past the username step.

## How the fix works

The module hooks `com.x.attestation.y1.a()` — the class responsible for generating attestation key pairs via `KeyPairGenerator` with the `AndroidKeyStore` provider. The original code creates a `KeyGenParameterSpec` without `setIsStrongBoxBacked(true)`, so it defaults to TEE attestation.

The hook replaces that call with one that sets `setIsStrongBoxBacked(true)`, routing key generation through the device's StrongBox (hardware security module) instead of the TEE. StrongBox uses pre-provisioned keys that don't depend on CSR v2 / RKP, so it works even when TEE remote provisioning is broken.

After the hook fires:
- StrongBox generates a 5-certificate attestation chain
- The coordinator sends it to X's `verify_android_key_attestation` GraphQL endpoint
- If the server accepts, it returns a `HardwareAttestationToken` via `exchange_android_attestation_for_token`
- The token gets cached in `com.twitter.network.appattestation.g`
- The signing gate starts returning `true`
- Login works, posting works, everything works

## Requirements

- Rooted Android device with Magisk/KernelSU
- ReZygisk + LSPosed (Zygisk fork) installed and functional
- StrongBox-capable device (Pixel 3+, Samsung S10+, OnePlus with StrongBox support, most 2019+ flagships)
- X (Twitter) v12.7.1+ (app package `com.twitter.android`)

**Check if your device has StrongBox:**
```kotlin
context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
```

If your device doesn't have StrongBox, this module won't help. The TEE path is broken at the firmware level and needs a different fix (either an OS update that fixes RKP, or a kernel-side workaround).

## Installation

1. Install the APK from the [releases page](releases)
2. Open LSPosed Manager
3. Find "X Attestation Bypass" in the Modules list
4. Enable it and set scope to `com.twitter.android`
5. Force-stop X and relaunch
6. Check logcat to verify the hook loaded:
   ```
   adb logcat -s XAttestFix
   ```
   You should see:
   ```
   I/XAttestFix: loaded for com.twitter.android
   I/XAttestFix: hooked chain provider
   I/XAttestFix: hooked signing gate
   ```
7. Try logging in with username. It should work now.

## Building from source

You need:
- JDK 17+
- Android SDK (platform 34, build-tools 34.0.0)
- Python 3

```bash
# Put the original module APK at ./base.apk.
# It provides the compiled manifest/resources expected by LSPosed.
# Compile StrongBoxHook.java against the stubs, DEX only StrongBoxHook and its
# anonymous callback class, then run:
python3 package_v11.py
```

The build reuses the original APK's `AndroidManifest.xml`, `resources.arsc`, and existing DEX files, appending the hook as `classes5.dex`. This preserves the manifest metadata LSPosed expects while keeping the new hook in a unique class: `io.github.mara.xbypass.StrongBoxHook`.

## Technical details

### The attestation flow in X v12.7.1

```
User taps "Next" on username screen
       |
       v
POST /onboarding/login/actions/username
       |
       v
com.twitter.network.q.a(userId, "POST", "jf.x.com", "/onboarding/login/actions/username")
       |
       |--- checks: cache.c(userId) != null  [token cache]
       |--- checks: signer.b(accountId)       [key availability]
       |
       | If either is false, gate returns false
       | Request goes out unsigned
       | Server rejects: "use official X apps"
       v
```

### The token generation chain

```
com.x.attestation.a1 (GooglePlayAttestationCoordinator)
       |
       |--- com.x.repositories.attestation.i.a()  → fetch nonce from server
       |--- com.x.attestation.y1.a(accountId, challenge)
       |         |
       |         v
       |    KeyPairGenerator("EC", "AndroidKeyStore")
       |    KeyGenParameterSpec.Builder(alias, SIGN)
       |        .setAttestationChallenge(challenge)
       |        .build()                    ← no setIsStrongBoxBacked here
       |    kpg.generateKeyPair()
       |         |
       |         v
       |    keystore2 → Keymaster HAL → TEE
       |         |
       |         v
       |    generate_csr_v2() → FAILS (-18)
       |    SECURE_HW_COMMUNICATION_FAILED
       |
       |--- com.x.repositories.attestation.i.b(certs, key, id, uuid)
       |    → verify_android_key_attestation (GraphQL)
       |
       |--- com.x.repositories.attestation.i.c(data, signed, keyId)
       |    → exchange_android_attestation_for_token (GraphQL)
       |    → HardwareAttestationToken
       |
       v
    Token cached in com.twitter.network.appattestation.g
    Gate starts passing
```

### What the hook changes

The hook intercepts `com.x.attestation.y1.a()` and replaces the key generation with:

```java
KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(alias, 4)
        .setDigests("SHA-256")
        .setAttestationChallenge(challenge)
        .setIsStrongBoxBacked(true)    // ← this is the fix
        .build();
```

StrongBox doesn't go through RKP/CSR v2. It has its own pre-provisioned keys in a separate hardware enclave. The resulting certificate chain still contains the same RootOfTrust data (including `deviceLocked` and `verifiedBootState`), so the server-side policy decisions are unchanged — the module just fixes the client-side path that was broken.

### Confirmed working on

- OnePlus 13 (CPH2653), Android 16, patch 2026-06-05
- X v12.7.1-release.0 (312071000)

### Signatures that the module touches

| Class | Method | Hook type | What it does |
|-------|--------|-----------|-------------|
| `com.x.attestation.y1` | `a(String, byte[])` | before | Replaces TEE key gen with StrongBox |
| `com.twitter.network.q` | `a(UserIdentifier, String, String, String)` | after (read-only) | Logs gate verdict |

The module does **not**:
- Modify network requests or responses
- Inject synthetic tokens
- Bypass certificate pinning
- Alter Play Integrity results
- Touch signatures or signing keys
- Change any server-side behavior

It only changes which hardware enclave generates the attestation key pair. The resulting attestation chain is real and verifiable.

## Security implications

This module demonstrates that X's backend accepts Android Key Attestation chains from StrongBox even when the device reports an unlocked bootloader (`deviceLocked=false`) and orange verified boot state (`verifiedBootState=UNVERIFIED_ORANGE`). The RootOfTrust data is embedded in the StrongBox-generated certificate chain and sent to the server, which issues a valid `HardwareAttestationToken` regardless.

If this token is used as a device-integrity or anti-abuse signal, the backend is treating an explicitly unlocked device as trustworthy. This is a server-side policy decision — the server receives the boot state in the attestation extension and accepts it anyway.

## Token lifetime and testing

This is not an immediate kill switch for an already authenticated app process. The hook runs when X generates or refreshes Android Key Attestation material. Once X has a valid `HardwareAttestationToken`, it persists the token and its StrongBox key in app storage. Disabling the module therefore does not revoke that existing server-issued token, and signed actions such as replies can continue until X refreshes or expires it.

When the cached token is missing or expired on a device with the affected TEE RKP failure, the difference is deterministic:

- Module enabled: X takes the StrongBox path, obtains a token, and the signing gate passes.
- Module disabled: X returns to TEE CSR v2, token generation fails, and the signing gate remains false.

Do not clear application data merely to demonstrate this on an account you care about. That removes the session as well as the attestation state. A clean test profile or a natural token refresh is the safe way to test the transition.

## Play Integrity versus StrongBox

The module does not forge, modify, or replace Play Integrity results. It fixes Android Key Attestation key generation. Those are separate checks.

The tested X flow still requests an Express Play Integrity token, so a device must satisfy whatever Play Integrity verdict X requires. There is no evidence from this test that `MEETS_STRONG_INTEGRITY` is required. The successful device was bootloader-unlocked and used a real StrongBox attestation chain with `deviceLocked=false` and orange verified boot; it would not qualify for genuine Strong Integrity.

For portability, the hardware requirement is StrongBox support. The module is useful when StrongBox works but TEE CSR v2/RKP fails. A device with no StrongBox cannot use this fallback. A device whose normal TEE path works does not need it.

## Log output

After installing, you can verify the module is working:

```
$ adb logcat -s XAttestFix

I/XAttestFix: StrongBox hook active
I/XAttestFix: StrongBox chain generated: certs=5
```

If you see `gate ... => false`, the token cache is still empty. Force-stop X and try again. The coordinator needs the app to be in the foreground to trigger token generation.

## License

MIT
