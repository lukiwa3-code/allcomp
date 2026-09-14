package pl.allcomp.allegro;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Allegro Radar v10
 *
 * V10 does NOT use the fake "1 offer" proxy from v9 and does not rely on the
 * offer-page count. It opens the exact seller offer, clicks either
 * "POROWNAJ X OFERTY TEGO PRODUKTU" or "Zobacz porownanie", and only then
 * reads the real count from the comparison screen, e.g. "(2 oferty)".
 * Next it clicks Najtaniej, enforces Stan=Nowe and reads the first new price.
 */
public class RadarMainActivityV10 extends RadarMainActivityV8 {
    private static final String SALES_URL = "https://salescenter.allegro.com/my-assortment?limit=120&publication.status=ACTIVE&sellingMode.format=BUY_NOW&context.marketplace=allegro-pl";
    private static final int CREATE_CSV = 910;

    private final Handler h10 = new Handler(Looper.getMainLooper());
    private final LinkedHashMap<String, JSONObject> collected10 = new LinkedHashMap<>();
    private final List<Offer10> selected10 = new ArrayList<>();
    private final List<Result10> results10 = new ArrayList<>();

    private WebView web10;
    private TextView status10;
    private Spinner range10;
    private boolean collecting10 = false;
    private boolean scanning10 = false;
    private boolean allMode10 = false;
    private int rangeStart10 = 1;
    private int rangeEnd10 = 10;
    private int iterations10 = 0;
    private int stagnant10 = 0;
    private int previousCount10 = 0;
    private int currentIndex10 = 0;
    private Offer10 current10;
    private Result10 currentResult10;
    private String pendingCsv10 = "";

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        View root = findViewById(android.R.id.content);
        web10 = findWeb(root);
        status10 = findStatus(root);
        range10 = findSpinner(root);
        rewire(root);
        setStatus10("Allegro Radar v10. Wybierz zakres i kliknij Skanuj zakres.");
    }

    @Override
    protected void onDestroy() {
        h10.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void rewire(View v) {
        if (v instanceof Button) {
            Button b = (Button) v;
            String t = String.valueOf(b.getText()).trim();
            if ("Skanuj zakres".equalsIgnoreCase(t)) b.setOnClickListener(x -> startRange10());
            else if ("Skanuj wszystkie".equalsIgnoreCase(t)) b.setOnClickListener(x -> startAll10());
            else if ("Raport".equalsIgnoreCase(t)) b.setOnClickListener(x -> showReport10());
            else if ("CSV".equalsIgnoreCase(t)) b.setOnClickListener(x -> exportCsv10());
            else if ("STOP".equalsIgnoreCase(t)) b.setOnClickListener(x -> stop10(true));
            else if ("Sales Center".equalsIgnoreCase(t)) b.setOnClickListener(x -> {
                stop10(false);
                if (web10 != null) web10.loadUrl(SALES_URL);
                setStatus10("Sales Center. Poczekaj, aż zobaczysz listę ofert.");
            });
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) rewire(g.getChildAt(i));
        }
    }

    private void startRange10() {
        if (range10 == null) return;
        int pos = range10.getSelectedItemPosition();
        rangeStart10 = pos * 10 + 1;
        rangeEnd10 = rangeStart10 + 9;
        allMode10 = false;
        startCollection10();
    }

    private void startAll10() {
        rangeStart10 = 1;
        rangeEnd10 = Integer.MAX_VALUE;
        allMode10 = true;
        startCollection10();
    }

    private void startCollection10() {
        if (collecting10 || scanning10) {
            Toast.makeText(this, "Skanowanie już trwa. Użyj STOP.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (web10 == null) return;
        String u = web10.getUrl();
        if (u == null || !u.contains("salescenter.allegro.com")) {
            web10.loadUrl(SALES_URL);
            Toast.makeText(this, "Otwieram Sales Center. Poczekaj na listę i kliknij ponownie.", Toast.LENGTH_LONG).show();
            return;
        }

        collecting10 = true;
        scanning10 = false;
        collected10.clear();
        selected10.clear();
        results10.clear();
        iterations10 = 0;
        stagnant10 = 0;
        previousCount10 = 0;
        setStatus10(allMode10 ? "Wczytuję wszystkie oferty…" : "Zakres " + rangeStart10 + "–" + rangeEnd10 + ": wczytuję Sales Center…");
        forceTop10();
        h10.postDelayed(this::forceTop10, 300);
        h10.postDelayed(this::forceTop10, 700);
        h10.postDelayed(this::collectStep10, 1200);
    }

    private void collectStep10() {
        if (!collecting10 || web10 == null) return;
        iterations10++;
        String js = staticString("JS_SCAN_SALES_V2");
        if (js.isEmpty()) {
            fail10("Brak skanera Sales Center.");
            return;
        }
        web10.evaluateJavascript(js, value -> {
            if (!collecting10) return;
            try {
                String d = decode(value);
                if (d != null) {
                    JSONObject root = new JSONObject(d);
                    JSONArray a = root.optJSONArray("offers");
                    if (a != null) for (int i = 0; i < a.length(); i++) {
                        JSONObject j = a.getJSONObject(i);
                        String id = j.optString("id", "");
                        if (id.isEmpty()) continue;
                        JSONObject old = collected10.get(id);
                        if (old == null || score(j) > score(old)) collected10.put(id, j);
                    }
                }
            } catch (Exception ignored) {}

            int c = collected10.size();
            if (allMode10) setStatus10("Sales Center: wczytano " + c + " ofert…");
            else setStatus10("Zakres " + rangeStart10 + "–" + rangeEnd10 + ": wczytano " + c + " / " + rangeEnd10);

            if (!allMode10 && c >= rangeEnd10) {
                finishCollection10();
                return;
            }
            if (c == previousCount10) stagnant10++; else stagnant10 = 0;
            previousCount10 = c;
            if ((allMode10 && stagnant10 >= 24 && c > 0) || iterations10 >= 150 || (!allMode10 && stagnant10 >= 28)) {
                finishCollection10();
                return;
            }
            String scroll = staticString("JS_SCROLL_SALES_V2");
            web10.evaluateJavascript(scroll, x -> h10.postDelayed(this::collectStep10, 500));
        });
    }

    private void finishCollection10() {
        collecting10 = false;
        List<JSONObject> all = new ArrayList<>(collected10.values());
        int from = allMode10 ? 0 : rangeStart10 - 1;
        int to = allMode10 ? all.size() : Math.min(rangeEnd10, all.size());
        if (from >= all.size() || from >= to) {
            fail10("Nie udało się wczytać wybranego zakresu. Wczytano " + all.size() + " ofert.");
            return;
        }
        selected10.clear();
        for (int i = from; i < to; i++) selected10.add(fromJson(all.get(i)));
        currentIndex10 = 0;
        scanning10 = true;
        setStatus10("Wybrano " + selected10.size() + " ofert. Zaczynam porównanie…");
        processNext10();
    }

    private void processNext10() {
        if (!scanning10) return;
        if (currentIndex10 >= selected10.size()) {
            scanning10 = false;
            setStatus10("Gotowe. Sprawdzono " + results10.size() + " ofert.");
            showReport10();
            return;
        }
        current10 = selected10.get(currentIndex10);
        currentResult10 = new Result10(current10);
        String url = current10.url != null && current10.url.contains("allegro.pl/oferta/") ? current10.url : "https://allegro.pl/oferta/" + current10.id;
        setStatus10("Oferta " + (currentIndex10 + 1) + "/" + selected10.size() + ": otwieram dokładną ofertę…");
        web10.loadUrl(url);
        h10.postDelayed(() -> offerCompareStep10(0), 1500);
    }

    /** Find and click either numbered POROWNAJ or plain Zobacz porownanie. */
    private void offerCompareStep10(int attempt) {
        if (!scanning10 || currentResult10 == null || web10 == null) return;
        String js = "(function(){try{" + DEEP_UTIL +
                "const numbered=/POR[ÓO]WNAJ\\s+[\\d\\s\\u00a0\\u202f]+\\s+OFERT(?:A|Y)?\\s+TEGO\\s+PRODUKTU/i;" +
                "let e=findEl((t)=>numbered.test(t));let kind='numbered';" +
                "if(!e){e=findEl((t)=>{const n=norm(t);return n==='zobacz porównanie'||n==='zobacz porownanie'||(n.includes('zobacz')&&(n.includes('porównanie')||n.includes('porownanie')))});kind='plain';}" +
                "if(e){clickEl(e);return JSON.stringify({found:true,kind:kind,url:location.href});}" +
                "let moved=scrollDeep();return JSON.stringify({found:false,moved:moved,url:location.href});" +
                "}catch(e){return JSON.stringify({found:false,error:String(e),url:location.href})}})()";
        web10.evaluateJavascript(js, value -> {
            try {
                JSONObject j = obj(value);
                if (j.optBoolean("found", false)) {
                    setStatus10("Oferta " + (currentIndex10 + 1) + "/" + selected10.size() + ": kliknięto porównanie. Czekam na ekran ofert…");
                    h10.postDelayed(() -> comparisonReady10(0), 850);
                    return;
                }
            } catch (Exception ignored) {}

            if (attempt >= 50) {
                currentResult10.status = "NIE ODCZYTANO";
                currentResult10.note = "Nie znalazłem przycisku Porównaj/Zobacz porównanie na dokładnej stronie oferty. URL: " + safeUrl();
                finishCurrent10();
            } else {
                setStatus10("Oferta " + (currentIndex10 + 1) + "/" + selected10.size() + ": szukam Porównaj… " + (attempt + 1));
                h10.postDelayed(() -> offerCompareStep10(attempt + 1), 350);
            }
        });
    }

    /** Only this screen is authoritative for the number of product offers. */
    private void comparisonReady10(int attempt) {
        if (!scanning10 || web10 == null) return;
        String js = "(function(){try{" + DEEP_UTIL +
                "let texts=allTexts();let joined=texts.join('\\n');" +
                "let hasCheap=texts.some(t=>norm(t)==='najtaniej');let hasFast=texts.some(t=>norm(t)==='najszybciej');" +
                "let count=0;for(const t of texts){let m=t.match(/^\\(\\s*(\\d+)\\s+ofert(?:a|y)?\\s*\\)$/i);if(!m)m=t.match(/^\\s*(\\d+)\\s+ofert(?:a|y)?\\s*$/i);if(m){let n=parseInt(m[1],10);if(!isNaN(n)&&n>0){count=n;break;}}}" +
                "return JSON.stringify({ready:hasCheap&&hasFast,count:count,url:location.href});" +
                "}catch(e){return JSON.stringify({ready:false,count:0,error:String(e),url:location.href})}})()";
        web10.evaluateJavascript(js, value -> {
            try {
                JSONObject j = obj(value);
                if (j.optBoolean("ready", false)) {
                    int c = j.optInt("count", 0);
                    if (c > 0) {
                        currentResult10.totalOffers = c;
                        currentResult10.otherOffers = Math.max(0, c - 1);
                    }
                    setStatus10("Oferta " + (currentIndex10 + 1) + "/" + selected10.size() + ": ekran porównania" + (c > 0 ? " • " + c + " ofert" : "") + ". Ustawiam Najtaniej…");
                    clickCheapest10(0);
                    return;
                }
            } catch (Exception ignored) {}

            if (attempt >= 35) {
                currentResult10.status = "BŁĄD";
                currentResult10.note = "Kliknięto porównanie, ale nie rozpoznałem ekranu z przyciskami Najtaniej/Najszybciej. URL: " + safeUrl();
                finishCurrent10();
            } else h10.postDelayed(() -> comparisonReady10(attempt + 1), 300);
        });
    }

    private void clickCheapest10(int attempt) {
        String js = "(function(){try{" + DEEP_UTIL +
                "let e=findEl((t)=>norm(t)==='najtaniej');if(!e)return JSON.stringify({ok:false,url:location.href});" +
                "clickEl(e);return JSON.stringify({ok:true,url:location.href});" +
                "}catch(e){return JSON.stringify({ok:false,error:String(e),url:location.href})}})()";
        web10.evaluateJavascript(js, value -> {
            try {
                JSONObject j = obj(value);
                if (j.optBoolean("ok", false)) {
                    setStatus10("Oferta " + (currentIndex10 + 1) + "/" + selected10.size() + ": Najtaniej ✓. Ustawiam Stan: Nowe…");
                    h10.postDelayed(() -> openState10(0), 650);
                    return;
                }
            } catch (Exception ignored) {}
            if (attempt >= 20) {
                currentResult10.status = "BŁĄD";
                currentResult10.note = "Ekran porównania jest otwarty, ale nie udało się kliknąć Najtaniej. URL: " + safeUrl();
                finishCurrent10();
            } else h10.postDelayed(() -> clickCheapest10(attempt + 1), 250);
        });
    }

    private void openState10(int attempt) {
        String js = "(function(){try{" + DEEP_UTIL +
                "let open=false;for(const r of roots()){let us=[],ns=[];try{us=[...r.querySelectorAll('*')].filter(e=>visible(e)&&norm(e.innerText)==='używane');ns=[...r.querySelectorAll('*')].filter(e=>visible(e)&&norm(e.innerText)==='nowe')}catch(x){}if(us.length&&ns.length){open=true;break;}}" +
                "if(open)return JSON.stringify({state:'open'});" +
                "let lab=findEl((t)=>norm(t)==='stan');if(!lab)return JSON.stringify({state:'missing'});" +
                "let p=lab;for(let i=0;i<7&&p;i++,p=p.parentElement){let txt=(p.innerText||'').trim();if(txt.length<280&&(/Wszystkie/i.test(txt)||/Nowe/i.test(txt))){clickEl(p);return JSON.stringify({state:'clicked'});}}" +
                "clickEl(lab);return JSON.stringify({state:'clicked'});" +
                "}catch(e){return JSON.stringify({state:'missing',error:String(e)})}})()";
        web10.evaluateJavascript(js, value -> {
            try {
                String s = obj(value).optString("state", "missing");
                if ("open".equals(s)) { h10.postDelayed(() -> chooseNew10(0), 200); return; }
                if ("clicked".equals(s)) { h10.postDelayed(() -> chooseNew10(0), 400); return; }
            } catch (Exception ignored) {}
            if (attempt >= 20) {
                currentResult10.status = "BŁĄD";
                currentResult10.note = "Nie udało się otworzyć filtra Stan na ekranie porównania.";
                finishCurrent10();
            } else h10.postDelayed(() -> openState10(attempt + 1), 250);
        });
    }

    private void chooseNew10(int attempt) {
        String js = "(function(){try{" + DEEP_UTIL +
                "for(const r of roots()){let es=[];try{es=[...r.querySelectorAll('label,button,[role=option],[role=checkbox],div,span')]}catch(x){}for(const e of es){if(!visible(e)||norm(e.innerText)!=='nowe')continue;let p=e;for(let i=0;i<6&&p;i++,p=p.parentElement){let txt=(p.innerText||'').trim();if(txt.length>320)continue;let cb=null;try{cb=p.querySelector('input[type=checkbox],[role=checkbox]')}catch(x){}if(cb||/Używane/i.test(txt)){if(cb){let checked=cb.checked===true||cb.getAttribute('aria-checked')==='true';if(checked)return JSON.stringify({state:'already'});}clickEl(e);return JSON.stringify({state:'clicked'});}}}}return JSON.stringify({state:'missing'});" +
                "}catch(e){return JSON.stringify({state:'missing',error:String(e)})}})()";
        web10.evaluateJavascript(js, value -> {
            try {
                String s = obj(value).optString("state", "missing");
                if ("already".equals(s) || "clicked".equals(s)) {
                    setStatus10("Oferta " + (currentIndex10 + 1) + "/" + selected10.size() + ": Stan: Nowe ✓. Czytam najniższą cenę…");
                    h10.postDelayed(() -> readPrice10(0), 1000);
                    return;
                }
            } catch (Exception ignored) {}
            if (attempt >= 20) {
                currentResult10.status = "BŁĄD";
                currentResult10.note = "Nie udało się zaznaczyć Stan: Nowe.";
                finishCurrent10();
            } else h10.postDelayed(() -> chooseNew10(attempt + 1), 250);
        });
    }

    private void readPrice10(int attempt) {
        String js = "(function(){try{" + DEEP_UTIL +
                "function money(t){for(const line of (t||'').split(/\\n+/)){let l=line.toLowerCase();if(l.includes('dostaw')||l.includes('rata')||l.includes('mies')||l.includes('cena z 30 dni'))continue;let m=line.match(/(\\d{1,3}(?:[ \\u00a0\\u202f]\\d{3})*(?:[,.]\\d{2}))\\s*zł/i);if(m){let n=parseFloat(m[1].replace(/[ \\u00a0\\u202f]/g,'').replace(',','.'));if(!isNaN(n))return n;}}return null;}" +
                "let cards=[];for(const r of roots()){let es=[];try{es=[...r.querySelectorAll('article,li,section,div')]}catch(x){}for(const e of es){if(!visible(e))continue;let t=(e.innerText||'').trim();if(t.length<20||t.length>2600)continue;if(!/Stan\\s*:?\\s*Nowy\\b/i.test(t))continue;if(!/\\d[\\d \\u00a0\\u202f]*[,.]\\d{2}\\s*zł/i.test(t))continue;let p=money(t);if(p===null)continue;let rr=e.getBoundingClientRect();cards.push({p:p,y:rr.top,len:t.length});}}" +
                "cards.sort((a,b)=>a.y-b.y||a.len-b.len);if(cards.length)return JSON.stringify({price:cards[0].p,count:cards.length});" +
                "scrollDeep();return JSON.stringify({price:null,count:0});" +
                "}catch(e){return JSON.stringify({price:null,count:0,error:String(e)})}})()";
        web10.evaluateJavascript(js, value -> {
            try {
                JSONObject j = obj(value);
                if (!j.isNull("price")) {
                    double p = j.optDouble("price", Double.NaN);
                    if (!Double.isNaN(p)) {
                        currentResult10.lowestNew = p;
                        currentResult10.status = "OK";
                        currentResult10.note = "Dokładna ścieżka: oferta → porównanie → Najtaniej → Stan: Nowe → pierwsza oferta.";
                        finishCurrent10();
                        return;
                    }
                }
            } catch (Exception ignored) {}
            if (attempt >= 25) {
                currentResult10.status = "CZĘŚCIOWO";
                currentResult10.note = "Ekran porównania i filtry zadziałały, ale nie odczytałem ceny pierwszej nowej oferty.";
                finishCurrent10();
            } else h10.postDelayed(() -> readPrice10(attempt + 1), 350);
        });
    }

    private void finishCurrent10() {
        if (currentResult10 == null) return;
        if (!Double.isNaN(currentResult10.lowestNew) && !Double.isNaN(currentResult10.ownPrice)) {
            currentResult10.diffPln = round2(currentResult10.ownPrice - currentResult10.lowestNew);
            if (currentResult10.lowestNew > 0) currentResult10.diffPct = round2(currentResult10.diffPln / currentResult10.lowestNew * 100.0);
        }
        results10.add(currentResult10);
        currentResult10 = null;
        current10 = null;
        currentIndex10++;
        h10.postDelayed(this::processNext10, 650);
    }

    private void showReport10() {
        if (results10.isEmpty()) {
            Toast.makeText(this, "Nie ma jeszcze wyników.", Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder s = new StringBuilder();
        for (Result10 r : results10) {
            s.append(r.title).append("\nID: ").append(r.offerId);
            if (r.ean != null && !r.ean.isEmpty()) s.append("   EAN: ").append(r.ean);
            s.append("\nMoja cena: ").append(money(r.ownPrice))
             .append("\nOferty produktu: ").append(r.totalOffers > 0 ? r.totalOffers : "—")
             .append("\nInne oferty: ").append(r.totalOffers > 0 ? r.otherOffers : "—")
             .append("\nNajniższa NOWY: ").append(money(r.lowestNew))
             .append("\nRóżnica: ").append(moneySigned(r.diffPln))
             .append("\nStatus: ").append(r.status);
            if (r.note != null && !r.note.isEmpty()) s.append("\n").append(r.note);
            s.append("\n\n────────────────────────\n\n");
        }
        TextView tv = new TextView(this);
        tv.setText(s.toString());
        tv.setTextSize(14);
        tv.setTextColor(Color.BLACK);
        tv.setPadding(dp(18), dp(12), dp(18), dp(18));
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this).setTitle("Allegro Radar v10").setView(sv).setPositiveButton("OK", null).show();
    }

    private void exportCsv10() {
        if (results10.isEmpty()) {
            Toast.makeText(this, "Najpierw wykonaj skanowanie.", Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder s = new StringBuilder("ID oferty;Tytuł;EAN;Moja cena;Oferty produktu;Inne oferty;Najniższa cena NOWY;Różnica PLN;Różnica %;Status;Uwagi\r\n");
        for (Result10 r : results10) {
            s.append(q(r.offerId)).append(';').append(q(r.title)).append(';').append(q(r.ean)).append(';')
             .append(num(r.ownPrice)).append(';').append(r.totalOffers > 0 ? r.totalOffers : "").append(';')
             .append(r.totalOffers > 0 ? r.otherOffers : "").append(';').append(num(r.lowestNew)).append(';')
             .append(num(r.diffPln)).append(';').append(num(r.diffPct)).append(';').append(q(r.status)).append(';').append(q(r.note)).append("\r\n");
        }
        pendingCsv10 = s.toString();
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/csv");
        i.putExtra(Intent.EXTRA_TITLE, "allegro_radar_v10.csv");
        startActivityForResult(i, CREATE_CSV);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == CREATE_CSV && res == RESULT_OK && data != null && data.getData() != null) {
            try (OutputStream os = getContentResolver().openOutputStream(data.getData())) {
                if (os != null) {
                    os.write(new byte[]{(byte)0xEF,(byte)0xBB,(byte)0xBF});
                    os.write(pendingCsv10.getBytes(StandardCharsets.UTF_8));
                    Toast.makeText(this, "CSV zapisany.", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "Błąd zapisu CSV: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }
    }

    private void stop10(boolean say) {
        collecting10 = false;
        scanning10 = false;
        h10.removeCallbacksAndMessages(null);
        if (say) setStatus10("Skanowanie zatrzymane.");
    }

    @Override
    public void onBackPressed() {
        if (collecting10 || scanning10) {
            new AlertDialog.Builder(this).setTitle("Skanowanie trwa").setMessage("Zatrzymać skanowanie?")
                    .setPositiveButton("Tak", (d,w) -> stop10(true)).setNegativeButton("Nie", null).show();
            return;
        }
        super.onBackPressed();
    }

    private void forceTop10() {
        if (web10 != null) web10.evaluateJavascript("(function(){try{window.scrollTo(0,0);const a=[document.scrollingElement,...document.querySelectorAll('*')].filter(Boolean);for(const e of a){try{if(e.scrollHeight>e.clientHeight+40)e.scrollTop=0}catch(x){}}window.scrollTo(0,0);return true}catch(e){return false}})()", null);
    }

    private void fail10(String msg) {
        collecting10 = false;
        scanning10 = false;
        setStatus10(msg);
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private Offer10 fromJson(JSONObject j) {
        Offer10 o = new Offer10();
        o.id = j.optString("id", "");
        o.title = j.optString("title", "");
        o.ean = j.optString("ean", "");
        o.url = j.optString("url", "");
        if (j.has("price") && !j.isNull("price")) o.price = j.optDouble("price", Double.NaN);
        return o;
    }

    private int score(JSONObject j) {
        int s=0;
        if(!j.optString("title","").isEmpty()) s+=2;
        if(!j.optString("ean","").isEmpty()) s+=3;
        if(j.has("price")&&!j.isNull("price")) s+=3;
        if(!j.optString("url","").isEmpty()) s++;
        return s;
    }

    private String staticString(String name) {
        try {
            Field f = MobileMainActivity.class.getDeclaredField(name);
            f.setAccessible(true);
            return String.valueOf(f.get(null));
        } catch (Exception e) { return ""; }
    }

    private JSONObject obj(String value) throws Exception {
        String d = decode(value);
        return d == null ? new JSONObject() : new JSONObject(d);
    }

    private String decode(String v) {
        if (v == null || "null".equals(v)) return null;
        try { return new JSONArray("[" + v + "]").getString(0); }
        catch (Exception e) { return null; }
    }

    private void setStatus10(String text) {
        if (status10 != null) status10.setText(text);
    }

    private String safeUrl() {
        try { return web10 == null ? "" : String.valueOf(web10.getUrl()); }
        catch (Exception e) { return ""; }
    }

    private WebView findWeb(View v) {
        if (v instanceof WebView) return (WebView)v;
        if (v instanceof ViewGroup) {
            ViewGroup g=(ViewGroup)v;
            for(int i=0;i<g.getChildCount();i++){WebView w=findWeb(g.getChildAt(i));if(w!=null)return w;}
        }
        return null;
    }

    private Spinner findSpinner(View v) {
        if (v instanceof Spinner) return (Spinner)v;
        if (v instanceof ViewGroup) {
            ViewGroup g=(ViewGroup)v;
            for(int i=0;i<g.getChildCount();i++){Spinner s=findSpinner(g.getChildAt(i));if(s!=null)return s;}
        }
        return null;
    }

    private TextView findStatus(View v) {
        if (v instanceof TextView && !(v instanceof Button)) {
            String t = String.valueOf(((TextView)v).getText());
            if (t.contains("Allegro Radar") || t.contains("Sales Center") || t.contains("Zaloguj")) return (TextView)v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g=(ViewGroup)v;
            for(int i=0;i<g.getChildCount();i++){TextView x=findStatus(g.getChildAt(i));if(x!=null)return x;}
        }
        return null;
    }

    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private double round2(double x){return Math.round(x*100.0)/100.0;}
    private String money(double d){return Double.isNaN(d)?"—":String.format(Locale.forLanguageTag("pl-PL"),"%.2f zł",d);}
    private String moneySigned(double d){return Double.isNaN(d)?"—":(d>0?"+":"")+String.format(Locale.forLanguageTag("pl-PL"),"%.2f zł",d);}
    private String q(String s){return "\""+(s==null?"":s.replace("\"","\"\""))+"\"";}
    private String num(double d){return Double.isNaN(d)?"":String.format(Locale.US,"%.2f",d).replace('.',',');}

    private static final String DEEP_UTIL =
            "function norm(s){return (s||'').replace(/\\s+/g,' ').trim().toLowerCase()}" +
            "function visible(e){try{const r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width>0&&r.height>0&&s.display!=='none'&&s.visibility!=='hidden'}catch(x){return false}}" +
            "function roots(){const out=[document],seen=new Set();for(let i=0;i<out.length;i++){const r=out[i];if(!r||seen.has(r))continue;seen.add(r);let es=[];try{es=[...r.querySelectorAll('*')]}catch(x){}for(const e of es){try{if(e.shadowRoot&&!seen.has(e.shadowRoot))out.push(e.shadowRoot)}catch(x){}try{if(e.tagName==='IFRAME'&&e.contentDocument&&!seen.has(e.contentDocument))out.push(e.contentDocument)}catch(x){}}}return [...seen]}" +
            "function allTexts(){const out=[];for(const r of roots()){let es=[];try{es=[...r.querySelectorAll('button,a,[role=button],[role=option],[role=checkbox],label,h1,h2,h3,div,span')]}catch(x){}for(const e of es){let t='';try{t=(e.innerText||'').trim()}catch(x){}if(t&&t.length<500)out.push(t)}}return out}" +
            "function findEl(test){let best=null,bestLen=1e9;for(const r of roots()){let es=[];try{es=[...r.querySelectorAll('button,a,[role=button],[role=option],[role=checkbox],label,div,span')]}catch(x){}for(const e of es){let t='';try{t=(e.innerText||'').trim()}catch(x){}if(!t||t.length>320)continue;if(test(t,e)&&t.length<bestLen){best=e;bestLen=t.length}}}return best}" +
            "function clickEl(e){if(!e)return false;let x=e;try{x=e.closest('button,a,[role=button],[role=option],[role=checkbox],label')||e}catch(z){}try{x.scrollIntoView({block:'center'})}catch(z){}try{x.click();return true}catch(z){}try{x.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,view:window}));return true}catch(z){}return false}" +
            "function scrollDeep(){let moved=false;try{const old=window.scrollY;window.scrollBy(0,Math.max(650,window.innerHeight*.78));if(window.scrollY>old+2)moved=true}catch(x){}for(const r of roots()){let es=[];try{es=[...r.querySelectorAll('*')]}catch(x){}for(const e of es){try{const s=getComputedStyle(e);if(!/(auto|scroll)/.test(s.overflowY)||e.scrollHeight<=e.clientHeight+150)continue;const old=e.scrollTop;e.scrollTop=Math.min(e.scrollTop+Math.max(550,e.clientHeight*.75),e.scrollHeight);if(e.scrollTop>old+2)moved=true}catch(x){}}}return moved}";

    private static class Offer10 {
        String id="", title="", ean="", url="";
        double price=Double.NaN;
    }

    private static class Result10 {
        String offerId="", title="", ean="", status="", note="";
        double ownPrice=Double.NaN, lowestNew=Double.NaN, diffPln=Double.NaN, diffPct=Double.NaN;
        int totalOffers=0, otherOffers=0;
        Result10(Offer10 o){offerId=o.id;title=o.title;ean=o.ean;ownPrice=o.price;}
    }
}
