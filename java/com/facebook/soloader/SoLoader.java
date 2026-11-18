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

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.os.StrictMode;
import android.text.TextUtils;
import com.facebook.soloader.nativeloader.NativeLoader;
import com.facebook.soloader.nativeloader.SystemDelegate;
import com.facebook.soloader.observer.ObserverHolder;
import com.facebook.soloader.recovery.DefaultRecoveryStrategyFactory;
import com.facebook.soloader.recovery.RecoveryStrategy;
import com.facebook.soloader.recovery.RecoveryStrategyFactory;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.NotThreadSafe;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Native code loader.
 *
 * <p>To load a native library, call the static method {@link #loadLibrary} from the static
 * initializer of the Java class declaring the native methods. The argument should be the library's
 * short name.
 *
 * <p><b>Note:</b> SoLoader is enabled in the source code by default but disabled for the OSS
 * package via meta-data config. The application could define com.facebook.soloader.enabled metadata
 * entry to override the default behavior.
 *
 * <p>For example, if the native code is in libmy_jni_methods.so:
 *
 * <pre>{@code
 * class MyClass {
 *   static {
 *     SoLoader.loadLibrary("my_jni_methods");
 *   }
 * }
 * }</pre>
 *
 * <p>Before any library can be loaded SoLoader needs to be initialized. The application using
 * SoLoader should do that by calling SoLoader.init early on app initialization path. The call must
 * happen before any class using SoLoader in its static initializer is loaded.
 *
 * <p>An example of the meta data entry to enable SoLoader:
 *
 * <pre>
 *     &lt;application ...&gt;
 *       &lt;meta-data
 *           tools:replace="android:value"
 *           android:name="com.facebook.soloader.enabled"
 *           android:value="true" /&gt;
 *     &lt;/application&gt;
 * </pre>
 */
@ThreadSafe
public class SoLoader {

  public static final String TAG = "SoLoader";
  /* package */ static final boolean DEBUG = false;
  /* package */ static final boolean SYSTRACE_LIBRARY_LOADING;
  /* package */ @Nullable static SoFileLoader sSoFileLoader;

  // optional identifier strings to facilitate bytecode analysis
  public static final String VERSION = "0.13.0";

  /**
   * locking controlling the list of SoSources. We want to allow long running iterations over the
   * list to happen concurrently, but also ensure that nothing modifies the list while others are
   * reading it.
   */
  private static final ReentrantReadWriteLock sSoSourcesLock = new ReentrantReadWriteLock();

  /* package */ @Nullable static Context sApplicationContext = null;
  /* package */ @Nullable static String sPrimaryAbi = null;
  /* package */ @Nullable static Provider<ApplicationInfo> sApplicationInfoProvider = null;

  /**
   * Ordered list of sources to consult when trying to load a shared library or one of its
   * dependencies. {@code null} indicates that SoLoader is uninitialized.
   */
  @GuardedBy("sSoSourcesLock")
  @Nullable
  private static volatile SoSource[] sSoSources = null;

  @GuardedBy("SoLoader.class")
  private static boolean sTestMode = false;

  @GuardedBy("sSoSourcesLock")
  private static final AtomicInteger sSoSourcesVersion = new AtomicInteger(0);

  @GuardedBy("SoLoader.class")
  @Nullable
  private static RecoveryStrategyFactory sRecoveryStrategyFactory = null;

  /** Records the sonames (e.g., "libdistract.so") of shared libraries we've loaded. */
  private static final Set<String> sLoadedLibraries =
      Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

  /**
   * Libraries that are in the process of being loaded, and lock objects to synchronize on and wait
   * for the loading to end.
   *
   * <p>To prevent potential deadlock, always acquire sSoSourcesLock before these locks!
   */
  @GuardedBy("SoLoader.class")
  private static final Map<String, Object> sLoadingLibraries = new HashMap<>();

  /**
   * Libraries that have loaded and completed their JniOnLoad. Reads and writes are guarded by the
   * corresponding lock in sLoadingLibraries. However, as we only add to this set (and never
   * remove), it is safe to perform un-guarded reads as an optional optimization prior to locking.
   */
  private static final Set<String> sLoadedAndJniInvoked =
      Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

  /**
   * Locks for invoking JNI_OnLoad for a merged library. This is used to lock on
   * sLoadedAndJniInvoked checks/mutations on a shortName library level.
   *
   * <p>To prevent potential deadlock, always acquire sSoSourcesLock before these locks!
   */
  @GuardedBy("SoLoader.class")
  private static final Map<String, Object> sInvokingJniForLibrary = new HashMap<>();

  /** Wrapper for System.loadLibrary. */
  @Nullable private static SystemLoadLibraryWrapper sSystemLoadLibraryWrapper = null;

  /** Whether SoLoader is enabled, via com.facebook.soloader.enabled meta data (boolean) */
  private static boolean isEnabled = true;

  /**
   * Name of the directory we use for extracted DSOs from built-in SO sources (main APK, exopackage)
   */
  public static final String SO_STORE_NAME_MAIN = "lib-main";

  /** Enable the exopackage SoSource. */
  public static final int SOLOADER_ENABLE_EXOPACKAGE = (1 << 0);

  /**
   * Allow deferring some initialization work to asynchronous background threads. Shared libraries
   * are nevertheless ready to load as soon as init returns.
   */
  public static final int SOLOADER_ALLOW_ASYNC_INIT = (1 << 1);

  public static final int SOLOADER_LOOK_IN_ZIP = (1 << 2);

  /**
   * In some contexts, using a backup so source in case of so corruption is not feasible e.g. lack
   * of write permissions to the library path.
   */
  public static final int SOLOADER_DISABLE_BACKUP_SOSOURCE = (1 << 3);

  /**
   * Skip calling JNI_OnLoad if the library is merged. This is necessary for libraries that don't
   * define JNI_OnLoad and are only loaded for their side effects (like static constructors
   * registering callbacks). DO NOT use this to allow implicit JNI registration (by naming your
   * methods Java_com_facebook_whatever) because that is buggy on Android.
   */
  public static final int SOLOADER_SKIP_MERGED_JNI_ONLOAD = (1 << 4);

  /** Deprecated, NO EFFECT */
  @Deprecated public static final int SOLOADER_DONT_TREAT_AS_SYSTEMAPP = (1 << 5);

  /**
   * In API level 23 and above, it’s possible to open a .so file directly from your APK. Enabling
   * this flag will explicitly add the direct SoSource in soSource list.
   */
  @Deprecated public static final int SOLOADER_ENABLE_DIRECT_SOSOURCE = (1 << 6);

  /**
   * For compatibility, we need explicitly enable the backup soSource. This flag conflicts with
   * {@link #SOLOADER_DISABLE_BACKUP_SOSOURCE}, you should only set one of them or none.
   */
  public static final int SOLOADER_EXPLICITLY_ENABLE_BACKUP_SOSOURCE = (1 << 7);

  /** Experiment ONLY: disable the fsync job in soSource */
  public static final int SOLOADER_DISABLE_FS_SYNC_JOB = (1 << 8);

  /**
   * Experiment ONLY: use the {@link SystemLoadWrapperSoSource} to instead of the
   * directApk/Application soSource. This could work on the apps w/o superpack or any other so file
   * compression.
   */
  public static final int SOLOADER_ENABLE_SYSTEMLOAD_WRAPPER_SOSOURCE = (1 << 9);

  /** Experiment ONLY: skip custom SoSources for base.apk and rely on System.loadLibrary calls. */
  public static final int SOLOADER_ENABLE_DIRECT_SPLIT_SO_SOURCE = (1 << 10);

  /** Experiment ONLY: skip DSONotFound error recovery for back up so source */
  public static final int SOLOADER_ENABLE_BACKUP_SOSOURCE_DSONOTFOUND_ERROR_RECOVERY = (1 << 11);

  public static final int SOLOADER_IMPLICIT_DEPENDENCIES = (1 << 12);

  /** Only initialize system SoSource */
  public static final int SOLOADER_SYSTEM_SOSOURCE_ONLY = (1 << 13);

  /**
   * Append a {@link SystemLoadWrapperSoSource} as the lowest-priority fallback. When no other
   * source can find a library, this delegates to {@code System.loadLibrary()} which uses the native
   * linker's namespace configuration — allowing it to resolve libraries in APEXes or other paths
   * that SoLoader's default directory sources don't search.
   */
  public static final int SOLOADER_ENABLE_SYSTEM_FALLBACK = (1 << 14);

  /**
   * Store the backup SoSource in device-protected storage so it is available during Direct Boot.
   * This flag has no effect unless the backup SoSource is enabled.
   */
  public static final int SOLOADER_USE_DEVICE_PROTECTED_STORAGE_FOR_BACKUP_SOSOURCE = (1 << 15);

  @GuardedBy("sSoSourcesLock")
  private static int sFlags;

  interface AppType {
    public static final int UNSET = 0;

    /** Normal user installed APP */
    public static final int THIRD_PARTY_APP = 1;

    /** The APP is installed in the device's system image. {@link ApplicationInfo#FLAG_SYSTEM} */
    public static final int SYSTEM_APP = 2;

    /**
     * The APP has been installed as an update to a built-in system application. {@link
     * ApplicationInfo#FLAG_UPDATED_SYSTEM_APP}
     */
    public static final int UPDATED_SYSTEM_APP = 3;
  }

  private static int sAppType = AppType.UNSET;

  /**
   * If provided during the init method, external libraries can provide their own implementation
   * ExternalSoMapping. This is useful for apps that want to use SoMerging in OSS.
   */
  @Nullable private static ExternalSoMapping externalSoMapping = null;

  static {
    boolean shouldSystrace = true;
    try {
      // Since we support API 21+, systrace is always available
    } catch (NoClassDefFoundError | UnsatisfiedLinkError e) {
      // In some test contexts, the Build class and/or some of its dependencies do not exist.
      shouldSystrace = false;
    }

    SYSTRACE_LIBRARY_LOADING = shouldSystrace;
  }

  public static void init(Context context, int flags) throws IOException {
    init(context, flags, null);
  }

  public static void init(Context context, int flags, @Nullable SoFileLoader soFileLoader)
      throws IOException {
    init(context, flags, soFileLoader, null);
  }

  /**
   * Initializes native code loading for this app; this class's other static facilities cannot be
   * used until this {@link #init} is called. This method is idempotent: calls after the first are
   * ignored.
   *
   * @param context application context
   * @param flags Zero or more of the SOLOADER_* flags
   * @param soFileLoader the custom {@link SoFileLoader}, you can implement your own loader
   * @param applicationInfoProvier
   * @throws IOException IOException
   */
  public static void init(
      Context context,
      int flags,
      @Nullable SoFileLoader soFileLoader,
      @Nullable Provider<ApplicationInfo> applicationInfoProvider)
      throws IOException {
    if (isInitialized()) {
      LogUtil.w(TAG, "SoLoader already initialized");
      return;
    }

    LogUtil.w(TAG, "Initializing SoLoader: " + flags);
    StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskWrites();
    try {
      isEnabled = initEnableConfig(context);
      if (isEnabled) {
        sAppType = getAppType(context);
        if ((flags & SOLOADER_EXPLICITLY_ENABLE_BACKUP_SOSOURCE) == 0
            && SysUtil.isSupportedDirectLoad(context, sAppType)) {
          // SoLoader doesn't need backup soSource if it supports directly loading .so file from APK
          flags |= SOLOADER_DISABLE_BACKUP_SOSOURCE;
        }

        initSoLoader(context, soFileLoader, applicationInfoProvider, flags);
        initSoSources(context, flags);
        LogUtil.v(TAG, "Init SoLoader delegate");
        NativeLoader.initIfUninitialized(new NativeLoaderToSoLoaderDelegate());
      } else {
        initDummySoSource();
        LogUtil.v(TAG, "Init System Loader delegate");
        NativeLoader.initIfUninitialized(new SystemDelegate());
      }
      LogUtil.w(TAG, "SoLoader initialized: " + flags);
    } finally {
      StrictMode.setThreadPolicy(oldPolicy);
    }
  }

  /**
   * Backward compatibility.
   *
   * @param context application context
   * @param nativeExopackage pass {@link #SOLOADER_ENABLE_EXOPACKAGE} as flags if true
   * @see <a href="https://buck.build/article/exopackage.html">What is exopackage?</a>
   */
  public static void init(Context context, boolean nativeExopackage) {
    try {
      init(context, nativeExopackage ? SOLOADER_ENABLE_EXOPACKAGE : 0, null);
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }
  }

  /**
   * Initializes native code loading for this app; this class's other static facilities cannot be
   * used until this {@link #init} is called. This method is idempotent: calls after the first are
   * ignored.
   *
   * <p>This is used only by apps that use SoMerging in OSS, such as React Native apps.
   *
   * @param context application context
   * @param externalSoMapping the custom {@link ExternalSoMapping} if the App is using SoMerging.
   */
  public static void init(Context context, @Nullable ExternalSoMapping externalSoMapping) {
    synchronized (SoLoader.class) {
      SoLoader.externalSoMapping = externalSoMapping;
    }
    try {
      init(context, 0);
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }
  }

  /**
   * Determine whether to enable soloader.
   *
   * @param context application context
   * @return Whether SoLoader is enabled
   */
  private static boolean initEnableConfig(Context context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
      return true;
    }

    if (SoLoader.externalSoMapping != null) {
      // This case is used by React Native apps that use SoMerging in OSS.
      // if the externalSoMapping has been provided, we don't need to check inside the Manifest
      // if SoLoader is enabled or not.
      return true;
    }

    final String name = "com.facebook.soloader.enabled";
    Bundle metaData = null;
    String packageName = null;
    try {
      packageName = context.getPackageName();
      // Check whether manifest explicitly enables/disables SoLoader for its package
      metaData =
          context
              .getPackageManager()
              .getApplicationInfo(packageName, PackageManager.GET_META_DATA)
              .metaData;
    } catch (Exception e) {
      // cannot happen, our package exists while app is running
      LogUtil.w(TAG, "Unexpected issue with package manager (" + packageName + ")", e);
    }

    // Keep the fallback value as true for backward compatibility.
    return (null == metaData || metaData.getBoolean(name, true));
  }

  private static void initSoSources(@Nullable Context context, int flags) throws IOException {
    if (sSoSources != null) {
      return;
    }

    sSoSourcesLock.writeLock().lock();
    try {
      // Double check that sSoSources wasn't initialized while waiting for the lock.
      if (sSoSources != null) {
        return;
      }

      sFlags = flags;

      ArrayList<SoSource> soSources = new ArrayList<>();
      final boolean isEnabledSystemLoadWrapper =
          (flags & SOLOADER_ENABLE_SYSTEMLOAD_WRAPPER_SOSOURCE) != 0;
      final boolean isEnabledBaseApkSplitSource =
          (flags & SOLOADER_ENABLE_DIRECT_SPLIT_SO_SOURCE) != 0;
      final boolean systemSoSourceOnly = (flags & SOLOADER_SYSTEM_SOSOURCE_ONLY) != 0;
      if (isEnabledSystemLoadWrapper) {
        addSystemLoadWrapperSoSource(context, soSources);
      } else if (systemSoSourceOnly) {
        addSystemLibSoSource(soSources);
      } else if (isEnabledBaseApkSplitSource) {
        addSystemLibSoSource(soSources);
        soSources.add(0, new DirectSplitSoSource("base"));
      } else {
        addSystemLibSoSource(soSources);

        // We can only proceed forward if we have a Context. The prominent case
        // where we don't have a Context is barebones dalvikvm instantiations. In
        // that case, the caller is responsible for providing a correct LD_LIBRARY_PATH.

        if (context != null) {
          // Prepend our own SoSource for our own DSOs.

          if ((flags & SOLOADER_ENABLE_EXOPACKAGE) != 0) {
            // Even for an exopackage, there might be some native libraries
            // packaged directly in the application (e.g. ASAN libraries alongside
            // a wrap.sh script [1]), so make sure we can load them.
            // [1] https://developer.android.com/ndk/guides/wrap-script
            addApplicationSoSource(soSources, getApplicationSoSourceFlags());
            LogUtil.d(TAG, "Adding exo package source: " + SO_STORE_NAME_MAIN);
            soSources.add(0, new ExoSoSource(context, SO_STORE_NAME_MAIN));
          } else {
            if (SysUtil.isSupportedDirectLoad(context, sAppType)) {
              addDirectApkSoSource(context, soSources);
            }
            addApplicationSoSource(soSources, getApplicationSoSourceFlags());
            addBackupSoSource(
                context,
                soSources,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                    && (flags & SOLOADER_IMPLICIT_DEPENDENCIES) != 0);
          }
        }
      }

      // Append system fallback as lowest-priority source if requested.
      if ((flags & SOLOADER_ENABLE_SYSTEM_FALLBACK) != 0) {
        soSources.add(new SystemLoadWrapperSoSource());
      }

      SoSource[] finalSoSources = soSources.toArray(new SoSource[soSources.size()]);
      int prepareFlags = makePrepareFlags();
      for (int i = finalSoSources.length; i-- > 0; ) {
        LogUtil.i(TAG, "Preparing SO source: " + finalSoSources[i]);

        if (SYSTRACE_LIBRARY_LOADING) {
          Api18TraceUtils.beginTraceSection(TAG, "_", finalSoSources[i].getClass().getSimpleName());
        }
        finalSoSources[i].prepare(prepareFlags);
        if (SYSTRACE_LIBRARY_LOADING) {
          Api18TraceUtils.endSection();
        }
      }
      sSoSources = finalSoSources;
      sSoSourcesVersion.getAndIncrement();
      LogUtil.d(TAG, "init finish: " + sSoSources.length + " SO sources prepared");
    } finally {
      sSoSourcesLock.writeLock().unlock();
    }
  }

  public static String getPrimaryAbi() {
    if (sPrimaryAbi == null) {
      throw new IllegalStateException("no primary abi");
    }
    return sPrimaryAbi;
  }

  public static Context getApplicationContext() {
    if (sApplicationContext == null) {
      throw new IllegalStateException("SoLoader.init() not yet called");
    }
    return sApplicationContext;
  }

  static ApplicationInfo getApplicationInfo() {
    if (sApplicationInfoProvider == null) {
      throw new IllegalStateException("SoLoader not initialized");
    }
    return sApplicationInfoProvider.get();
  }

  private static void initDummySoSource() {
    if (sSoSources != null) {
      return;
    }

    sSoSourcesLock.writeLock().lock();
    try {
      // Double check that sSoSources wasn't initialized while waiting for the lock.
      if (sSoSources != null) {
        return;
      }
      sSoSources = new SoSource[0];
    } finally {
      sSoSourcesLock.writeLock().unlock();
    }
  }

  private static int getApplicationSoSourceFlags() {
    switch (sAppType) {
      case AppType.THIRD_PARTY_APP:
        return 0;
      case AppType.SYSTEM_APP:
      case AppType.UPDATED_SYSTEM_APP:
        return DirectorySoSource.RESOLVE_DEPENDENCIES;
      default:
        throw new RuntimeException("Unsupported app type, we should not reach here");
    }
  }

  /** Add DirectApk SoSources for disabling android:extractNativeLibs */
  private static void addDirectApkSoSource(Context context, ArrayList<SoSource> soSources) {
    DirectApkSoSource directApkSoSource = new DirectApkSoSource(context);
    LogUtil.d(TAG, "validating/adding directApk source: " + directApkSoSource.toString());
    if (directApkSoSource.isValid()) {
      soSources.add(0, directApkSoSource);
    }
  }

  /** Add a DirectorySoSource for the application's nativeLibraryDir . */
  private static void addApplicationSoSource(ArrayList<SoSource> soSources, int flags) {

    // Since we support API 21+, Bionic's built-in resolver suffices for dependency resolution.

    SoSource applicationSoSource = new ApplicationSoSource(sApplicationContext, flags);
    LogUtil.d(TAG, "Adding application source: " + applicationSoSource.toString());
    soSources.add(0, applicationSoSource);
  }

  /** Add the SoSources for recovering the dso if the file is corrupted or missed */
  @SuppressLint("CatchGeneralException")
  private static void addBackupSoSource(
      Context context, ArrayList<SoSource> soSources, boolean implicitDependencies)
      throws IOException {
    if ((sFlags & SOLOADER_DISABLE_BACKUP_SOSOURCE) != 0) {
      return;
    }

    BackupSoSource backupSoSource;
    if ((sFlags & SOLOADER_USE_DEVICE_PROTECTED_STORAGE_FOR_BACKUP_SOSOURCE) != 0
        && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      Context backupContext =
          context.isDeviceProtectedStorage()
              ? context
              : context.createDeviceProtectedStorageContext();
      backupSoSource =
          new BackupSoSource(
              backupContext,
              backupContext.getApplicationInfo(),
              new File(backupContext.getDataDir(), SO_STORE_NAME_MAIN),
              !implicitDependencies,
              true);
    } else {
      backupSoSource = new BackupSoSource(context, SO_STORE_NAME_MAIN, !implicitDependencies);
    }
    soSources.add(0, backupSoSource);
  }

  /**
   * Add SoSource objects for each of the system library directories.
   *
   * @param soSources target soSource list
   */
  private static void addSystemLibSoSource(ArrayList<SoSource> soSources) {
    String systemLibPaths =
        SysUtil.is64Bit()
            ? "/system/lib64:/system_ext/lib64:/vendor/lib64"
            : "/system/lib:/system_ext/lib:/vendor/lib";

    String LD_LIBRARY_PATH = System.getenv("LD_LIBRARY_PATH");
    if (LD_LIBRARY_PATH != null && !LD_LIBRARY_PATH.equals("")) {
      systemLibPaths = LD_LIBRARY_PATH + ":" + systemLibPaths;
    }

    final Set<String> libPathSet = new HashSet<>(Arrays.asList(systemLibPaths.split(":")));
    for (String libPath : libPathSet) {
      // Don't pass DirectorySoSource.RESOLVE_DEPENDENCIES for directories we find on
      // LD_LIBRARY_PATH: Bionic's dynamic linker is capable of correctly resolving dependencies
      // these libraries have on each other, so doing that ourselves would be a waste.
      LogUtil.d(TAG, "adding system library source: " + libPath);
      File systemSoDirectory = new File(libPath);
      soSources.add(new DirectorySoSource(systemSoDirectory, DirectorySoSource.ON_LD_LIBRARY_PATH));
    }
  }

  private static void addSystemLoadWrapperSoSource(Context context, ArrayList<SoSource> soSources) {
    SystemLoadWrapperSoSource systemLoadWrapperSoSource = new SystemLoadWrapperSoSource();
    LogUtil.d(TAG, "adding systemLoadWrapper source: " + systemLoadWrapperSoSource);
    soSources.add(0, systemLoadWrapperSoSource);
  }

  private static int makePrepareFlags() {
    int prepareFlags = 0;
    // ensure the write lock is being held to protect sFlags
    // this is used when preparing new SoSources in the list.
    sSoSourcesLock.writeLock().lock();
    try {
      if ((sFlags & SOLOADER_ALLOW_ASYNC_INIT) != 0) {
        prepareFlags |= SoSource.PREPARE_FLAG_ALLOW_ASYNC_INIT;
      }
      if ((sFlags & SOLOADER_DISABLE_FS_SYNC_JOB) != 0) {
        prepareFlags |= SoSource.PREPARE_FLAG_DISABLE_FS_SYNC_JOB;
      }
      if ((sFlags & SOLOADER_EXPLICITLY_ENABLE_BACKUP_SOSOURCE) == 0) {
        prepareFlags |= SoSource.PREPARE_FLAG_SKIP_BACKUP_SO_SOURCE;
      }
      return prepareFlags;
    } finally {
      sSoSourcesLock.writeLock().unlock();
    }
  }

  private static int makeRecoveryFlags(int flags) {
    int recoveryFlags = 0;
    if ((flags & SOLOADER_ENABLE_BACKUP_SOSOURCE_DSONOTFOUND_ERROR_RECOVERY) != 0) {
      recoveryFlags |= RecoveryStrategy.FLAG_ENABLE_DSONOTFOUND_ERROR_RECOVERY_FOR_BACKUP_SO_SOURCE;
    }
    return recoveryFlags;
  }

  private static synchronized void initSoLoader(
      @Nullable Context context,
      @Nullable SoFileLoader soFileLoader,
      @Nullable Provider<ApplicationInfo> applicationInfoProvider,
      int flags) {
    if (context != null) {
      Context applicationContext = context.getApplicationContext();

      if (applicationContext == null) {
        applicationContext = context;
        LogUtil.w(
            TAG,
            "context.getApplicationContext returned null, holding reference to original context."
                + "ApplicationSoSource fallbacks to: "
                + context.getApplicationInfo().nativeLibraryDir);
      }

      sApplicationContext = applicationContext;
      sApplicationInfoProvider =
          applicationInfoProvider == null
              ? new SimpleApplicationInfoProvider(applicationContext)
              : applicationInfoProvider;
      sPrimaryAbi = SysUtil.getPrimaryAbi(applicationContext.getApplicationInfo());
      sRecoveryStrategyFactory =
          new DefaultRecoveryStrategyFactory(makeRecoveryFlags(flags), sApplicationInfoProvider);
    }

    if (soFileLoader == null && sSoFileLoader != null) {
      return;
    }

    if (soFileLoader == null) {
      soFileLoader = new SoFileLoaderImpl();
    }
    if (!(soFileLoader instanceof InstrumentedSoFileLoader)) {
      soFileLoader = new InstrumentedSoFileLoader(soFileLoader);
    }

    sSoFileLoader = soFileLoader;
  }

  private static int getAppType(@Nullable Context context) {
    if (sAppType != AppType.UNSET) {
      return sAppType;
    }
    if (context == null) {
      LogUtil.d(TAG, "context is null, fallback to THIRD_PARTY_APP appType");
      return AppType.THIRD_PARTY_APP;
    }

    final int type;
    final ApplicationInfo appInfo = context.getApplicationInfo();

    if ((appInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
      type = AppType.THIRD_PARTY_APP;
    } else if ((appInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) {
      type = AppType.UPDATED_SYSTEM_APP;
    } else {
      type = AppType.SYSTEM_APP;
    }
    LogUtil.d(TAG, "ApplicationInfo.flags is: " + appInfo.flags + " appType is: " + type);
    return type;
  }

  /** Turn shared-library loading into a no-op. Useful in special circumstances. */
  public static void setInTestMode() {
    TestOnlyUtils.setInTestMode();
  }

  public static synchronized boolean isInTestMode() {
    return sTestMode;
  }

  /** Make shared-library loading delegate to the system. Useful for tests. */
  public static void deinitForTest() {
    TestOnlyUtils.deinit();
  }

  @NotThreadSafe
  static class TestOnlyUtils {
    /* Set so sources. Useful for tests. */
    /* package */ static void setSoSources(@Nullable SoSource[] sources) {
      sSoSourcesLock.writeLock().lock();
      try {
        sSoSources = sources;
        sSoSourcesVersion.getAndIncrement();
      } finally {
        sSoSourcesLock.writeLock().unlock();
      }
    }

    /**
     * Set so file loader. <br>
     * N.B. <b>ONLY FOR TESTS</b>. It has read/write race with {@code
     * SoLoader.sSoFileLoader.load(String, int)} in {@link DirectorySoSource#loadLibraryFrom}
     *
     * @param loader {@link SoFileLoader}
     */
    /* package */ static void setSoFileLoader(SoFileLoader loader) {
      sSoFileLoader = loader;
    }

    /** Reset internal status. Only for tests. */
    /* package */ static void resetStatus() {
      synchronized (SoLoader.class) {
        sLoadedLibraries.clear();
        sLoadedAndJniInvoked.clear();
        sLoadingLibraries.clear();
        sSoFileLoader = null;
        sTestMode = false;
        sApplicationContext = null;
        sPrimaryAbi = null;
        sApplicationInfoProvider = null;
        sRecoveryStrategyFactory = null;
        ObserverHolder.resetObserversForTestsOnly();
      }
      setSoSources(null);
    }

    /* package */ static void setContext(Context context) {
      synchronized (SoLoader.class) {
        sApplicationContext = context;
        try {
          sPrimaryAbi = SysUtil.getPrimaryAbi(context.getApplicationInfo());
        } catch (IllegalStateException e) {
          // In JVM test environments Build.SUPPORTED_ABIS is empty, fall back.
          sPrimaryAbi = "x86";
        }
        sApplicationInfoProvider = new SimpleApplicationInfoProvider(context);
      }
    }

    /* package */ static void setInTestMode() {
      setSoSources(new SoSource[] {new NoopSoSource()});
      synchronized (SoLoader.class) {
        sTestMode = true;
      }
    }

    /* package */ static void deinit() {
      synchronized (SoLoader.class) {
        sTestMode = false;
      }
      setSoSources(null);
    }
  }

  /**
   * Provide a wrapper object for calling {@link System#loadLibrary}. This is useful for controlling
   * which ClassLoader libraries are loaded into.
   *
   * @param wrapper the wrapper you wanna set
   */
  public static void setSystemLoadLibraryWrapper(SystemLoadLibraryWrapper wrapper) {
    sSystemLoadLibraryWrapper = wrapper;
  }

  public static final class WrongAbiError extends UnsatisfiedLinkError {
    WrongAbiError(Throwable cause, String machine) {
      super(
          "APK was built for a different platform. Supported ABIs: "
              + Arrays.toString(SysUtil.getSupportedAbis())
              + " error: "
              + machine);
      initCause(cause);
    }
  }

  /**
   * Gets the full path of a library.
   *
   * @param libName the library file name, including the prefix and extension.
   * @return the full path of the library, or null if it is not found in none of the SoSources.
   * @throws IOException if there is an error calculating the canonical path of libName
   */
  public static @Nullable String getLibraryPath(String libName) throws IOException {
    String libPath = null;

    sSoSourcesLock.readLock().lock();
    try {
      if (sSoSources != null) {
        for (int i = 0; libPath == null && i < sSoSources.length; ++i) {
          SoSource currentSource = sSoSources[i];
          libPath = currentSource.getLibraryPath(libName);
        }
      }
    } finally {
      sSoSourcesLock.readLock().unlock();
    }

    return libPath;
  }

  public static SoSource[] cloneSoSources() {
    sSoSourcesLock.readLock().lock();
    try {
      return (sSoSources == null) ? new SoSource[0] : sSoSources.clone();
    } finally {
      sSoSourcesLock.readLock().unlock();
    }
  }

  /**
   * Gets the dependencies of a library.
   *
   * @param libName the library file name, including the prefix and extension.
   * @return An array naming the dependencies of the library, or null if it is not found in any
   *     SoSources
   * @throws IOException if there is an error reading libName
   */
  public static @Nullable String[] getLibraryDependencies(String libName) throws IOException {
    String[] deps = null;

    sSoSourcesLock.readLock().lock();
    try {
      if (sSoSources != null) {
        for (int i = 0; deps == null && i < sSoSources.length; ++i) {
          SoSource currentSource = sSoSources[i];
          deps = currentSource.getLibraryDependencies(libName);
        }
      }
    } finally {
      sSoSourcesLock.readLock().unlock();
    }

    return deps;
  }

  /**
   * Returns the so file for the specified library. Returns null if the library does not exist or if
   * it's not backed by a file.
   *
   * @param shortName Name of library to find, without "lib" prefix or ".so" suffix
   * @return the File object of the so file
   */
  public static @Nullable File getSoFile(String shortName) {
    String mergedLibName;
    if (externalSoMapping != null) {
      mergedLibName = externalSoMapping.mapLibName(shortName);
    } else {
      mergedLibName = MergedSoMapping.mapLibName(shortName);
    }
    String soName = mergedLibName != null ? mergedLibName : shortName;
    String mappedName = System.mapLibraryName(soName);

    sSoSourcesLock.readLock().lock();
    try {
      if (sSoSources != null) {
        for (int i = 0; i < sSoSources.length; ++i) {
          SoSource currentSource = sSoSources[i];
          try {
            File soFile = currentSource.getSoFileByName(mappedName);
            if (soFile != null) {
              return soFile;
            }
          } catch (IOException e) {
            // Failed to get the file, let's skip this so source.
          }
        }
      }
    } finally {
      sSoSourcesLock.readLock().unlock();
    }

    return null;
  }

  // same as loadLibrary, but the given library-name must not be a string constant; as a result, a
  // tool like Redex cannot determine which library is being referenced, possibly leading to the
  // removal of any such indirectly referenced library.
  public static boolean loadLibraryUnsafe(String shortName) {
    return loadLibrary(shortName);
  }

  public static boolean loadLibrary(String shortName) {
    return isEnabled ? loadLibrary(shortName, 0) : NativeLoader.loadLibrary(shortName);
  }

  // same as loadLibrary, but the given library-name must not be a string constant; as a result, a
  // tool like Redex cannot determine which library is being referenced, possibly leading to the
  // removal of any such indirectly referenced library.
  public static boolean loadLibraryUnsafe(String shortName, int loadFlags) {
    return loadLibrary(shortName, loadFlags);
  }

  /**
   * Load a shared library, initializing any JNI binding it contains.
   *
   * @param shortName Name of library to find, without "lib" prefix or ".so" suffix
   * @param loadFlags Control flags for the loading behavior. See available flags under {@link
   *     SoSource} (LOAD_FLAG_XXX).
   * @return Whether the library was loaded as a result of this call (true), or was already loaded
   *     through a previous call to SoLoader (false).
   */
  public static boolean loadLibrary(String shortName, int loadFlags) throws UnsatisfiedLinkError {
    Boolean needsLoad = loadLibraryOnNonAndroid(shortName);
    if (needsLoad != null) {
      return needsLoad;
    }

    if (Thread.currentThread() == Looper.getMainLooper().getThread()) {
      LogUtil.w(TAG, "Loading " + shortName + " on the main thread");
    }

    if (!isEnabled) {
      return NativeLoader.loadLibrary(shortName);
    }

    // This is to account for the fact that we want to load .so files from the apk itself when it is
    // a system app.
    if ((sAppType == AppType.SYSTEM_APP || sAppType == AppType.UPDATED_SYSTEM_APP)
        && sSystemLoadLibraryWrapper != null) {
      sSystemLoadLibraryWrapper.loadLibrary(shortName);
      return true;
    }

    return loadLibraryOnAndroid(shortName, loadFlags);
  }

  @SuppressLint({"CatchGeneralException", "EmptyCatchBlock"})
  private static boolean loadLibraryOnAndroid(String shortName, int loadFlags) {
    @Nullable Throwable failure = null;
    String mergedLibName;
    if (externalSoMapping != null) {
      mergedLibName = externalSoMapping.mapLibName(shortName);
    } else {
      mergedLibName = MergedSoMapping.mapLibName(shortName);
    }
    String soName = mergedLibName != null ? mergedLibName : shortName;
    ObserverHolder.onLoadLibraryStart(shortName, mergedLibName, loadFlags);
    boolean wasLoaded = false;
    try {
      wasLoaded =
          loadLibraryBySoName(
              System.mapLibraryName(soName), shortName, mergedLibName, loadFlags, null);
      return wasLoaded;
    } catch (Throwable t) {
      failure = t;
      throw t;
    } finally {
      ObserverHolder.onLoadLibraryEnd(failure, wasLoaded);
    }
  }

  private static @Nullable Boolean loadLibraryOnNonAndroid(String shortName) {
    if (sSoSources == null) {
      sSoSourcesLock.readLock().lock();
      try {
        if (sSoSources == null) {
          // This should never happen during normal operation,
          // but if we're running in a non-Android environment,
          // fall back to System.loadLibrary.
          if ("http://www.android.com/".equals(System.getProperty("java.vendor.url"))) {
            // This will throw.
            assertInitialized();
          } else {
            // Not on an Android system.  Ask the JVM to load for us.
            synchronized (SoLoader.class) {
              boolean needsLoad = !sLoadedLibraries.contains(shortName);
              if (needsLoad) {
                if (sSystemLoadLibraryWrapper != null) {
                  sSystemLoadLibraryWrapper.loadLibrary(shortName);
                } else {
                  System.loadLibrary(shortName);
                }
              }
              return needsLoad;
            }
          }
        }
      } finally {
        sSoSourcesLock.readLock().unlock();
      }
    }
    return null;
  }

  /**
   * Load a library that is a dependency of another library by name. A dedicated entry point allows
   * SoLoader to optimise recursive calls by assuming that the current platform is Android and merge
   * map does not need to be consulted.
   *
   * @param soName Name of the library to load, as extracted from dynamic section.
   * @param loadFlags
   * @param oldPolicy
   */
  @SuppressLint({"CatchGeneralException", "EmptyCatchBlock"})
  /* package */ static void loadDependency(
      String soName, int loadFlags, StrictMode.ThreadPolicy oldPolicy) {
    @Nullable Throwable failure = null;
    ObserverHolder.onLoadDependencyStart(soName, loadFlags);
    boolean wasLoaded = false;
    try {
      wasLoaded =
          loadLibraryBySoNameImpl(
              soName,
              null,
              null,
              loadFlags | SoSource.LOAD_FLAG_ALLOW_IMPLICIT_PROVISION,
              oldPolicy);
    } catch (Throwable t) {
      failure = t;
      throw t;
    } finally {
      ObserverHolder.onLoadDependencyEnd(failure, wasLoaded);
    }
  }

  private static boolean loadLibraryBySoName(
      String soName,
      @Nullable String shortName,
      @Nullable String mergedLibName,
      int loadFlags,
      @Nullable StrictMode.ThreadPolicy oldPolicy) {
    @Nullable RecoveryStrategy recovery = null;
    while (true) {
      try {
        return loadLibraryBySoNameImpl(soName, shortName, mergedLibName, loadFlags, oldPolicy);
      } catch (UnsatisfiedLinkError e) {
        recovery = recover(soName, e, recovery);
      }
    }
  }

  @SuppressLint("CatchGeneralException")
  private static RecoveryStrategy recover(
      String soName, UnsatisfiedLinkError e, @Nullable RecoveryStrategy recovery) {
    LogUtil.w(TAG, "Running a recovery step for " + soName + " due to " + e.toString());
    sSoSourcesLock.writeLock().lock();
    try {
      if (recovery == null) {
        recovery = getRecoveryStrategy();
        if (recovery == null) {
          LogUtil.w(TAG, "No recovery strategy");
          throw e;
        }
      }
      if (recoverLocked(e, recovery)) {
        sSoSourcesVersion.getAndIncrement();
        return recovery;
      }
    } catch (NoBaseApkException noBaseApkException) {
      // If we failed during recovery, we only want to throw the recovery exception for the case
      // when the base APK path does not exist, everything else should preserve the initial
      // error.
      LogUtil.e(TAG, "Base APK not found during recovery", noBaseApkException);
      throw noBaseApkException;
    } catch (Exception recoveryException) {
      LogUtil.e(
          TAG,
          "Got an exception during recovery, will throw the initial error instead",
          recoveryException);
      throw e;
    } finally {
      sSoSourcesLock.writeLock().unlock();
    }

    // No recovery mechanism worked, throwing initial error
    LogUtil.w(TAG, "Failed to recover");
    throw e;
  }

  @SuppressLint({"CatchGeneralException", "EmptyCatchBlock"})
  private static boolean recoverLocked(UnsatisfiedLinkError e, RecoveryStrategy recovery) {
    @Nullable Throwable failure = null;
    ObserverHolder.onRecoveryStart(recovery);
    try {
      return recovery.recover(e, sSoSources);
    } catch (Throwable t) {
      failure = t;
      throw t;
    } finally {
      ObserverHolder.onRecoveryEnd(failure);
    }
  }

  private static synchronized @Nullable RecoveryStrategy getRecoveryStrategy() {
    return sRecoveryStrategyFactory == null ? null : sRecoveryStrategyFactory.get();
  }

  /* protected */ static synchronized void setRecoveryStrategyFactory(
      RecoveryStrategyFactory factory) {
    sRecoveryStrategyFactory = factory;
  }

  private static boolean loadLibraryBySoNameImpl(
      String soName,
      @Nullable String shortName,
      @Nullable String mergedLibName,
      int loadFlags,
      @Nullable StrictMode.ThreadPolicy oldPolicy) {
    // As an optimization, avoid taking locks if the library has already loaded. Without locks this
    // does not provide 100% coverage (e.g. another thread may currently be loading or initializing
    // the library), so we'll need to check again with locks held, below.
    if (!TextUtils.isEmpty(shortName) && sLoadedAndJniInvoked.contains(shortName)) {
      return false;
    }
    if (sLoadedLibraries.contains(soName) && mergedLibName == null) {
      return false;
    }

    // LoadingLibLock is used to ensure that doLoadLibraryBySoName and its corresponding JniOnload
    // are only executed once per library. It also guarantees that concurrent calls to loadLibrary
    // for the same library do not return until both its load and JniOnLoad have completed.
    Object loadingLibLock;
    Object invokingJniLock;
    boolean loaded = false;
    synchronized (SoLoader.class) {
      if (sLoadedLibraries.contains(soName)) {
        if (mergedLibName == null) {
          // Not a merged lib, no need to init
          return false;
        }
        loaded = true;
      }
      if (sLoadingLibraries.containsKey(soName)) {
        loadingLibLock = sLoadingLibraries.get(soName);
      } else {
        loadingLibLock = new Object();
        sLoadingLibraries.put(soName, loadingLibLock);
      }
      if (sInvokingJniForLibrary.containsKey(shortName)) {
        invokingJniLock = sInvokingJniForLibrary.get(shortName);
      } else {
        invokingJniLock = new Object();
        sInvokingJniForLibrary.put(shortName, invokingJniLock);
      }
    }

    // Note that both doLoadLibraryBySoName and invokeJniOnload (below) may re-enter loadLibrary
    // (or loadLibraryBySoNameImpl), recursively acquiring additional library and soSource locks.
    //
    // To avoid bi-directional lock usage (threadA takes loadingLibLock then sSoSourcesLock, threadB
    // takes sSoSourcesLock then loadingLibLock) and potential deadlock as this method recursively
    // calls loadLibrary, we must acquire sSoSourcesLock first.
    sSoSourcesLock.readLock().lock();
    try {
      synchronized (loadingLibLock) {
        if (!loaded) {
          if (sLoadedLibraries.contains(soName)) {
            // Library was successfully loaded by other thread while we waited
            if (mergedLibName == null) {
              // Not a merged lib, no need to init
              return false;
            }
            loaded = true;
          }
          // Else, load was not successful on other thread. We will try in this one.

          if (!loaded) {
            try {
              LogUtil.d(TAG, "About to load: " + soName);
              doLoadLibraryBySoName(soName, shortName, loadFlags, oldPolicy);
            } catch (UnsatisfiedLinkError ex) {
              String message = ex.getMessage();
              if (message != null && message.contains("unexpected e_machine:")) {
                String machine_msg =
                    message.substring(message.lastIndexOf("unexpected e_machine:"));
                throw new WrongAbiError(ex, machine_msg);
              }
              throw ex;
            }
            LogUtil.d(TAG, "Loaded: " + soName);
            sLoadedLibraries.add(soName);
          }
        }
      }

      synchronized (invokingJniLock) {
        if ((loadFlags & SOLOADER_SKIP_MERGED_JNI_ONLOAD) == 0 && mergedLibName != null) {
          // MergedSoMapping#invokeJniOnload does not necessarily handle concurrent nor redundant
          // invocation. sLoadedAndJniInvoked is used in conjunction with loadingLibLock to
          // ensure one invocation per library.
          boolean wasAlreadyJniInvoked =
              !TextUtils.isEmpty(shortName) && sLoadedAndJniInvoked.contains(shortName);
          if (!wasAlreadyJniInvoked) {
            if (SYSTRACE_LIBRARY_LOADING && externalSoMapping == null) {
              Api18TraceUtils.beginTraceSection("MergedSoMapping.invokeJniOnload[", shortName, "]");
            }
            try {
              LogUtil.d(
                  TAG,
                  "About to invoke JNI_OnLoad for merged library "
                      + shortName
                      + ", which was merged into "
                      + soName);
              if (externalSoMapping != null) {
                externalSoMapping.invokeJniOnload(shortName);
              } else {
                MergedSoMapping.invokeJniOnload(shortName);
              }
              sLoadedAndJniInvoked.add(shortName);
            } catch (UnsatisfiedLinkError e) {
              // If you are seeing this exception, first make sure your library sets
              // allow_jni_merging=True.  Trying to merge a library without that
              // will trigger this error.  If that's already in place, you're probably
              // not defining JNI_OnLoad.  Calling SoLoader.loadLibrary on a library
              // that doesn't define JNI_OnLoad is a no-op when that library is not merged.
              // Once you enable merging, it throws an UnsatisfiedLinkError.
              // There are three main reasons a library might not define JNI_OnLoad,
              // and the solution depends on which case you have.
              // - You might be using implicit registration (native methods defined like
              //   `Java_com_facebook_Foo_bar(JNIEnv* env)`).  This is not safe on Android
              //   https://fb.workplace.com/groups/442333009148653/permalink/651212928260659/
              //   and is not compatible with FBJNI.  Stop doing it.  Use FBJNI registerNatives.
              // - You might have a C++-only library with no JNI bindings and no static
              //   initializers with side-effects.  You can just delete the loadLibrary call.
              // - You might have a C++-only library that needs to be loaded explicitly because
              //   it has static initializers whose side-effects are needed.  In that case,
              //   pass the SOLOADER_SKIP_MERGED_JNI_ONLOAD flag to loadLibrary.
              throw new RuntimeException(
                  "Failed to call JNI_OnLoad from '"
                      + shortName
                      + "', which has been merged into '"
                      + soName
                      + "'.  See comment for details.",
                  e);
            } finally {
              if (SYSTRACE_LIBRARY_LOADING && externalSoMapping == null) {
                Api18TraceUtils.endSection();
              }
            }
          }
        }
      }
    } finally {
      sSoSourcesLock.readLock().unlock();
    }
    return !loaded;
  }

  /**
   * Unpack library and its dependencies, returning the location of the unpacked library file. All
   * non-system dependencies of the given library will either be on LD_LIBRARY_PATH or will be in
   * the same directory as the returned File.
   *
   * @param shortName Name of library to find, without "lib" prefix or ".so" suffix
   * @return Unpacked DSO location
   */
  public static File unpackLibraryAndDependencies(String shortName) throws UnsatisfiedLinkError {
    assertInitialized();
    try {
      return unpackLibraryBySoName(System.mapLibraryName(shortName));
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }
  }

  private static void doLoadLibraryBySoName(
      String soName,
      @Nullable String shortName,
      int loadFlags,
      @Nullable StrictMode.ThreadPolicy oldPolicy)
      throws UnsatisfiedLinkError {
    sSoSourcesLock.readLock().lock();
    try {
      if (sSoSources == null) {
        LogUtil.e(TAG, "Could not load: " + soName + " because SoLoader is not initialized");
        throw new UnsatisfiedLinkError(
            "SoLoader not initialized, couldn't find DSO to load: " + soName);
      }
    } finally {
      sSoSourcesLock.readLock().unlock();
    }

    // This way, we set the thread policy only one per loadLibrary no matter how many
    // dependencies we load.  Each call to StrictMode.allowThreadDiskWrites allocates.
    boolean restoreOldPolicy = false;
    if (oldPolicy == null) {
      oldPolicy = StrictMode.allowThreadDiskReads();
      restoreOldPolicy = true;
    }

    if (SYSTRACE_LIBRARY_LOADING) {
      if (shortName != null) {
        Api18TraceUtils.beginTraceSection("SoLoader.loadLibrary[", shortName, "]");
      }
      Api18TraceUtils.beginTraceSection("SoLoader.loadLibrary[", soName, "]");
    }

    try {
      sSoSourcesLock.readLock().lock();
      try {
        for (SoSource source : sSoSources) {
          if (loadLibraryFromSoSource(source, soName, loadFlags, oldPolicy)) {
            return;
          }
        }
        // Load failure has not been caused by dependent libraries, the library itself was not
        // found. Print the sources and current native library directory
        throw SoLoaderDSONotFoundError.create(soName, sApplicationContext, sSoSources);
      } catch (IOException err) {
        // General SoLoaderULError
        SoLoaderULError error = new SoLoaderULError(soName, err.toString());
        error.initCause(err);
        throw error;
      } finally {
        sSoSourcesLock.readLock().unlock();
      }
    } finally {
      if (SYSTRACE_LIBRARY_LOADING) {
        if (shortName != null) {
          Api18TraceUtils.endSection();
        }
        Api18TraceUtils.endSection();
      }

      if (restoreOldPolicy) {
        StrictMode.setThreadPolicy(oldPolicy);
      }
    }
  }

  @SuppressLint({"CatchGeneralException", "EmptyCatchBlock", "MissingSoLoaderLibrary"})
  private static boolean loadLibraryFromSoSource(
      SoSource source, String name, int loadFlags, StrictMode.ThreadPolicy oldPolicy)
      throws IOException {
    @Nullable Throwable failure = null;
    ObserverHolder.onSoSourceLoadLibraryStart(source);
    try {
      return source.loadLibrary(name, loadFlags, oldPolicy) != SoSource.LOAD_RESULT_NOT_FOUND;
    } catch (Throwable t) {
      failure = t;
      throw t;
    } finally {
      ObserverHolder.onSoSourceLoadLibraryEnd(failure);
    }
  }

  /* package */ static File unpackLibraryBySoName(String soName) throws IOException {
    sSoSourcesLock.readLock().lock();
    try {
      for (SoSource soSource : sSoSources) {
        File unpacked = soSource.unpackLibrary(soName);
        if (unpacked != null) {
          return unpacked;
        }
      }
    } finally {
      sSoSourcesLock.readLock().unlock();
    }

    throw new FileNotFoundException(soName);
  }

  private static void assertInitialized() {
    if (!isInitialized()) {
      throw new IllegalStateException("SoLoader.init() not called yet");
    }
  }

  public static boolean isInitialized() {
    if (sSoSources != null) {
      return true;
    }
    sSoSourcesLock.readLock().lock();
    try {
      return sSoSources != null;
    } finally {
      sSoSourcesLock.readLock().unlock();
    }
  }

  public static int getSoSourcesVersion() {
    return sSoSourcesVersion.get();
  }

  /**
   * Add a new source of native libraries. SoLoader consults the new source *before* any
   * currently-installed source.
   *
   * @param extraSoSource The SoSource to install
   * @throws IOException IOException
   */
  public static void prependSoSource(SoSource extraSoSource) throws IOException {
    sSoSourcesLock.writeLock().lock();
    try {
      assertInitialized();
      extraSoSource.prepare(makePrepareFlags());
      SoSource[] newSoSources = new SoSource[sSoSources.length + 1];
      newSoSources[0] = extraSoSource;
      System.arraycopy(sSoSources, 0, newSoSources, 1, sSoSources.length);
      sSoSources = newSoSources;
      sSoSourcesVersion.getAndIncrement();
      LogUtil.d(TAG, "Prepended to SO sources: " + extraSoSource);
    } finally {
      sSoSourcesLock.writeLock().unlock();
    }
  }

  /**
   * Add a new source of native libraries. SoLoader consults the new source *after* any
   * currently-installed source.
   *
   * @param extraSoSource The SoSource to install
   * @throws IOException IOException
   */
  public static void appendSoSource(SoSource extraSoSource) throws IOException {
    sSoSourcesLock.writeLock().lock();
    try {
      assertInitialized();
      extraSoSource.prepare(makePrepareFlags());
      SoSource[] newSoSources = new SoSource[sSoSources.length + 1];
      System.arraycopy(sSoSources, 0, newSoSources, 0, sSoSources.length);
      newSoSources[sSoSources.length] = extraSoSource;
      sSoSources = newSoSources;
      sSoSourcesVersion.getAndIncrement();
      LogUtil.d(TAG, "Appended to SO sources: " + extraSoSource);
    } finally {
      sSoSourcesLock.writeLock().unlock();
    }
  }

  /**
   * Retrieve an LD_LIBRARY_PATH value suitable for using the native linker to resolve our shared
   * libraries.
   *
   * @return the LD_LIBRARY_PATH in string
   */
  public static String makeLdLibraryPath() {
    sSoSourcesLock.readLock().lock();
    try {
      assertInitialized();
      ArrayList<String> pathElements = new ArrayList<>();
      SoSource[] soSources = sSoSources;
      if (soSources != null) {
        for (SoSource soSource : soSources) {
          soSource.addToLdLibraryPath(pathElements);
        }
      }
      String joinedPaths = TextUtils.join(":", pathElements);
      LogUtil.d(TAG, "makeLdLibraryPath final path: " + joinedPaths);
      return joinedPaths;
    } finally {
      sSoSourcesLock.readLock().unlock();
    }
  }

  /**
   * This function ensure that every SoSources Abi is supported for at least one abi in
   * SysUtil.getSupportedAbis
   *
   * @return true if all SoSources have their Abis supported
   */
  public static boolean areSoSourcesAbisSupported() {
    sSoSourcesLock.readLock().lock();
    try {
      if (sSoSources == null) {
        return false;
      }

      String[] supportedAbis = SysUtil.getSupportedAbis();
      for (SoSource soSource : sSoSources) {
        String[] soSourceAbis = soSource.getSoSourceAbis();
        for (String soSourceAbi : soSourceAbis) {
          boolean soSourceSupported = false;
          for (int k = 0; k < supportedAbis.length && !soSourceSupported; ++k) {
            soSourceSupported = soSourceAbi.equals(supportedAbis[k]);
          }
          if (!soSourceSupported) {
            LogUtil.e(TAG, "abi not supported: " + soSourceAbi);
            return false;
          }
        }
      }

      return true;
    } finally {
      sSoSourcesLock.readLock().unlock();
    }
  }

  /**
   * Enables the use of a deps file to fetch the native library dependencies to avoid reading them
   * from the ELF files. The file is expected to be in the APK in assets/native_deps.txt. Returns
   * true on success, false on failure. On failure, dependencies will be read from ELF files instead
   * of the deps file.
   *
   * @param context - Application context, used to find native deps file in APK
   * @param async - If true, initialization will occur in a background thread and we library loading
   *     will wait for initialization to complete.
   * @param extractToDisk - If true, the native deps file will be extract from the APK and written
   *     to disk. This can be useful when the file is compressed in the APK, since we can prevent
   *     decompressing the file every time.
   * @return True if initialization succeeded, false otherwise. Always returns true if async is
   *     true.
   */
  public static boolean useDepsFile(Context context, boolean useAssetManagerForNativeDepsFile) {
    if (!useAssetManagerForNativeDepsFile) {
      return NativeDeps.useDepsFile(context);
    }

    return NativeDeps.useDepsFileWithAssetManager(context);
  }

  /**
   * Returns count of already loaded so libraries
   *
   * @return number of loaded libraries
   */
  public static int getLoadedLibrariesCount() {
    return sLoadedLibraries.size();
  }

  /**
   * Whether SoLoader has loaded the library with this soname (e.g. "libfoo.so"; for a merged
   * library, the library it is merged into): true once its dlopen has returned, before {@link
   * com.facebook.soloader.observer.Observer#onLoadLibraryEnd} reports it, though for a merged
   * library possibly before its own JNI_OnLoad has run. Always false for a library loaded around
   * SoLoader's own loading: with SoLoader disabled, through the system-app wrapper, or off Android.
   */
  public static boolean isLoaded(@Nullable String soName) {
    // The set cannot hold null, and asking it about null throws.
    return soName != null && sLoadedLibraries.contains(soName);
  }
}
