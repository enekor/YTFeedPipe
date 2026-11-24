package es.eneko.ytfeedpipe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.LoudnessEnhancer;
import android.os.Build;
import android.os.IBinder;

import java.util.HashMap;
import java.util.Map;

/**
 * Servicio en primer plano que amplifica el audio de NewPipe por encima del 100%.
 *
 * NewPipe anuncia su sesión de audio con un broadcast al empezar a reproducir; aquí se
 * le engancha un LoudnessEnhancer. En "modo global" se usa la sesión 0 (mezcla de salida
 * de todo el sistema), que depende del fabricante.
 */
public class BoostService extends Service {

    public static final String PREFS = "boost";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_PERCENT = "percent";
    public static final String KEY_GLOBAL = "global";

    public static final int MIN_PERCENT = 100;
    public static final int MAX_PERCENT = 400;   // +12 dB

    private static final String ACTION_STOP = "es.eneko.ytfeedpipe.BOOST_STOP";
    private static final String NEWPIPE_PREFIX = "org.schabi.newpipe";
    private static final String CHANNEL = "boost";
    private static final int NOTIF_ID = 1;

    /** Estado legible para mostrarlo en el diálogo. */
    public static volatile String status = "Desactivado";

    private final Map<Integer, LoudnessEnhancer> effects = new HashMap<>();
    private boolean receiverRegistered;
    private boolean global;
    private int percent = MIN_PERCENT;

    private final BroadcastReceiver sessionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String pkg = i.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME);
            int session = i.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1);
            if (pkg == null || !pkg.startsWith(NEWPIPE_PREFIX) || session <= 0) return;

            if (AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION.equals(i.getAction())) {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putInt("last_session", session).apply();
                if (!global) attach(session);
            } else {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove("last_session").apply();
                release(session);
            }
            refreshStatus();
        }
    };

    /** Arranca, actualiza o detiene el servicio según las preferencias guardadas. */
    public static void sync(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, MODE_PRIVATE);
        Intent i = new Intent(c, BoostService.class);
        if (!p.getBoolean(KEY_ENABLED, false)) {
            c.stopService(i);
            status = "Desactivado";
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (RuntimeException e) {
            // Android 12+ no deja arrancarlo con la app en segundo plano.
            status = "No se pudo iniciar el servicio";
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            p.edit().putBoolean(KEY_ENABLED, false).apply();
        }

        percent = clamp(p.getInt(KEY_PERCENT, 200));
        showNotification();   // obligatorio tras startForegroundService, aunque luego paremos

        if (!p.getBoolean(KEY_ENABLED, false)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!receiverRegistered) {
            IntentFilter f = new IntentFilter();
            f.addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
            f.addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(sessionReceiver, f, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(sessionReceiver, f);
            }
            receiverRegistered = true;
        }

        boolean wantGlobal = p.getBoolean(KEY_GLOBAL, false);
        if (wantGlobal != global || effects.isEmpty()) {
            releaseAll();
            global = wantGlobal;
            if (global) {
                attach(0);
            } else {
                // Por si NewPipe ya estaba reproduciendo antes de arrancar el servicio.
                int last = p.getInt("last_session", -1);
                if (last > 0) attach(last);
            }
        }
        for (LoudnessEnhancer e : effects.values()) apply(e);
        refreshStatus();
        return START_STICKY;
    }

    private void attach(int session) {
        if (effects.containsKey(session)) return;
        try {
            LoudnessEnhancer e = new LoudnessEnhancer(session);
            effects.put(session, e);
            apply(e);
        } catch (RuntimeException ex) {
            // Sesión inexistente o efecto no soportado por el dispositivo.
        }
    }

    private void apply(LoudnessEnhancer e) {
        try {
            // Ganancia en milibelios: 200% = +6 dB = 602 mB.
            int mb = (int) Math.round(2000.0 * Math.log10(percent / 100.0));
            e.setTargetGain(mb);
            e.setEnabled(mb > 0);
        } catch (RuntimeException ignored) { }
    }

    private void release(int session) {
        LoudnessEnhancer e = effects.remove(session);
        if (e != null) {
            try { e.release(); } catch (RuntimeException ignored) { }
        }
    }

    private void releaseAll() {
        for (LoudnessEnhancer e : effects.values()) {
            try { e.release(); } catch (RuntimeException ignored) { }
        }
        effects.clear();
    }

    private void refreshStatus() {
        if (global) {
            status = effects.isEmpty()
                    ? "Modo global no soportado en este dispositivo"
                    : "Activo en todo el sistema (" + percent + "%)";
        } else {
            status = effects.isEmpty()
                    ? "Esperando a que NewPipe reproduzca…"
                    : "Enganchado a NewPipe (" + percent + "%)";
        }
    }

    private void showNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, "Boost de volumen", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, BoostService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
                .setContentTitle("Boost de volumen: " + percent + "%")
                .setContentText("Toca para ajustar")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Desactivar", stop).build());
        Notification n = b.build();

        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (RuntimeException e) {
            // Android 12+ puede negarlo si el sistema reinicia el servicio en segundo plano.
        }
    }

    private static int clamp(int pct) {
        return Math.max(MIN_PERCENT, Math.min(MAX_PERCENT, pct));
    }

    @Override
    public void onDestroy() {
        if (receiverRegistered) unregisterReceiver(sessionReceiver);
        releaseAll();
        status = "Desactivado";
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
