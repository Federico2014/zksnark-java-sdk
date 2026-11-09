# zksnark-java-sdk

A Java JNI wrapper for ZK-SNARK cryptographic functions, providing Sapling protocol support for the TRON blockchain.

## Overview

This library enables Java applications to perform ZK-SNARK operations including:
- Sapling spend and output proof generation
- Proof verification
- ZIP32 hierarchical key derivation
- Note encryption/decryption using ChaCha20-Poly1305
- Blake2b hashing with salt and personalization

## Requirements

| Tool | Version | Notes |
|------|---------|-------|
| cmake | >= 3.16 | CMake 3.x and 4.x supported |
| Rust (cargo) | 1.91.1 | Pinned in `rust-toolchain.toml` |
| libtool | >= 2.4.0 | GNU libtool |
| automake | >= 1.13 | GNU automake |
| Maven | >= 3.5.0 | Apache Maven |
| JDK | 8+ | JAVA_HOME must be set |
| Xcode Command Line Tools | Latest | macOS only |

## Installation

### Clone the Repository

```bash
# Clone with submodules
git clone git@github.com:tronprotocol/zksnark-java-sdk.git --recursive

# Or initialize submodules after cloning
git clone git@github.com:tronprotocol/zksnark-java-sdk.git
cd zksnark-java-sdk
git submodule update --init --recursive
```

### Build the Native Library

**Linux / macOS (Intel):**

```bash
cd cpp && mkdir build && cd build
cmake ..
make -j4
make install
```

**macOS (M1/Apple Silicon):**

```bash
cd cpp && mkdir build && cd build
CC=cc cmake ..
make -j4
make install
```

### Build the Java Package

```bash
mvn clean package
```

This creates a JAR file with the native libraries bundled for the current platform.

## Clean and Rebuild

### Clean Build Artifacts

```bash
# Clean CMake build
rm -rf cpp/build

# Clean Rust build
rm -rf rust/librustzcash/target

# Clean Java build
mvn clean
```

## Usage

### Initialize ZK-SNARK Parameters

Before using any ZK-SNARK functions, initialize the Sapling parameters:

```java
import org.tron.common.zksnark.LibrustzcashWrapper;
import org.tron.common.util.Utils;

LibrustzcashWrapper.getInstance().librustzcashInitZksnarkParams(
    Utils.getParamsFile("sapling-spend.params"),
    "8270785a1a0d0bc77196f000ee6d221c9c9894f55307bd9357c3f0105d31ca63991ab91324160d8f53e2bbd3c2633a6eb8bdf5205d822e7f3f73edac51b2b70c",
    Utils.getParamsFile("sapling-output.params"),
    "657e3d38dbb5cb5e7dd2970e8b03d69b4787dd907285b5a7f0790dcc8072f60bf593b32cc2d1c030e00ff5ae64bf84c5c3beb84ddc841d48264b4a171744d028"
);
```

### Key Derivation (ZIP32)

```java
import org.tron.common.zksnark.LibrustzcashWrapper;

// Derive master xsk from seed
byte[] seed = new byte[32]; // your seed
byte[] xskMaster = new byte[169];
LibrustzcashWrapper.getInstance().librustzcashZip32XskMaster(seed, seed.length, xskMaster);

// Derive child xsk at index i
byte[] xskChild = new byte[169];
LibrustzcashWrapper.getInstance().librustzcashZip32XskDerive(xskMaster, i, xskChild);

// Derive address from xfvk
byte[] xfvk = new byte[169];
byte[] index = new byte[11];
byte[] jRet = new byte[11];
byte[] addr = new byte[43];
boolean success = LibrustzcashWrapper.getInstance()
    .librustzcashZip32XfvkAddress(xfvk, index, jRet, addr);
```

### Generate Sapling Spend Proof

```java
import org.tron.common.zksnark.LibrustzcashWrapper;

// Initialize proving context
long ctx = LibrustzcashWrapper.getInstance().librustzcashSaplingProvingCtxInit();

// Generate spend proof
byte[] ak = new byte[32];
byte[] nsk = new byte[32];
byte[] diversifier = new byte[11];
byte[] rcm = new byte[32];
byte[] ar = new byte[32];
long value = 1000000L;
byte[] anchor = new byte[32];
byte[] witness = new byte[1065]; // Merkle path
byte[] cv = new byte[32];
byte[] rk = new byte[32];
byte[] zkproof = new byte[192];

boolean result = LibrustzcashWrapper.getInstance().librustzcashSaplingSpendProof(
    ctx, ak, nsk, diversifier, rcm, ar, value, anchor, witness, cv, rk, zkproof
);

// Clean up
LibrustzcashWrapper.getInstance().librustzcashSaplingProvingCtxFree(ctx);
```

### Verify Sapling Proofs

