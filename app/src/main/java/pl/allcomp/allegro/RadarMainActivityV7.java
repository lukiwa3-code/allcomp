package pl.allcomp.allegro;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
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
import java.util.Map;

/**
 * Allegro Radar v7
 *
 * Market scan no longer uses Allegro search/listing heuristics.
 * For every selected Sales Center offer it follows the exact mobile flow:
 * 1. open the seller's exact offer page,
 * 2. read and click "PORÓWNAJ X OFERTY TEGO PRODUKTU",
 * 3. read the product offer count again on the comparison screen,
 * 4. choose "Najtaniej",
 * 5. choose state "Nowe",
 * 6. read the first NEW offer price from the sorted comparison list.
 *
 * This prevents unrelated products from contaminating the minimum price.
 */
public class RadarMainActivityV7 extends RadarMainActivityV3 {
    private static final String SALES_URL = "https://salescenter.allegro.com/my-assortment?limit=120&publication.status=ACTIVE&sellingMode.format=BUY_NOW&context.marketplace=allegro-pl";
    private static final int CREATE_CSV_V7 = 907;

    private final Handler h = new Handler(Looper.getMainLooper());
    private final LinkedHashMap<String, JSONObject> collected = new LinkedHashMap<>();
    private final List<OfferData> selectedOffers = new ArrayList<>();
    private final List<ResultData> resultsV7 = new ArrayList<>();

    private WebView radarWebView;
    private Spinner rangeSpinner;
    private TextView statusView;

    private boolean collecting = false;
    private boolean scanning = false;
    private boolean allMode = false;
    private int rangeStart = 1;
    private int rangeEnd = 10;
    private int iterations = 0;
    private int stagnant = 0;
    private int previousCount = 0;
    private int currentIndex = 0;
    private OfferData current;
    private ResultData currentResult;
    private String pendingCsv = "";

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        View root = findViewById(android.R.id.content);
        radarWebView = findWebView(root);
        statusView = findStatusView(root);
        addRangeControls(root);
        rewireTopButtons(root);
        updateVersionText(root);
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void addRangeControls(View root) {
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
        for (int s=1; s<=111; s+=10) ranges.add(s + "–" + (s+9));
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, ranges);
        rangeSpinner.setAdapter(adapter);

        Button scan = new Button(this);
        scan.setText("Skanuj zakres");
        scan.setAllCaps(false);
        scan.setTextSize(12);
        scan.setOnClickListener(v -> startSelectedRange());

        row.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(rangeSpinner, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(scan, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));

