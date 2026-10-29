package es.eneko.ytfeedpipe;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    private static final String HOME = "https://m.youtube.com/";
    private static final String NEWPIPE = "org.schabi.newpipe";

    // UA de Chrome móvil normal (sin "; wv") para que Google permita iniciar sesión.
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 "
          + "(KHTML, like Gecko) Chrome/119.0.0.0 Mobile Safari/537.36";

    private static final Pattern ID_PATTERN = Pattern.compile(
            "(?:[?&]v=|/shorts/|youtu\\.be/)([A-Za-z0-9_-]{11})");

    // Intercepta los toques en enlaces a vídeos antes de que YouTube los gestione.
    private static final String CLICK_HOOK =
            "(function(){if(window.__ytfp)return;window.__ytfp=1;"
          + "document.addEventListener('click',function(e){"
          + "var a=e.target.closest&&e.target.closest('a[href*=\"/watch\"],a[href*=\"/shorts/\"]');"
          + "if(!a)return;e.preventDefault();e.stopImmediatePropagation();"
          + "YTFP.open(a.href);},true);})();";

    private WebView web;
    private long lastLaunch = 0;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString(UA);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setSupportMultipleWindows(false);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.addJavascriptInterface(new Bridge(), "YTFP");
        web.setWebViewClient(new Client());

        if (state != null) web.restoreState(state);
        else web.loadUrl(HOME);
    }

    /** Puente JS -> Java. */
    private class Bridge {
        @JavascriptInterface
        public void open(final String url) {
            runOnUiThread(() -> openInNewPipe(url));
        }
    }

    private class Client extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            return handleNav(r.getUrl().toString());
        }

        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView v, String url) {
            return handleNav(url);
        }

        @Override
        public void onPageStarted(WebView v, String url, Bitmap fav) {
            v.evaluateJavascript(CLICK_HOOK, null);
        }

        @Override
        public void onPageFinished(WebView v, String url) {
            v.evaluateJavascript(CLICK_HOOK, null);
            CookieManager.getInstance().flush();
        }

        // Plan B: YouTube navega con pushState (SPA); si se cuela un /watch, lo capturamos aquí.
        @Override
        public void doUpdateVisitedHistory(WebView v, String url, boolean reload) {
            if (isVideo(url)) {
                openInNewPipe(url);
                if (v.canGoBack()) v.goBack(); else v.loadUrl(HOME);
            }
        }

        // Bloquea la descarga de vídeo dentro del WebView (previsualizaciones automáticas).
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
            String host = r.getUrl().getHost();
            if (host != null && (host.endsWith("googlevideo.com")
                    || host.endsWith("doubleclick.net")
                    || host.endsWith("googlesyndication.com"))) {
                return new WebResourceResponse("text/plain", "utf-8",
                        new ByteArrayInputStream(new byte[0]));
            }
            return null;
        }
    }

    /** true = navegación consumida. */
    private boolean handleNav(String url) {
        if (isVideo(url)) {
            openInNewPipe(url);
            return true;
        }
        Uri u = Uri.parse(url);
        String scheme = u.getScheme();
        if (scheme != null && !scheme.startsWith("http")) {
            // intent://, vnd.youtube:// etc. -> ignorar
            return true;
        }
        return false;
    }

    private static boolean isVideo(String url) {
        return url != null && (url.contains("/watch") || url.contains("/shorts/"))
                && ID_PATTERN.matcher(url).find();
    }

    private void openInNewPipe(String url) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastLaunch < 1500) return; // evita doble apertura (click + pushState)
        lastLaunch = now;

        Matcher m = ID_PATTERN.matcher(url);
        if (!m.find()) return;
        Uri target = Uri.parse("https://www.youtube.com/watch?v=" + m.group(1));

        Intent i = new Intent(Intent.ACTION_VIEW, target);
        i.setPackage(NEWPIPE);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "NewPipe no encontrado", Toast.LENGTH_SHORT).show();
            i.setPackage(null);
            try { startActivity(Intent.createChooser(i, "Abrir con")); }
            catch (ActivityNotFoundException ignored) { }
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
        web.pauseTimers();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        web.resumeTimers();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW) web.freeMemory();
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }
}
