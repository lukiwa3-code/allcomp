package pl.legoallegro.compare;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.style.UnderlineSpan;
import android.net.Uri;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.text.NumberFormat;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private WebView webView;
    private EditText setNumber;
    private TextView status, firstOffer, secondOffer, difference;
    private LinearLayout results;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final NumberFormat currency = NumberFormat.getCurrencyInstance(new Locale("pl", "PL"));

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.webView);
        setNumber = findViewById(R.id.setNumber);
        status = findViewById(R.id.status);
        results = findViewById(R.id.results);
        firstOffer = findViewById(R.id.firstOffer);
        secondOffer = findViewById(R.id.secondOffer);
        difference = findViewById(R.id.difference);
        Button search = findViewById(R.id.searchButton);

        webView.setBackgroundColor(Color.WHITE);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setUserAgentString(webView.getSettings().getUserAgentString() + " LegoPriceCompare/1.0");
        webView.addJavascriptInterface(new OfferBridge(), "AndroidOffers");
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                status.setText("Analizuję widoczne oferty…");
                handler.postDelayed(MainActivity.this::extractOffers, 1800);
                handler.postDelayed(MainActivity.this::extractOffers, 4500);
            }
        });
        search.setOnClickListener(v -> search());
        setNumber.setOnEditorActionListener((v, actionId, event) -> { search(); return true; });
        webView.loadUrl("https://allegro.pl/");
    }

    private void search() {
        String number = setNumber.getText().toString().trim();
        if (!number.matches("\\d{3,8}")) {
            Toast.makeText(this, "Podaj numer zestawu, np. 76476", Toast.LENGTH_SHORT).show();
            return;
        }
        results.setVisibility(View.GONE);
        status.setText("Szukam nowych zestawów LEGO " + number + "…");
        String query = Uri.encode("LEGO " + number);
        webView.loadUrl("https://allegro.pl/listing?string=" + query + "&stan=nowe&order=p");
    }

    private void extractOffers() {
        String js = "javascript:(()=>{" +
            "const n=s=>(s||'').replace(/\\s+/g,' ').trim();" +
            "const price=s=>{const m=n(s).replace(/zł/gi,'').match(/(?:^|\\s)(\\d{1,3}(?:[ .]\\d{3})*|\\d+)[,.](\\d{2})(?:\\s|$)/);return m?Number(m[1].replace(/[ .]/g,'')+'.'+m[2]):null};" +
            "const seen=new Set(),out=[];" +
            "document.querySelectorAll('a[href*=\"/oferta/\"]').forEach(a=>{" +
            "let href=a.href.split('?')[0];if(seen.has(href))return;" +
            "let c=a.closest('article')||a.closest('[data-role=offer]')||a.parentElement?.parentElement;if(!c)return;" +
            "let p=price(c.innerText),h=c.querySelector('h2,h3,[role=heading]'),t=n(h?.textContent||a.textContent);" +
            "if(p==null||t.length<4)return;seen.add(href);out.push({title:t,price:p,url:href})});" +
            "out.sort((a,b)=>a.price-b.price);AndroidOffers.onOffers(JSON.stringify(out.slice(0,2)));})()";
        webView.evaluateJavascript(js, null);
    }

    private class OfferBridge {
        @JavascriptInterface public void onOffers(String json) {
            runOnUiThread(() -> {
                try {
                    JSONArray offers = new JSONArray(json);
                    if (offers.length() < 2) {
                        status.setText("Za mało ofert. Przewiń wyniki i dotknij strony, aby je wczytać.");
                        return;
                    }
                    JSONObject first = offers.getJSONObject(0), second = offers.getJSONObject(1);
                    bindOffer(firstOffer, "NAJTAŃSZA", first);
                    bindOffer(secondOffer, "DRUGA NAJTAŃSZA", second);
                    difference.setText("Różnica: " + currency.format(second.getDouble("price") - first.getDouble("price")) + "\nCeny bez dostawy.");
                    results.setVisibility(View.VISIBLE);
                    status.setText("Znaleziono dwie najtańsze widoczne oferty.");
                } catch (Exception e) { status.setText("Nie udało się odczytać ofert. Allegro mogło zmienić stronę."); }
            });
        }
    }

    private void bindOffer(TextView view, String label, JSONObject offer) throws Exception {
        String text = label + "\n" + offer.getString("title") + "\n" + currency.format(offer.getDouble("price"));
        SpannableString linked = new SpannableString(text);
        linked.setSpan(new UnderlineSpan(), label.length() + 1, text.length(), 0);
        view.setText(linked);
        view.setOnClickListener(v -> webView.loadUrl(offer.optString("url")));
    }

    @Override public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }
}
