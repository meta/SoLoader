# SoLoader

[![Build CI](https://github.com/facebook/soloader/actions/workflows/build.yml/badge.svg)](https://github.com/facebook/SoLoader/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.facebook.soloader/soloader/badge.svg?style=flat)](https://maven-badges.herokuapp.com/maven-central/com.facebook.soloader/soloader)

SoLoader is a native code loader for Android. It takes care of unpacking your native libraries
and recursively loads dependencies on Android API 23 and earlier, since those old OS versions
do not support all of that functionality out of box.

## Requirements
SoLoader is useful for applications running on Android API 23 and earlier. SoLoader should not
be used on Android API 24 and above unless the app is delivered as
[Exopackage](https://buck.build/article/exopackage.html), requires
[Android Native Library Merging](https://engineering.fb.com/2018/01/23/android/android-native-library-merging/)
or uses [Superpack](https://engineering.fb.com/2021/09/13/core-data/superpack/)
compression.

## Including SoLoader in your apps
You can use [prebuilt aars](https://github.com/facebook/soloader/releases/latest)
or fetch SoLoader from Maven repository by adding the following to your
`build.gradle` file:
```groovy
implementation 'com.facebook.soloader:soloader:0.13.0+'
```

## Building from source
To build the Android library and its two Java dependencies with Gradle, install
the Android SDK and JDK 17, then run:

```shell
./gradlew :soloader:assembleDebug :annotation:jar :nativeloader:jar
```

The AAR is written to `soloader/build/outputs/aar/`, and the annotation and
nativeloader JARs to their respective `build/libs/` directories.
Run `./gradlew publishToMavenLocal` to publish all three artifacts to your local
Maven repository, using the version from the `artifacts/` configuration files.
The published class files target Java 7, matching earlier SoLoader releases.

## Formatting Java sources

From this directory, run `./gradlew format` to format all Java sources, or
`./gradlew formatCheck` to check them without making changes. Both commands work
in a standalone checkout or in `fbsource/fbandroid/libraries/soloader` and use a
pinned Google Java Format version downloaded by Gradle. No formatter binary is
stored in the repository.

The existing [Buck](https://buckbuild.com/) build is also available. Once you have
Buck installed, run the following commands from the project root directory:
```shell
  buck fetch //...
  buck build :soloader
```
The build command generates `buck-out/gen/soloader.aar` file.

## Troubleshooting "couldn't find DSO to load"

This error means SoLoader could not load the named library from its configured
sources. The named library can also be a dependency of the one your app requested.

1. Keep the full exception and the preceding SoLoader logs. Current versions include
   `existing SO sources` and, when an application context is available, `Native lib dir`
   in the not-found error. `existing SO sources` lists the sources SoLoader tried;
   `Native lib dir` is the app's native library directory.
2. Inspect the APKs installed on the affected device, including ABI splits if the app
   is delivered as an Android App Bundle. With a local copy of each APK, list its entries:

   ```shell
   unzip -l app.apk
   ```

   Replace `app.apk` with the local APK path. For standard APK packaging, look for
   `lib/<abi>/<library>`, for example `lib/arm64-v8a/libhermes.so`. Compare the ABI with
   the source paths in the error; a library under a different ABI is not a match.
   If the entry is missing, check the dependency that supplies the native library and
   the app's native-library packaging configuration.
3. `DirectApkSoSource` loads only libraries stored uncompressed in the APK and skips
   compressed entries. `unzip -lv app.apk` shows the compression method of each entry.
   If a linker error was logged, inspect its missing-dependency or incompatible-ABI
   details. `DirectApkSoSource` logs an `UnsatisfiedLinkError` from loading an entry
   and continues searching, so the final not-found message does not by itself prove
   the file is absent.

## Join our community
Please use our [issues page](https://github.com/facebook/soloader/issues) to let us know of any problems.
See the [CONTRIBUTING](https://github.com/facebook/soloader/blob/main/CONTRIBUTING.md) file for how to
help out.

## License
SoLoader is [Apache-2.0-licensed](https://github.com/facebook/soloader/blob/main/LICENSE).
