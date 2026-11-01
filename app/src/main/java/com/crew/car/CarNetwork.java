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

    /** Prefer cellular when the car's Wi-Fi has no internet; do not bind the whole process. */
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
        return fallback;
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
