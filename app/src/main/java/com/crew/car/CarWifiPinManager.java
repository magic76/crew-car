package com.crew.car;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/**
 * User-selected local-only car Wi-Fi, independent of the phone's default
 * Internet route. A remembered SSID is fail-closed until Android delivers
 * an actual Network from the specifier request.
 *
 * Android owns the OS Wi-Fi consent dialog and can still drop the radio;
 * this is not a platform-wide or permanent Wi-Fi lock.
 */
public final class CarWifiPinManager {
    public interface Listener {
        void onState(String message, boolean disconnected);
    }

    private final ConnectivityManager connectivity;
    private final WifiManager wifiManager;
    private final CarLog log;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ConnectivityManager.NetworkCallback callback;
    private Runnable timeout;
    private Listener listener;
    private int generation;
    private volatile boolean required;
    private volatile Network selected;
    private volatile String ssid = "";
    private volatile String status = "未指定車輛 Wi-Fi";
    private WifiManager.WifiLock lowLatencyLock;
    private boolean foreground;

    public CarWifiPinManager(Context context, CarLog log) {
        this.connectivity = (ConnectivityManager) context.getSystemService(
                Context.CONNECTIVITY_SERVICE);
        this.wifiManager = (WifiManager) context.getApplicationContext().getSystemService(
                Context.WIFI_SERVICE);
        this.log = log;
    }

    public boolean required() { return required; }
    public String ssid() { return ssid; }
    public String status() { return status; }
    public boolean connected() { return selectedNetwork() != null; }

    /** Restore a saved user choice, but do NOT silently connect or arm motors. */
    public void require(String selectedSsid) {
        if (!CarWifiPinPolicy.validSsid(selectedSsid))
            throw new IllegalArgumentException("請輸入有效車輛 Wi-Fi 名稱");
        unregisterRequest();
        releaseLowLatencyLock();
        selected = null;
        ssid = selectedSsid;
        required = true;
        status = "已指定車輛 Wi-Fi，請按連接";
    }

    public Network selectedNetwork() {
        Network candidate = selected;
        if (!required || candidate == null) return null;
        NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(candidate);
        return capabilities != null &&
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?
                candidate : null;
    }

    /** Call only from foreground UI after requesting nearby Wi-Fi permission. */
    public String connect(String selectedSsid, String password, Listener stateListener) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
            return "Android 10 以下請從系統 Wi-Fi 設定連接小車";
        if (!CarWifiPinPolicy.validSsid(selectedSsid))
            return "車子 Wi-Fi 名稱（SSID）不得空白，最長 32 bytes";
        if (!CarWifiPinPolicy.validPassphrase(password))
            return "密碼留白表示開放網路，WPA2 密碼須為 8–63 個 ASCII 字元";

        require(selectedSsid);
        listener = stateListener;
        WifiNetworkSpecifier.Builder specifier = new WifiNetworkSpecifier.Builder()
                .setSsid(selectedSsid);
        if (!password.isEmpty()) specifier.setWpa2Passphrase(password);
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier.build())
                .build();

        final int requestGeneration = generation;
        ConnectivityManager.NetworkCallback requested = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                if (requestGeneration != generation || !required) return;
                selected = network;
                status = "車子 Wi-Fi 已固定連線";
                cancelTimeout();
                updateLowLatencyLock();
                log.i("CAR_WIFI", "specified car Wi-Fi network available id=" + network);
                notifyState(false);
            }

            @Override public void onLost(Network network) {
                if (requestGeneration != generation || !network.equals(selected)) return;
                selected = null;
                releaseLowLatencyLock();
                status = "車子 Wi-Fi 已中斷，請重新連線";
                log.e("CAR_WIFI", "requested car Wi-Fi Network lost; STOP required");
                notifyState(true);
            }

            @Override public void onUnavailable() {
                if (requestGeneration != generation || selected != null) return;
                status = "找不到指定車子 Wi-Fi；請確認電源／SSID／密碼後重連";
                log.e("CAR_WIFI", "Android could not fulfill selected Wi-Fi request");
                unregisterRequest();
                notifyState(false);
            }
        };
        try {
            callback = requested;
            status = "等待 Android 授權並連接指定車子 Wi-Fi…";
            connectivity.requestNetwork(request, requested, main);
            timeout = () -> {
                if (requestGeneration != generation || selected != null) return;
                status = "車子 Wi-Fi 連接逾時；請檢查車子電源與 Wi-Fi 後重連";
                log.e("CAR_WIFI", "selected Wi-Fi request timed out (no network)");
                unregisterRequest();
                notifyState(false);
            };
            main.postDelayed(timeout, 30_000);
            log.i("CAR_WIFI", "requested local-only Wi-Fi via OS picker; credentials not logged");
            return null;
        } catch (RuntimeException error) {
            status = "要求車子 Wi-Fi 失敗，請確認權限後重試";
            unregisterRequest();
            log.e("CAR_WIFI", "Android Wi-Fi request rejected class=" +
                    error.getClass().getSimpleName());
            notifyState(false);
            return status;
        }
    }

    public void setForeground(boolean active) {
        foreground = active;
        updateLowLatencyLock();
    }

    private void updateLowLatencyLock() {
        if (!foreground || selectedNetwork() == null || wifiManager == null ||
                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            releaseLowLatencyLock();
            return;
        }
        if (lowLatencyLock != null && lowLatencyLock.isHeld()) return;
        try {
            if (lowLatencyLock == null) {
                lowLatencyLock = wifiManager.createWifiLock(
                        WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "CrewCar:local-fpv");
                lowLatencyLock.setReferenceCounted(false);
            }
            lowLatencyLock.acquire();
            log.i("CAR_WIFI", "low-latency Wi-Fi lock requested (best effort only)");
        } catch (RuntimeException ex) {
            log.e("CAR_WIFI", "low-latency lock unavailable class=" +
                    ex.getClass().getSimpleName());
        }
    }

    private void releaseLowLatencyLock() {
        if (lowLatencyLock == null) return;
        try {
            if (lowLatencyLock.isHeld()) lowLatencyLock.release();
        } catch (RuntimeException ignored) { }
    }

    private void notifyState(boolean disconnected) {
        Listener current = listener;
        if (current != null) current.onState(status, disconnected);
    }

    private void cancelTimeout() {
        if (timeout != null) main.removeCallbacks(timeout);
        timeout = null;
    }

    private void unregisterRequest() {
        generation++;
        cancelTimeout();
        ConnectivityManager.NetworkCallback previous = callback;
        callback = null;
        if (previous != null) {
            try { connectivity.unregisterNetworkCallback(previous); }
            catch (IllegalArgumentException ignored) { }
        }
    }

    /** Only user explicitly disabling the dedicated connection allows fallback. */
    public void release() {
        unregisterRequest();
        releaseLowLatencyLock();
        selected = null;
        required = false;
        ssid = "";
        status = "已停用車輛專用 Wi-Fi；請手動確認連線";
        listener = null;
    }
}