```java
import org.tron.common.zksnark.LibrustzcashWrapper;

// Initialize verification context
long ctx = LibrustzcashWrapper.getInstance().librustzcashSaplingVerificationCtxInit();

// Verify spend
boolean spendValid = LibrustzcashWrapper.getInstance().librustzcashSaplingCheckSpend(
    ctx, cv, anchor, nullifier, rk, zkproof, spendAuthSig, sighashValue
);

// Verify output
boolean outputValid = LibrustzcashWrapper.getInstance().librustzcashSaplingCheckOutput(
    ctx, cv, cm, ephemeralKey, zkproof
);

// Final binding check
boolean valid = LibrustzcashWrapper.getInstance().librustzcashSaplingFinalCheck(
    ctx, valueBalance, bindingSig, sighashValue
);

// Clean up
LibrustzcashWrapper.getInstance().librustzcashSaplingVerificationCtxFree(ctx);
```

## Running Tests

The current tests use JDK 8. CI installs a native Zulu JDK 8 on each architecture.

Rebuild the native library from the matching `rust/librustzcash` submodule source
before running tests. The checked JNI methods require the rebuilt library; old
bundled binaries do not provide them. To test without replacing bundled binaries,
point the loader at the directory containing the rebuilt JNI library:

```bash
mvn -Dlibrary.zksnarkjni.path=/absolute/path/to/native-library-directory test
```

```bash
mvn test
```

Tests cover:
- Key derivation (ask→ak, nsk→nk, ivk derivation)
- Proof generation and verification
- Note encryption/decryption
- Merkle tree operations

### Checked API migration

Use `librustzcashSaplingComputeNfChecked` and check its boolean result before
consuming the nullifier output. The legacy `void` method remains for binary
compatibility and cannot report failure. Never infer success from the output
being nonzero.

`librustzcashZip32XskDeriveChecked` returns false for malformed extended keys or
depth 255. The legacy `void` derivation method throws `IllegalArgumentException`
on those inputs. ZIP32 extended keys and their output buffers are exactly 169
bytes; negative Java child indices retain their unsigned hardened-index meaning.

Release the Rust submodule revision and rebuild native libraries for every
supported platform together with the Java wrapper. Consumers such as java-tron
can then update the SDK dependency and adopt the checked nullifier method.

## Native CI builds

`.github/workflows/native-build.yml` builds and tests four native libraries in
parallel, then assembles one SDK JAR after all four jobs pass:

| Platform | Runner | Resource directory |
|----------|--------|--------------------|
| Linux x86_64 | `ubuntu-22.04` | `linux64` |
| Linux ARM64 | `ubuntu-22.04-arm` | `linux64/aarch64` |
| macOS x86_64 | `macos-15-intel` | `osx64` |
| macOS ARM64 | `macos-15` | `osx64/aarch64` |

The initial fork trial uses `Federico2014/librustzcash` at commit
`66cc3896401e7d48d3d9d7fd95af23fdcc861d6a` from
`fix/native-boundary-error-handling`. Checkout follows the committed submodule
SHA, not the moving branch head. Switch the submodule URL and commit back to
upstream once the corresponding Rust changes are merged.

Each job builds Rust with `--locked`, builds the SHA-256-verified libsodium
1.0.15 release, generates JNI headers, and runs Rust and Java regression tests.
Maven's `native.resource.directory` property isolates tests from the prebuilt
libraries committed in this repository. The scripts never replace those files.

To run the same native job locally with JDK 8 and the required build tools:

```bash
bash scripts/build-native.sh macos-x86_64
# Other native hosts: macos-arm64, linux-x86_64, linux-arm64
```

`LIBSODIUM_ARCHIVE=/absolute/path/to/libsodium-1.0.15.tar.gz` can reuse an offline
archive; the same checksum is required. Build outputs and logs are under
`cpp/build/ci-<platform>/`. `scripts/setup-ci-tools.sh` installs CI tools only on
disposable GitHub runners; it is not a local workstation installer.

Each `native-<platform>` Actions artifact contains only its newly built library
and a manifest recording source commits, architecture, JNI exports, toolchain,
dependency checksum, library checksum, and Java test counts. Packaging rejects
missing platforms, mixed revisions, uncommitted sources, and mismatched hashes.
The final `zksnark-java-sdk-all-platforms` artifact contains the JAR and the four
build manifests. Diagnostic artifacts are uploaded even if a job fails.

The Linux jobs build and test on Ubuntu 22.04; older glibc compatibility is not
established by this workflow. macOS builds set a deployment target of 11.0 and
run tests on macOS 15; compatibility with older macOS runners is not tested.
No release or Maven publication runs automatically.

## Project Structure

```text
zksnark-java-sdk/
├── src/main/java/                      # Java JNI wrappers
│   └── org/tron/common/
│       ├── zksnark/                    # Librustzcash, Libsodium, Wrapper classes
│       └── util/                       # Utils (library loading)
├── src/main/resources/
│   └── META-INF/native/                # Native libraries (osx64/, linux64/)
├── cpp/                                # C++ JNI implementation
├── rust/librustzcash/                  # Rust ZK-SNARK library (submodule)
└── pom.xml                             # Maven build configuration
```

## License

Apache License 2.0

## Resources

- [Zcash Sapling Protocol](https://zips.z.cash/protocol/protocol.pdf)
- [ZIP32 - Hierarchical Key Derivation](https://zips.z.cash/zip-0032)
