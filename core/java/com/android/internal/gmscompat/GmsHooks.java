/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.internal.gmscompat;

import android.Manifest;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.app.Application;
import android.app.ApplicationErrorReport;
import android.app.BroadcastOptions;
import android.app.PendingIntent;
import android.app.Service;
import android.app.compat.gms.GmsCompat;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.os.Bundle;
import android.os.DeadSystemRuntimeException;
import android.os.IBinder;
import android.os.Parcel;
import android.os.PowerExemptionManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Downloads;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;
import android.webkit.WebView;

import com.android.internal.gmscompat.client.GmsCompatClientService;
import com.android.internal.gmscompat.flags.GmsFlag;
import com.android.internal.gmscompat.flags.GmsFlagOverrides;
import com.android.internal.gmscompat.gcarriersettings.GCarrierSettingsApp;
import com.android.internal.gmscompat.gcarriersettings.TestCarrierConfigService;
import com.android.internal.gmscompat.sysservice.GmcPackageManager;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static com.android.internal.gmscompat.GmsInfo.PACKAGE_GMS_CORE;

public final class GmsHooks {
    private static final String TAG = "GmsCompat/Hooks";

    private static volatile GmsCompatConfig config;

    public static final String PERSISTENT_GmsCore_PROCESS = PACKAGE_GMS_CORE + ".persistent";
    public static boolean inPersistentGmsCoreProcess;
    public static final String UI_GmsCore_PROCESS = PACKAGE_GMS_CORE + ".ui";

    public static GmsCompatConfig config() {
        // thread-safe: immutable after publication
        return config;
    }

    public static void init(Context ctx, String packageName, String processName) {
        Log.e(TAG, "GmsHooks.init() called: pkg=" + packageName + " process=" + processName
                + " isGmsCore=" + GmsCompat.isGmsCore());
        if (!packageName.equals(processName)) {
            // Fix RuntimeException: Using WebView from more than one process at once with the same data
            // directory is not supported. https://crbug.com/558377
            WebView.setDataDirectorySuffix("process-shim--" + processName);
        }

        if (GmsCompat.isGmsCore()) {
            inPersistentGmsCoreProcess = processName.equals(PERSISTENT_GmsCore_PROCESS);
        }

        GmsCompatLib.init(ctx, processName);

        if (GmsCompat.isPlayStore()) {
            PlayStoreHooks.init();
        }

        if (GmsCompat.isGCarrierSettings()) {
            GCarrierSettingsApp.init();
        }

        configUpdateLock = new Object();
        tlPermissionsToSpoof = new ThreadLocal<>();

        // Locking is needed to prevent a race that would occur if config is updated via
        // BinderGca2Gms#updateConfig in the time window between BinderGms2Gca#connect and setConfig()
        // call below. Older GmsCompatConfig would overwrite the newer one in that case.
        Log.e(TAG, "About to call GmsCompatApp.connect()");
        synchronized (configUpdateLock) {
            GmsCompatConfig config = GmsCompatApp.connect(ctx, processName);
            Log.e(TAG, "GmsCompatApp.connect() returned, config=" + (config != null ? "non-null" : "NULL"));
            setConfig(config);
        }

        Thread.setUncaughtExceptionPreHandler(new UncaughtExceptionPreHandler());

        Log.e(TAG, "inPersistentGmsCoreProcess=" + inPersistentGmsCoreProcess
                + " PERSISTENT_PROCESS=" + PERSISTENT_GmsCore_PROCESS
                + " processName=" + processName);
        if (inPersistentGmsCoreProcess) {
            Log.e(TAG, "About to call GmsFlagOverrides.init()");
            try {
                GmsFlagOverrides.init(ctx);
                Log.e(TAG, "GmsFlagOverrides.init() completed successfully");
            } catch (Throwable t) {
                Log.e(TAG, "GmsFlagOverrides.init() CRASHED", t);
            }
        } else {
            Log.e(TAG, "NOT persistent process, skipping GmsFlagOverrides");
        }

        GmcPackageManager.init(ctx);
    }

    static Object configUpdateLock;

    static void setConfig(GmsCompatConfig c) {
        // configUpdateLock should never be null at this point, it's initialized before GmsCompatApp
        // gets a handle to BinderGca2Gms that is used for updating GmsCompatConfig
        synchronized (configUpdateLock) {
            config = c;
        }
    }

    static class UncaughtExceptionPreHandler implements Thread.UncaughtExceptionHandler {
        final Thread.UncaughtExceptionHandler orig = Thread.getUncaughtExceptionPreHandler();

