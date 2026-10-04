package com.enjt.roguelike;

import android.app.Activity;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Contenedor del juego: un WebView a pantalla completa.
 *
 * El HTML se sirve siempre desde la misma dirección interna (https://juego.local/index.html),
 * venga de la APK o de una actualización descargada, para que el progreso guardado
 * (localStorage) sea el mismo entre versiones.
 */
public class MainActivity extends Activity {
    private static final String HOST = "juego.local";
    private static final String GAME_URL = "https://" + HOST + "/index.html";
    private static final String PREFS = "actualizador";
    private static final String KEY_VERSION = "version_descargada";
    private static final String GAME_FILE = "game.html";

    private WebView web;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        web.setBackgroundColor(0xFF0D0A12);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true); // localStorage: progreso del juego
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (!HOST.equals(u.getHost())) return null; // p. ej. tipografías: van por red
                try {
                    if ("/index.html".equals(u.getPath())) {
                        return new WebResourceResponse("text/html", "utf-8", openGame());
                    }
                } catch (IOException ignored) {
                }
                return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !HOST.equals(request.getUrl().getHost()); // el juego no navega fuera
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onCloseWindow(WebView window) {
                finish(); // botón "Salir" del juego
            }
        });

        setContentView(web);
        web.loadUrl(GAME_URL);
        checkForUpdate();
    }

    /** Versión que se está jugando: la descargada si es más nueva que la incluida en la APK. */
    private String currentVersion() {
        String downloaded = prefs.getString(KEY_VERSION, null);
        File f = new File(getFilesDir(), GAME_FILE);
        if (downloaded != null && f.length() > 0 && compareVersions(downloaded, BuildConfig.VERSION_NAME) > 0) {
            return downloaded;
        }
        return BuildConfig.VERSION_NAME;
    }

    private InputStream openGame() throws IOException {
        if (!currentVersion().equals(BuildConfig.VERSION_NAME)) {
            return new FileInputStream(new File(getFilesDir(), GAME_FILE));
        }
        return getAssets().open("index.html");
    }

    /**
     * Consulta version.json en UPDATE_URL y, si anuncia una versión más nueva, descarga el HTML.
     * La nueva versión se usa a partir del siguiente arranque. Cualquier fallo (sin conexión,
     * archivo corrupto...) se ignora y se sigue jugando con la versión actual.
     */
    private void checkForUpdate() {
        final String base = BuildConfig.UPDATE_URL;
        if (base.isEmpty()) return;
        new Thread(() -> {
            try {
                String noCache = "?t=" + System.currentTimeMillis();
                JSONObject info = new JSONObject(new String(fetch(base + "/version.json" + noCache, 64 * 1024), StandardCharsets.UTF_8));
                String remote = info.getString("version");
                if (compareVersions(remote, currentVersion()) <= 0) return;

                byte[] html = fetch(base + "/" + info.optString("file", "index.html") + noCache, 16 * 1024 * 1024);
                if (html.length < 1000 || !new String(html, StandardCharsets.UTF_8).contains("</html>")) return;
                String sha = info.optString("sha256", "");
                if (!sha.isEmpty() && !sha.equalsIgnoreCase(sha256(html))) return;

                File tmp = new File(getFilesDir(), GAME_FILE + ".tmp");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(html);
                }
                if (!tmp.renameTo(new File(getFilesDir(), GAME_FILE))) return;
                prefs.edit().putString(KEY_VERSION, remote).apply();
                runOnUiThread(() -> Toast.makeText(this,
                        "Versión " + remote + " descargada. Se aplicará al reiniciar el juego.",
                        Toast.LENGTH_LONG).show());
            } catch (Exception ignored) {
            }
        }).start();
    }

    private static byte[] fetch(String url, int maxBytes) throws IOException {
        if (!url.startsWith("https://")) throw new IOException("solo HTTPS");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setUseCaches(false);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > maxBytes) throw new IOException("demasiado grande");
                }
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    private static String sha256(byte[] data) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** Compara versiones tipo "0.1.47" número a número. */
    static int compareVersions(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int d = Integer.compare(part(x, i), part(y, i));
            if (d != 0) return d;
        }
        return 0;
    }

    private static int part(String[] v, int i) {
        if (i >= v.length) return 0;
        String digits = v[i].replaceAll("\\D", "");
        return digits.isEmpty() ? 0 : Integer.parseInt(digits);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) { // pantalla completa inmersiva
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause(); // pausa el juego y la música en segundo plano
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }
}