        main.addView(row, Math.min(1, main.getChildCount()));
    }

    private void rewireTopButtons(View view) {
        if (view instanceof Button) {
            Button b=(Button)view;
            String t=String.valueOf(b.getText()).trim();
            if ("Skanuj 5".equalsIgnoreCase(t)) {
                b.setVisibility(View.GONE);
            } else if ("Skanuj wszystkie".equalsIgnoreCase(t)) {
                b.setOnClickListener(v -> startAll());
            } else if ("Raport".equalsIgnoreCase(t)) {
                b.setOnClickListener(v -> showReportV7());
            } else if ("CSV".equalsIgnoreCase(t)) {
                b.setOnClickListener(v -> exportCsvV7());
            } else if ("STOP".equalsIgnoreCase(t)) {
                b.setOnClickListener(v -> stopV7(true));
            } else if ("Sales Center".equalsIgnoreCase(t)) {
                b.setOnClickListener(v -> {
                    stopV7(false);
                    radarWebView.loadUrl(SALES_URL);
                    setStatus("Sales Center. Poczekaj aż zobaczysz listę ofert.");
                });
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup g=(ViewGroup)view;
            for (int i=0;i<g.getChildCount();i++) rewireTopButtons(g.getChildAt(i));
        }
    }

    private void startSelectedRange() {
        if (rangeSpinner == null) return;
        int pos=rangeSpinner.getSelectedItemPosition();
        rangeStart=pos*10+1;
        rangeEnd=rangeStart+9;
        allMode=false;
        startCollection();
    }

    private void startAll() {
        rangeStart=1;
        rangeEnd=Integer.MAX_VALUE;
        allMode=true;
        startCollection();
    }

    private void startCollection() {
        if (collecting || scanning) {
            Toast.makeText(this,"Skanowanie już trwa. Użyj STOP.",Toast.LENGTH_SHORT).show();
            return;
        }
        if (radarWebView == null) return;
        String u=radarWebView.getUrl();
        if (u==null || !u.contains("salescenter.allegro.com")) {
            radarWebView.loadUrl(SALES_URL);
            Toast.makeText(this,"Otwieram Sales Center. Poczekaj na listę i kliknij ponownie.",Toast.LENGTH_LONG).show();
            return;
        }

        collecting=true;
        scanning=false;
        collected.clear();
        selectedOffers.clear();
        resultsV7.clear();
        iterations=0; stagnant=0; previousCount=0;
        setStatus(allMode ? "Wczytuję wszystkie oferty z Sales Center…" : "Zakres " + rangeStart + "–" + rangeEnd + ": wczytuję Sales Center…");

        forceTop();
        h.postDelayed(this::forceTop,300);
        h.postDelayed(this::forceTop,700);
        h.postDelayed(this::collectionStep,1200);
    }

    private void collectionStep() {
        if (!collecting || radarWebView==null) return;
        iterations++;
        String scanJs=getStaticString("JS_SCAN_SALES_V2");
        if (scanJs.isEmpty()) { failCollection("Brak skanera Sales Center."); return; }

        radarWebView.evaluateJavascript(scanJs, value -> {
            if (!collecting) return;
            try {
                String d=decodeJs(value);
                if (d!=null) {
                    JSONObject root=new JSONObject(d);
                    JSONArray arr=root.optJSONArray("offers");
                    if (arr!=null) for(int i=0;i<arr.length();i++) {
                        JSONObject j=arr.getJSONObject(i);
                        String id=j.optString("id","");
                        if(id.isEmpty()) continue;
                        JSONObject old=collected.get(id);
                        if(old==null || jsonScore(j)>jsonScore(old)) collected.put(id,j);
                    }
                }
            } catch(Exception ignored) {}

            int count=collected.size();
            if (allMode) setStatus("Sales Center: wczytano " + count + " ofert…");
            else setStatus("Zakres " + rangeStart + "–" + rangeEnd + ": wczytano " + count + " / " + rangeEnd);

            boolean enough=!allMode && count>=rangeEnd;
            if (enough) { finishCollection(); return; }

            if(count==previousCount) stagnant++; else stagnant=0;
            previousCount=count;
            if((allMode && stagnant>=24 && count>0) || iterations>=150 || (!allMode && stagnant>=28)) {
                finishCollection(); return;
            }

            String scrollJs=getStaticString("JS_SCROLL_SALES_V2");
            radarWebView.evaluateJavascript(scrollJs, x -> h.postDelayed(this::collectionStep,500));
        });
    }

    private void finishCollection() {
        collecting=false;
        List<JSONObject> all=new ArrayList<>(collected.values());
        int from=allMode ? 0 : rangeStart-1;
        int to=allMode ? all.size() : Math.min(rangeEnd,all.size());
        if(from>=all.size() || from>=to) {
            failCollection("Nie udało się wczytać wybranego zakresu. Wczytano " + all.size() + " ofert.");
            return;
        }

        selectedOffers.clear();
        for(int i=from;i<to;i++) selectedOffers.add(fromJson(all.get(i)));
        currentIndex=0;
        scanning=true;
        setStatus("Wybrano " + selectedOffers.size() + " ofert. Zaczynam dokładne porównanie…");
        processNext();
    }

    private void processNext() {
        if (!scanning) return;
        if (currentIndex>=selectedOffers.size()) {
            scanning=false;
            setStatus("Gotowe. Sprawdzono " + resultsV7.size() + " ofert.");
            showReportV7();
            return;
        }

        current=selectedOffers.get(currentIndex);
        currentResult=new ResultData(current);
        String url=(current.url!=null && current.url.contains("allegro.pl/oferta/")) ? current.url : "https://allegro.pl/oferta/" + current.id;
        setStatus("Oferta " + (currentIndex+1) + "/" + selectedOffers.size() + ": otwieram dokładną ofertę…");
        radarWebView.loadUrl(url);
        h.postDelayed(() -> inspectOfferPage(0),1200);
    }

    private void inspectOfferPage(int attempt) {
        if (!scanning || currentResult==null) return;
        String js="(function(){try{" +
                "const t=(document.body&&document.body.innerText)||'';" +
                "let m=t.match(/POR[ÓO]WNAJ\\s+([\\d\\s\\u00a0\\u202f]+)\\s+OFERT(?:A|Y)?\\s+TEGO\\s+PRODUKTU/i);" +
                "const n=m?parseInt(m[1].replace(/\\D/g,''),10):0;" +
                "const nw=/\\bStan\\s*:?\\s*Nowy\\b/i.test(t);" +
                "return JSON.stringify({count:isNaN(n)?0:n,newState:nw,body:t.slice(0,1000)});" +
                "}catch(e){return JSON.stringify({count:0,newState:false})}})()";
        radarWebView.evaluateJavascript(js, value -> {
            if(!scanning) return;
            try {
                String d=decodeJs(value);
                JSONObject j=d==null?new JSONObject():new JSONObject(d);
                int count=j.optInt("count",0);
                boolean ownNew=j.optBoolean("newState",false);
                if(count>0) {
                    currentResult.totalOffers=count;
                    currentResult.otherOffers=Math.max(0,count-1);
                    setStatus("Oferta " + (currentIndex+1) + "/" + selectedOffers.size() + ": znaleziono „Porównaj " + count + " ofert”…");
                    clickCompare(0);
                    return;
                }
                if(attempt>=18) {
                    currentResult.totalOffers=1;
                    currentResult.otherOffers=0;
                    if(ownNew && !Double.isNaN(current.price)) currentResult.lowestNew=current.price;
                    currentResult.status=Double.isNaN(currentResult.lowestNew)?"CZĘŚCIOWO":"OK";
                    currentResult.note="Brak przycisku „Porównaj”. Przyjmuję 1 ofertę produktu.";
                    finishCurrent();
                } else {
                    h.postDelayed(() -> inspectOfferPage(attempt+1),300);
                }
            } catch(Exception e) {
                if(attempt>=18) { currentResult.status="BŁĄD"; currentResult.note="Nie udało się odczytać strony oferty."; finishCurrent(); }
                else h.postDelayed(() -> inspectOfferPage(attempt+1),300);
            }
        });
    }

    private void clickCompare(int attempt) {
        if(!scanning) return;
        String js="(function(){try{" +
                "const re=/POR[ÓO]WNAJ\\s+[\\d\\s\\u00a0\\u202f]+\\s+OFERT(?:A|Y)?\\s+TEGO\\s+PRODUKTU/i;" +
                "const all=[...document.querySelectorAll('a,button,[role=button],div,span')];" +
                "let best=null;for(const e of all){const t=(e.innerText||'').trim();if(!re.test(t)||t.length>160)continue;if(!best||t.length<(best.innerText||'').trim().length)best=e;}" +
                "if(!best)return false;let x=best.closest('a,button,[role=button]')||best;try{x.click();return true}catch(z){return false}" +
                "}catch(e){return false}})()";
        radarWebView.evaluateJavascript(js, value -> {
            boolean clicked="true".equalsIgnoreCase(String.valueOf(value));
            if(clicked) h.postDelayed(() -> waitComparison(0),700);
            else if(attempt<10) h.postDelayed(() -> clickCompare(attempt+1),250);
            else { currentResult.status="BŁĄD"; currentResult.note="Nie udało się kliknąć „Porównaj oferty”."; finishCurrent(); }
        });
    }

    private void waitComparison(int attempt) {
        if(!scanning) return;
        String js="(function(){try{" +
                "const t=(document.body&&document.body.innerText)||'';" +
                "const ok=/Najtań|Najtaniej/i.test(t)&&/Najszybciej/i.test(t)&&/Oferty/i.test(t);" +
                "let c=0;for(const e of document.querySelectorAll('body *')){const x=(e.innerText||'').trim();const m=x.match(/^\\(?\\s*(\\d+)\\s+ofert(?:a|y)?\\s*\\)?$/i);if(m){c=parseInt(m[1],10);break;}}" +
                "return JSON.stringify({ok:ok,count:isNaN(c)?0:c});" +
                "}catch(e){return JSON.stringify({ok:false,count:0})}})()";
        radarWebView.evaluateJavascript(js, value -> {
            try {
                String d=decodeJs(value); JSONObject j=d==null?new JSONObject():new JSONObject(d);
                if(j.optBoolean("ok",false)) {
                    int c=j.optInt("count",0);
                    if(c>0){currentResult.totalOffers=c;currentResult.otherOffers=Math.max(0,c-1);}
                    setStatus("Oferta " + (currentIndex+1) + "/" + selectedOffers.size() + ": sortuję „Najtaniej”…");
                    clickCheapest(0);
                    return;
                }
            } catch(Exception ignored) {}
            if(attempt>=20){currentResult.status="BŁĄD";currentResult.note="Nie otworzył się ekran porównania ofert.";finishCurrent();}
            else h.postDelayed(() -> waitComparison(attempt+1),300);
        });
    }

    private void clickCheapest(int attempt) {
        String js="(function(){try{" +
                "const a=[...document.querySelectorAll('button,a,[role=button],div,span')].filter(e=>(e.innerText||'').trim().toLowerCase()==='najtaniej');" +
                "if(!a.length)return false;let e=a[0];let x=e.closest('button,a,[role=button]')||e;try{x.click();return true}catch(z){return false}" +
                "}catch(e){return false}})()";
        radarWebView.evaluateJavascript(js, value -> {
            boolean ok="true".equalsIgnoreCase(String.valueOf(value));
            if(ok) {
                setStatus("Oferta " + (currentIndex+1) + "/" + selectedOffers.size() + ": wybieram stan „Nowe”…");
                h.postDelayed(() -> openStateFilter(0),450);
            } else if(attempt<10) h.postDelayed(() -> clickCheapest(attempt+1),250);
            else {currentResult.status="BŁĄD";currentResult.note="Nie znalazłem sortowania „Najtaniej”.";finishCurrent();}
        });
    }

    private void openStateFilter(int attempt) {
        String js="(function(){try{" +
                "function vis(e){const r=e.getBoundingClientRect();const s=getComputedStyle(e);return r.width>0&&r.height>0&&s.visibility!=='hidden'&&s.display!=='none'}" +
                "const exact=[...document.querySelectorAll('body *')].filter(e=>vis(e)&&(e.innerText||'').trim()==='Stan');" +
                "for(const lab of exact){let p=lab.parentElement;for(let i=0;i<5&&p;i++,p=p.parentElement){const txt=(p.innerText||'').trim();if(txt.length<180&&/(Wszystkie|Nowe|Używane)/i.test(txt)){let b=p.querySelector('button,[role=button]');if(!b){const q=[...p.querySelectorAll('div,span')].find(x=>vis(x)&&/^(Wszystkie|Nowe|Używane)$/i.test((x.innerText||'').trim()));b=q||p;}try{b.click();return true}catch(z){}}}}" +
                "const vals=[...document.querySelectorAll('button,[role=button],div')].filter(e=>vis(e)&&/^(Wszystkie|Nowe)$/i.test((e.innerText||'').trim()));if(vals.length){try{vals[vals.length-1].click();return true}catch(z){}}" +
                "return false;}catch(e){return false}})()";
        radarWebView.evaluateJavascript(js, value -> {
            boolean ok="true".equalsIgnoreCase(String.valueOf(value));
            if(ok) h.postDelayed(() -> chooseNewState(0),350);
            else if(attempt<10) h.postDelayed(() -> openStateFilter(attempt+1),250);
            else {currentResult.status="BŁĄD";currentResult.note="Nie udało się otworzyć filtra „Stan”.";finishCurrent();}
        });
    }

    private void chooseNewState(int attempt) {
        String js="(function(){try{" +
                "function vis(e){const r=e.getBoundingClientRect();const s=getComputedStyle(e);return r.width>0&&r.height>0&&s.visibility!=='hidden'&&s.display!=='none'}" +
                "const es=[...document.querySelectorAll('body *')].filter(e=>vis(e)&&(e.innerText||'').trim()==='Nowe');" +
                "for(const e of es){let p=e;for(let i=0;i<6&&p;i++,p=p.parentElement){const txt=(p.innerText||'').trim();if(txt.length<220&&/Używane/i.test(txt)){const cb=p.querySelector('input[type=checkbox],[role=checkbox]');if(cb){const checked=cb.checked===true||cb.getAttribute('aria-checked')==='true';if(checked)return 'already';}let x=e.closest('label,button,[role=option],[role=checkbox]')||e;try{x.click();return 'clicked'}catch(z){}}}}" +
                "return 'missing';}catch(e){return 'missing'}})()";
        radarWebView.evaluateJavascript(js, value -> {
            String s=String.valueOf(value).replace("\"","");
            if("clicked".equals(s)||"already".equals(s)) {
                h.postDelayed(() -> readCheapestNew(0),900);
            } else if(attempt<10) {
                h.postDelayed(() -> chooseNewState(attempt+1),250);
            } else {
                currentResult.status="BŁĄD";
                currentResult.note="Nie udało się zaznaczyć stanu „Nowe”.";
                finishCurrent();
            }
        });
    }

    private void readCheapestNew(int attempt) {
        if(!scanning) return;
        String js="(function(){try{" +
                "function money(t){const lines=t.split(/\\n+/);for(const line of lines){const l=line.toLowerCase();if(l.includes('dostaw')||l.includes('rata')||l.includes('mies')||l.includes('cena z 30 dni'))continue;const m=line.match(/(\\d{1,3}(?:[ \\u00a0\\u202f]\\d{3})*(?:[,.]\\d{2}))\\s*zł/i);if(m){const n=parseFloat(m[1].replace(/[ \\u00a0\\u202f]/g,'').replace(',','.'));if(!isNaN(n))return n;}}return null;}" +
                "let hy=0;for(const e of document.querySelectorAll('body *')){if((e.innerText||'').trim()==='Oferty'){const r=e.getBoundingClientRect();hy=r.top+window.scrollY;break;}}" +
                "const c=[];for(const e of document.querySelectorAll('article,li,section,div')){const t=(e.innerText||'').trim();if(t.length<20||t.length>2200)continue;if(!/Stan\\s*:?\\s*Nowy\\b/i.test(t))continue;if(!/\\d[\\d \\u00a0\\u202f]*[,.]\\d{2}\\s*zł/i.test(t))continue;const r=e.getBoundingClientRect();const y=r.top+window.scrollY;if(y+5<hy)continue;const p=money(t);if(p!==null)c.push({p:p,y:y,len:t.length,text:t.slice(0,500)});}" +
                "c.sort((a,b)=>a.y-b.y||a.len-b.len);" +
                "return JSON.stringify({price:c.length?c[0].p:null,count:c.length,first:c.length?c[0].text:''});" +
                "}catch(e){return JSON.stringify({price:null,count:0})}})()";
        radarWebView.evaluateJavascript(js, value -> {
            try {
                String d=decodeJs(value); JSONObject j=d==null?new JSONObject():new JSONObject(d);
                if(!j.isNull("price")) {
                    double p=j.optDouble("price",Double.NaN);
                    if(!Double.isNaN(p)) {
                        currentResult.lowestNew=p;
                        currentResult.status="OK";
                        currentResult.note="Cena z dokładnej ścieżki: Porównaj → Najtaniej → Stan: Nowe → pierwsza oferta.";
                        finishCurrent();
                        return;
                    }
                }
            } catch(Exception ignored) {}
            if(attempt>=18) {
                currentResult.status="CZĘŚCIOWO";
                currentResult.note="Liczbę ofert odczytano, ale po filtrze „Nowe” nie udało się odczytać pierwszej ceny.";
                finishCurrent();
            } else h.postDelayed(() -> readCheapestNew(attempt+1),350);
        });
    }

    private void finishCurrent() {
        if(currentResult==null) return;
        if(!Double.isNaN(currentResult.lowestNew) && !Double.isNaN(currentResult.ownPrice)) {
            currentResult.diffPln=round2(currentResult.ownPrice-currentResult.lowestNew);
            if(currentResult.lowestNew>0) currentResult.diffPct=round2(currentResult.diffPln/currentResult.lowestNew*100.0);
        }
        resultsV7.add(currentResult);
        currentResult=null;
        current=null;
        currentIndex++;
        h.postDelayed(this::processNext,650);
    }

    private void showReportV7() {
        if(resultsV7.isEmpty()) {
            Toast.makeText(this,"Nie ma jeszcze wyników.",Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder s=new StringBuilder();
        for(ResultData r:resultsV7) {
            s.append(r.title).append("\nID: ").append(r.offerId);
            if(r.ean!=null&&!r.ean.isEmpty()) s.append("   EAN: ").append(r.ean);
            s.append("\nMoja cena: ").append(money(r.ownPrice))
             .append("\nOferty produktu: ").append(r.totalOffers>0?r.totalOffers:"—")
             .append("\nInne oferty: ").append(r.totalOffers>0?r.otherOffers:"—")
             .append("\nNajniższa NOWY: ").append(money(r.lowestNew))
             .append("\nRóżnica: ").append(moneySigned(r.diffPln))
             .append("\nStatus: ").append(r.status);
            if(r.note!=null&&!r.note.isEmpty()) s.append("\n").append(r.note);
            s.append("\n\n────────────────────────\n\n");
        }
        TextView tv=new TextView(this);
        tv.setText(s.toString()); tv.setTextSize(14); tv.setTextColor(Color.BLACK); tv.setPadding(dp(18),dp(12),dp(18),dp(18));
        ScrollView sv=new ScrollView(this); sv.addView(tv);
        new AlertDialog.Builder(this).setTitle("Allegro Radar v7").setView(sv).setPositiveButton("OK",null).show();
    }

    private void exportCsvV7() {
        if(resultsV7.isEmpty()) {Toast.makeText(this,"Najpierw wykonaj skanowanie.",Toast.LENGTH_SHORT).show();return;}
        StringBuilder s=new StringBuilder("ID oferty;Tytuł;EAN;Moja cena;Oferty produktu;Inne oferty;Najniższa cena NOWY;Różnica PLN;Różnica %;Status;Uwagi\r\n");
        for(ResultData r:resultsV7) {
            s.append(q(r.offerId)).append(';').append(q(r.title)).append(';').append(q(r.ean)).append(';')
             .append(num(r.ownPrice)).append(';').append(r.totalOffers).append(';').append(r.otherOffers).append(';')
             .append(num(r.lowestNew)).append(';').append(num(r.diffPln)).append(';').append(num(r.diffPct)).append(';')
             .append(q(r.status)).append(';').append(q(r.note)).append("\r\n");
        }
        pendingCsv=s.toString();
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("text/csv"); i.putExtra(Intent.EXTRA_TITLE,"allegro_radar_v7.csv");
        startActivityForResult(i,CREATE_CSV_V7);
    }

    @Override
    protected void onActivityResult(int req,int res,Intent data) {
        super.onActivityResult(req,res,data);
        if(req==CREATE_CSV_V7 && res==RESULT_OK && data!=null && data.getData()!=null) {
            try(OutputStream os=getContentResolver().openOutputStream(data.getData())) {
                if(os!=null){os.write(new byte[]{(byte)0xEF,(byte)0xBB,(byte)0xBF});os.write(pendingCsv.getBytes(StandardCharsets.UTF_8));Toast.makeText(this,"CSV zapisany.",Toast.LENGTH_SHORT).show();}
            } catch(Exception e){Toast.makeText(this,"Błąd zapisu CSV: "+e.getMessage(),Toast.LENGTH_LONG).show();}
        }
    }

    private void stopV7(boolean say) {
        collecting=false; scanning=false;
        h.removeCallbacksAndMessages(null);
        if(say) setStatus("Skanowanie zatrzymane.");
    }

    @Override
    public void onBackPressed() {
        if(collecting||scanning) {
            new AlertDialog.Builder(this).setTitle("Skanowanie trwa").setMessage("Zatrzymać skanowanie?")
                    .setPositiveButton("Tak",(d,w)->stopV7(true)).setNegativeButton("Nie",null).show();
            return;
        }
        super.onBackPressed();
    }

    private void forceTop() {
        if(radarWebView==null)return;
        radarWebView.evaluateJavascript("(function(){try{window.scrollTo(0,0);const a=[document.scrollingElement,...document.querySelectorAll('*')].filter(Boolean);for(const e of a){try{if(e.scrollHeight>e.clientHeight+40)e.scrollTop=0}catch(x){}}window.scrollTo(0,0);return true}catch(e){return false}})()",null);
    }

    private void failCollection(String msg) {
        collecting=false; scanning=false; setStatus(msg); Toast.makeText(this,msg,Toast.LENGTH_LONG).show();
    }

    private OfferData fromJson(JSONObject j) {
        OfferData o=new OfferData();
        o.id=j.optString("id",""); o.title=j.optString("title",""); o.ean=j.optString("ean",""); o.url=j.optString("url","");
        if(j.has("price")&&!j.isNull("price"))o.price=j.optDouble("price",Double.NaN);
        return o;
    }

    private int jsonScore(JSONObject j) {
        int s=0;if(!j.optString("title","").isEmpty())s+=2;if(!j.optString("ean","").isEmpty())s+=3;if(j.has("price")&&!j.isNull("price"))s+=3;if(!j.optString("url","").isEmpty())s++;return s;
    }

    private String getStaticString(String name) {
        try{Field f=MobileMainActivity.class.getDeclaredField(name);f.setAccessible(true);return String.valueOf(f.get(null));}catch(Exception e){return "";}
    }

    private String decodeJs(String v) {
        if(v==null||"null".equals(v))return null;
        try{return new JSONArray("["+v+"]").getString(0);}catch(Exception e){return null;}
    }

    private void setStatus(String text) {
        if(statusView!=null) statusView.setText(text);
        else try{Field f=MobileMainActivity.class.getDeclaredField("status");f.setAccessible(true);Object x=f.get(this);if(x instanceof TextView)((TextView)x).setText(text);}catch(Exception ignored){}
    }

    private TextView findStatusView(View view) {
        if(view instanceof TextView && !(view instanceof Button)) {
            String t=String.valueOf(((TextView)view).getText());
            if(t.contains("Allegro Radar")||t.contains("Zaloguj")||t.contains("Sales Center")) return (TextView)view;
        }
        if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++){TextView x=findStatusView(g.getChildAt(i));if(x!=null)return x;}}
        return null;
    }

    private WebView findWebView(View view) {
        if(view instanceof WebView)return (WebView)view;
        if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++){WebView w=findWebView(g.getChildAt(i));if(w!=null)return w;}}
        return null;
    }

    private LinearLayout findMainVerticalLayout(View view) {
        if(view instanceof LinearLayout){LinearLayout l=(LinearLayout)view;if(l.getOrientation()==LinearLayout.VERTICAL&&findWebView(l)!=null)return l;}
        if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++){LinearLayout l=findMainVerticalLayout(g.getChildAt(i));if(l!=null)return l;}}
        return null;
    }

    private void updateVersionText(View view) {
        if(view instanceof TextView && !(view instanceof Button)) {
            TextView t=(TextView)view;String s=String.valueOf(t.getText());
            if(s.contains("Allegro Radar")||s.contains("Radar v")) t.setText(s.replace("v2","v7").replace("v3","v7").replace("v4","v7").replace("v5","v7").replace("v6","v7"));
        }
        if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++)updateVersionText(g.getChildAt(i));}
    }

    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private double round2(double x){return Math.round(x*100.0)/100.0;}
    private String money(double d){return Double.isNaN(d)?"—":String.format(Locale.forLanguageTag("pl-PL"),"%.2f zł",d);}
    private String moneySigned(double d){return Double.isNaN(d)?"—":(d>0?"+":"")+String.format(Locale.forLanguageTag("pl-PL"),"%.2f zł",d);}
    private String q(String s){return "\""+(s==null?"":s.replace("\"","\"\""))+"\"";}
    private String num(double d){return Double.isNaN(d)?"":String.format(Locale.US,"%.2f",d).replace('.',',');}

    private static class OfferData {
        String id="",title="",ean="",url=""; double price=Double.NaN;
    }

    private static class ResultData {
        String offerId="",title="",ean="",status="",note="";
        double ownPrice=Double.NaN,lowestNew=Double.NaN,diffPln=Double.NaN,diffPct=Double.NaN;
        int totalOffers=0,otherOffers=0;
        ResultData(OfferData o){offerId=o.id;title=o.title;ean=o.ean;ownPrice=o.price;}
    }
}
