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
    private enum Stage { IDLE, FIND_PRODUCT, READ_OFFERS }
    private WebView webView;
    private EditText setNumber;
    private TextView status, firstOffer, secondOffer, difference;
    private LinearLayout results;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final NumberFormat currency = NumberFormat.getCurrencyInstance(new Locale("pl", "PL"));
    private Stage stage = Stage.IDLE;

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
                if (stage == Stage.FIND_PRODUCT) {
                    status.setText("Wybieram produkt z największą liczbą ofert…");
                    handler.postDelayed(MainActivity.this::findLargestProduct, 1800);
                    handler.postDelayed(MainActivity.this::findLargestProduct, 4500);
                } else if (stage == Stage.READ_OFFERS) {
                    status.setText("Sortuję oferty produktu od najtańszej…");
                    handler.postDelayed(MainActivity.this::extractOffers, 1800);
                    handler.postDelayed(MainActivity.this::scrollAndExtractOffers, 4500);
                    handler.postDelayed(MainActivity.this::scrollAndExtractOffers, 8000);
                    handler.postDelayed(MainActivity.this::scrollAndExtractOffers, 12000);
                }
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
        stage = Stage.FIND_PRODUCT;
        String query = Uri.encode("LEGO " + number);
        webView.loadUrl("https://allegro.pl/listing?string=" + query + "&stan=nowe");
    }

    private void findLargestProduct() {
        if (stage != Stage.FIND_PRODUCT) return;
        String js = "javascript:(()=>{" +
            "const n=s=>(s||'').replace(/\\s+/g,' ').trim();" +
            "const count=s=>[...n(s).matchAll(/(\\d[\\d\\s]*)\\s+ofert(?:a|y)?\\b/gi)].reduce((m,x)=>Math.max(m,Number(x[1].replace(/\\s/g,''))||0),0);" +
            "const out=[],seen=new Set();" +
            "document.querySelectorAll('a[href]').forEach(a=>{let k=count(a.innerText);if(!k||seen.has(a.href))return;seen.add(a.href);out.push({url:a.href,count:k,title:n(a.textContent)})});" +
            "document.querySelectorAll('article,[data-role=product],[data-box-name]').forEach(c=>{let k=count(c.innerText);if(!k)return;let a=[...c.querySelectorAll('a[href]')].find(x=>count(x.innerText)>0)||c.querySelector('a[href*=\\\"/produkt/\\\"],a[href*=\\\"product.id\\\"],a[href*=\\\"productId\\\"]');if(!a||seen.has(a.href))return;seen.add(a.href);let h=c.querySelector('h2,h3,[role=heading]');out.push({url:a.href,count:k,title:n(h?.textContent||a.textContent)})});" +
            "out.sort((a,b)=>b.count-a.count);if(out[0])AndroidOffers.onProduct(JSON.stringify(out[0]));else AndroidOffers.onProduct('{}');})()";
        webView.evaluateJavascript(js, null);
    }

    private void extractOffers() {
        if (stage != Stage.READ_OFFERS) return;
        String js = "javascript:(()=>{" +
            "const n=s=>(s||'').replace(/\\s+/g,' ').trim();" +
            "const price=s=>{s=n(s);let m=s.match(/(\\d{1,3}(?:[ .]\\d{3})*|\\d+)\\s*[,.]\\s*(\\d{2})\\s*zł/i);if(m)return Number(m[1].replace(/[ .]/g,'')+'.'+m[2]);m=s.match(/(\\d{1,3}(?:[ .]\\d{3})*|\\d+)\\s+(\\d{2})\\s*zł/i);if(m)return Number(m[1].replace(/[ .]/g,'')+'.'+m[2]);m=s.match(/(\\d{1,3}(?:[ .]\\d{3})*|\\d+)\\s*zł/i);return m?Number(m[1].replace(/[ .]/g,'')):null};" +
            "const seen=new Set(),out=[];" +
            "document.querySelectorAll('a[href*=\"/oferta/\"]').forEach(a=>{" +
            "let href=a.href.split('?')[0];if(seen.has(href))return;" +
            "let c=a.closest('article')||a.closest('[data-role=offer]'),p=null,x=a;for(let i=0;!c&&i<7&&x;i++,x=x.parentElement){if(price(x.innerText)!=null)c=x}if(!c)return;" +
            "p=price(c.innerText);let h=c.querySelector('h2,h3,[role=heading]'),t=n(h?.textContent||a.textContent);" +
            "if(p==null||t.length<4)return;seen.add(href);out.push({title:t,price:p,url:href})});" +
            "out.sort((a,b)=>a.price-b.price);AndroidOffers.onOffers(JSON.stringify(out.slice(0,2)));})()";
        webView.evaluateJavascript(js, null);
    }

    private void scrollAndExtractOffers() {
        if (stage != Stage.READ_OFFERS) return;
        webView.evaluateJavascript("javascript:window.scrollTo(0, Math.min(document.body.scrollHeight, window.scrollY + window.innerHeight * 2))", null);
        handler.postDelayed(this::extractOffers, 700);
    }

    private class OfferBridge {
        @JavascriptInterface public void onProduct(String json) {
            runOnUiThread(() -> {
                if (stage != Stage.FIND_PRODUCT) return;
                try {
                    JSONObject product = new JSONObject(json);
                    String url = product.optString("url");
                    if (url.isEmpty()) {
                        status.setText("Nie znalazłem karty produktu z liczbą ofert. Poczekaj na pełne wczytanie wyników.");
                        return;
                    }
                    status.setText("Wybrano produkt z " + product.getInt("count") + " ofertami. Otwieram listę…");
                    stage = Stage.READ_OFFERS;
                    Uri sorted = Uri.parse(url).buildUpon()
                        .appendQueryParameter("stan", "nowe")
                        .appendQueryParameter("order", "p")
                        .build();
                    webView.loadUrl(sorted.toString());
                } catch (Exception e) {
                    status.setText("Nie udało się wybrać produktu z największą liczbą ofert.");
                }
            });
        }

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
                    stage = Stage.IDLE;
                    status.setText("Dwie najtańsze oferty produktu z największą liczbą ofert.");
                } catch (Exception e) { status.setText("Nie udało się odczytać ofert. Allegro mogło zmienić stronę."); }
            });
        }
    }

    private void bindOffer(TextView view, String label, JSONObject offer) throws Exception {
        String text = label + "\n" + currency.format(offer.getDouble("price"));
        SpannableString linked = new SpannableString(text);
        linked.setSpan(new UnderlineSpan(), label.length() + 1, text.length(), 0);
        view.setText(linked);
        view.setOnClickListener(v -> webView.loadUrl(offer.optString("url")));
    }

    @Override public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }
}