        @Override
        public void uncaughtException(Thread t, Throwable e) {
            Context ctx = GmsCompat.appContext();

            ApplicationErrorReport aer = new ApplicationErrorReport();
            aer.type = ApplicationErrorReport.TYPE_CRASH;
            aer.crashInfo = new ApplicationErrorReport.ParcelableCrashInfo(e);

            ApplicationInfo ai = ctx.getApplicationInfo();
            aer.packageName = ai.packageName;
            aer.applicationInfo = ai;
            aer.processName = Application.getProcessName();

            // In some cases, GMS kills its process when it receives an uncaught exception, which
            // bypasses the standard crash handling infrastructure.
            // Send the report to GmsCompatApp before GMS receives the uncaughtException() callback.

            if (!shouldSkipException(e)) {
                try {
                    GmsCompatApp.iGms2Gca().onUncaughtException(aer);
                } catch (RemoteException re) {
                    Log.e(TAG, "", re);
                }
            }

            if (orig != null) {
                orig.uncaughtException(t, e);
            }
        }

        private static boolean shouldSkipException(Throwable e) {
            for (;;) {
                if (e == null) {
                    return false;
                }

                boolean skip =
    // in some cases a DeadSystemRuntimeException is thrown despite the system being actually
    // still alive, likely when the Binder buffer space is full and a binder transaction with
    // system_server fails.
    // See https://cs.android.com/android/platform/superproject/+/android-13.0.0_r3:frameworks/base/core/jni/android_util_Binder.cpp;l=894
    // (DeadObjectException is rethrown as DeadSystemRuntimeException by
    // android.os.RemoteException#rethrowFromSystemServer())
                    e instanceof DeadSystemRuntimeException
                ;

                if (skip) {
                    return true;
                }

                e = e.getCause();
            }
        }
    }

    // ContextImpl#getSystemService(String)
    public static boolean isHiddenSystemService(String name) {
        // return true only for services that are null-checked
        switch (name) {
            case Context.WIFI_SCANNING_SERVICE:
                return !GmsCompat.isAndroidAuto();
            case Context.CONTEXTHUB_SERVICE:
            case Context.APP_INTEGRITY_SERVICE:
            // used for factory reset protection
            case Context.PERSISTENT_DATA_BLOCK_SERVICE:
            // used for updateable fonts
            case Context.FONT_SERVICE:
                return true;
        }
        return false;
    }

    /**
     * Use the per-app SSAID as a random serial number for SafetyNet. This doesn't necessarily make
     * pass, but at least it retusn a valid "failed" response and stops spamming device key
     * requests.
     *
     * This isn't a privacy risk because all unprivileged apps already have access to random SSAIDs.
     */
    // Build#getSerial()
    @SuppressLint("HardwareIds")
    public static String getSerial() {
        String ssaid = Settings.Secure.getString(GmsCompat.appContext().getContentResolver(),
                Settings.Secure.ANDROID_ID);
        String serial = ssaid.toUpperCase();
        Log.d(TAG, "Generating serial number from SSAID: " + serial);
        return serial;
    }

    static class RecentBinderPid implements Comparable<RecentBinderPid> {
        int pid;
        int uid;
        long lastSeen;
        volatile String[] packageNames; // lazily inited

        static final int MAX_MAP_SIZE = 50;
        static final int MAP_SIZE_TRIM_TO = 40;
        static final SparseArray<RecentBinderPid> map = new SparseArray(MAX_MAP_SIZE + 1);

        public int compareTo(RecentBinderPid b) {
            return Long.compare(b.lastSeen, lastSeen); // newest come first
        }
    }

    // Remember recent Binder peers to include them in the result of ActivityManager.getRunningAppProcesses()
    // Binder#execTransact(int, long, long, int)
    public static void onBinderTransaction(int pid, int uid) {
        SparseArray<RecentBinderPid> map = RecentBinderPid.map;
        synchronized (map) {
            RecentBinderPid rbp = map.get(pid);
            if (rbp != null) {
                if (rbp.uid != uid) { // pid was reused
                    rbp = null;
                }
            }
            if (rbp == null) {
                rbp = new RecentBinderPid();
                rbp.pid = pid;
                rbp.uid = uid;
                map.put(pid, rbp);
            }
            rbp.lastSeen = SystemClock.uptimeMillis();

            int mapSize = map.size();
            if (mapSize <= RecentBinderPid.MAX_MAP_SIZE) {
                return;
            }
            RecentBinderPid[] arr = new RecentBinderPid[mapSize];
            for (int i = 0; i < mapSize; ++i) {
                arr[i] = map.valueAt(i);
            }
            // sorted by lastSeen field in reverse order
            Arrays.sort(arr);
            map.clear();
            for (int i = 0; i < RecentBinderPid.MAP_SIZE_TRIM_TO; ++i) {
                RecentBinderPid e = arr[i];
                map.put(e.pid, e);
            }
        }
    }

