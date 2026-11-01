package com.crew.car;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;

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
