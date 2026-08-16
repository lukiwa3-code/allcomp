package pl.legoallegro.compare;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private enum Stage { IDLE, FIND_PRODUCT, READ_OFFERS }
    private static final String SETS_FILE = "sets.txt";
    private static final String OWN_SELLER = "lukiwa";
    private static final long SET_DELAY_MS = 10_000L;

    private WebView webView;
    private EditText setNumber;
    private TextView status, batchResults;
    private GridLayout setButtons;
    private Button addButton, removeButton, checkAllButton;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final NumberFormat currency = NumberFormat.getCurrencyInstance(new Locale("pl", "PL"));
    private final ArrayList<String> savedSets = new ArrayList<>();
    private final HashSet<String> selectedSets = new HashSet<>();
    private ArrayList<String> batchQueue = new ArrayList<>();
    private Stage stage = Stage.IDLE;
    private int queueIndex = 0, currentPage = 1, maxPage = 1;
    private int extractionAttempts = 0;
    private String currentNumber = "", productOffersUrl = "";
    private Double firstPrice, secondPrice, ownPrice;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        webView = findViewById(R.id.webView);
        setNumber = findViewById(R.id.setNumber);
        setButtons = findViewById(R.id.setButtons);
        status = findViewById(R.id.status);
        batchResults = findViewById(R.id.batchResults);
        addButton = findViewById(R.id.addButton);
        removeButton = findViewById(R.id.removeButton);
        checkAllButton = findViewById(R.id.checkAllButton);

        webView.setBackgroundColor(Color.WHITE);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.addJavascriptInterface(new OfferBridge(), "AndroidOffers");
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                if (stage == Stage.FIND_PRODUCT) {
                    status.setText("Zestaw " + currentNumber + ": wybieram produkt z największą liczbą ofert…");
                    handler.postDelayed(MainActivity.this::findLargestProduct, 1800);
                    handler.postDelayed(MainActivity.this::findLargestProduct, 4500);
                } else if (stage == Stage.READ_OFFERS) {
                    final int expectedPage = currentPage;
                    status.setText("Zestaw " + currentNumber + ": sprawdzam oferty, strona " + currentPage + "/" + maxPage + "…");
                    handler.postDelayed(() -> extractOffers(expectedPage), 1800);
                    handler.postDelayed(() -> scrollAndExtractOffers(expectedPage), 4500);
                }
            }
        });

        addButton.setOnClickListener(v -> addSet());
        removeButton.setOnClickListener(v -> removeSet());
        checkAllButton.setOnClickListener(v -> checkAll());
        loadSets();
        renderSavedSets();
        webView.loadUrl("https://allegro.pl/");
    }

    private String enteredNumber() {
        return setNumber.getText().toString().trim();
    }

    private boolean validNumber(String number) {
        if (number.matches("\\d{3,8}")) return true;
        Toast.makeText(this, "Podaj numer zestawu, np. 76442", Toast.LENGTH_SHORT).show();
        return false;
    }

    private void addSet() {
        String number = enteredNumber();
        if (!validNumber(number)) return;
        if (!savedSets.contains(number)) savedSets.add(number);
        saveSets();
        renderSavedSets();
        setNumber.setText("");
    }

    private void removeSet() {
        if (!selectedSets.isEmpty()) {
            savedSets.removeAll(selectedSets);
            selectedSets.clear();
        } else {
            String number = enteredNumber();
            if (!validNumber(number)) return;
            savedSets.remove(number);
        }
        saveSets();
        renderSavedSets();
        setNumber.setText("");
    }

    private void loadSets() {
        savedSets.clear();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(openFileInput(SETS_FILE)))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.matches("\\d{3,8}") && !savedSets.contains(line)) savedSets.add(line);
            }
        } catch (Exception ignored) { }
    }

    private void saveSets() {
        try (OutputStreamWriter writer = new OutputStreamWriter(openFileOutput(SETS_FILE, MODE_PRIVATE))) {
            for (String number : savedSets) writer.write(number + "\n");
        } catch (Exception e) {
            Toast.makeText(this, "Nie udało się zapisać sets.txt", Toast.LENGTH_SHORT).show();
        }
    }

    private void renderSavedSets() {
        setButtons.removeAllViews();
        selectedSets.retainAll(savedSets);
        if (savedSets.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Brak zapisanych zestawów");
            empty.setPadding(12, 8, 12, 8);
            setButtons.addView(empty);
            return;
        }
        for (String number : savedSets) {
            ToggleButton button = new ToggleButton(this);
            button.setTextOn(number);
            button.setTextOff(number);
            button.setText(number);
            button.setChecked(selectedSets.contains(number));
            button.setOnCheckedChangeListener((view, checked) -> {
                if (checked) selectedSets.add(number); else selectedSets.remove(number);
            });
            GridLayout.LayoutParams params = new GridLayout.LayoutParams();
            params.width = 0;
            params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
            params.setMargins(4, 4, 4, 4);
            setButtons.addView(button, params);
        }
    }

    private void checkAll() {
        if (stage != Stage.IDLE) return;
        if (savedSets.isEmpty()) {
            Toast.makeText(this, "Najpierw dodaj co najmniej jeden numer", Toast.LENGTH_SHORT).show();
            return;
        }
        batchQueue = new ArrayList<>(savedSets);
        queueIndex = 0;
        batchResults.setText("");
        setControlsEnabled(false);
        startNextSet();
    }

    private void setControlsEnabled(boolean enabled) {
        addButton.setEnabled(enabled);
        removeButton.setEnabled(enabled);
        checkAllButton.setEnabled(enabled);
    }

    private void startNextSet() {
        if (queueIndex >= batchQueue.size()) {
            stage = Stage.IDLE;
            status.setText("Gotowe — sprawdzono " + batchQueue.size() + " zestawów.");
            setControlsEnabled(true);
            return;
        }
        currentNumber = batchQueue.get(queueIndex);
        currentPage = 1;
        maxPage = 1;
        extractionAttempts = 0;
        productOffersUrl = "";
        firstPrice = secondPrice = ownPrice = null;
        stage = Stage.FIND_PRODUCT;
        status.setText("Zestaw " + currentNumber + ": wyszukuję…");
        webView.loadUrl("https://allegro.pl/listing?string=" + Uri.encode("LEGO " + currentNumber) + "&stan=nowe");
    }

    private void findLargestProduct() {
        if (stage != Stage.FIND_PRODUCT) return;
        String js = "javascript:(()=>{" +
            "const n=s=>(s||'').replace(/\\s+/g,' ').trim();" +
            "const count=s=>[...n(s).matchAll(/(\\d[\\d\\s]*)\\s+ofert(?:a|y)?\\b/gi)].reduce((m,x)=>Math.max(m,Number(x[1].replace(/\\s/g,''))||0),0);" +
            "const out=[],seen=new Set();" +
            "[...document.querySelectorAll('button')].find(b=>/nie zgadzam się/i.test(b.textContent||''))?.click();" +
            "document.querySelectorAll('a[href*=\\\"/oferty-produktu/\\\"]').forEach(a=>{let k=count(a.textContent);if(!k||seen.has(a.href))return;seen.add(a.href);out.push({url:a.href,count:k,title:n(a.textContent)})});" +
            "out.sort((a,b)=>b.count-a.count);AndroidOffers.onProduct(JSON.stringify(out[0]||{}));})()";
        webView.evaluateJavascript(js, null);
    }

    private void extractOffers(int expectedPage) {
        if (stage != Stage.READ_OFFERS || expectedPage != currentPage) return;
        String js = "javascript:(()=>{" +
            "const n=s=>(s||'').replace(/\\s+/g,' ').trim();" +
            "const price=s=>{s=n(s);let marker=s.toLowerCase().indexOf('cena z 30 dni');if(marker>=0)s=s.slice(marker+13);let m=s.match(/(\\d{1,3}(?:[ .]\\d{3})*|\\d+)\\s*[,.]\\s*(\\d{2})\\s*zł/i);if(m)return Number(m[1].replace(/[ .]/g,'')+'.'+m[2]);m=s.match(/(\\d{1,3}(?:[ .]\\d{3})*|\\d+)\\s*zł/i);return m?Number(m[1].replace(/[ .]/g,'')):null};" +
            "const seen=new Set(),out=[],expected=location.pathname.replace('/oferty-produktu/','/produkt/');let own=null,maxPage=1;" +
            "document.querySelectorAll('a[href*=\\\"p=\\\"]').forEach(a=>{let p=Number(new URL(a.href).searchParams.get('p'));if(p>maxPage)maxPage=p});" +
            "document.querySelectorAll('a[href*=\\\"offerId=\\\"]').forEach(a=>{let href=a.href;if(new URL(href).pathname!==expected||seen.has(href))return;let c=a.closest('article');if(!c)return;let p=price(c.textContent),compact=(c.textContent||'').replace(/\\s+/g,'').toLowerCase();if(p==null)return;seen.add(href);out.push({price:p});if(compact.includes('lukiwa'))own=p});" +
            "out.sort((a,b)=>a.price-b.price);AndroidOffers.onOffers(JSON.stringify({page:Number(new URL(location.href).searchParams.get('p')||1),maxPage:maxPage,prices:out.slice(0,2),ownPrice:own}));})()";
        webView.evaluateJavascript(js, null);
    }

    private void scrollAndExtractOffers(int expectedPage) {
        if (stage != Stage.READ_OFFERS || expectedPage != currentPage) return;
        webView.evaluateJavascript("javascript:window.scrollTo(0, document.body.scrollHeight)", null);
        handler.postDelayed(() -> extractOffers(expectedPage), 700);
    }

    private class OfferBridge {
        @JavascriptInterface public void onProduct(String json) {
            runOnUiThread(() -> {
                if (stage != Stage.FIND_PRODUCT) return;
                try {
                    JSONObject product = new JSONObject(json);
                    String url = product.optString("url");
                    if (url.isEmpty()) { finishCurrentWithError("brak karty produktu"); return; }
                    productOffersUrl = Uri.parse(url).buildUpon().appendQueryParameter("stan", "nowe").appendQueryParameter("order", "p").build().toString();
                    stage = Stage.READ_OFFERS;
                    webView.loadUrl(productOffersUrl);
                } catch (Exception e) { finishCurrentWithError("błąd wyboru produktu"); }
            });
        }

        @JavascriptInterface public void onOffers(String json) {
            runOnUiThread(() -> {
                if (stage != Stage.READ_OFFERS) return;
                try {
                    JSONObject result = new JSONObject(json);
                    int resultPage = result.getInt("page");
                    if (resultPage != currentPage) return;
                    maxPage = Math.max(maxPage, result.optInt("maxPage", 1));
                    JSONArray prices = result.getJSONArray("prices");
                    if (currentPage == 1 && prices.length() >= 2) {
                        firstPrice = prices.getJSONObject(0).getDouble("price");
                        secondPrice = prices.getJSONObject(1).getDouble("price");
                    }
                    if (!result.isNull("ownPrice")) ownPrice = result.getDouble("ownPrice");
                    boolean pageNotReady = prices.length() == 0 ||
                        (currentPage == 1 && (firstPrice == null || secondPrice == null)) ||
                        ownPrice == null;
                    if (pageNotReady && extractionAttempts < 4) {
                        extractionAttempts++;
                        final int retryPage = currentPage;
                        status.setText("Zestaw " + currentNumber + ": czekam na oferty, próba " + (extractionAttempts + 1) + "/5…");
                        handler.postDelayed(() -> scrollAndExtractOffers(retryPage), 1800);
                        return;
                    }
                    extractionAttempts = 0;
                    if (ownPrice == null && currentPage < maxPage) {
                        currentPage++;
                        extractionAttempts = 0;
                        webView.loadUrl(Uri.parse(productOffersUrl).buildUpon().appendQueryParameter("p", String.valueOf(currentPage)).build().toString());
                    } else {
                        finishCurrentResult();
                    }
                } catch (Exception e) { finishCurrentWithError("nie udało się odczytać cen"); }
            });
        }
    }

    private void finishCurrentResult() {
        if (firstPrice == null || secondPrice == null) { finishCurrentWithError("mniej niż dwie rozpoznane ceny"); return; }
        String mine = ownPrice == null ? "brak oferty" : currency.format(ownPrice);
        appendResult(currentNumber + "\nNajtańsza: " + currency.format(firstPrice) + "\nDruga: " + currency.format(secondPrice) + "\nMoja cena (" + OWN_SELLER + "): " + mine);
        advanceQueue();
    }

    private void finishCurrentWithError(String error) {
        appendResult(currentNumber + "\nBłąd: " + error);
        advanceQueue();
    }

    private void appendResult(String value) {
        if (batchResults.length() > 0) batchResults.append("\n\n");
        batchResults.append(value);
    }

    private void advanceQueue() {
        stage = Stage.IDLE;
        queueIndex++;
        if (queueIndex < batchQueue.size()) {
            status.setText("Następny zestaw za 10 sekund…");
            handler.postDelayed(this::startNextSet, SET_DELAY_MS);
        } else {
            startNextSet();
        }
    }

    @Override public void onBackPressed() {
        if (stage == Stage.IDLE && webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }
}
