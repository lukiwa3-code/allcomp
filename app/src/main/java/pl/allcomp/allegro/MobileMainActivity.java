package pl.allcomp.allegro;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MobileMainActivity extends Activity {
    private static final String SALES_URL = "https://salescenter.allegro.com/my-assortment?limit=120&publication.status=ACTIVE&sellingMode.format=BUY_NOW&context.marketplace=allegro-pl";
    private static final int CREATE_CSV = 902;
    private static final String VERSION = "v2";

    private WebView webView;
    private TextView status;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LinkedHashMap<String, Offer> offers = new LinkedHashMap<>();
    private final List<ResultRow> results = new ArrayList<>();
    private final LinkedHashMap<String, MarketCard> marketCards = new LinkedHashMap<>();
    private final List<GroupCandidate> marketGroups = new ArrayList<>();

    private boolean cancelled, scanningSales, scanningMarket, waitingMarketPage;
    private int requestedLimit = 5, salesIterations, salesStagnant, salesPreviousCount, marketIndex, marketScrollIteration, lastNrCount;
    private String pendingCsv = "";

    private static final Pattern PRICE_RE = Pattern.compile("(?<!\\d)(\\d{1,3}(?:[ \\u00a0\\u202f]\\d{3})*(?:[,.]\\d{2}))\\s*zł", Pattern.CASE_INSENSITIVE);
    private static final Pattern MODEL_RE = Pattern.compile("[A-Za-z0-9-]*\\d[A-Za-z0-9-]{3,}");

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        buildUi();
        configureWebView();
        webView.loadUrl(SALES_URL);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(4), dp(4), dp(4), dp(2));

        Button sales = button("Sales Center"), scan5 = button("Skanuj 5"), scanAll = button("Skanuj wszystkie"), report = button("Raport"), csv = button("CSV"), stop = button("STOP");
        row.addView(sales); row.addView(scan5); row.addView(scanAll); row.addView(report); row.addView(csv); row.addView(stop);
        hs.addView(row);

        status = new TextView(this);
        status.setText("Allegro Radar " + VERSION + ". Zaloguj się i kliknij Skanuj 5.");
        status.setTextSize(14);
        status.setTextColor(Color.rgb(30,30,30));
        status.setPadding(dp(10),dp(6),dp(10),dp(6));
        status.setBackgroundColor(Color.rgb(244,246,248));

        webView = new WebView(this);
        webView.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1));
        root.addView(hs); root.addView(status); root.addView(webView);
        setContentView(root);

        sales.setOnClickListener(v -> { stopScan(false); webView.loadUrl(SALES_URL); setStatus("Sales Center. Poczekaj aż zobaczysz listę ofert."); });
        scan5.setOnClickListener(v -> startSalesScan(5));
        scanAll.setOnClickListener(v -> startSalesScan(0));
        report.setOnClickListener(v -> showReport());
        csv.setOnClickListener(v -> exportCsv());
        stop.setOnClickListener(v -> stopScan(true));
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text); b.setTextSize(12); b.setAllCaps(false);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        p.setMargins(dp(2),0,dp(2),0); b.setLayoutParams(p);
        return b;
    }

    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadsImagesAutomatically(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView,true);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                if (scanningMarket && waitingMarketPage && !cancelled) {
                    waitingMarketPage=false;
                    handler.postDelayed(() -> { acceptCookies(); handler.postDelayed(MobileMainActivity.this::marketCollectStep,1000); },900);
                }
            }
        });
    }

    private void startSalesScan(int limit) {
        if (scanningSales || scanningMarket) {
            Toast.makeText(this,"Skanowanie już trwa. Użyj STOP.",Toast.LENGTH_SHORT).show();
            return;
        }
        String u=webView.getUrl();
        if (u==null || !u.contains("salescenter.allegro.com")) {
            webView.loadUrl(SALES_URL);
            Toast.makeText(this,"Otwieram Sales Center. Poczekaj na listę i kliknij ponownie.",Toast.LENGTH_LONG).show();
            return;
        }
        cancelled=false; scanningSales=true; requestedLimit=limit;
        offers.clear(); results.clear();
        salesIterations=0; salesStagnant=0; salesPreviousCount=0; lastNrCount=0;
        setStatus("Radar " + VERSION + ": czytam mobilny Sales Center…");
        webView.evaluateJavascript("(function(){try{window.scrollTo(0,0);const e=document.scrollingElement;if(e)e.scrollTop=0;return true}catch(x){return false}})()", x -> handler.postDelayed(this::salesScanStep,350));
    }

    private void salesScanStep() {
        if (!scanningSales || cancelled) return;
        salesIterations++;
        webView.evaluateJavascript(JS_SCAN_SALES_V2, value -> {
            if (!scanningSales || cancelled) return;
            try {
                String d=decode(value);
                if(d!=null) {
                    JSONObject root=new JSONObject(d);
                    lastNrCount=root.optInt("nrCount",0);
                    JSONArray a=root.optJSONArray("offers");
                    if(a!=null) {
                        for(int i=0;i<a.length();i++) {
                            JSONObject j=a.getJSONObject(i);
                            Offer o=new Offer();
                            o.id=j.optString("id","");
                            o.title=j.optString("title","");
                            o.ean=j.optString("ean","");
                            o.url=j.optString("url","");
                            if(!j.isNull("price")) o.price=j.optDouble("price",Double.NaN);
                            if(!o.id.isEmpty()) {
                                Offer old=offers.get(o.id);
                                if(old==null || score(o)>score(old)) offers.put(o.id,o);
                            }
                        }
                    }
                }
            } catch(Exception ignored) {}

            int c=offers.size();
            setStatus("Sales Center: znaleziono " + c + (requestedLimit>0?" / "+requestedLimit:"") + "  •  widzę nr: " + lastNrCount);
            if(requestedLimit>0 && c>=requestedLimit) { finishSalesScan(); return; }

            if(c==salesPreviousCount) salesStagnant++; else salesStagnant=0;
            salesPreviousCount=c;

            if((salesStagnant>=16 && c>0) || salesIterations>=80 || (salesStagnant>=22 && c==0)) {
                finishSalesScan(); return;
            }

            webView.evaluateJavascript(JS_SCROLL_SALES_V2, x -> handler.postDelayed(this::salesScanStep,520));
        });
    }

    private int score(Offer o) {
        int s=0;
        if(o.title!=null&&!o.title.isEmpty()) s+=2;
        if(o.ean!=null&&!o.ean.isEmpty()) s+=3;
        if(!Double.isNaN(o.price)) s+=3;
        if(o.url!=null&&!o.url.isEmpty()) s++;
        return s;
    }

    private void finishSalesScan() {
        scanningSales=false;
        if(cancelled) return;
        if(offers.isEmpty()) {
            setStatus("0 ofert. Tekst 'nr:' widziany na stronie: " + lastNrCount + ".");
            Toast.makeText(this,"Nie udało się odczytać kart. Licznik nr: " + lastNrCount,Toast.LENGTH_LONG).show();
            return;
        }
        if(requestedLimit>0 && offers.size()>requestedLimit) {
            List<Offer> l=new ArrayList<>(offers.values()).subList(0,requestedLimit);
            offers.clear(); for(Offer o:l) offers.put(o.id,o);
        }
        marketIndex=0; results.clear(); scanningMarket=true;
        setStatus("Odczytano " + offers.size() + " ofert. Sprawdzam konkurencję…");
        scanNextMarketOffer();
    }

    private void scanNextMarketOffer() {
        if(!scanningMarket||cancelled) return;
        List<Offer> list=new ArrayList<>(offers.values());
        if(marketIndex>=list.size()) {
            scanningMarket=false;
            setStatus("Gotowe. Sprawdzono " + results.size() + " ofert.");
            showReport();
            return;
        }
        Offer item=list.get(marketIndex);
        marketCards.clear(); marketGroups.clear(); marketScrollIteration=0;
        String q=!item.ean.isEmpty()?item.ean:item.title;
        if(q==null||q.trim().isEmpty()) {
            ResultRow r=ResultRow.from(item); r.status="BRAK DANYCH"; r.note="Brak EAN i tytułu.";
            results.add(r); marketIndex++; scanNextMarketOffer(); return;
        }
        setStatus("Konkurencja " + (marketIndex+1) + "/" + list.size() + ": " + shortText(item.title,50));
        try {
            waitingMarketPage=true;
            webView.loadUrl("https://allegro.pl/listing?string=" + URLEncoder.encode(q, StandardCharsets.UTF_8.name()) + "&stan=nowe&order=p");
        } catch(Exception e) {
            ResultRow r=ResultRow.from(item); r.status="BŁĄD"; r.note=e.getMessage(); results.add(r);
            marketIndex++; handler.postDelayed(this::scanNextMarketOffer,400);
        }
    }

    private void marketCollectStep() {
        if(!scanningMarket||cancelled) return;
        webView.evaluateJavascript(JS_SCAN_MARKET, value -> {
            if(!scanningMarket||cancelled) return;
            try {
                String d=decode(value);
                if(d!=null) {
                    JSONObject root=new JSONObject(d);
                    JSONArray c=root.optJSONArray("cards");
                    if(c!=null) for(int i=0;i<c.length();i++) {
                        JSONObject j=c.getJSONObject(i);
                        MarketCard m=new MarketCard();
                        m.offerId=j.optString("offerId",""); m.text=j.optString("text",""); m.seller=j.optString("seller","");
                        if(!m.offerId.isEmpty()) marketCards.put(m.offerId,m);
                    }
                    JSONArray g=root.optJSONArray("groups");
                    if(g!=null) for(int i=0;i<g.length();i++) {
                        JSONObject j=g.getJSONObject(i);
                        GroupCandidate x=new GroupCandidate(); x.count=j.optInt("count",0); x.text=j.optString("text","");
                        if(x.count>0&&!x.text.isEmpty()) marketGroups.add(x);
                    }
                }
            } catch(Exception ignored) {}
            marketScrollIteration++;
            if(marketScrollIteration>=10 || marketCards.size()>=160) { finalizeMarketOffer(); return; }
            webView.evaluateJavascript("(function(){try{window.scrollBy(0,Math.max(1000,window.innerHeight*1.35));return window.scrollY}catch(e){return 0}})()", x -> handler.postDelayed(this::marketCollectStep,500));
        });
    }

    private void finalizeMarketOffer() {
        Offer item=new ArrayList<>(offers.values()).get(marketIndex);
        ResultRow r=ResultRow.from(item);
        List<MarketCard> relevant=new ArrayList<>();
        Set<String> sellers=new LinkedHashSet<>();
        double lowest=Double.NaN;

        for(MarketCard c:marketCards.values()) {
            if(item.id.equals(c.offerId) || !relevant(c.text,item)) continue;
            double p=currentPrice(c.text);
            if(!Double.isNaN(p)) {
                relevant.add(c);
                if(Double.isNaN(lowest)||p<lowest) lowest=p;
                if(c.seller!=null&&!c.seller.trim().isEmpty()) sellers.add(c.seller.trim().toLowerCase(Locale.ROOT));
            }
        }

        GroupCandidate best=bestGroup(item);
        int groupCount=best==null?0:best.count;
        double gp=best==null?Double.NaN:currentPrice(best.text);
        if(Double.isNaN(lowest)&&!Double.isNaN(gp)) lowest=gp;

        if(!sellers.isEmpty()) {
            r.otherSellers=sellers.size(); r.method="unikalni sprzedawcy";
        } else if(!relevant.isEmpty()) {
            r.otherSellers=relevant.size(); r.method="inne oferty";
            r.note="Allegro nie pokazało nazw sprzedawców w kartach.";
        } else if(groupCount>0) {
            r.otherSellers=Math.max(groupCount-1,0); r.method="oferty produktu - 1";
            r.note="Liczba konkurentów oszacowana z grupy ofert produktu.";
        } else {
            r.otherSellers=0; r.method="brak grupy";
            r.note="Nie znaleziono innych ofert tego produktu.";
        }

        r.totalOffers=groupCount>0?groupCount:r.otherSellers+1;
        r.lowestNew=lowest;
        if(!Double.isNaN(r.ownPrice)&&!Double.isNaN(lowest)) {
            r.diffPln=round2(r.ownPrice-lowest);
            if(lowest>0) r.diffPct=round2(r.diffPln/lowest*100);
        }
        r.status=(!Double.isNaN(r.ownPrice)&&!Double.isNaN(lowest))?"OK":"CZĘŚCIOWO";
        results.add(r);
        marketIndex++;
        handler.postDelayed(this::scanNextMarketOffer,650);
    }

    private GroupCandidate bestGroup(Offer item) {
        GroupCandidate b=null; int best=Integer.MIN_VALUE;
        for(GroupCandidate g:marketGroups) {
            int s=similarity(g.text,item.title);
            if(b==null||s>best) { b=g; best=s; }
        }
        return b;
    }

    private boolean relevant(String text,Offer item) {
        List<String> t=tokens(item.title);
        if(t.isEmpty()) return true;
        String l=(text==null?"":text).toLowerCase(Locale.ROOT);
        for(String x:t) if(l.contains(x.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private List<String> tokens(String title) {
        List<String> out=new ArrayList<>();
        if(title==null) return out;
        Matcher m=MODEL_RE.matcher(title);
        while(m.find()) {
            String t=m.group();
            if(t.length()>=4&&!t.matches("\\d{8,14}")) out.add(t);
            if(out.size()>=5) break;
        }
        return out;
    }

    private int similarity(String text,String title) {
        String l=(text==null?"":text).toLowerCase(Locale.ROOT); int s=0;
        for(String t:tokens(title)) if(l.contains(t.toLowerCase(Locale.ROOT))) s+=5;
        return s;
    }

    private double currentPrice(String text) {
        if(text==null) return Double.NaN;
        for(String line:text.split("\\n")) {
            String l=line.toLowerCase(Locale.ROOT);
            if(l.contains("cena z 30 dni")||l.contains("dostaw")||l.contains("rata")||l.contains("mies")||l.contains("/szt")||l.contains("/kg")||l.contains("/100")) continue;
            Matcher m=PRICE_RE.matcher(line); double best=Double.NaN;
            while(m.find()) {
                double p=parsePrice(m.group(1));
                if(!Double.isNaN(p)&&(Double.isNaN(best)||p<best)) best=p;
            }
            if(!Double.isNaN(best)) return best;
        }
        Matcher m=PRICE_RE.matcher(text);
        return m.find()?parsePrice(m.group(1)):Double.NaN;
    }

    private double parsePrice(String s) {
        try { return Double.parseDouble(s.replace("\u00a0","").replace("\u202f","").replace(" ","").replace(',','.')); }
        catch(Exception e) { return Double.NaN; }
    }

    private void showReport() {
        if(results.isEmpty()) {
            if(!offers.isEmpty()) {
                StringBuilder tmp=new StringBuilder("Oferty odczytane z Sales Center:\n\n");
                for(Offer o:offers.values()) tmp.append(o.id).append("\n").append(o.title).append("\nEAN: ").append(o.ean).append("\nMoja cena: ").append(money(o.price)).append("\n\n");
                showTextDialog("Allegro Radar " + VERSION,tmp.toString());
            } else Toast.makeText(this,"Nie ma jeszcze wyników.",Toast.LENGTH_SHORT).show();
            return;
        }
        StringBuilder s=new StringBuilder();
        for(ResultRow r:results) {
            s.append(r.title).append("\nID: ").append(r.offerId).append("   EAN: ").append(r.ean)
                    .append("\nMoja cena: ").append(money(r.ownPrice))
                    .append("\nNajniższa NOWY: ").append(money(r.lowestNew))
                    .append("\nInni sprzedawcy/oferty: ").append(r.otherSellers)
                    .append("\nRóżnica: ").append(moneySigned(r.diffPln))
                    .append("\nStatus: ").append(r.status).append(" • ").append(r.method);
            if(r.note!=null&&!r.note.isEmpty()) s.append("\n").append(r.note);
            s.append("\n\n────────────────────────\n\n");
        }
        showTextDialog("Allegro Radar " + VERSION,s.toString());
    }

    private void showTextDialog(String title,String text) {
        TextView tv=new TextView(this);
        tv.setText(text); tv.setTextSize(14); tv.setTextColor(Color.BLACK); tv.setPadding(dp(18),dp(12),dp(18),dp(18));
        ScrollView sv=new ScrollView(this); sv.addView(tv);
        new AlertDialog.Builder(this).setTitle(title).setView(sv).setPositiveButton("OK",null).show();
    }

    private void exportCsv() {
        if(results.isEmpty()) { Toast.makeText(this,"Najpierw wykonaj pełne skanowanie.",Toast.LENGTH_SHORT).show(); return; }
        pendingCsv=csvText();
        Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("text/csv"); i.putExtra(Intent.EXTRA_TITLE,"allegro_radar.csv");
        startActivityForResult(i,CREATE_CSV);
    }

    @Override protected void onActivityResult(int req,int res,Intent data) {
        super.onActivityResult(req,res,data);
        if(req==CREATE_CSV&&res==RESULT_OK&&data!=null&&data.getData()!=null) {
            try(OutputStream os=getContentResolver().openOutputStream(data.getData())) {
                if(os!=null) {
                    os.write(new byte[]{(byte)0xEF,(byte)0xBB,(byte)0xBF});
                    os.write(pendingCsv.getBytes(StandardCharsets.UTF_8));
                    Toast.makeText(this,"CSV zapisany.",Toast.LENGTH_SHORT).show();
                }
            } catch(Exception e) { Toast.makeText(this,"Błąd zapisu: "+e.getMessage(),Toast.LENGTH_LONG).show(); }
        }
    }

    private String csvText() {
        StringBuilder s=new StringBuilder("ID oferty;Tytuł;EAN;Moja cena;Oferty produktu;Inni sprzedawcy/oferty;Najniższa cena NOWY;Różnica PLN;Różnica %;Status;Metoda;Uwagi\r\n");
        for(ResultRow r:results) s.append(q(r.offerId)).append(';').append(q(r.title)).append(';').append(q(r.ean)).append(';').append(num(r.ownPrice)).append(';').append(r.totalOffers).append(';').append(r.otherSellers).append(';').append(num(r.lowestNew)).append(';').append(num(r.diffPln)).append(';').append(num(r.diffPct)).append(';').append(q(r.status)).append(';').append(q(r.method)).append(';').append(q(r.note)).append("\r\n");
        return s.toString();
    }

    private String q(String s) { return "\""+(s==null?"":s.replace("\"","\"\""))+"\""; }
    private String num(double d) { return Double.isNaN(d)?"":String.format(Locale.US,"%.2f",d).replace('.',','); }

    private void acceptCookies() {
        webView.evaluateJavascript("(function(){for(const e of document.querySelectorAll('button,a')){const t=(e.innerText||'').toLowerCase();if(t.includes('zgadzam się')||t.includes('akceptuj')||t.includes('przejdź do serwisu')){try{e.click();return true}catch(x){}}}return false;})()",null);
    }

    private void stopScan(boolean say) {
        cancelled=true; scanningSales=false; scanningMarket=false; waitingMarketPage=false;
        handler.removeCallbacksAndMessages(null);
        if(say) setStatus("Skanowanie zatrzymane.");
    }

    @Override public void onBackPressed() {
        if(scanningSales||scanningMarket) {
            new AlertDialog.Builder(this).setTitle("Skanowanie trwa").setMessage("Zatrzymać skanowanie?").setPositiveButton("Tak",(d,w)->stopScan(true)).setNegativeButton("Nie",null).show();
            return;
        }
        if(webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }

    private String decode(String v) {
        if(v==null||"null".equals(v)) return null;
        try { return new JSONArray("["+v+"]").getString(0); }
        catch(Exception e) { return null; }
    }

    private void setStatus(String s) { status.setText(s); }
    private int dp(int v) { return Math.round(v*getResources().getDisplayMetrics().density); }
    private static double round2(double v) { return Math.round(v*100.0)/100.0; }
    private static String shortText(String s,int n) { if(s==null)return ""; return s.length()<=n?s:s.substring(0,n)+"…"; }
    private static String money(double d) { return Double.isNaN(d)?"—":String.format(Locale.forLanguageTag("pl-PL"),"%.2f zł",d); }
    private static String moneySigned(double d) { return Double.isNaN(d)?"—":(d>0?"+":"")+String.format(Locale.forLanguageTag("pl-PL"),"%.2f zł",d); }

    private static class Offer { String id="",title="",ean="",url=""; double price=Double.NaN; }
    private static class MarketCard { String offerId="",text="",seller=""; }
    private static class GroupCandidate { int count; String text=""; }
    private static class ResultRow {
        String offerId="",title="",ean="",status="",method="",note="";
        double ownPrice=Double.NaN,lowestNew=Double.NaN,diffPln=Double.NaN,diffPct=Double.NaN;
        int totalOffers,otherSellers;
        static ResultRow from(Offer o) { ResultRow r=new ResultRow(); r.offerId=o.id; r.title=o.title; r.ean=o.ean; r.ownPrice=o.price; return r; }
    }

    private static final String JS_SCROLL_SALES_V2 =
            "(function(){try{" +
            "let moved=false;" +
            "const de=document.scrollingElement||document.documentElement;" +
            "if(de){const old=de.scrollTop;de.scrollTop=Math.min(de.scrollTop+Math.max(560,window.innerHeight*.78),de.scrollHeight);if(de.scrollTop>old+2)moved=true;}" +
            "try{const old=window.scrollY;window.scrollBy(0,Math.max(560,window.innerHeight*.78));if(window.scrollY>old+2)moved=true;}catch(e){}" +
            "const arr=[...document.querySelectorAll('*')].filter(e=>{try{const s=getComputedStyle(e);return /(auto|scroll)/.test(s.overflowY)&&e.scrollHeight>e.clientHeight+150}catch(x){return false}}).sort((a,b)=>(b.scrollHeight-b.clientHeight)-(a.scrollHeight-a.clientHeight));" +
            "for(const e of arr.slice(0,4)){const old=e.scrollTop;e.scrollTop=Math.min(e.scrollTop+Math.max(500,e.clientHeight*.76),e.scrollHeight);if(e.scrollTop>old+2)moved=true;}" +
            "return moved;}catch(e){return false}})()";

    private static final String JS_SCAN_SALES_V2 =
            "(function(){try{" +
            "const found=new Map();" +
            "const idRe=/\\bnr\\s*[:：]?\\s*(\\d{8,16})\\b/gi;" +
            "const eanRe=/EAN\\s*\\(?(?:GTIN)?\\)?\\s*[:：]?\\s*(\\d{8,14})/i;" +
            "const moneyRe=/(\\d{1,3}(?:[ \\u00a0\\u202f]\\d{3})*(?:[,.]\\d{2}))\\s*zł/i;" +
            "function clean(x){return (x||'').replace(/\\u00a0/g,' ').replace(/\\u202f/g,' ').replace(/[ \\t]+/g,' ').trim();}" +
            "function price(t){let m=t.match(/(?:^|\\n)\\s*cena\\s*(?:\\n|\\s)+(?:(?:od|za)\\s+)?(\\d{1,3}(?:[ \\u00a0\\u202f]\\d{3})*(?:[,.]\\d{2}))\\s*zł/i);if(!m)m=t.match(moneyRe);if(!m)return null;const n=parseFloat(m[1].replace(/[ \\u00a0\\u202f]/g,'').replace(',','.'));return isNaN(n)?null:n;}" +
            "function title(t,id){const lines=t.split(/\\n+/).map(clean).filter(Boolean);let idx=lines.findIndex(x=>new RegExp('\\bnr\\s*[:：]?\\s*'+id+'\\b','i').test(x));if(idx<0){idx=lines.findIndex(x=>x.includes(id));}if(idx<0)return '';const noise=['zaznacz','sortowanie','ofert','cena','liczba sztuk','statystyki','rynki','cechy','status','szczegóły','wizyty','sprzedano','obserwujący','smart','business','aktywna','pokaż wskazówki'];const a=[];for(let i=idx-1;i>=0&&i>=idx-8;i--){const x=lines[i],l=x.toLowerCase();if(!x||/^\\d+$/.test(x)||moneyRe.test(x)||noise.some(n=>l===n||l.startsWith(n+':'))){if(a.length)break;continue;}a.unshift(x);if(a.length>=5||a.join(' ').length>220)break;}return clean(a.join(' '));}" +
            "function add(id,t,el){if(!id)return;const em=t.match(eanRe);const p=price(t);const ti=title(t,id);let url='';try{if(el){for(const a of el.querySelectorAll('a[href]')){const h=a.href||'';if(h.includes('/oferta/')&&h.includes(id)){url=h;break;}}}}catch(e){}const q=(em?3:0)+(p!==null?3:0)+(ti?2:0)+(url?1:0);const old=found.get(id);if(!old||q>old.q||((q===old.q)&&t.length<old.raw.length))found.set(id,{id:id,title:ti,ean:em?em[1]:'',price:p,url:url,q:q,raw:t});}" +
            "for(const el of document.querySelectorAll('body *')){let t='';try{t=(el.innerText||'').trim();}catch(e){continue;}if(t.length<35||t.length>4200)continue;const ms=[...t.matchAll(/\\bnr\\s*[:：]?\\s*(\\d{8,16})\\b/gi)];if(ms.length!==1)continue;if(!eanRe.test(t)&&!moneyRe.test(t))continue;add(ms[0][1],t,el);}" +
            "const body=(document.body&&document.body.innerText)||'';const all=[...body.matchAll(/\\bnr\\s*[:：]?\\s*(\\d{8,16})\\b/gi)];for(let i=0;i<all.length;i++){const m=all[i],start=Math.max(0,m.index-420),end=Math.min(body.length,(i+1<all.length?all[i+1].index:m.index+900));add(m[1],body.slice(start,end),null);}" +
            "const offers=[...found.values()].map(x=>({id:x.id,title:x.title,ean:x.ean,price:x.price,url:x.url}));" +
            "return JSON.stringify({offers:offers,nrCount:all.length});" +
            "}catch(e){return JSON.stringify({offers:[],nrCount:0,error:String(e)})}})()";

    private static final String JS_SCAN_MARKET =
            "(function(){try{" +
            "const cards=[],groups=[],seen=new Set();" +
            "for(const a of document.querySelectorAll('a[href*=\\\"/oferta/\\\"]')){const href=a.href||'',m=href.match(/(\\d{8,16})(?:[?#]|$)/),id=m?m[1]:'';if(!id||seen.has(id))continue;let e=a,b='',be=null;for(let i=0;i<9&&e;i++,e=e.parentElement){const t=(e.innerText||'').trim();if(t.length>=15&&t.length<=3000&&/\\d[\\d \\u00a0\\u202f]*[,.]\\d{2}\\s*zł/i.test(t)){if(!b||t.length<b.length){b=t;be=e}}}if(!b)continue;let s='';try{const x=be.querySelector('a[href*=\\\"/uzytkownik/\\\"]');if(x)s=(x.innerText||'').trim()||(x.getAttribute('href')||'').split('/uzytkownik/')[1].split(/[?#/]/)[0]}catch(z){}cards.push({offerId:id,text:b,seller:s});seen.add(id);}" +
            "const gs=new Set();for(const e of document.querySelectorAll('body *')){const o=(e.innerText||'').trim(),m=o.match(/(?:zobacz\\s+)?(\\d+)\\s+ofert(?:a|y)?(?:\\s+tego\\s+produktu)?/i);if(!m)continue;let x=e,b='';for(let i=0;i<7&&x;i++,x=x.parentElement){const t=(x.innerText||'').trim();if(t.length>20&&t.length<2800&&/\\d[\\d \\u00a0\\u202f.,]*\\s*zł/i.test(t)){if(!b||t.length<b.length)b=t}}if(b){const k=m[1]+'|'+b.slice(0,400);if(!gs.has(k)){gs.add(k);groups.push({count:parseInt(m[1]),text:b})}}}" +
            "return JSON.stringify({cards:cards,groups:groups});" +
            "}catch(e){return JSON.stringify({cards:[],groups:[]})}})()";
}
