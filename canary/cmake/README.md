# Choosing the SDK source for the producer canaries

Both `canary/producer-c` and `canary/producer-cpp` include `KvsSdkSource.cmake`, which decides where each KVS SDK layer comes from.

| `-DKVS_SDK_TRACK=` | Producer CPP | Producer C | PIC |
|---|---|---|---|
| `release` (default) | `master` | `master` (producer-c canary) or the tag pinned in producer-sdk-cpp (cpp canary) | the tag pinned in producer-c |
| `develop` | `develop` | `develop` | `develop` |

`release` is byte-for-byte the behaviour the canaries always had. `develop` is the fast feedback path: a change merged to any develop branch is picked up by the next canary iteration, without waiting for a release and a pin bump.

## How develop works without touching the SDK repos

The develop branches of producer-c and producer-sdk-cpp already accept a local checkout in place of their pinned fetch:

- producer-c: `LOCAL_KVSPIC_PATH` is `add_subdirectory()`ed instead of fetching the pinned PIC tag.
- producer-sdk-cpp: `LOCAL_KVSC_PATH` likewise for producer-c, and it forwards `LOCAL_KVSPIC_PATH` down.

`kvs_sdk_prepare_layers()` clones PIC (and producer-c, for the cpp canary) at their develop refs with `FetchContent` and sets those cache entries before the canary `add_subdirectory()`s the top-level SDK. `kvs_sdk_verify_layers()` then compares the directories the SDKs actually configured against the fetched ones and fails the configure if they differ. That guard matters: `master` of producer-c predates `LOCAL_KVSPIC_PATH` and would silently build its pinned PIC tag instead.

Individual refs can be overridden on the develop track: `-DKVS_PIC_REF=`, `-DKVS_PRODUCER_C_REF=`, `-DKVS_PRODUCER_CPP_REF=` take a branch, tag or SHA.

## Jenkins

The runner jobs take a `KVS_SDK_TRACK` parameter (default `release`) and pass it to `cmake`. On the develop track the build directory is wiped first so `FetchContent` re-resolves the branch heads on every iteration instead of reusing the sources cached by the previous one. Release builds are not wiped, and concurrent builds of one runner job share its `@2`, `@3` workspaces, so a release configure can land in a build directory a develop configure used. A release configure therefore clears `LOCAL_KVSPIC_PATH` / `LOCAL_KVSC_PATH`, and `kvs_sdk_verify_layers()` fails it if PIC or Producer C still resolves to a develop checkout.

The orchestrators schedule the develop track as extra jobs with their own `RUNNER_LABEL`s, `LongrunDevelop` and `PeriodicDevelop`. The label is the `ProducerSDKCanaryType` / `ProducerCppCanaryType` CloudWatch dimension, so develop numbers land in separate time series. Dashboards and alarms keyed on those dimension values are configured separately from this repo; until they exist, the develop numbers are only visible directly in CloudWatch.

The two extra jobs per canary each take a producer node slot on `producer-uw2` / `producer-cpp-uw2`.