    // In some cases (Play Games Services, Play {Asset, Feature} Delivery)
    // GMS relies on getRunningAppProcesses() to figure out whether its client is running.
    // This workaround is racy, because unprivileged apps can't know whether an arbitrary pid is alive.
    // ActivityManager#getRunningAppProcesses()
    public static ArrayList<RunningAppProcessInfo> addRecentlyBoundPids(Context context,
                                                                        List<RunningAppProcessInfo> orig) {
        final RecentBinderPid[] binderPids;
        final int binderPidsCount;
        // copy to array to avoid long lock contention with Binder.execTransact(),
        // there are expensive getPackagesForUid() calls below
        {
            SparseArray<RecentBinderPid> map = RecentBinderPid.map;
            synchronized (map) {
                binderPidsCount = map.size();
                binderPids = new RecentBinderPid[binderPidsCount];
                for (int i = 0; i < binderPidsCount; ++i) {
                    binderPids[i] = map.valueAt(i);
                }
            }
        }
        PackageManager pm = context.getPackageManager();
        ArrayList<RunningAppProcessInfo> res = new ArrayList<>(orig.size() + binderPidsCount);
        res.addAll(orig);
        for (int i = 0; i < binderPidsCount; ++i) {
            RecentBinderPid rbp = binderPids[i];
            String[] pkgs = rbp.packageNames;
            if (pkgs == null) {
                if (UserHandle.getUserId(rbp.uid) != UserHandle.myUserId()) {
                    // SystemUI from userId 0 sends callbacks to apps from all userIds via
                    // android.window.IOnBackInvokedCallback.
                    // getPackagesForUid() will fail due to missing privileged
                    // INTERACT_ACROSS_USERS permission
                    continue;
                }

                pkgs = pm.getPackagesForUid(rbp.uid);
                if (pkgs == null || pkgs.length == 0) {
                    continue;
                }
                // this field is volatile
                rbp.packageNames = pkgs;
            }
            RunningAppProcessInfo pi = new RunningAppProcessInfo();
            // these fields are immutable after publication
            pi.pid = rbp.pid;
            pi.uid = rbp.uid;
            pi.processName = pkgs[0];
            pi.pkgList = pkgs;
            pi.importance = RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
            res.add(pi);
        }
        return res;
    }

    // ContentResolver#query(Uri, String[], Bundle, CancellationSignal)
    public static Cursor maybeModifyQueryResult(Uri uri,
            @Nullable String[] projection, @Nullable Bundle queryArgs, @Nullable Cursor origCursor) {
        String uriString = uri.toString();
        Log.d(TAG, "maybeModifyQueryResult for " + uriString);

        Consumer<ArrayMap<String, String>> mutator = null;
        if (uriString.startsWith(GmsFlag.PHENOTYPE_URI_PREFIX)) {
            List<String> path = uri.getPathSegments();
            if (path.size() != 1) {
                Log.e(TAG, "unknown phenotype uri " + uriString, new Throwable());
                return null;
            }

            String namespace = path.get(0);
            Log.e(TAG, "Phenotype query: namespace=" + namespace);

            GmsCompatConfig config = config();

            ArrayList<String> forceDefaultFlagsRegexes = config.forceDefaultFlagsMap.get(namespace);
            ArrayMap<String, GmsFlag> namespaceFlags = config.flags.get(namespace);

            if (forceDefaultFlagsRegexes == null && namespaceFlags == null) {
                return null;
            }

            mutator = map -> {
                if (forceDefaultFlagsRegexes != null) {
                    int patternCnt = forceDefaultFlagsRegexes.size();
                    Pattern[] patterns = new Pattern[patternCnt];
                    for (int i = 0; i < patternCnt; ++i) {
                        patterns[i] = Pattern.compile(forceDefaultFlagsRegexes.get(i));
                    }
                    ArrayMap filteredMap = new ArrayMap<>(map.size());

                    outer:
                    for (int entryIdx = 0, entryCnt = map.size(); entryIdx < entryCnt; ++entryIdx) {
                        String key = map.keyAt(entryIdx);
                        for (int patternIdx = 0; patternIdx < patternCnt; ++patternIdx) {
                            if (patterns[patternIdx].matcher(key).matches()) {
                                continue outer;
                            }
                        }
                        filteredMap.put(key, map.valueAt(entryIdx));
                    }
                    map.clear();
                    map.putAll(filteredMap);
                }

                // Apply flag overrides from config directly into the query result
                if (namespaceFlags != null) {
                    for (GmsFlag flag : namespaceFlags.values()) {
                        flag.applyToPhenotypeMap(map);
                    }
                }
            };
        }

        if (mutator != null) {
            return modifyKvCursor(origCursor, projection, mutator);
        }

        return null;
    }

