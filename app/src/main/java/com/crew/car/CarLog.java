package com.crew.car;

import android.util.Log;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.function.Consumer;

public final class CarLog {
    public static final String TAG = "CrewCar";
    private final ArrayDeque<String> recent = new ArrayDeque<>();
    private Consumer<String> listener;

    public synchronized void setListener(Consumer<String> listener) {
        this.listener = listener;
    }

    public void i(String scope, String message) {
        add(scope, message, false);
    }

    public void e(String scope, String message) {
        add(scope, message, true);
    }

    private synchronized void add(String scope, String message, boolean error) {
        String safe = message == null ? "" : message.replaceAll("(?i)(key|api_key|token)=[^\\s&]+", "$1=[REDACTED]");
        String entry = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date())
                + " [" + scope + "] " + safe;
        if (error) Log.e(TAG, entry); else Log.d(TAG, entry);
        recent.addLast(entry);
        while (recent.size() > 180) recent.removeFirst();
        if (listener != null) listener.accept(snapshot());
    }

    public synchronized String snapshot() {
        return String.join("\n", recent);
    }
}
