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

## Join our community
Please use our [issues page](https://github.com/facebook/soloader/issues) to let us know of any problems.
See the [CONTRIBUTING](https://github.com/facebook/soloader/blob/main/CONTRIBUTING.md) file for how to
help out.

## License
SoLoader is [Apache-2.0-licensed](https://github.com/facebook/soloader/blob/main/LICENSE).