    private static Cursor modifyKvCursor(@Nullable Cursor origCursor, @Nullable String[] projection,
                                         Consumer<ArrayMap<String, String>> mutator) {
        final int keyIndex = 0;
        final int valueIndex = 1;
        final int projectionLength = 2;

        if (origCursor != null) {
            projection = origCursor.getColumnNames();
        }

        boolean expectedProjection = projection != null && projection.length == projectionLength
                && "key".equals(projection[keyIndex]) && "value".equals(projection[valueIndex]);

        if (!expectedProjection) {
            if (origCursor == null) {
                // Original query failed (e.g. Phenotype namespace authorization error).
                // Use default projection so we can still provide our flag overrides.
                Log.d(TAG, "using default [key, value] projection for null cursor");
                projection = new String[]{"key", "value"};
            } else {
                Log.e(TAG, "unexpected projection " + Arrays.toString(projection), new Throwable());
                return null;
            }
        }

        final ArrayMap<String, String> map;
        if (origCursor == null) {
            map = new ArrayMap<>();
        } else {
            map = new ArrayMap<>(origCursor.getColumnCount() + 10);
            try (Cursor orig = origCursor) {
                while (orig.moveToNext()) {
                    String key = orig.getString(keyIndex);
                    String value = orig.getString(valueIndex);

                    map.put(key, value);
                }
            }
        }

        mutator.accept(map);

        final int mapSize = map.size();
        MatrixCursor result = new MatrixCursor(projection, mapSize);

        for (int i = 0; i < mapSize; ++i) {
            Object[] row = new Object[projectionLength];
            row[keyIndex] = map.keyAt(i);
            row[valueIndex] = map.valueAt(i);

            result.addRow(row);
        }

        return result;
    }

    // Instrumentation#execStartActivity(Context, IBinder, IBinder, Activity, Intent, int, Bundle)
    public static void onActivityStart(int resultCode, Intent intent, int requestCode, Bundle options) {
        if (resultCode != ActivityManager.START_ABORTED) {
            return;
        }

        // handle background activity starts, which normally require a privileged permission

        if (requestCode >= 0) {
            Log.d(TAG, "attempt to call startActivityForResult() from the background " + intent, new Throwable());
            return;
        }

        // needed to prevent invalid reuse of PendingIntents, see PendingIntent doc
        intent.setIdentifier(UUID.randomUUID().toString());

        Context ctx = GmsCompat.appContext();
        PendingIntent pendingIntent = PendingIntent.getActivity(ctx, 0, intent,
                PendingIntent.FLAG_IMMUTABLE, options);
        try {
            GmsCompatApp.iGms2Gca().startActivityFromTheBackground(ctx.getPackageName(), pendingIntent);
        } catch (RemoteException e) {
            GmsCompatApp.callFailed(e);
        }
    }

    // Activity#onCreate(Bundle)
    public static void activityOnCreate(Activity activity) {

    }

    // ContentResolver#insert(Uri, ContentValues, Bundle)
    public static void filterContentValues(Uri url, ContentValues values) {
        if (values != null && Downloads.Impl.CONTENT_URI.equals(url)) {
            Integer otherUid = values.getAsInteger(Downloads.Impl.COLUMN_OTHER_UID);
            if (otherUid != null) {
                if (otherUid.intValue() != Process.SYSTEM_UID) {
                    throw new IllegalStateException("unexpected COLUMN_OTHER_UID " + otherUid);
                }
                // gated by the privileged ACCESS_DOWNLOAD_MANAGER_ADVANCED permission
                values.remove(Downloads.Impl.COLUMN_OTHER_UID);
            }
        }
    }

    private static boolean hasNearbyDevicesPermission() {
        // "Nearby devices" user-facing permission grants multiple underlying permissions,
        // checking one is enough
        return GmsCompat.hasPermission(Manifest.permission.BLUETOOTH_SCAN);
    }

