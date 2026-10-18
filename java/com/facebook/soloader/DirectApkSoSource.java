/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.soloader;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.StrictMode;
import android.text.TextUtils;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

/**
 * {@link SoSource} that directly finds shared libraries in a APK. The native libraries must be page
 * aligned and stored uncompressed in the APK.
 *
 * @see <a
 *     href="https://developer.android.com/guide/topics/manifest/application-element#extractNativeLibs">
 *     android:extractNativeLibs</a>
 */
@ThreadSafe
public class DirectApkSoSource extends SoSource implements RecoverableSoSource {

  // <key: ld path, value: libs set>
  private final Map<String, Set<String>> mLibsInApkCache = new HashMap<>();
  // <key: so name, value libs set>
  private final Map<String, Set<String>> mDepsCache = new HashMap<>();
  private final Set<String> mDirectApkLdPaths;

  // <key: apk path, value: opened apk>. Opening a ZipFile parses the APK's whole central
  // directory, so its cost grows with the number of entries in the APK. Reuse one per APK instead
  // of re-opening it for every library and every ABI.
  @GuardedBy("mZipFiles")
  private final Map<String, ZipFile> mZipFiles = new HashMap<>();

  public DirectApkSoSource(ApplicationInfo aInfo) {
    super();
    mDirectApkLdPaths = getDirectApkLdPaths(aInfo);
  }

  public DirectApkSoSource(Context context) {
    this(context.getApplicationInfo());
  }

  public DirectApkSoSource(Set<String> directApkLdPaths) {
    super();
    mDirectApkLdPaths = directApkLdPaths;
  }

  @Override
  public int loadLibrary(String soName, int loadFlags, StrictMode.ThreadPolicy threadPolicy)
      throws IOException {
    if (SoLoader.sSoFileLoader == null) {
      throw new IllegalStateException("SoLoader.init() not yet called");
    }

    for (String directApkLdPath : mDirectApkLdPaths) {
      Set<String> libsInApk = mLibsInApkCache.get(directApkLdPath);
      if (TextUtils.isEmpty(directApkLdPath) || libsInApk == null || !libsInApk.contains(soName)) {
        LogUtil.v(SoLoader.TAG, soName + " not found on " + directApkLdPath);
        continue;
      }

      loadDependencies(directApkLdPath, soName, loadFlags, threadPolicy);

      try {
        final String soPath = directApkLdPath + File.separator + soName;
        loadFlags |= SoLoader.SOLOADER_LOOK_IN_ZIP;
        SoLoader.sSoFileLoader.load(soPath, loadFlags);
      } catch (UnsatisfiedLinkError e) {
        LogUtil.w(
            SoLoader.TAG, soName + " not found on " + directApkLdPath + " flag: " + loadFlags, e);
        continue;
      }

      LogUtil.d(SoLoader.TAG, soName + " found on " + directApkLdPath);
      return LOAD_RESULT_LOADED;
    }
    return LOAD_RESULT_NOT_FOUND;
  }

  @Override
  public File unpackLibrary(String soName) throws IOException {
    throw new UnsupportedOperationException("DirectAPKSoSource doesn't support unpackLibrary");
  }

  public boolean isValid() {
    return !mDirectApkLdPaths.isEmpty();
  }

  @Override
  @Nullable
  public String getLibraryPath(String soName) throws IOException {
    for (String directApkLdPath : mDirectApkLdPaths) {
      Set<String> libsInApk = mLibsInApkCache.get(directApkLdPath);
      if (!TextUtils.isEmpty(directApkLdPath) && libsInApk != null && libsInApk.contains(soName)) {
        return directApkLdPath + File.separator + soName;
      }
    }
    return null;
  }

  /*package*/ static Set<String> getDirectApkLdPaths(ApplicationInfo aInfo) {
    Set<String> directApkPathSet = new LinkedHashSet<>();

    final String apkPath = aInfo.sourceDir;
    final @Nullable Set<String> fallbackApkLdPath = getFallbackApkLdPath(apkPath);
    if (fallbackApkLdPath != null && !fallbackApkLdPath.isEmpty()) {
      directApkPathSet.addAll(fallbackApkLdPath);
    }

    if (aInfo.splitSourceDirs != null) {
      for (String splitApkPath : aInfo.splitSourceDirs) {
        final @Nullable Set<String> fallbackSplitApkLdPath = getFallbackApkLdPath(splitApkPath);
        if (fallbackSplitApkLdPath != null && !fallbackSplitApkLdPath.isEmpty()) {
          directApkPathSet.addAll(fallbackSplitApkLdPath);
        }
      }
    }

    return directApkPathSet;
  }

  private static @Nullable Set<String> getFallbackApkLdPath(String apkPath) {
    final String[] supportedAbis = SysUtil.getSupportedAbis();
    if (apkPath == null || apkPath.isEmpty()) {
      LogUtil.w(
          SoLoader.TAG,
          "Cannot compute fallback path, apk path is " + ((apkPath == null) ? "null" : "empty"));
      return null;
    }
    if (supportedAbis == null || supportedAbis.length == 0) {
      LogUtil.w(
          SoLoader.TAG,
          "Cannot compute fallback path, supportedAbis is "
              + ((supportedAbis == null) ? "null" : "empty"));
      return null;
    }
    Set<String> apkLdPaths = new LinkedHashSet<>();
    for (String supportedAbi : supportedAbis) {
      apkLdPaths.add(apkPath + "!/lib/" + supportedAbi);
    }
    return apkLdPaths;
  }

