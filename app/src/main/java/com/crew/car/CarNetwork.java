package com.crew.car;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import java.util.function.Consumer;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;

public final class CarNetwork {
    private final ConnectivityManager cm;
    private final CarLog log;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ConnectivityManager.NetworkCallback cellularRequest;
    private Consumer<Network> pendingCellularReady;
    private Runnable pendingCellularFailure;
    private Runnable cellularTimeout;
    private int cellularGeneration;
    private DatagramSocket controlSocket;
    private Network controlNetwork;
    private String controlHost;
    private int controlPort;
    private InetAddress controlDestination;
    public CarNetwork(Context ctx, CarLog log) {
        cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.log = log;
    }

    /** Car access is explicitly bound to a Wi-Fi network, not the cellular default route. */
    public Network wifi() {
        Network fallback = null;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities cap = cm.getNetworkCapabilities(n);
            if (cap != null && cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                if (!cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return n;
                fallback = n;
            }
        }
        return fallback;
    }

    /** Prefer a separately validated cellular network for car Wi-Fi. When no car is present,
     * allow ordinary internet Wi-Fi for read-only Live tool smoke tests. Never bind the process.
     */
    public Network internet(Network car) {
        Network fallback = null;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities cap = cm.getNetworkCapabilities(n);
            if (cap == null || n.equals(car) ||
                    !cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                    !cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) continue;
            if (cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return n;
            fallback = n;
        }
        // An offline toy Wi-Fi must never be used for a cloud websocket.
        if (car != null && !isValidatedInternet(car)) return null;
        if (fallback != null) return fallback;
        return isValidatedInternet(car) ? car : null;
    }

    public boolean isValidatedInternet(Network n) {
        if (n == null) return false;
        NetworkCapabilities cap = cm.getNetworkCapabilities(n);
        return cap != null &&
                cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
                cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    /**
     * Request cellular data alongside the toy's offline Wi-Fi.
     * The caller must retain the request until Live disconnects.
     * requestNetwork requires CHANGE_NETWORK_STATE and cannot request VALIDATED
     * as a constraint. Wait until onCapabilitiesChanged confirms validation.
     */
    public void requestCellularForLive(Consumer<Network> onReady, Runnable onFailure) {
        releaseCellularForLive();
        final int generation = cellularGeneration;
        pendingCellularReady = onReady;
        pendingCellularFailure = onFailure;
        ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) {
                checkCellularReady(network, cm.getNetworkCapabilities(network), generation);
            }

            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                checkCellularReady(network, caps, generation);
            }

            @Override public void onLost(Network network) {
                log.i("NET", "requested cellular route lost; car Wi-Fi unaffected");
            }