    // ContextImpl#sendBroadcast
    // ContextImpl#sendOrderedBroadcast
    // ContextImpl#sendBroadcastAsUser
    // ContextImpl#sendOrderedBroadcastAsUser
    public static Bundle filterBroadcastOptions(Intent intent, Bundle options) {
        if (options == null) {
            return null;
        }

        String targetPkg = intent.getPackage();

        if (targetPkg == null) {
            ComponentName cn = intent.getComponent();
            if (cn != null) {
                targetPkg = cn.getPackageName();
            }
        }

        if (targetPkg == null) {
            return options;
        }

        return filterBroadcastOptions(options, targetPkg);
    }

    // PendingIntent#send
    public static Bundle filterBroadcastOptions(Bundle options, String targetPkg) {
        BroadcastOptions bo = new BroadcastOptions(options);

        if (bo.getTemporaryAppAllowlistType() == PowerExemptionManager.TEMPORARY_ALLOW_LIST_TYPE_NONE) {
            return options;
        }
        // handle privileged BroadcastOptions#setTemporaryAppAllowlist() that is used for
        // high-priority FCM pushes, location updates via PendingIntent,
        // geofencing and activity detection notifications etc

        long duration = bo.getTemporaryAppAllowlistDuration();

        if (duration <= 0) {
            return options;
        }

        GmsCompatApp.raisePackageToForeground(targetPkg, duration,
                bo.getTemporaryAppAllowlistReason(), bo.getTemporaryAppAllowlistReasonCode());

        bo.setTemporaryAppAllowlist(0, PowerExemptionManager.TEMPORARY_ALLOW_LIST_TYPE_NONE,
                PowerExemptionManager.REASON_UNKNOWN, null);
        return bo.toBundle();
    }

    // Parcel#readException
    public static boolean interceptException(Exception e, Parcel p) {
        if (!(e instanceof SecurityException)) {
            return false;
        }

        if (p.dataAvail() != 0) {
            Log.w(TAG, "malformed Parcel: dataAvail() " + p.dataAvail() + " after exception", e);
            return false;
        }

        StubDef stub = StubDef.find(e.getStackTrace(), config(), StubDef.FIND_MODE_Parcel);

        if (stub == null) {
            return false;
        }

        boolean res = stub.stubOutMethod(p);

        String logTag = "GmcDynStub";
        if (GmsCompat.isDevBuild() || Log.isLoggable(logTag, Log.DEBUG)) {
            Log.d(logTag, res ? "intercepted" : "stubOut failed", e);
        }

        return res;
    }

    public static void onSQLiteOpenHelperConstructed(SQLiteOpenHelper h, @Nullable Context context) {
        if (context == null) {
            return;
        }

        if (GmsCompat.isGmsCore()) {
            if (inPersistentGmsCoreProcess) {
                if ("phenotype.db".equals(h.getDatabaseName()) && !context.isDeviceProtectedStorage()) {
                    if (phenotypeDb != null) {
                        Log.w(TAG, "reassigning phenotypeDb", new Throwable());
                    }
                    phenotypeDb = h;
                }
            }
        }
    }

    @Nullable
    public static Service maybeInstantiateService(String className) {
        if (GmsCompatClientService.class.getName().equals(className)) {
            return new GmsCompatClientService();
        }

        if (GmsCompat.isEnabled()) {
            if (GmsCompat.isGmsCore()) {
                if (GmcMediaProjectionService.class.getName().equals(className)) {
                    return new GmcMediaProjectionService();
                }
            }
            if (GmsCompat.isGCarrierSettings()) {
                if (TestCarrierConfigService.class.getName().equals(className)) {
                    return new TestCarrierConfigService();
                }
            }
        }

        return null;
    }

    private static volatile SQLiteOpenHelper phenotypeDb;
    public static SQLiteOpenHelper getPhenotypeDb() { return phenotypeDb; }

    private static ThreadLocal<ArraySet<String>> tlPermissionsToSpoof;

    public static boolean shouldSpoofSelfPermissionCheck(String perm) {
        ArraySet<String> set = tlPermissionsToSpoof.get();
        if (set == null) {
            return false;
        }

        return set.contains(perm);
    }

    public static final String GMS_SERVICE_BROKER_INTERFACE_DESCRIPTOR =
            "com.google.android.gms.common.internal.IGmsServiceBroker";

