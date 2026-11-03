package com.android.tvremoteime;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.text.TextUtils;
import android.util.JsonReader;
import android.util.Log;
import android.view.WindowManager;

import com.android.tvremoteime.http.HTTPGet;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
/**
 * Created by kingt on 2018/4/11.
 */
public class AutoUpdateManager {
    private static String TAG = "AutoUpdateManager";
    private Context context;
    private Handler handler;
    private File localFile = null;
    private static String VERSION_URL = "https://raw.githubusercontent.com/coldboy404/TVRemoteIME/master/released/version.json";
    public AutoUpdateManager(Context context, Handler handler){
        this.context = context;
        this.handler = handler;
        File cacheDir = context.getExternalCacheDir();
        if (cacheDir == null) {
            // External cache can be unavailable (e.g. storage unmounted);
            // fall back to internal cache instead of crashing on a null parent.
            cacheDir = context.getCacheDir();
        }
        this.localFile = new File(cacheDir, context.getString(R.string.app_name) + ".apk");
        this.startUpdateThread();
    }

    private void startUpdateThread(){
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try{
                    JSONObject versionObj = getServerVersionObj();
                    if(versionObj != null && needUpdate(versionObj)){
                        if(downloadInstallAPK(versionObj)){
                            String message = versionObj.has("message") ? versionObj.getString("message") : "";
                            String versionName = versionObj.has("versionName") ? versionObj.getString("versionName") : AppPackagesHelper.getCurrentPackageVersion(context);
                            StringBuilder msg = new StringBuilder();
                            msg.append("发现新版本：").append(versionName);
                            if(!TextUtils.isEmpty(message)){
                                msg.append("\r\n更新内容：\r\n\r\n").append(message);
                            }
                            final String content = msg.toString();
                            final boolean forced = versionObj.has("forced") && versionObj.getBoolean("forced");

                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    AlertDialog.Builder builder = new AlertDialog.Builder(context);
                                    builder.setTitle(context.getString(R.string.app_name) + "版本更新提示").setIcon(R.drawable.ic_launcher);
                                    builder.setMessage(content);
                                    if (!forced) {
                                        builder.setPositiveButton("稍后更新", new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface dialog, int which) {
                                                dialog.dismiss();
                                            }
                                        });
                                    }
                                    builder.setNegativeButton("马上更新", new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {
                                            AppPackagesHelper.installPackage(localFile, context);
                                            dialog.dismiss();
                                        }
                                    });
                                    try {
                                        AlertDialog dialog = builder.create();
                                        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
                                        dialog.setCanceledOnTouchOutside(false);
                                        dialog.show();
                                    } catch (Exception ex) {
                                        // The old code installed the APK
                                        // unconditionally when the dialog
                                        // failed to show, i.e. updated with
                                        // no user confirmation at all. A
                                        // failed prompt must mean "no
                                        // install", never "install anyway".
                                        Log.e(TAG, "Failed to show update dialog; not installing", ex);
                                    }
                                }
                            });
                        }
                    }
                }catch (Exception e){
                    Log.e(TAG, "startUpdateThread", e);
                }
            }
        }, "tvremoteime-update-check");
        thread.start();
    }

    private int getCurrentPackageVersion(){
        try {
            PackageInfo packageInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return packageInfo.versionCode;
        }catch (PackageManager.NameNotFoundException e){}
        return -1;
    }

    private JSONObject getServerVersionObj(){
        try{
            String jsonData = HTTPGet.readString(VERSION_URL);
            if (!TextUtils.isEmpty(jsonData)) {
                if(Environment.needDebug) Environment.debug(TAG, "getServerVersionObj:\r\n" + jsonData);
                return new JSONObject(jsonData);
            }
        } catch (Exception e) {
            Log.e(TAG, "getServerVersionObj", e);
        }
        return null;
    }

    private boolean needUpdate(JSONObject versionObj){
        int version = getCurrentPackageVersion();
        if(version == -1 || versionObj == null) return false;

        try {
            return (versionObj.has("versionCode") &&
                     versionObj.has("installAPK") &&
                     version < versionObj.getInt("versionCode"));
        } catch (JSONException e) {
            return false;
        }
    }

    private boolean needDownloadAPK(JSONObject versionObj) {
        try {
            if(! this.localFile.exists()) return true;

            PackageManager pm = context.getPackageManager();
            PackageInfo packInfo = pm.getPackageArchiveInfo(this.localFile.getAbsolutePath(), PackageManager.GET_ACTIVITIES);
            if (packInfo == null) return true;
            int localVersion = packInfo.versionCode;
            if(Environment.needDebug) Environment.debug(TAG, "needDownloadAPK: localVersion = " + localVersion);
            int version = versionObj.getInt("versionCode");
            return localVersion < version;
        }catch (Exception e){
            return true;
        }
    }

    private boolean downloadInstallAPK(JSONObject versionObj){
        try {
            if(needDownloadAPK(versionObj)){
                String url = versionObj.getString("installAPK");
                if(TextUtils.isEmpty(url)) return false;
                if(Environment.needDebug) Environment.debug(TAG, "downloadInstallAPK starting: " + url);
                boolean flag = HTTPGet.downloadFile(url, this.localFile);
                if(Environment.needDebug) Environment.debug(TAG, "downloadInstallAPK finished. result: " + flag);
                if(!flag) return false;
            }
            // Never offer to install an APK that is not this app. The
            // version feed only carries a plain URL, so verify the
            // downloaded archive before prompting: package name must
            // match, the signing certificate must match the installed
            // app, and when the feed provides "apkSha256" the file hash
            // must match too. (The platform installer additionally
            // enforces the signature for a real update.)
            return verifyDownloadedApk(versionObj);
        } catch (Exception e) {
            Log.e(TAG, "downloadInstallAPK", e);
        }
        return false;
    }

    private boolean verifyDownloadedApk(JSONObject versionObj) {
        try {
            PackageManager pm = context.getPackageManager();
            int flags = android.os.Build.VERSION.SDK_INT >= 28
                    ? PackageManager.GET_SIGNING_CERTIFICATES
                    : PackageManager.GET_SIGNATURES;
            PackageInfo archiveInfo = pm.getPackageArchiveInfo(this.localFile.getAbsolutePath(), flags);
            if (archiveInfo == null || !context.getPackageName().equals(archiveInfo.packageName)) {
                Log.e(TAG, "Downloaded APK is not " + context.getPackageName() + ", discarding it");
                deleteLocalFile();
                return false;
            }
            PackageInfo installedInfo = pm.getPackageInfo(context.getPackageName(), flags);
            byte[] archiveCert = firstSignerCert(archiveInfo);
            byte[] installedCert = firstSignerCert(installedInfo);
            if (archiveCert == null || installedCert == null
                    || !java.security.MessageDigest.isEqual(archiveCert, installedCert)) {
                Log.e(TAG, "Downloaded APK signer does not match the installed app, discarding it");
                deleteLocalFile();
                return false;
            }
            String expectedSha256 = versionObj.optString("apkSha256", "");
            if (!TextUtils.isEmpty(expectedSha256) && !expectedSha256.equalsIgnoreCase(sha256OfFile(this.localFile))) {
                Log.e(TAG, "Downloaded APK SHA-256 mismatch, discarding it");
                deleteLocalFile();
                return false;
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "verifyDownloadedApk", e);
            return false;
        }
    }

    private static byte[] firstSignerCert(PackageInfo info) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                if (info.signingInfo == null) return null;
                android.content.pm.Signature[] signers = info.signingInfo.getApkContentsSigners();
                return (signers != null && signers.length > 0) ? signers[0].toByteArray() : null;
            }
            return (info.signatures != null && info.signatures.length > 0)
                    ? info.signatures[0].toByteArray() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String sha256OfFile(File file) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        try (java.io.InputStream in = new java.io.FileInputStream(file)) {
            int n;
            while ((n = in.read(buffer)) != -1) {
                digest.update(buffer, 0, n);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private void deleteLocalFile() {
        if (this.localFile.exists() && !this.localFile.delete()) {
            Log.w(TAG, "Failed to delete rejected APK: " + this.localFile);
        }
    }
}