  private void loadDependencies(
      String directApkLdPath, String soName, int loadFlags, StrictMode.ThreadPolicy threadPolicy)
      throws IOException {
    Set<String> dependencies = getDepsFromCache(directApkLdPath, soName);
    if (dependencies == null) {
      buildLibDepsCache(directApkLdPath, soName);
      dependencies = getDepsFromCache(directApkLdPath, soName);
    }

    if (dependencies != null) {
      for (String dependency : dependencies) {
        SoLoader.loadDependency(dependency, loadFlags, threadPolicy);
      }
    }
  }

  @Override
  protected void prepare(int flags) throws IOException {
    prepare();
  }

  private void prepare() throws IOException {
    final Set<String> apksWithLibs = new HashSet<>();
    for (String directApkLdPath : mDirectApkLdPaths) {
      String subDir = null;
      if (!TextUtils.isEmpty(directApkLdPath)) {
        final int i = directApkLdPath.indexOf('!');
        if (i >= 0 && i + 2 < directApkLdPath.length()) {
          // Exclude `!` and `/` in the path
          subDir = directApkLdPath.substring(i + 2);
        }
      }
      if (TextUtils.isEmpty(subDir)) {
        continue;
      }
      final String apkPath = getApkPathFromLdPath(directApkLdPath);
      final ZipFile zipFile = getZipFile(apkPath);
      Enumeration<? extends ZipEntry> entries = zipFile.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        if (entry != null
            && entry.getMethod() == ZipEntry.STORED
            && entry.getName().startsWith(subDir)
            && entry.getName().endsWith(".so")) {
          final String soName = entry.getName().substring(subDir.length() + 1);
          appendLibsInApkCache(directApkLdPath, soName);
          apksWithLibs.add(apkPath);
        }
      }
    }
    // Only APKs with native libraries are read again, so don't keep the others (such as language
    // or density splits) open.
    closeZipFilesExcept(apksWithLibs);
  }

  private void buildLibDepsCache(String directApkLdPath, String soName) throws IOException {
    final String apkPath = getApkPathFromLdPath(directApkLdPath);

    final ZipFile zipFile = getZipFile(apkPath);
    final String path = getLibraryPathInApk(directApkLdPath, soName);
    final ZipEntry entry = zipFile.getEntry(path);

    if (entry == null) {
      LogUtil.e(SoLoader.TAG, path + " not found in " + apkPath);
      return;
    }

    buildLibDepsCacheImpl(directApkLdPath, zipFile, entry, soName);
  }

  private ZipFile getZipFile(String apkPath) throws IOException {
    synchronized (mZipFiles) {
      ZipFile zipFile = mZipFiles.get(apkPath);
      if (zipFile == null) {
        zipFile = new ZipFile(apkPath);
        mZipFiles.put(apkPath, zipFile);
      }
      return zipFile;
    }
  }

  private void closeZipFiles() {
    closeZipFilesExcept(Collections.<String>emptySet());
  }

  private void closeZipFilesExcept(Set<String> apkPaths) {
    synchronized (mZipFiles) {
      Iterator<Map.Entry<String, ZipFile>> it = mZipFiles.entrySet().iterator();
      while (it.hasNext()) {
        Map.Entry<String, ZipFile> entry = it.next();
        if (apkPaths.contains(entry.getKey())) {
          continue;
        }
        try {
          entry.getValue().close();
        } catch (IOException e) {
          LogUtil.w(SoLoader.TAG, "Failed to close " + entry.getKey(), e);
        }
        it.remove();
      }
    }
  }

  private void buildLibDepsCacheImpl(
      String directApkLdPath, ZipFile zipFile, ZipEntry entry, String soName) throws IOException {
    try (ElfByteChannel bc = new ElfZipFileChannel(zipFile, entry)) {
      for (String dependency : NativeDeps.getDependencies(soName, bc)) {
        if (dependency.startsWith("/")) {
          // Bionic dynamic linker could correctly resolving system dependencies, we don't
          // need to load them by ourselves.
          continue;
        }
        appendDepsCache(directApkLdPath, soName, dependency);
      }
    }
  }

  @Override
  public String getName() {
    return "DirectApkSoSource";
  }

  @Override
  public String toString() {
    return new StringBuilder()
        .append(getName())
        .append("[root = ")
        .append(mDirectApkLdPaths.toString())
        .append(']')
        .toString();
  }

  private void appendLibsInApkCache(String ldPath, String soName) {
    synchronized (mLibsInApkCache) {
      if (!mLibsInApkCache.containsKey(ldPath)) {
        mLibsInApkCache.put(ldPath, new HashSet<String>());
      }
      mLibsInApkCache.get(ldPath).add(soName);
    }
  }

  private void appendDepsCache(String directApkLdPath, String soName, String depSoName) {
    synchronized (mDepsCache) {
      final String soPath = directApkLdPath + soName;
      if (!mDepsCache.containsKey(soPath)) {
        mDepsCache.put(soPath, new HashSet<String>());
      }
      mDepsCache.get(soPath).add(depSoName);
    }
  }

  private @Nullable Set<String> getDepsFromCache(String directApkLdPath, String soName) {
    synchronized (mDepsCache) {
      final String soPath = directApkLdPath + soName;
      return mDepsCache.get(soPath);
    }
  }

  private static String getApkPathFromLdPath(String ldPath) {
    return ldPath.substring(0, ldPath.indexOf('!'));
  }

  private static String getLibraryPathInApk(String directApkLdPath, String soName) {
    final int index = directApkLdPath.indexOf('!');
    return directApkLdPath.substring(index + 2) + File.separator + soName;
  }

  @Override
  public SoSource recover(ApplicationInfo aInfo) {
    // Recovery holds SoLoader's sources lock for writing, so no load is using these. If this source
    // stays in use, it re-opens the APKs on demand.
    closeZipFiles();
    DirectApkSoSource recovered = new DirectApkSoSource(aInfo);
    try {
      recovered.prepare();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return recovered;
  }
}
