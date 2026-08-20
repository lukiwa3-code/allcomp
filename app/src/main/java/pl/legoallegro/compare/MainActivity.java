package pl.legoallegro.compare;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private enum Stage { IDLE, LOAD_LEGO_DEALS, FIND_PRODUCT, READ_OFFERS, READ_OWN_PRICE }
    private enum Mode { SAVED_SETS, LEGO_DEALS }

    private static class DealProduct {
        final String number;
        final String title;
        final double legoPrice;

        DealProduct(String number, String title, double legoPrice) {
            this.number = number;
            this.title = title;
            this.legoPrice = legoPrice;
        }
    }

    private static final String SETS_FILE = "sets.txt";
    private static final String OWN_SELLER = "lukiwa";
    private static final String LEGO_DEALS_URL = "https://www.lego.com/pl-pl/categories/sales-and-deals";
    private static final long SAVED_SET_DELAY_MS = 10_000L;
    private static final long LEGO_DEALS_DELAY_MS = 2_000L;

    private WebView webView;
    private EditText setNumber;
    private TextView status, batchResults, dealsStatus;
    private GridLayout setButtons;
    private LinearLayout dealsResults;
    private ScrollView savedTabContent, dealsTabContent;
    private Button addButton, removeButton, checkAllButton, refreshDealsButton;
    private Button savedTabButton, dealsTabButton;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final NumberFormat currency = NumberFormat.getCurrencyInstance(new Locale("pl", "PL"));
    private final ArrayList<String> savedSets = new ArrayList<>();
    private final HashSet<String> selectedSets = new HashSet<>();
    private final LinkedHashMap<String, DealProduct> legoDeals = new LinkedHashMap<>();
    private ArrayList<String> batchQueue = new ArrayList<>();
    private Stage stage = Stage.IDLE;
    private Mode mode = Mode.SAVED_SETS;
    private int queueIndex = 0, currentPage = 1, maxPage = 1;
    private int extractionAttempts = 0;
    private int productAttempts = 0;
    private int ownPriceAttempts = 0;
    private int legoCatalogPage = 0;
    private int legoCatalogAttempts = 0;
    private boolean legoPageAdvancePending = false;
    private String currentNumber = "", productOffersUrl = "", expectedProductPath = "";
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
        dealsStatus = findViewById(R.id.dealsStatus);
        dealsResults = findViewById(R.id.dealsResults);
        savedTabContent = findViewById(R.id.savedTabContent);
        dealsTabContent = findViewById(R.id.dealsTabContent);
        addButton = findViewById(R.id.addButton);
        removeButton = findViewById(R.id.removeButton);
        checkAllButton = findViewById(R.id.checkAllButton);
        refreshDealsButton = findViewById(R.id.refreshDealsButton);
        savedTabButton = findViewById(R.id.savedTabButton);
        dealsTabButton = findViewById(R.id.dealsTabButton);

        webView.setBackgroundColor(Color.WHITE);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.addJavascriptInterface(new OfferBridge(), "AndroidOffers");
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                if (stage == Stage.LOAD_LEGO_DEALS) {
                    setProgressStatus("Pobieram produkty promocyjne z LEGO.com…");
                    handler.postDelayed(MainActivity.this::extractLegoDeals, 2200);
                    handler.postDelayed(MainActivity.this::extractLegoDeals, 5200);
                } else if (stage == Stage.FIND_PRODUCT) {
                    setProgressStatus("Zestaw " + currentNumber + ": wybieram produkt z największą liczbą ofert…");
                    handler.postDelayed(MainActivity.this::findLargestProduct, 1800);
                    handler.postDelayed(MainActivity.this::findLargestProduct, 4500);
                } else if (stage == Stage.READ_OFFERS) {
                    final int expectedPage = currentPage;
                    setProgressStatus("Zestaw " + currentNumber + ": sprawdzam oferty, strona " + currentPage + "/" + maxPage + "…");
                    handler.postDelayed(() -> extractOffers(expectedPage), 1800);
                    handler.postDelayed(() -> scrollAndExtractOffers(expectedPage), 4500);
                } else if (stage == Stage.READ_OWN_PRICE) {
                    setProgressStatus("Zestaw " + currentNumber + ": sprawdzam cenę sprzedawcy " + OWN_SELLER + "…");
                    handler.postDelayed(MainActivity.this::extractOwnPrice, 1800);
                    handler.postDelayed(MainActivity.this::extractOwnPrice, 4500);
                }
            }
        });

        addButton.setOnClickListener(v -> addSet());
        removeButton.setOnClickListener(v -> removeSet());
        checkAllButton.setOnClickListener(v -> checkAll());
        refreshDealsButton.setOnClickListener(v -> startDealsComparison());
        savedTabButton.setOnClickListener(v -> showTab(false));
        dealsTabButton.setOnClickListener(v -> showTab(true));
        loadSets();
        renderSavedSets();
        showTab(false);
        webView.loadUrl("https://allegro.pl/");
    }

    private void showTab(boolean deals) {
        savedTabContent.setVisibility(deals ? View.GONE : View.VISIBLE);
        dealsTabContent.setVisibility(deals ? View.VISIBLE : View.GONE);
        int red = Color.rgb(215, 25, 32);
        int gray = Color.rgb(232, 232, 232);
        savedTabButton.setBackgroundTintList(ColorStateList.valueOf(deals ? gray : red));
        savedTabButton.setTextColor(deals ? Color.BLACK : Color.WHITE);
        dealsTabButton.setBackgroundTintList(ColorStateList.valueOf(deals ? red : gray));
        dealsTabButton.setTextColor(deals ? Color.WHITE : Color.BLACK);
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
            button.setBackgroundTintList(new ColorStateList(
                new int[][] { new int[] { android.R.attr.state_checked }, new int[] {} },
                new int[] { Color.rgb(215, 25, 32), Color.rgb(232, 232, 232) }
            ));
            button.setTextColor(new ColorStateList(
                new int[][] { new int[] { android.R.attr.state_checked }, new int[] {} },
                new int[] { Color.WHITE, Color.BLACK }
            ));
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
        mode = Mode.SAVED_SETS;
        batchQueue = new ArrayList<>(savedSets);
        queueIndex = 0;
        batchResults.setText("");
        setControlsEnabled(false);
        startNextSet();
    }

    private void startDealsComparison() {
        if (stage != Stage.IDLE) return;
        mode = Mode.LEGO_DEALS;
        showTab(true);
        legoDeals.clear();
        dealsResults.removeAllViews();
        legoCatalogPage = 0;
        legoCatalogAttempts = 0;
        legoPageAdvancePending = false;
        setControlsEnabled(false);
        stage = Stage.LOAD_LEGO_DEALS;
        setProgressStatus("Ładuję aktualne promocje LEGO.com…");
        webView.loadUrl(LEGO_DEALS_URL);
    }

    private void setControlsEnabled(boolean enabled) {
        addButton.setEnabled(enabled);
        removeButton.setEnabled(enabled);
        checkAllButton.setEnabled(enabled);
        refreshDealsButton.setEnabled(enabled);
    }

    private void setProgressStatus(String value) {
        if (mode == Mode.LEGO_DEALS) dealsStatus.setText(value); else status.setText(value);
    }

    private void extractLegoDeals() {
        if (stage != Stage.LOAD_LEGO_DEALS || legoPageAdvancePending) return;
        String js = "javascript:(()=>{" +
            "const gate=document.querySelector('button[data-test=\\\"age-gate-grown-up-cta\\\"]');if(gate){gate.click();AndroidOffers.onLegoDeals(JSON.stringify({waiting:true}));return;}" +
            "const cookies=document.querySelector('button[data-test=\\\"cookie-necessary-button\\\"]');if(cookies){cookies.click();AndroidOffers.onLegoDeals(JSON.stringify({waiting:true}));return;}" +
            "const n=s=>(s||'').replace(/\\s+/g,' ').trim();" +
            "const price=s=>{let m=n(s).match(/(\\d{1,3}(?:[ .]\\d{3})*|\\d+)\\s*[,.]\\s*(\\d{2})\\s*zł/i);return m?Number(m[1].replace(/[ .]/g,'')+'.'+m[2]):null};" +
            "let page=1,total=1;document.querySelectorAll('button').forEach(b=>{let t=n(b.textContent),a=b.getAttribute('aria-label')||'';if(/^\\d+$/.test(t)&&b.getAttribute('aria-disabled')==='true')page=Number(t);let m=a.match(/of\\s+(\\d+)/i);if(m)total=Math.max(total,Number(m[1]))});" +
            "const products=[];document.querySelectorAll('article[data-test=\\\"product-leaf\\\"]').forEach(c=>{let number=c.getAttribute('data-test-key')||'',title=n(c.querySelector('[data-test=\\\"product-leaf-title\\\"]')?.textContent),node=c.querySelector('[data-test=\\\"product-leaf-discounted-price\\\"]')||c.querySelector('[data-test=\\\"product-leaf-price\\\"]'),p=price(node?.textContent);if(/^\\d{3,8}$/.test(number)&&title&&p!=null)products.push({number:number,title:title,price:p})});" +
            "AndroidOffers.onLegoDeals(JSON.stringify({page:page,total:total,products:products}));})()";
        webView.evaluateJavascript(js, null);
    }

    private void goToNextLegoPage() {
        if (stage != Stage.LOAD_LEGO_DEALS) return;
        legoPageAdvancePending = true;
        String js = "(()=>{const buttons=[...document.querySelectorAll('button[data-test=\\\"pagination-next\\\"]')].filter(b=>b.getAttribute('aria-disabled')!=='true');const b=buttons.find(x=>x.offsetParent!==null)||buttons[0];if(!b)return false;b.click();return true})()";
        webView.evaluateJavascript(js, value -> {
            if (!"true".equals(value)) {
                legoPageAdvancePending = false;
                finishLegoCatalogWithError("nie udało się przejść do kolejnej strony LEGO");
                return;
            }
            handler.postDelayed(() -> {
                legoPageAdvancePending = false;
                extractLegoDeals();
            }, 3500);
        });
    }

    private void beginDealsQueue() {
        if (legoDeals.isEmpty()) {
            finishLegoCatalogWithError("nie znaleziono produktów promocyjnych");
            return;
        }
        batchQueue = new ArrayList<>(legoDeals.keySet());
        queueIndex = 0;
        setProgressStatus("Pobrano " + legoDeals.size() + " produktów. Zaczynam porównanie z Allegro…");
        startNextSet();
    }

    private void finishLegoCatalogWithError(String error) {
        if (stage != Stage.LOAD_LEGO_DEALS) return;
        stage = Stage.IDLE;
        dealsStatus.setText("Błąd: " + error);
        setControlsEnabled(true);
    }

    private void startNextSet() {
        if (queueIndex >= batchQueue.size()) {
            stage = Stage.IDLE;
            if (mode == Mode.LEGO_DEALS) {
                dealsStatus.setText("Gotowe — porównano " + batchQueue.size() + " produktów z promocji LEGO.");
            } else {
                status.setText("Gotowe — sprawdzono " + batchQueue.size() + " zestawów.");
            }
            setControlsEnabled(true);
            return;
        }
        handler.removeCallbacksAndMessages(null);
        currentNumber = batchQueue.get(queueIndex);
        currentPage = 1;
        maxPage = 1;
        extractionAttempts = 0;
        productAttempts = 0;
        ownPriceAttempts = 0;
        productOffersUrl = "";
        expectedProductPath = "";
        firstPrice = secondPrice = ownPrice = null;
        stage = Stage.FIND_PRODUCT;
        if (mode == Mode.LEGO_DEALS) {
            setProgressStatus("Produkt " + (queueIndex + 1) + "/" + batchQueue.size() + " — " + currentNumber + ": wyszukuję na Allegro…");
        } else {
            setProgressStatus("Zestaw " + currentNumber + ": wyszukuję…");
        }
        webView.loadUrl("https://allegro.pl/listing?string=" + Uri.encode("LEGO " + currentNumber) + "&stan=nowe");
    }

    private void findLargestProduct() {
        if (stage != Stage.FIND_PRODUCT) return;
        String quotedNumber = JSONObject.quote(currentNumber);
        String js = "javascript:(()=>{" +
            "const n=s=>(s||'').replace(/\\s+/g,' ').trim();" +
            "const count=s=>[...n(s).matchAll(/(\\d[\\d\\s]*)\\s+ofert(?:a|y)?\\b/gi)].reduce((m,x)=>Math.max(m,Number(x[1].replace(/\\s/g,''))||0),0);" +
            "const out=[],seen=new Set(),number=" + quotedNumber + ";" +
            "[...document.querySelectorAll('button')].find(b=>/nie zgadzam się/i.test(b.textContent||''))?.click();" +
            "document.querySelectorAll('a[href*=\\\"/oferty-produktu/\\\"]').forEach(a=>{let u=new URL(a.href),card=a.closest('article'),text=n(card?.textContent),exact=new RegExp('(^|\\\\D)'+number+'(\\\\D|$)').test(u.pathname)||new RegExp('numer produktu\\\\s*'+number+'(\\\\D|$)','i').test(text),k=count(a.textContent);if(!exact||!k||seen.has(a.href))return;seen.add(a.href);out.push({url:a.href,count:k,title:n(card?.querySelector('h2,h3')?.textContent||a.textContent)})});" +
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

    private void startOwnPriceLookup() {
        stage = Stage.READ_OWN_PRICE;
        ownPriceAttempts = 0;
        webView.loadUrl("https://allegro.pl/uzytkownik/" + OWN_SELLER + "?string=" + Uri.encode(currentNumber) + "&order=p");
    }

    private void extractOwnPrice() {
        if (stage != Stage.READ_OWN_PRICE) return;
        String quotedPath = JSONObject.quote(expectedProductPath);
        String quotedNumber = JSONObject.quote(currentNumber);
        String js = "javascript:(()=>{" +
            "const n=s=>(s||'').replace(/\\s+/g,' ').trim();" +
            "const price=s=>{s=n(s);let marker=s.toLowerCase().indexOf('cena z 30 dni');if(marker>=0)s=s.slice(marker+13);let m=s.match(/(\\d{1,3}(?:[ .]\\d{3})*|\\d+)\\s*[,.]\\s*(\\d{2})\\s*zł/i);if(m)return Number(m[1].replace(/[ .]/g,'')+'.'+m[2]);m=s.match(/(\\d{1,3}(?:[ .]\\d{3})*|\\d+)\\s*zł/i);return m?Number(m[1].replace(/[ .]/g,'')):null};" +
            "let own=null,matches=0,expected=" + quotedPath + ",number=" + quotedNumber + ";const seen=new Set();" +
            "document.querySelectorAll('a[href*=\\\"offerId=\\\"]').forEach(a=>{if(new URL(a.href).pathname!==expected||seen.has(a.href))return;let c=a.closest('article');if(!c)return;let p=price(c.textContent);if(p==null)return;seen.add(a.href);matches++;if(own==null||p<own)own=p});" +
            "if(own==null)document.querySelectorAll('article').forEach(c=>{let text=n(c.textContent),exact=new RegExp('(^|\\\\D)'+number+'(\\\\D|$)').test(text);if(!exact)return;let p=price(text);if(p==null)return;matches++;if(own==null||p<own)own=p});" +
            "AndroidOffers.onOwnPrice(JSON.stringify({price:own,matches:matches}));})()";
        webView.evaluateJavascript(js, null);
    }

    private class OfferBridge {
        @JavascriptInterface public void onLegoDeals(String json) {
            runOnUiThread(() -> {
                if (stage != Stage.LOAD_LEGO_DEALS) return;
                try {
                    JSONObject result = new JSONObject(json);
                    if (result.optBoolean("waiting")) {
                        handler.postDelayed(MainActivity.this::extractLegoDeals, 1600);
                        return;
                    }
                    int page = result.optInt("page", 1);
                    int total = Math.max(page, result.optInt("total", page));
                    JSONArray products = result.optJSONArray("products");
                    if (products == null || products.length() == 0) {
                        if (legoCatalogAttempts++ < 4) {
                            setProgressStatus("Czekam na produkty LEGO, próba " + (legoCatalogAttempts + 1) + "/5…");
                            handler.postDelayed(MainActivity.this::extractLegoDeals, 1800);
                        } else {
                            finishLegoCatalogWithError("nie udało się odczytać listy produktów");
                        }
                        return;
                    }
                    if (page <= legoCatalogPage) {
                        if (page < total && !legoPageAdvancePending) goToNextLegoPage();
                        return;
                    }
                    legoCatalogAttempts = 0;
                    for (int i = 0; i < products.length(); i++) {
                        JSONObject product = products.getJSONObject(i);
                        String number = product.optString("number");
                        String title = product.optString("title");
                        double price = product.optDouble("price", -1);
                        if (number.matches("\\d{3,8}") && !title.isEmpty() && price >= 0) {
                            legoDeals.put(number, new DealProduct(number, title, price));
                        }
                    }
                    legoCatalogPage = page;
                    setProgressStatus("Pobrano stronę " + page + "/" + total + " — razem " + legoDeals.size() + " produktów.");
                    if (page < total) goToNextLegoPage(); else beginDealsQueue();
                } catch (Exception e) {
                    finishLegoCatalogWithError("nie udało się przetworzyć listy LEGO");
                }
            });
        }

        @JavascriptInterface public void onProduct(String json) {
            runOnUiThread(() -> {
                if (stage != Stage.FIND_PRODUCT) return;
                try {
                    JSONObject product = new JSONObject(json);
                    String url = product.optString("url");
                    if (url.isEmpty()) {
                        if (productAttempts < 4) {
                            productAttempts++;
                            setProgressStatus("Zestaw " + currentNumber + ": czekam na karty produktów, próba " + (productAttempts + 1) + "/5…");
                            handler.postDelayed(MainActivity.this::findLargestProduct, 1800);
                        } else {
                            finishCurrentWithError("brak karty produktu po 5 próbach");
                        }
                        return;
                    }
                    productAttempts = 0;
                    productOffersUrl = Uri.parse(url).buildUpon().appendQueryParameter("stan", "nowe").appendQueryParameter("order", "p").build().toString();
                    expectedProductPath = Uri.parse(url).getPath().replace("/oferty-produktu/", "/produkt/");
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
                    if (currentPage == 1 && prices.length() >= 1) {
                        firstPrice = prices.getJSONObject(0).getDouble("price");
                    }
                    if (mode == Mode.SAVED_SETS && currentPage == 1 && prices.length() >= 2) {
                        secondPrice = prices.getJSONObject(1).getDouble("price");
                    }
                    if (!result.isNull("ownPrice")) ownPrice = result.getDouble("ownPrice");
                    boolean pageNotReady = mode == Mode.LEGO_DEALS
                        ? firstPrice == null
                        : firstPrice == null || secondPrice == null;
                    if (pageNotReady && extractionAttempts < 4) {
                        extractionAttempts++;
                        final int retryPage = currentPage;
                        setProgressStatus("Zestaw " + currentNumber + ": czekam na oferty, próba " + (extractionAttempts + 1) + "/5…");
                        handler.postDelayed(() -> scrollAndExtractOffers(retryPage), 1800);
                        return;
                    }
                    extractionAttempts = 0;
                    if (mode == Mode.LEGO_DEALS) {
                        if (firstPrice != null) finishCurrentResult(); else finishCurrentWithError("brak rozpoznanej ceny nowego zestawu");
                    } else if (firstPrice != null && secondPrice != null) {
                        if (ownPrice != null) finishCurrentResult(); else startOwnPriceLookup();
                    } else {
                        finishCurrentWithError("mniej niż dwie rozpoznane ceny");
                    }
                } catch (Exception e) { finishCurrentWithError("nie udało się odczytać cen"); }
            });
        }

        @JavascriptInterface public void onOwnPrice(String json) {
            runOnUiThread(() -> {
                if (stage != Stage.READ_OWN_PRICE) return;
                try {
                    JSONObject result = new JSONObject(json);
                    if (!result.isNull("price")) {
                        ownPrice = result.getDouble("price");
                        finishCurrentResult();
                    } else if (ownPriceAttempts < 4) {
                        ownPriceAttempts++;
                        setProgressStatus("Zestaw " + currentNumber + ": czekam na ofertę " + OWN_SELLER + ", próba " + (ownPriceAttempts + 1) + "/5…");
                        handler.postDelayed(MainActivity.this::extractOwnPrice, 1800);
                    } else {
                        finishCurrentResult();
                    }
                } catch (Exception e) { finishCurrentResult(); }
            });
        }
    }

    private void finishCurrentResult() {
        if (mode == Mode.LEGO_DEALS) {
            if (firstPrice == null) { finishCurrentWithError("brak rozpoznanej ceny nowego zestawu"); return; }
            appendDealResult(legoDeals.get(currentNumber), firstPrice, null);
            advanceQueue();
            return;
        }
        if (firstPrice == null || secondPrice == null) { finishCurrentWithError("mniej niż dwie rozpoznane ceny"); return; }
        String mine = ownPrice == null ? "brak oferty" : currency.format(ownPrice);
        appendResult(currentNumber + "\nNajtańsza: " + currency.format(firstPrice) + "\nDruga: " + currency.format(secondPrice) + "\nMoja cena (" + OWN_SELLER + "): " + mine);
        advanceQueue();
    }

    private void finishCurrentWithError(String error) {
        if (mode == Mode.LEGO_DEALS) {
            appendDealResult(legoDeals.get(currentNumber), null, error);
        } else {
            appendResult(currentNumber + "\nBłąd: " + error);
        }
        advanceQueue();
    }

    private void appendResult(String value) {
        if (batchResults.length() > 0) batchResults.append("\n\n");
        batchResults.append(value);
    }

    private void appendDealResult(DealProduct deal, Double allegroPrice, String error) {
        if (deal == null) return;
        TextView row = new TextView(this);
        String allegro = allegroPrice == null ? "brak ceny" : currency.format(allegroPrice);
        String value = deal.number + " · " + deal.title + "\nLEGO: " + currency.format(deal.legoPrice) + "     Allegro (nowy): " + allegro;
        if (error != null) value += "\nBłąd: " + error;
        row.setText(value);
        row.setTextColor(Color.rgb(22, 22, 22));
        row.setTextSize(16);
        row.setLineSpacing(0, 1.08f);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setStroke(dp(1), Color.rgb(210, 210, 210));
        background.setCornerRadius(dp(9));
        row.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(9));
        dealsResults.addView(row, params);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void advanceQueue() {
        stage = Stage.IDLE;
        queueIndex++;
        if (queueIndex < batchQueue.size()) {
            boolean dealsMode = mode == Mode.LEGO_DEALS;
            long delay = dealsMode ? LEGO_DEALS_DELAY_MS : SAVED_SET_DELAY_MS;
            setProgressStatus(dealsMode ? "Następny produkt za 2 sekundy…" : "Następny zestaw za 10 sekund…");
            handler.postDelayed(this::startNextSet, delay);
        } else {
            startNextSet();
        }
    }

    @Override public void onBackPressed() {
        if (stage == Stage.IDLE && webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }
}