            @Override public void onUnavailable() {
                failCellular(generation, "cellular request unavailable");
            }
        };
        cellularRequest = callback;
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        try {
            cm.requestNetwork(request, callback, main);
            log.i("NET", "requested CELLULAR + INTERNET alongside car Wi-Fi");
        } catch (RuntimeException e) {
            log.e("NET", "cellular request denied class=" + e.getClass().getSimpleName());
            failCellular(generation, "cellular request rejected");
            return;
        }
        if (cellularRequest == callback && pendingCellularReady != null) {
            cellularTimeout = () -> failCellular(generation, "cellular not validated within 18s");
            main.postDelayed(cellularTimeout, 18_000);
        }
    }

    private void checkCellularReady(Network network, NetworkCapabilities caps, int generation) {
        if (generation != cellularGeneration || pendingCellularReady == null || caps == null)
            return;
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return;
        Consumer<Network> ready = pendingCellularReady;
        pendingCellularReady = null;
        pendingCellularFailure = null;
        if (cellularTimeout != null) main.removeCallbacks(cellularTimeout);
        cellularTimeout = null;
        log.i("NET", "validated CELLULAR route ready for Gemini; car Wi-Fi remains separate");
        ready.accept(network);
    }

    private void failCellular(int generation, String explanation) {
        if (generation != cellularGeneration || pendingCellularReady == null) return;
        Runnable failed = pendingCellularFailure;
        log.e("NET", explanation);
        releaseCellularForLive();
        if (failed != null) failed.run();
    }

    /** Unregister when Live disconnects, app exits, or a pending request expires. */
    public void releaseCellularForLive() {
        cellularGeneration++;
        pendingCellularReady = null;
        pendingCellularFailure = null;
        if (cellularTimeout != null) main.removeCallbacks(cellularTimeout);
        cellularTimeout = null;
        ConnectivityManager.NetworkCallback callback = cellularRequest;
        cellularRequest = null;
        if (callback != null) {
            try {
                cm.unregisterNetworkCallback(callback);
            } catch (IllegalArgumentException ignored) {
                // Android may already have released a timed-out request.
            }
        }
    }

    public boolean cellularRequestActive() { return cellularRequest != null; }

    public URLConnection carConnection(URL url) throws Exception {
        Network n = wifi();
        if (n == null) throw new IllegalStateException("沒有 Wi-Fi 網路，請先連小車 Wi-Fi");
        return n.openConnection(url);
    }

    public void send(String host, int port, boolean tcp, byte[] payload) throws Exception {
        Network n = wifi();
        if (n == null) throw new IllegalStateException("Wi-Fi 已斷線");
        if (host == null || host.isBlank() || port < 1 || port > 65535)
            throw new IllegalArgumentException("請確認控制 IP 與 Port");
        InetAddress dest = n.getAllByName(host)[0];
        if (tcp) {
            try (Socket socket = n.getSocketFactory().createSocket()) {
                socket.connect(new InetSocketAddress(dest, port), 1500);
                socket.setSoTimeout(1500);
                socket.getOutputStream().write(payload);
                socket.getOutputStream().flush();
            }
        } else {
            try (DatagramSocket socket = new DatagramSocket()) {
                n.bindSocket(socket);
                socket.send(new DatagramPacket(payload, payload.length, dest, port));
            }
        }
    }

    /**
     * Persistent UDP socket for the 50 ms motor control loop.
     * Unlike camera-start packets, control traffic keeps one bound source port.
     * A changed Wi-Fi Network or endpoint forces a fresh socket; TX is still not an ACK.
     */
    public synchronized void sendControl(String host, int port, boolean tcp, byte[] payload)
            throws Exception {
        if (tcp) {
            closeControlChannel();
            send(host, port, true, payload);
            return;
        }
        Network wifi = wifi();
        if (wifi == null) {
            closeControlChannel();
            throw new IllegalStateException("Car Wi-Fi disconnected");
        }
        if (host == null || host.isBlank() || port < 1 || port > 65535)
            throw new IllegalArgumentException("Invalid car endpoint");
        if (controlSocket == null || controlSocket.isClosed() ||
                !wifi.equals(controlNetwork) || !host.equals(controlHost) || port != controlPort) {
            closeControlChannel();
            DatagramSocket socket = new DatagramSocket();
            try {
                wifi.bindSocket(socket);
                InetAddress destination = wifi.getAllByName(host)[0];
                controlSocket = socket;
                controlNetwork = wifi;
                controlHost = host;
                controlPort = port;
                controlDestination = destination;
                log.i("NET", "UDP control socket bound network=" + wifi +
                        " destination=" + host + ":" + port + " sourcePort=" + socket.getLocalPort());
            } catch (Exception e) {
                socket.close();
                throw e;
            }
        }
        try {
            controlSocket.send(new DatagramPacket(payload, payload.length, controlDestination, controlPort));
        } catch (Exception e) {
            closeControlChannel();
            throw e;
        }
    }

    public synchronized void closeControlChannel() {
        if (controlSocket != null) controlSocket.close();
        controlSocket = null;
        controlNetwork = null;
        controlHost = null;
        controlPort = 0;
        controlDestination = null;
    }

    public void diagnostics() {
        Network car = wifi();
        log.i("NET", "carWiFi=" + (car != null) + " internetViaSeparateNetwork=" + (internet(car) != null));
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            LinkProperties lp = cm.getLinkProperties(n);
            if (caps == null) continue;
            String type = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "WIFI" :
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "CELLULAR" : "OTHER";
            log.i("NET", "net=" + n + " type=" + type +
                    " validated=" + caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) +
                    " internet=" + caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
            if (lp != null) {
                log.i("NET", "iface=" + lp.getInterfaceName() + " addresses=" + lp.getLinkAddresses());
                for (RouteInfo route : lp.getRoutes())
                    if (route.hasGateway()) log.i("NET", "gateway=" + route.getGateway().getHostAddress());
            }
        }
    }
}