    public static boolean onBeginGmsServiceBrokerCall(int transactionCode, Parcel data) {
        if (transactionCode != 46) { // getService() method
            return false;
        }

        try {
            data.enforceInterface(GMS_SERVICE_BROKER_INTERFACE_DESCRIPTOR);
            // IGmsCallbacks binder
            data.readStrongBinder();

            if (data.readInt() == 1) { // GetServiceRequest is present
                // GetServiceRequest object header
                data.readInt();
                data.readInt();

                // version
                data.readInt();
                data.readInt();

                // id of serviceId property
                data.readInt();

                int serviceId = data.readInt();

                ArraySet<String> permsToSpoof = config().gmsServiceBrokerPermissionBypasses.get(serviceId);
                if (permsToSpoof != null) {
                    Log.d(TAG, "start spoofing self permission checks for getService() call for API "
                            + serviceId + ", perms: " + Arrays.toString(permsToSpoof.toArray()));
                    tlPermissionsToSpoof.set(permsToSpoof);
                    // there's a second layer of caching inside GmsCore, need to notify permission
                    // change listener used by that cache
                    GmcPackageManager.notifyPermissionsChangeListeners();
                    return true;
                }
            }
        } finally {
            data.setDataPosition(0);
        }

        return false;
    }

    public static void onEndGmsServiceBrokerCall() {
        Log.d(TAG, "end self permission check spoofing");
        tlPermissionsToSpoof.set(null);
        // invalidate the cache of permission state inside GmsCore
        GmcPackageManager.notifyPermissionsChangeListeners();
    }

    public static IBinder maybeOverrideBinder(IBinder binder) {
        boolean proceed = GmsCompat.isEnabled() || GmsCompat.isClientOfGmsCore();
        if (!proceed) {
            return null;
        }

        String ifaceName = null;
        try {
            ifaceName = binder.getInterfaceDescriptor();
        } catch (RemoteException e) {
            Log.d(TAG, "", e);
        }

        if (ifaceName == null) {
            return null;
        }

        return GmcBinderDefs.maybeOverrideBinder(binder, ifaceName);
    }

    // MobileConfiguration injection for Google Messages RCS provisioning.
    // Messages stores RCS config in its SharedStorageProvider as base64-encoded protobuf.
    // When SIM is present, Messages ALWAYS reads from MobileConfiguration (not Phenotype).
    // After data clear or fresh install, MobileConfiguration is empty, causing availability=2
    // (DISABLED_VIA_GSERVICES) which prevents RCS provisioning from starting.
    // This hook injects synthetic RCS onboarding flags (containing the ACS URL) when Messages
    // reads an empty result, breaking the chicken-and-egg cycle.

    private static final String MESSAGES_SHARED_STORAGE_AUTHORITY =
            "com.google.android.apps.messaging.shared.datamodel.provider.sharedstorage.SharedStorageProvider";
    private static final String MOBILE_CONFIG_STORAGE_FILE = "bugle_mobile_configuration";
    private static final String RCS_ONBOARDING_FLAGS_SUFFIX = ".CONFIGURATION_TYPE_RCS_ONBOARDING_FLAGS";
    // AT&T Jibe ACS URL
    private static final String RCS_ACS_URL = "http://rcs-acs-att-us.jibe.google.com";

