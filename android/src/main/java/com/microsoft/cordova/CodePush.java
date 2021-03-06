package com.microsoft.cordova;

import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.util.Base64;
import android.webkit.WebView;

import com.getcapacitor.JSObject;
import com.getcapacitor.NativePlugin;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;

import org.json.JSONException;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URI;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Date;
import java.util.Map;

/**
 * Native Android CodePush Capacitor Plugin.
 */
@NativePlugin()
public class CodePush extends Plugin {

    private static final String DEPLOYMENT_KEY_PREFERENCE = "codepushdeploymentkey";
    private static final String PUBLIC_KEY_PREFERENCE = "codepushpublickey";
    private static final String SERVER_URL_PREFERENCE = "codepushserverurl";
    private static final String WWW_ASSET_PATH_PREFIX = "file:///android_asset/www/";
    private static final String NEW_LINE = System.getProperty("line.separator");
    private static boolean ShouldClearHistoryOnLoad = false;
    private CodePushPackageManager codePushPackageManager;
    private CodePushReportingManager codePushReportingManager;
    private StatusReport rollbackStatusReport;
    private boolean pluginDestroyed = false;
    private boolean didUpdate = false;
    private boolean didStartApp = false;
    private long lastPausedTimeMs = 0;

    @Override
    public void load() {
        CodePushPreferences codePushPreferences = new CodePushPreferences(bridge.getActivity());
        codePushPackageManager = new CodePushPackageManager(bridge.getActivity(), codePushPreferences);
        codePushReportingManager = new CodePushReportingManager(bridge.getActivity(), codePushPreferences);
    }

    @PluginMethod()
    public void getDeploymentKey(PluginCall call) {
        this.returnStringPreference(DEPLOYMENT_KEY_PREFERENCE, call);
    }

    @PluginMethod()
    public void getServerURL(PluginCall call) {
        this.returnStringPreference(SERVER_URL_PREFERENCE, call);
    }

    private JSObject jsObjectValue(String value) {
        // TODO: fix all client calls
        JSObject ret = new JSObject();
        ret.put("value", value);
        return ret;
    }

    private JSObject jsObjectValue(boolean value) {
        // TODO: fix all client calls
        JSObject ret = new JSObject();
        ret.put("value", value);
        return ret;
    }

    @PluginMethod()
    public void getPublicKey(PluginCall call) {
        String publicKey = (String) getConfigValue(PUBLIC_KEY_PREFERENCE);
        call.success(jsObjectValue(publicKey));
    }

