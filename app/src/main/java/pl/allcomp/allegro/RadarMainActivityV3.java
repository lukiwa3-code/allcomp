package pl.allcomp.allegro;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.Toast;

import java.lang.reflect.Method;

/**
 * v3 wrapper for the mobile scanner.
 * - keeps the display awake while the app is open
 * - forces Sales Center to the very top before a scan starts so the first offer is not skipped
 */
public class RadarMainActivityV3 extends MobileMainActivity {
    private final Handler v3Handler = new Handler(Looper.getMainLooper());
    private WebView radarWebView;

    @Override
    public void onCreate(Bundle state) {
        // Prevent Android/Samsung screen timeout while Radar is open.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onCreate(state);

        View root = findViewById(android.R.id.content);
        radarWebView = findWebView(root);
        if (radarWebView != null) radarWebView.setKeepScreenOn(true);

        // Replace only the two scan button listeners. Everything else remains v2 logic.
        replaceScanButtons(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (radarWebView != null) radarWebView.setKeepScreenOn(true);
    }

    private void replaceScanButtons(View view) {
        if (view instanceof Button) {
            Button b = (Button) view;
            String text = String.valueOf(b.getText()).trim();
            if ("Skanuj 5".equalsIgnoreCase(text)) {
                b.setOnClickListener(v -> prepareAndStartScan(5));
            } else if ("Skanuj wszystkie".equalsIgnoreCase(text)) {
                b.setOnClickListener(v -> prepareAndStartScan(0));
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) replaceScanButtons(g.getChildAt(i));
        }
    }

    private WebView findWebView(View view) {
        if (view instanceof WebView) return (WebView) view;
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                WebView found = findWebView(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private void prepareAndStartScan(int limit) {
        if (radarWebView == null) {
            Toast.makeText(this, "Nie widzę Sales Center. Otwórz listę ofert.", Toast.LENGTH_LONG).show();
            return;
        }

        // Sales Center virtualizes the mobile list. One jump to 0 can still leave the first
        // card unrendered, so we force the top several times and give React time to repaint.
        forceTop();
        v3Handler.postDelayed(this::forceTop, 300);
        v3Handler.postDelayed(this::forceTop, 750);
        v3Handler.postDelayed(() -> invokeOriginalStartScan(limit), 1400);
    }

    private void forceTop() {
        if (radarWebView == null) return;
        String js = "(function(){try{" +
                "window.scrollTo(0,0);" +
                "const a=[document.scrollingElement,...document.querySelectorAll('*')].filter(Boolean);" +
                "for(const e of a){try{if(e.scrollHeight>e.clientHeight+40)e.scrollTop=0}catch(x){}}" +
                "window.scrollTo(0,0);return true" +
                "}catch(e){return false}})()";
        radarWebView.evaluateJavascript(js, null);
    }

    private void invokeOriginalStartScan(int limit) {
        try {
            Method m = MobileMainActivity.class.getDeclaredMethod("startSalesScan", int.class);
            m.setAccessible(true);
            m.invoke(this, limit);
        } catch (Exception e) {
            Toast.makeText(this, "Błąd startu skanowania: " + e.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
        }
    }
}