    /**
     * Intercept PUT operations to Messages' SharedStorageProvider for RCS onboarding flags.
     * When the MobileConfiguration sync stores server data, modify the protobuf to change
     * field 24 (G = provisioning method) from 0 to 2 (UPI) BEFORE it's stored.
     * This ensures the clpu cache gets populated with G=2 regardless of code path.
     */
    public static void maybeModifyMobileConfigPut(
            String authority, String method, @Nullable Bundle extras) {
        if (!"PUT".equals(method) || !MESSAGES_SHARED_STORAGE_AUTHORITY.equals(authority)) {
            return;
        }
        if (extras == null) {
            return;
        }
        String storageFile = extras.getString("storage_file_name");
        if (!MOBILE_CONFIG_STORAGE_FILE.equals(storageFile)) {
            return;
        }
        String key = extras.getString("preference_key");
        if (key == null || !key.endsWith(RCS_ONBOARDING_FLAGS_SUFFIX)) {
            return;
        }
        String value = extras.getString("preference_value");
        if (value == null || value.isEmpty()) {
            return;
        }

        try {
            byte[] data = android.util.Base64.decode(value, android.util.Base64.DEFAULT);
            // Search for field 33 tag (0x88 0x02) followed by value 0/1 and replace with value 2.
            // Proto field 33 = Java fffq.G (uppercase), wire type 0 (varint):
            //   (33 << 3) | 0 = 264 = 0x108, varint-encoded as 0x88 0x02.
            // G controls UPI path selection: G=2 → ffgj.a(2)=4 → UPI verification path.
            // NOTE: Proto field 24 = fffq.g (lowercase), NOT G! Setting g=2 causes
            //   fffk.a(2)=4 → DISABLED_VIA_FLAGS (availability=23). Do NOT touch field 24.
            boolean modified = false;
            boolean found = false;
            for (int i = 0; i < data.length - 2; i++) {
                if ((data[i] & 0xff) == 0x88 && (data[i + 1] & 0xff) == 0x02) {
                    found = true;
                    int val = data[i + 2] & 0xff;
                    if (val == 0x00 || val == 0x01) {
                        data[i + 2] = 0x02;
                        modified = true;
                        Log.i(TAG, "PUT hook: changed G (field 33) from " + val + " to 2 at offset " + (i + 2) + " for key: " + key);
                        break;
                    } else if (val == 0x02) {
                        Log.d(TAG, "PUT hook: G already 2, no change needed");
                        break;
                    }
                }
            }
            if (modified) {
                String newValue = android.util.Base64.encodeToString(data, android.util.Base64.DEFAULT).trim();
                extras.putString("preference_value", newValue);
                Log.i(TAG, "PUT hook: modified RCS onboarding flags protobuf for storage");
            } else if (!found) {
                // Field 33 not present in protobuf — append it with value 2
                // Tag: 0x88 0x02 (field 33, wire type 0), Value: 0x02
                byte[] appendBytes = new byte[]{(byte) 0x88, 0x02, 0x02};
                byte[] newData = new byte[data.length + appendBytes.length];
                System.arraycopy(data, 0, newData, 0, data.length);
                System.arraycopy(appendBytes, 0, newData, data.length, appendBytes.length);
                String newValue = android.util.Base64.encodeToString(newData, android.util.Base64.DEFAULT).trim();
                extras.putString("preference_value", newValue);
                Log.i(TAG, "PUT hook: appended G=2 (field 33) to protobuf (" + data.length + " → " + newData.length + " bytes) for key: " + key);
            }
        } catch (Exception e) {
            Log.e(TAG, "PUT hook: error modifying protobuf", e);
        }
    }

    /**
     * Check if a ContentResolver.call() result from Messages' SharedStorageProvider should have
     * RCS onboarding flags injected. Called from ContentResolver.call() for all apps, so this
     * method must return quickly for the common case (non-Messages callers).
     */
    public static @Nullable Bundle maybeInjectMobileConfig(
            String authority, String method, @Nullable Bundle extras, @Nullable Bundle result) {
        // Fast path: skip if not a GET to Messages' SharedStorageProvider
        if (!"GET".equals(method) || !MESSAGES_SHARED_STORAGE_AUTHORITY.equals(authority)) {
            return null;
        }

        if (extras == null) {
            return null;
        }

        String storageFile = extras.getString("storage_file_name");
        if (!MOBILE_CONFIG_STORAGE_FILE.equals(storageFile)) {
            return null;
        }

        String key = extras.getString("preference_key");
        if (key == null || !key.endsWith(RCS_ONBOARDING_FLAGS_SUFFIX)) {
            return null;
        }

        // If there's existing data, patch G field in-place (preserving all carrier config).
        // If no data exists, inject synthetic protobuf with just the ACS URL and G=2.
        if (result != null) {
            String value = result.getString("preference_key");
            if (value != null && !value.isEmpty()) {
                // Patch G field (field 33) in existing data to value 2 (UPI)
                // Proto field 33 = fffq.G (uppercase), tag 0x88 0x02
                try {
                    byte[] data = android.util.Base64.decode(value, android.util.Base64.DEFAULT);
                    boolean patched = false;
                    for (int i = 0; i < data.length - 2; i++) {
                        if ((data[i] & 0xff) == 0x88 && (data[i + 1] & 0xff) == 0x02) {
                            int val = data[i + 2] & 0xff;
                            if (val != 0x02 && val <= 0x04) {
                                Log.i(TAG, "GET hook: patching G (field 33) from " + val + " to 2 at offset " + (i + 2));
                                data[i + 2] = 0x02;
                                patched = true;
                                break;
                            } else if (val == 0x02) {
                                Log.d(TAG, "GET hook: G already 2, no patch needed");
                                return null; // Data already correct, use original
                            }
                        }
                    }
                    if (patched) {
                        String newValue = android.util.Base64.encodeToString(data, android.util.Base64.DEFAULT).trim();
                        Bundle injected = new Bundle();
                        injected.putString("preference_key", newValue);
                        Log.i(TAG, "GET hook: returning patched data (" + data.length + " bytes) with G=2");
                        return injected;
                    }
                    // Field 33 not found in existing data — append G=2
                    byte[] appendBytes = new byte[]{(byte) 0x88, 0x02, 0x02};
                    byte[] newData = new byte[data.length + appendBytes.length];
                    System.arraycopy(data, 0, newData, 0, data.length);
                    System.arraycopy(appendBytes, 0, newData, data.length, appendBytes.length);
                    String appendedValue = android.util.Base64.encodeToString(newData, android.util.Base64.DEFAULT).trim();
                    Bundle appended = new Bundle();
                    appended.putString("preference_key", appendedValue);
                    Log.i(TAG, "GET hook: appended G=2 (field 33) to existing data (" + data.length + " → " + newData.length + " bytes)");
                    return appended;
                } catch (Exception e) {
                    Log.e(TAG, "GET hook: error patching data", e);
                    return null;
                }
            }
        }

        // No existing data — inject synthetic protobuf with ACS URL and G=2
        String b64Data = buildRcsOnboardingFlagsProtobuf(RCS_ACS_URL);
        if (b64Data == null) {
            Log.e(TAG, "Failed to build RCS onboarding flags protobuf");
            return null;
        }
        Log.i(TAG, "GET hook: injecting synthetic protobuf (no existing data)");
        Bundle injected = new Bundle();
        injected.putString("preference_key", b64Data);
        return injected;
    }

