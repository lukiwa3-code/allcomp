package pl.allcomp.allegro;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Allegro Radar v5
 * Adds selectable blocks of 10 Sales Center offers: 1-10, 11-20, ... 101-110.
 * Only the selected block is sent to the slower competitor/product scan.
 * v4 exact "POROWNAJ X OFERT TEGO PRODUKTU" counting and v3 KEEP_SCREEN_ON remain active.
 */
public class RadarMainActivityV5 extends RadarMainActivityV4 {
    private static final String SALES_URL = "https://salescenter.allegro.com/my-assortment?limit=120&publication.status=ACTIVE&sellingMode.format=BUY_NOW&context.marketplace=allegro-pl";

    private final Handler rangeHandler = new Handler(Looper.getMainLooper());
    private final LinkedHashMap<String, JSONObject> collected = new LinkedHashMap<>();
    private WebView radarWebView;
    private Spinner rangeSpinner;
    private boolean rangeScanning = false;
    private int rangeStart = 1;
    private int rangeEnd = 10;
    private int stagnant = 0;
    private int previousCount = 0;
    private int iterations = 0;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        View root = findViewById(android.R.id.content);
        radarWebView = findWebView(root);
        addRangeControls(root);
        hideOldScan5(root);
        updateVersionText(root);
    }

    @Override
    protected void onDestroy() {
        rangeHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void addRangeControls(View root) {
        if (!(root instanceof ViewGroup)) return;
        LinearLayout main = findMainVerticalLayout(root);
        if (main == null) return;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(2), dp(8), dp(4));

        TextView label = new TextView(this);
        label.setText("Zakres:");
        label.setTextSize(14);
        label.setPadding(0,0,dp(6),0);

        rangeSpinner = new Spinner(this);
        List<String> ranges = new ArrayList<>();
        for (int s = 1; s <= 101; s += 10) ranges.add(s + "–" + (s + 9));
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, ranges);
        rangeSpinner.setAdapter(adapter);

        Button scanRange = new Button(this);
        scanRange.setText("Skanuj zakres");
        scanRange.setAllCaps(false);
        scanRange.setTextSize(12);
        scanRange.setOnClickListener(v -> startSelectedRange());

        row.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(rangeSpinner, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(scanRange, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));

        // Top row is index 0, status is normally index 1. Insert range row between them.
        int insertAt = Math.min(1, main.getChildCount());
        main.addView(row, insertAt);
    }

    private void hideOldScan5(View view) {
        if (view instanceof Button) {
            Button b = (Button) view;
            if ("Skanuj 5".equalsIgnoreCase(String.valueOf(b.getText()).trim())) b.setVisibility(View.GONE);
        }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i=0;i<g.getChildCount();i++) hideOldScan5(g.getChildAt(i));
        }
    }

    private void updateVersionText(View view) {
        if (view instanceof TextView && !(view instanceof Button)) {
            TextView t = (TextView) view;
            String s = String.valueOf(t.getText());
            if (s.contains("Allegro Radar") || s.contains("Radar v")) {
                t.setText(s.replace("v2","v5").replace("v3","v5").replace("v4","v5"));
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i=0;i<g.getChildCount();i++) updateVersionText(g.getChildAt(i));
        }
    }

    private void startSelectedRange() {
        if (rangeSpinner == null || radarWebView == null) return;
        if (rangeScanning || getPrivateBoolean("scanningSales") || getPrivateBoolean("scanningMarket")) {
            Toast.makeText(this,"Skanowanie już trwa. Użyj STOP.",Toast.LENGTH_SHORT).show();
            return;
        }

        int pos = rangeSpinner.getSelectedItemPosition();
        rangeStart = pos * 10 + 1;
        rangeEnd = rangeStart + 9;

        String url = radarWebView.getUrl();
        if (url == null || !url.contains("salescenter.allegro.com")) {
            radarWebView.loadUrl(SALES_URL);
            Toast.makeText(this,"Otwieram Sales Center. Poczekaj na listę i kliknij Skanuj zakres ponownie.",Toast.LENGTH_LONG).show();
            return;
        }

        rangeScanning = true;
        collected.clear();
        stagnant = 0;
        previousCount = 0;
        iterations = 0;
        setStatus("Zakres " + rangeStart + "–" + rangeEnd + ": wczytuję oferty z Sales Center…");

        forceTop();
        rangeHandler.postDelayed(this::forceTop, 300);
        rangeHandler.postDelayed(this::forceTop, 700);
        rangeHandler.postDelayed(this::rangeStep, 1200);
    }

    private void rangeStep() {
        if (!rangeScanning || radarWebView == null) return;
        iterations++;
        String scanJs = getStaticString("JS_SCAN_SALES_V2");
        if (scanJs.isEmpty()) {
            failRange("Brak skanera Sales Center.");
            return;
        }

        radarWebView.evaluateJavascript(scanJs, value -> {
            if (!rangeScanning) return;
            try {
                String decoded = decodeJs(value);
                if (decoded != null) {
                    JSONObject root = new JSONObject(decoded);
                    JSONArray arr = root.optJSONArray("offers");
                    if (arr != null) {
                        for (int i=0;i<arr.length();i++) {
                            JSONObject j = arr.getJSONObject(i);
                            String id = j.optString("id","");
                            if (!id.isEmpty()) {
                                JSONObject old = collected.get(id);
                                if (old == null || jsonScore(j) > jsonScore(old)) collected.put(id,j);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}

            int count = collected.size();
            setStatus("Zakres " + rangeStart + "–" + rangeEnd + ": wczytano " + count + " / " + rangeEnd + " pozycji");

            if (count >= rangeEnd) {
                finishRangeCollection();
                return;
            }

            if (count == previousCount) stagnant++; else stagnant = 0;
            previousCount = count;
            if (iterations >= 100 || stagnant >= 22) {
                finishRangeCollection();
                return;
            }

            String scrollJs = getStaticString("JS_SCROLL_SALES_V2");
            radarWebView.evaluateJavascript(scrollJs, x -> rangeHandler.postDelayed(this::rangeStep, 500));
        });
    }

    private void finishRangeCollection() {
        rangeScanning = false;
        List<JSONObject> all = new ArrayList<>(collected.values());
        int from = rangeStart - 1;
        int to = Math.min(rangeEnd, all.size());
        if (from >= all.size() || from >= to) {
            failRange("Nie udało się wczytać pozycji " + rangeStart + "–" + rangeEnd + ". Wczytano tylko " + all.size() + " ofert.");
            return;
        }

        try {
            Map<String,Object> offers = getOffersMap();
            offers.clear();
            for (int i=from;i<to;i++) {
                JSONObject j = all.get(i);
                Object offer = newOffer(j);
                String id = j.optString("id","");
                if (!id.isEmpty()) offers.put(id, offer);
            }

            List<Object> results = getResultsList();
            results.clear();
            setPrivateBoolean("cancelled", false);
            setPrivateBoolean("scanningSales", false);
            setPrivateBoolean("scanningMarket", true);
            setPrivateBoolean("waitingMarketPage", false);
            setPrivateInt("marketIndex", 0);

            setStatus("Wybrano " + rangeStart + "–" + Math.min(rangeEnd, all.size()) + ". Sprawdzam " + offers.size() + " ofert…");
            invokePrivate("scanNextMarketOffer");
        } catch (Exception e) {
            failRange("Błąd uruchomienia zakresu: " + e.getClass().getSimpleName());
        }
    }

    private Object newOffer(JSONObject j) throws Exception {
        Class<?> c = Class.forName("pl.allcomp.allegro.MobileMainActivity$Offer");
        Constructor<?> ctor = c.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object o = ctor.newInstance();
        setField(o,"id",j.optString("id",""));
        setField(o,"title",j.optString("title",""));
        setField(o,"ean",j.optString("ean",""));
        setField(o,"url",j.optString("url",""));
        setField(o,"price",j.has("price") && !j.isNull("price") ? j.optDouble("price",Double.NaN) : Double.NaN);
        return o;
    }

    @SuppressWarnings("unchecked")
    private Map<String,Object> getOffersMap() throws Exception {
        Field f=MobileMainActivity.class.getDeclaredField("offers"); f.setAccessible(true);
        return (Map<String,Object>) f.get(this);
    }

    @SuppressWarnings("unchecked")
    private List<Object> getResultsList() throws Exception {
        Field f=MobileMainActivity.class.getDeclaredField("results"); f.setAccessible(true);
        return (List<Object>) f.get(this);
    }

    private void invokePrivate(String name) throws Exception {
        Method m=MobileMainActivity.class.getDeclaredMethod(name); m.setAccessible(true); m.invoke(this);
    }

    private void setField(Object obj,String name,Object value) throws Exception {
        Field f=obj.getClass().getDeclaredField(name); f.setAccessible(true);
        if (f.getType()==double.class && value instanceof Number) f.setDouble(obj,((Number)value).doubleValue());
        else f.set(obj,value);
    }

    private boolean getPrivateBoolean(String name) {
        try { Field f=MobileMainActivity.class.getDeclaredField(name); f.setAccessible(true); return f.getBoolean(this); }
        catch(Exception e){ return false; }
    }

    private void setPrivateBoolean(String name,boolean value) throws Exception {
        Field f=MobileMainActivity.class.getDeclaredField(name); f.setAccessible(true); f.setBoolean(this,value);
    }

    private void setPrivateInt(String name,int value) throws Exception {
        Field f=MobileMainActivity.class.getDeclaredField(name); f.setAccessible(true); f.setInt(this,value);
    }

    private String getStaticString(String name) {
        try { Field f=MobileMainActivity.class.getDeclaredField(name); f.setAccessible(true); return String.valueOf(f.get(null)); }
        catch(Exception e){ return ""; }
    }

    private int jsonScore(JSONObject j) {
        int s=0;
        if(!j.optString("title","").isEmpty())s+=2;
        if(!j.optString("ean","").isEmpty())s+=3;
        if(j.has("price")&&!j.isNull("price"))s+=3;
        if(!j.optString("url","").isEmpty())s++;
        return s;
    }

    private String decodeJs(String v) {
        if(v==null||"null".equals(v))return null;
        try{return new JSONArray("["+v+"]").getString(0);}catch(Exception e){return null;}
    }

    private void forceTop() {
        if (radarWebView == null) return;
        radarWebView.evaluateJavascript("(function(){try{window.scrollTo(0,0);const a=[document.scrollingElement,...document.querySelectorAll('*')].filter(Boolean);for(const e of a){try{if(e.scrollHeight>e.clientHeight+40)e.scrollTop=0}catch(x){}}window.scrollTo(0,0);return true}catch(e){return false}})()",null);
    }

    private void failRange(String msg) {
        rangeScanning=false;
        setStatus(msg);
        Toast.makeText(this,msg,Toast.LENGTH_LONG).show();
    }

    private void setStatus(String text) {
        try { Field f=MobileMainActivity.class.getDeclaredField("status"); f.setAccessible(true); Object x=f.get(this); if(x instanceof TextView)((TextView)x).setText(text); }
        catch(Exception ignored){}
    }

    private WebView findWebView(View view) {
        if(view instanceof WebView)return (WebView)view;
        if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++){WebView w=findWebView(g.getChildAt(i));if(w!=null)return w;}}
        return null;
    }

    private LinearLayout findMainVerticalLayout(View view) {
        if (view instanceof LinearLayout) {
            LinearLayout l=(LinearLayout)view;
            if(l.getOrientation()==LinearLayout.VERTICAL && findWebView(l)!=null) return l;
        }
        if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++){LinearLayout l=findMainVerticalLayout(g.getChildAt(i));if(l!=null)return l;}}
        return null;
    }

    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