    @PluginMethod()
    public void decodeSignature(final PluginCall call) {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... voids) {
                try {
                    // TODO: fix client
                    String stringPublicKey = call.getString("publicKey");

                    final PublicKey publicKey;
                    try {
                        publicKey = parsePublicKey(stringPublicKey);
                    } catch (CodePushException e) {
                        call.error("Error occurred while creating the a public key" + e.getMessage());
                        return null;
                    }

                    // TODO: fix client
                    final String signature = call.getString("signature");

                    final Map<String, Object> claims;
                    try {
                        claims = verifyAndDecodeJWT(signature, publicKey);
                    } catch (CodePushException e) {
                        call.error("The update could not be verified because it was not signed by a trusted party. " + e.getMessage());
                        return null;
                    }

                    final String contentHash = (String) claims.get("contentHash");
                    if (contentHash == null) {
                        call.error("The update could not be verified because the signature did not specify a content hash.");
                        return null;
                    }
                    call.success(jsObjectValue(contentHash));

                } catch (Exception e) {
                    call.error("Unknown error occurred during signature decoding. " + e.getMessage());
                }

                return null;
            }
        }.execute();
    }

    private PublicKey parsePublicKey(String stringPublicKey) throws CodePushException {
        try {
            stringPublicKey = stringPublicKey
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replace("&#xA;", "") //gradle automatically replaces new line to &#xA;
                    .replace(NEW_LINE, "");
            byte[] byteKey = Base64.decode(stringPublicKey.getBytes(), Base64.DEFAULT);
            X509EncodedKeySpec X509Key = new X509EncodedKeySpec(byteKey);
            KeyFactory kf = KeyFactory.getInstance("RSA");
            return kf.generatePublic(X509Key);
        } catch (Exception e) {
            throw new CodePushException(e);
        }
    }

    private Map<String, Object> verifyAndDecodeJWT(String jwt, PublicKey publicKey) throws CodePushException {
        try {
            SignedJWT signedJWT = SignedJWT.parse(jwt);
            JWSVerifier verifier = new RSASSAVerifier((RSAPublicKey) publicKey);
            if (signedJWT.verify(verifier)) {
                Map<String, Object> claims = signedJWT.getJWTClaimsSet().getClaims();
                Utilities.logMessage("JWT verification succeeded, payload content: " + claims.toString());
                return claims;
            }
            throw new CodePushException("JWT verification failed: wrong signature");
        } catch (Exception e) {
            throw new CodePushException(e);
        }
    }

    @PluginMethod()
    public void getBinaryHash(final PluginCall call) {
        String cachedBinaryHash = codePushPackageManager.getCachedBinaryHash();
        if (cachedBinaryHash == null) {
            new AsyncTask<Void, Void, Void>() {
                @Override
                protected Void doInBackground(Void... params) {
                    try {
                        String binaryHash = UpdateHashUtils.getBinaryHash(bridge.getActivity());
                        codePushPackageManager.saveBinaryHash(binaryHash);
                        call.success(jsObjectValue(binaryHash));
                    } catch (Exception e) {
                        call.error("An error occurred when trying to get the hash of the binary contents. " + e.getMessage());
                    }

                    return null;
                }
            }.execute();
        } else {
            call.success(jsObjectValue(cachedBinaryHash));
        }
    }

    @PluginMethod()
    public void getPackageHash(final PluginCall call) {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                try {
                    // TODO: fix client side
                    String binaryHash = UpdateHashUtils.getHashForPath(bridge.getActivity(), call.getString("path") + "/www");
                    call.success(jsObjectValue(binaryHash));
                } catch (Exception e) {
                    call.error("An error occurred when trying to get the hash of the binary contents. " + e.getMessage());
                }

                return null;
            }
        }.execute();
    }

    @PluginMethod()
    public void unzip(final PluginCall call) {
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                try {
                    File zipFile = new File(new URI(call.getString("zipFile")));
                    File targetDirectory = new File(new URI(call.getString("targetDirectory")));
                    Utilities.unzip(zipFile, targetDirectory);
                    call.resolve();
                } catch (Exception e) {
                    call.error("An error occurred when trying to unzip package. " + e.getMessage());
                }

                return null;
            }
        }.execute();
    }

    @PluginMethod()
    public void notifyApplicationReady(PluginCall call) {
        if (this.codePushPackageManager.isBinaryFirstRun()) {
            // Report first run of a store version app
            this.codePushPackageManager.saveBinaryFirstRunFlag();
            try {
                String appVersion = Utilities.getAppVersionName(bridge.getActivity());
                codePushReportingManager.reportStatus(new StatusReport(ReportingStatus.STORE_VERSION, null, appVersion, (String) getConfigValue(DEPLOYMENT_KEY_PREFERENCE)), bridge.getWebView());
            } catch (PackageManager.NameNotFoundException e) {
                // Should not happen unless the appVersion is not specified, in which case we can't report anything anyway.
                e.printStackTrace();
            }
        } else if (this.codePushPackageManager.installNeedsConfirmation()) {
            // Report CodePush update installation that has not been confirmed yet
            CodePushPackageMetadata currentMetadata = this.codePushPackageManager.getCurrentPackageMetadata();
            codePushReportingManager.reportStatus(new StatusReport(ReportingStatus.UPDATE_CONFIRMED, currentMetadata.label, currentMetadata.appVersion, currentMetadata.deploymentKey), bridge.getWebView());
        } else if (rollbackStatusReport != null) {
            // Report a CodePush update that has been rolled back
            codePushReportingManager.reportStatus(rollbackStatusReport, bridge.getWebView());
            rollbackStatusReport = null;
        } else if (codePushReportingManager.hasFailedReport()) {
            // Previous status report failed, so try it again
            codePushReportingManager.reportStatus(codePushReportingManager.getAndClearFailedReport(), bridge.getWebView());
        }

        // Mark the update as confirmed and not requiring a rollback
        this.codePushPackageManager.clearInstallNeedsConfirmation();
        this.cleanOldPackageSilently();
        call.success();
    }

    @PluginMethod()
    public void isFirstRun(PluginCall call) {
        boolean isFirstRun = false;
        // TODO: fix client side
        String packageHash = call.getString("packageHash");
        CodePushPackageMetadata currentPackageMetadata = codePushPackageManager.getCurrentPackageMetadata();
        if (null != currentPackageMetadata) {
            /* This is the first run for a package if we just updated, and the current package hash matches the one provided. */
            isFirstRun = (null != packageHash
                    && !packageHash.isEmpty()
                    && packageHash.equals(currentPackageMetadata.packageHash)
                    && didUpdate);
        }
        call.success(jsObjectValue(isFirstRun));
    }

    @PluginMethod()
    public void isPendingUpdate(PluginCall call) {
        try {
            InstallOptions pendingInstall = this.codePushPackageManager.getPendingInstall();
            call.success(jsObjectValue(pendingInstall != null));
        } catch (Exception e) {
            call.error("An error occurred. " + e.getMessage());
        }
    }

    @PluginMethod()
    public void isFailedUpdate(PluginCall call) {
        // TODO: fix client side
        final String packageHash = call.getString("packageHash");
        boolean isFailedUpdate = this.codePushPackageManager.isFailedUpdate(packageHash);
        call.success(jsObjectValue(isFailedUpdate));
    }

    @PluginMethod()
    public void install(PluginCall call) {
        try {
            // TODO: fix client side
            final String startLocation = call.getString("startLocation");
            final InstallMode installMode = InstallMode.fromValue(call.getInt("installMode"));
            final int minimumBackgroundDuration = call.getInt("minimumBackgroundDuration");

            File startPage = this.getStartPageForPackage(startLocation);
            if (startPage != null) {
                /* start page file exists */
                /* navigate to the start page */
                if (InstallMode.IMMEDIATE.equals(installMode)) {
                    this.navigateToFile(startPage);
                    markUpdate();
                } else {
                    InstallOptions pendingInstall = new InstallOptions(installMode, minimumBackgroundDuration);
                    this.codePushPackageManager.savePendingInstall(pendingInstall);
                }

                call.success();
            } else {
                call.error("Could not find the package start page.");
            }
        } catch (Exception e) {
            call.error("Could not read webview URL: " + e.getMessage());
        }
    }

    @PluginMethod()
    public void reportFailed(PluginCall call) {
        try {
            // TODO: fix client side
            StatusReport statusReport = StatusReport.deserialize(call.getObject("statusReport"));
            codePushReportingManager.saveFailedReport(statusReport);
        } catch (JSONException e) {
            Utilities.logException(e);
        }
    }

    @PluginMethod()
    public void reportSucceeded(PluginCall call) {
        try {
            // TODO: fix client side
            StatusReport statusReport = StatusReport.deserialize(call.getObject("statusReport"));
            codePushReportingManager.saveSuccessfulReport(statusReport);
        } catch (JSONException e) {
            Utilities.logException(e);
        }
    }

    @PluginMethod()
    public void restartApplication(PluginCall call) {
        try {
            /* check if we have a deployed package already */
            CodePushPackageMetadata deployedPackageMetadata = this.codePushPackageManager.getCurrentPackageMetadata();
            if (deployedPackageMetadata != null) {
                call.success();
                didStartApp = false;
                handleOnStart();
            } else {
                final String configLaunchUrl = this.getConfigLaunchUrl();
                if (!this.pluginDestroyed) {
                    call.success();
                    this.bridge.getActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            navigateToURL(configLaunchUrl);
                        }
                    });
                }
            }
        } catch (Exception e) {
            call.error("An error occurred while restarting the application." + e.getMessage());
        }
    }

    private void markUpdate() {
    /* this flag will clear when reloading the plugin */
        this.didUpdate = true;
        this.codePushPackageManager.markInstallNeedsConfirmation();
    }

    private void cleanOldPackageSilently() {
        try {
            this.codePushPackageManager.cleanOldPackage();
        } catch (Exception e) {
            /* silently fail if there was an error during cleanup */
            Utilities.logException(e);
        }
    }

    private void clearDeploymentsIfBinaryUpdated() {
        /* check if we have a deployed package already */
        CodePushPackageMetadata deployedPackageMetadata = this.codePushPackageManager.getCurrentPackageMetadata();
        if (deployedPackageMetadata != null) {
            String deployedPackageTimeStamp = deployedPackageMetadata.nativeBuildTime;
            long nativeBuildTime = Utilities.getApkBuildTime(this.bridge.getActivity());

            String deployedPackageVersion = deployedPackageMetadata.appVersion;
            String applicationVersion = null;
            try {
                applicationVersion = Utilities.getAppVersionName(this.bridge.getActivity());
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
            }

            if (nativeBuildTime != -1 && applicationVersion != null) {
                String currentAppTimeStamp = String.valueOf(nativeBuildTime);
                if (!currentAppTimeStamp.equals(deployedPackageTimeStamp) ||
                        !(applicationVersion.equals(deployedPackageVersion))) {
                    this.codePushPackageManager.cleanDeployments();
                    this.codePushPackageManager.clearFailedUpdates();
                    this.codePushPackageManager.clearPendingInstall();
                    this.codePushPackageManager.clearInstallNeedsConfirmation();
                    this.codePushPackageManager.clearBinaryFirstRunFlag();
                }
            }
        }
    }

    private void navigateToLocalDeploymentIfExists() {
        CodePushPackageMetadata deployedPackageMetadata = this.codePushPackageManager.getCurrentPackageMetadata();
        if (deployedPackageMetadata != null && deployedPackageMetadata.localPath != null) {
            this.bridge.setServerBasePath(this.getBasePathForPackage(deployedPackageMetadata.localPath));
        }
    }

    @PluginMethod()
    public void preInstall(PluginCall call) {
        /* check if package is valid */
        try {
            // TODO: fix client side
            final String startLocation = call.getString("startLocation");
            File startPage = this.getStartPageForPackage(startLocation);
            if (startPage != null) {
                /* start page exists */
                call.success();
            } else {
                call.error("Could not get the package start page");
            }
        } catch (Exception e) {
            call.error("Could not get the package start page");
        }
    }

    @PluginMethod()
    public void getAppVersion(PluginCall call) {
        try {
            String appVersionName = Utilities.getAppVersionName(this.bridge.getActivity());
            call.success(jsObjectValue(appVersionName));
        } catch (PackageManager.NameNotFoundException e) {
            call.error("Cannot get application version.");
        }
    }

    @PluginMethod()
    public void getNativeBuildTime(PluginCall call) {
        long millis = Utilities.getApkBuildTime(this.bridge.getActivity());
        if (millis == -1) {
            call.error("Could not get the application buildstamp.");
        } else {
            String result = String.valueOf(millis);
            call.success(jsObjectValue(result));
        }
    }

    private void returnStringPreference(String preferenceName, PluginCall call) {
        String result = (String) getConfigValue(preferenceName);
        if (result != null) {
            call.success(jsObjectValue(result));
        } else {
            call.error("Could not get preference: " + preferenceName);
        }
    }

    private void handleUnconfirmedInstall(boolean navigate) {
        if (this.codePushPackageManager.installNeedsConfirmation()) {
            /* save status for later reporting */
            CodePushPackageMetadata currentMetadata = this.codePushPackageManager.getCurrentPackageMetadata();
            rollbackStatusReport = new StatusReport(ReportingStatus.UPDATE_ROLLED_BACK, currentMetadata.label, currentMetadata.appVersion, currentMetadata.deploymentKey);

            /* revert application to the previous version */
            this.codePushPackageManager.clearInstallNeedsConfirmation();
            this.codePushPackageManager.revertToPreviousVersion();

            /* reload the previous version */
            if (navigate) {
                String url;
                try {
                    CodePushPackageMetadata currentPackageMetadata = this.codePushPackageManager.getCurrentPackageMetadata();
                    url = this.getStartPageURLForPackage(currentPackageMetadata.localPath);
                } catch (Exception e) {
                    url = this.getConfigLaunchUrl();
                }

                final String finalURL = url;

                if (!this.pluginDestroyed) {
                    this.bridge.getActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            navigateToURL(finalURL);
                        }
                    });
                }
            }
        }
    }

    private void navigateToFile(File startPageFile) throws MalformedURLException {
        if (startPageFile != null) {
            String url = startPageFile.toURI().toURL().toString();
            this.navigateToURL(url);
        }
    }

    private void navigateToURL(String url) {
        if (url != null) {
            CodePush.ShouldClearHistoryOnLoad = true;
            bridge.getWebView().loadUrl(url);
        }
    }

    private File getStartPageForPackage(String packageLocation) {
        if (packageLocation != null) {
            File startPage = new File(this.bridge.getActivity().getFilesDir() + "/" + packageLocation, "www/index.html");
            if (startPage.exists()) {
                return startPage;
            }
        }

        return null;
    }

    private String getBasePathForPackage(String packageLocation) {
        if (packageLocation != null) {
            return new File(this.bridge.getActivity().getFilesDir() + "/" + packageLocation, "www").toString();
        }

        return null;
    }

    private String getStartPageURLForPackage(String packageLocation) throws MalformedURLException {
        String result = null;
        File startPageFile = getStartPageForPackage(packageLocation);
        if (startPageFile != null) {
            result = startPageFile.toURI().toURL().toString();
        }

        return result;
    }

    private String getConfigLaunchUrl() {
        // TODO: implement me
        return "https://localhost";
    }

    /**
     * Called when the system is about to start resuming a previous activity.
     */
    @Override
    public void handleOnPause() {
        lastPausedTimeMs = new Date().getTime();
    }

    /**
     * Called when the activity will start interacting with the user.
     */
    @Override
    public void handleOnResume() {
        this.pluginDestroyed = false;
    }

    /**
     * Called when the activity is becoming visible to the user.
     */
    @Override
    public void handleOnStart() {
        clearDeploymentsIfBinaryUpdated();
        if (!didStartApp) {
            /* The application was just started. */
            didStartApp = true;
            InstallOptions pendingInstall = this.codePushPackageManager.getPendingInstall();

            /* Revert to the previous version if the install is not confirmed and no update is pending. */
            if (pendingInstall == null) {
                handleUnconfirmedInstall(false);
            }

            navigateToLocalDeploymentIfExists();
            /* Handle ON_NEXT_RESUME and ON_NEXT_RESTART pending installations */
            if (pendingInstall != null && (InstallMode.ON_NEXT_RESUME.equals(pendingInstall.installMode) || InstallMode.ON_NEXT_RESTART.equals(pendingInstall.installMode))) {
                this.markUpdate();
                this.codePushPackageManager.clearPendingInstall();
            }
        } else {
            /* The application was resumed from the background. */
            /* Handle ON_NEXT_RESUME pending installations. */
            InstallOptions pendingInstall = this.codePushPackageManager.getPendingInstall();
            long durationInBackground = (new Date().getTime() - lastPausedTimeMs) / 1000;
            if (pendingInstall != null && InstallMode.ON_NEXT_RESUME.equals(pendingInstall.installMode) && durationInBackground >= pendingInstall.minimumBackgroundDuration) {
                navigateToLocalDeploymentIfExists();
                this.markUpdate();
                this.codePushPackageManager.clearPendingInstall();
            } else if (codePushReportingManager.hasFailedReport()) {
                codePushReportingManager.reportStatus(codePushReportingManager.getAndClearFailedReport(), bridge.getWebView());
            }
        }
    }

    /**
     * The final call you receive before your activity is destroyed.
     */
    // TODO: capacitor doesn't seem to have this hook
    public void onDestroy() {
        this.pluginDestroyed = true;
    }


    public Object onMessage(String id, Object data) {
        // TODO: capacitor doesn't seem to have this hook
        if ("onPageFinished".equals(id)) {
            if (CodePush.ShouldClearHistoryOnLoad) {
                CodePush.ShouldClearHistoryOnLoad = false;
                WebView webView = this.bridge.getWebView();
                if (webView != null) {
                    webView.clearHistory();
                }
            }
        }

        return null;
    }
}