    /**
     * Build base64-encoded protobuf for RCS onboarding flags.
     *
     * Proto hierarchy (from decompiled Messages):
     *   drbo (ConfigurationData):
     *     field 2 = ffeo (MobileConfigEntry)
     *   ffeo (MobileConfigEntry):
     *     field 3 = fffq (RcsOnboardingFlags) [oneof case]
     *   fffq (RcsOnboardingFlags):
     *     field 2 = string (direct URL, sets oneof e=2)
     *     field 33 = int (provisioning method G uppercase, value 2 = UPI)
     *     field 24 = int (g lowercase — DO NOT SET to 2, causes DISABLED_VIA_FLAGS!)
     *
     * Field 33 (G uppercase) controls the ReadyState branch in dplg.l():
     *   G=2 → ffgj.a(2)=4 → UPI path → VerifyMsisdnState (correct)
     *   G=0 (default) → ffgj.a(0)=2 → non-UPI → RequestWithHeState (wrong)
     *
     * Field 24 (g lowercase) controls availability in cqps.u():
     *   g=2 → fffk.a(2)=4 → DISABLED_VIA_FLAGS (availability=23) — BAD!
     *   g=0 or g=1 → OK, no disable
     */
    private static @Nullable String buildRcsOnboardingFlagsProtobuf(String acsUrl) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            // fffq: field 2 = acsUrl (wire type 2 = length-delimited)
            byte[] urlBytes = acsUrl.getBytes("UTF-8");
            byte[] fffq = new byte[0];
            {
                ByteArrayOutputStream fffqOut = new ByteArrayOutputStream();
                writeTag(fffqOut, 2, 2);
                writeVarint(fffqOut, urlBytes.length);
                fffqOut.write(urlBytes);
                // field 33 = provisioning method G uppercase (wire type 0 = varint)
                // Value 2 → ffgj.a(2)=4 → UPI path in ReadyState
                // NOTE: field 24 is g (lowercase), setting it to 2 causes DISABLED_VIA_FLAGS!
                writeTag(fffqOut, 33, 0);
                writeVarint(fffqOut, 2);
                fffq = fffqOut.toByteArray();
            }

            // ffeo: field 3 = fffq (wire type 2 = length-delimited)
            byte[] ffeo;
            {
                ByteArrayOutputStream ffeoOut = new ByteArrayOutputStream();
                writeTag(ffeoOut, 3, 2);
                writeVarint(ffeoOut, fffq.length);
                ffeoOut.write(fffq);
                ffeo = ffeoOut.toByteArray();
            }

            // drbo: field 2 = ffeo (wire type 2 = length-delimited)
            writeTag(out, 2, 2);
            writeVarint(out, ffeo.length);
            out.write(ffeo);

            return Base64.encodeToString(out.toByteArray(), Base64.DEFAULT).trim();
        } catch (IOException e) {
            Log.e(TAG, "Error building protobuf", e);
            return null;
        }
    }

    private static void writeVarint(ByteArrayOutputStream out, int value) {
        while (value > 0x7f) {
            out.write(0x80 | (value & 0x7f));
            value >>>= 7;
        }
        out.write(value & 0x7f);
    }

    private static void writeTag(ByteArrayOutputStream out, int fieldNumber, int wireType) {
        writeVarint(out, (fieldNumber << 3) | wireType);
    }

    private GmsHooks() {}
}
