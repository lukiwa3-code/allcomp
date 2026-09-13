package pl.allcomp.allegro;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
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

public class MainActivity extends Activity {
    private static final String SALES_URL = "https://salescenter.allegro.com/my-assortment?limit=120&publication.status=ACTIVE&sellingMode.format=BUY_NOW&context.marketplace=allegro-pl";
    private static final int CREATE_CSV = 901;

    private WebView webView;
    private TextView status;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LinkedHashMap<String, Offer> offers = new LinkedHashMap<>();
    private final List<ResultRow> results = new ArrayList<>();
    private final LinkedHashMap<String, MarketCard> marketCards = new LinkedHashMap<>();
    private final List<GroupCandidate> marketGroups = new ArrayList<>();

    private boolean cancelled, scanningSales, scanningMarket, waitingMarketPage;
    private int requestedLimit = 5, salesIterations, salesStagnant, salesPreviousCount, marketIndex, marketScrollIteration;
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
        row.setPadding(dp(5), dp(5), dp(5), dp(3));

        Button sales = button("Sales Center"), scan5 = button("Skanuj 5"), scanAll = button("Skanuj wszystkie"), report = button("Raport"), csv = button("CSV"), stop = button("STOP");
        row.addView(sales); row.addView(scan5); row.addView(scanAll); row.addView(report); row.addView(csv); row.addView(stop);
        hs.addView(row);

        status = new TextView(this);
        status.setText("Zaloguj się do Allegro. Potem wybierz Skanuj 5.");
        status.setTextSize(14); status.setTextColor(Color.rgb(35,35,35));
        status.setPadding(dp(10),dp(6),dp(10),dp(6));
        status.setBackgroundColor(Color.rgb(244,246,248));

        webView = new WebView(this);
        webView.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,0,1));
        root.addView(hs); root.addView(status); root.addView(webView);
        setContentView(root);

        sales.setOnClickListener(v -> { stopScan(false); webView.loadUrl(SALES_URL); setStatus("Sales Center. Zaloguj się i poczekaj na listę ofert."); });
        scan5.setOnClickListener(v -> startSalesScan(5));
        scanAll.setOnClickListener(v -> startSalesScan(0));
        report.setOnClickListener(v -> showReport());
        csv.setOnClickListener(v -> exportCsv());
        stop.setOnClickListener(v -> stopScan(true));
    }

    private Button button(String text) {
        Button b = new Button(this); b.setText(text); b.setTextSize(12); b.setAllCaps(false);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44));
        p.setMargins(dp(2),0,dp(2),0); b.setLayoutParams(p); return b;
    }

    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setDatabaseEnabled(true); s.setLoadsImagesAutomatically(true);
        s.setBuiltInZoomControls(true); s.setDisplayZoomControls(false); s.setJavaScriptCanOpenWindowsAutomatically(true); s.setSupportMultipleWindows(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager cm = CookieManager.getInstance(); cm.setAcceptCookie(true); cm.setAcceptThirdPartyCookies(webView,true);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                if (scanningMarket && waitingMarketPage && !cancelled) {
                    waitingMarketPage=false;
                    handler.postDelayed(() -> { acceptCookies(); handler.postDelayed(MainActivity.this::marketCollectStep,900); },1100);
                }
            }
        });
    }

    private void startSalesScan(int limit) {
        if (scanningSales || scanningMarket) { Toast.makeText(this,"Skanowanie już trwa. Użyj STOP.",Toast.LENGTH_SHORT).show(); return; }
        String u=webView.getUrl();
        if (u==null || !u.contains("salescenter.allegro.com")) { webView.loadUrl(SALES_URL); Toast.makeText(this,"Otwieram Sales Center. Zaloguj się i kliknij Skanuj ponownie.",Toast.LENGTH_LONG).show(); return; }
        cancelled=false; scanningSales=true; requestedLimit=limit; offers.clear(); results.clear(); salesIterations=0; salesStagnant=0; salesPreviousCount=0;
        setStatus("Czytam oferty z Sales Center…"); salesScanStep();
    }

    private void salesScanStep() {
        if (!scanningSales || cancelled) return;
        salesIterations++;
        webView.evaluateJavascript(JS_SCAN_SALES, value -> {
            if (!scanningSales || cancelled) return;
            try {
                String d=decode(value); if(d!=null){ JSONArray a=new JSONArray(d); for(int i=0;i<a.length();i++){ JSONObject j=a.getJSONObject(i); Offer o=new Offer();
                    o.id=j.optString("id",""); o.title=j.optString("title",""); o.ean=j.optString("ean",""); o.url=j.optString("url",""); if(!j.isNull("price")) o.price=j.optDouble("price",Double.NaN); if(!o.id.isEmpty()) offers.put(o.id,o); }}
            } catch(Exception ignored){}
            int c=offers.size(); setStatus("Sales Center: znaleziono "+c+" ofert"+(requestedLimit>0?" / "+requestedLimit:""));
            if(requestedLimit>0 && c>=requestedLimit){ finishSalesScan(); return; }
            if(c==salesPreviousCount) salesStagnant++; else salesStagnant=0; salesPreviousCount=c;
            if((salesStagnant>=8 && c>0)||salesIterations>=120){ finishSalesScan(); return; }
            webView.evaluateJavascript(JS_SCROLL_SALES, x -> handler.postDelayed(this::salesScanStep,450));
        });
    }

    private void finishSalesScan() {
        scanningSales=false; if(cancelled)return;
        if(offers.isEmpty()){ setStatus("Nie znalazłem ofert. Zaloguj się, poczekaj na listę i kliknij Skanuj 5."); Toast.makeText(this,"0 ofert. Najpierw pokaż listę Sales Center.",Toast.LENGTH_LONG).show(); return; }
        if(requestedLimit>0 && offers.size()>requestedLimit){ List<Offer> l=new ArrayList<>(offers.values()).subList(0,requestedLimit); offers.clear(); for(Offer o:l) offers.put(o.id,o); }
        marketIndex=0; results.clear(); scanningMarket=true; setStatus("Odczytano "+offers.size()+" ofert. Sprawdzam konkurencję…"); scanNextMarketOffer();
    }

    private void scanNextMarketOffer() {
        if(!scanningMarket||cancelled)return;
        List<Offer> list=new ArrayList<>(offers.values());
        if(marketIndex>=list.size()){ scanningMarket=false; setStatus("Gotowe. Sprawdzono "+results.size()+" ofert."); showReport(); return; }
        Offer item=list.get(marketIndex); marketCards.clear(); marketGroups.clear(); marketScrollIteration=0;
        String q=!item.ean.isEmpty()?item.ean:item.title;
        if(q==null||q.trim().isEmpty()){ ResultRow r=ResultRow.from(item); r.status="BRAK DANYCH"; r.note="Brak EAN i tytułu."; results.add(r); marketIndex++; scanNextMarketOffer(); return; }
        setStatus("Sprawdzam "+(marketIndex+1)+"/"+list.size()+": "+shortText(item.title,55));
        try{ waitingMarketPage=true; webView.loadUrl("https://allegro.pl/listing?string="+URLEncoder.encode(q,StandardCharsets.UTF_8.name())+"&stan=nowe&order=p"); }
        catch(Exception e){ ResultRow r=ResultRow.from(item); r.status="BŁĄD"; r.note=e.getMessage(); results.add(r); marketIndex++; handler.postDelayed(this::scanNextMarketOffer,400); }
    }

    private void marketCollectStep() {
        if(!scanningMarket||cancelled)return;
        webView.evaluateJavascript(JS_SCAN_MARKET, value -> {
            if(!scanningMarket||cancelled)return;
            try{ String d=decode(value); if(d!=null){ JSONObject root=new JSONObject(d); JSONArray c=root.optJSONArray("cards"); if(c!=null)for(int i=0;i<c.length();i++){JSONObject j=c.getJSONObject(i);MarketCard m=new MarketCard();m.offerId=j.optString("offerId","");m.text=j.optString("text","");m.seller=j.optString("seller","");if(!m.offerId.isEmpty())marketCards.put(m.offerId,m);} JSONArray g=root.optJSONArray("groups");if(g!=null)for(int i=0;i<g.length();i++){JSONObject j=g.getJSONObject(i);GroupCandidate x=new GroupCandidate();x.count=j.optInt("count",0);x.text=j.optString("text","");if(x.count>0&&!x.text.isEmpty())marketGroups.add(x);}} }catch(Exception ignored){}
            marketScrollIteration++;
            if(marketScrollIteration>=9||marketCards.size()>=140){ finalizeMarketOffer(); return; }
            webView.evaluateJavascript("(function(){window.scrollBy(0,Math.max(1400,window.innerHeight*1.6));return window.scrollY;})()",x->handler.postDelayed(this::marketCollectStep,420));
        });
    }

    private void finalizeMarketOffer() {
        Offer item=new ArrayList<>(offers.values()).get(marketIndex); ResultRow r=ResultRow.from(item); List<MarketCard> relevant=new ArrayList<>(); Set<String>sellers=new LinkedHashSet<>(); double lowest=Double.NaN;
        for(MarketCard c:marketCards.values()){ if(item.id.equals(c.offerId)||!relevant(c.text,item))continue; double p=currentPrice(c.text); if(!Double.isNaN(p)){relevant.add(c);if(Double.isNaN(lowest)||p<lowest)lowest=p;if(c.seller!=null&&!c.seller.trim().isEmpty())sellers.add(c.seller.trim().toLowerCase(Locale.ROOT));}}
        GroupCandidate best=bestGroup(item); int groupCount=best==null?0:best.count; double gp=best==null?Double.NaN:currentPrice(best.text); if(Double.isNaN(lowest)&&!Double.isNaN(gp))lowest=gp;
        if(!sellers.isEmpty()){r.otherSellers=sellers.size();r.method="unikalni sprzedawcy";} else if(!relevant.isEmpty()){r.otherSellers=relevant.size();r.method="inne oferty";r.note="Brak nazw sprzedawców w kartach, więc liczba oznacza unikalne inne oferty.";} else if(groupCount>0){r.otherSellers=Math.max(groupCount-1,0);r.method="liczba ofert produktu - 1";r.note="Fallback: Allegro podało liczbę ofert produktu.";} else {r.otherSellers=0;r.method="brak grupy";r.note="Nie znalazłem grupy innych ofert.";}
        r.totalOffers=groupCount>0?groupCount:r.otherSellers+1; r.lowestNew=lowest;
        if(!Double.isNaN(r.ownPrice)&&!Double.isNaN(lowest)){r.diffPln=round2(r.ownPrice-lowest);if(lowest>0)r.diffPct=round2(r.diffPln/lowest*100);}
        r.status=(!Double.isNaN(r.ownPrice)&&!Double.isNaN(lowest))?"OK":"CZĘŚCIOWO"; results.add(r); marketIndex++; handler.postDelayed(this::scanNextMarketOffer,700);
    }

    private GroupCandidate bestGroup(Offer item){GroupCandidate b=null;int score=Integer.MIN_VALUE;for(GroupCandidate g:marketGroups){int s=similarity(g.text,item.title);if(b==null||s>score){b=g;score=s;}}return b;}
    private boolean relevant(String text,Offer item){List<String>t=tokens(item.title);if(t.isEmpty())return true;String l=(text==null?"":text).toLowerCase(Locale.ROOT);for(String x:t)if(l.contains(x.toLowerCase(Locale.ROOT)))return true;return false;}
    private List<String> tokens(String title){List<String>o=new ArrayList<>();if(title==null)return o;Matcher m=MODEL_RE.matcher(title);while(m.find()){String t=m.group();if(t.length()>=4&&!t.matches("\\d{8,14}"))o.add(t);if(o.size()>=5)break;}return o;}
    private int similarity(String text,String title){String l=(text==null?"":text).toLowerCase(Locale.ROOT);int s=0;for(String t:tokens(title))if(l.contains(t.toLowerCase(Locale.ROOT)))s+=5;return s;}

    private double currentPrice(String text){if(text==null)return Double.NaN;for(String line:text.split("\\n")){String l=line.toLowerCase(Locale.ROOT);if(l.contains("cena z 30 dni")||l.contains("dostaw")||l.contains("rata")||l.contains("mies")||l.contains("/szt")||l.contains("/kg")||l.contains("/100"))continue;Matcher m=PRICE_RE.matcher(line);double best=Double.NaN;while(m.find()){double p=parsePrice(m.group(1));if(!Double.isNaN(p)&&(Double.isNaN(best)||p<best))best=p;}if(!Double.isNaN(best))return best;}Matcher m=PRICE_RE.matcher(text);return m.find()?parsePrice(m.group(1)):Double.NaN;}
    private double parsePrice(String s){try{return Double.parseDouble(s.replace("\u00a0","").replace("\u202f","").replace(" ","").replace(',','.'));}catch(Exception e){return Double.NaN;}}

    private void showReport(){if(results.isEmpty()){Toast.makeText(this,"Nie ma jeszcze wyników.",Toast.LENGTH_SHORT).show();return;}StringBuilder h=new StringBuilder("<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'><style>body{font-family:Arial;background:#f5f6f8;margin:0;padding:12px;color:#222}.c{background:white;border-radius:12px;padding:12px;margin-bottom:10px;box-shadow:0 1px 4px #0002}.t{font-weight:700}.m{color:#666;font-size:12px}.g{display:grid;grid-template-columns:1fr 1fr;gap:7px;margin-top:8px}.v{font-weight:700}.r{color:#b3261e}.ok{color:#137333}.p{display:inline-block;background:#eef2f6;border-radius:10px;padding:2px 7px;font-size:11px;margin-top:6px}</style></head><body><h2>Allegro Radar</h2>");for(ResultRow r:results){String cl=!Double.isNaN(r.diffPln)&&r.diffPln>0?"r":"ok";h.append("<div class='c'><div class='t'>").append(esc(r.title)).append("</div><div class='m'>ID ").append(esc(r.offerId)).append(" · EAN ").append(esc(r.ean)).append("</div><div class='g'><div>Moja cena<br><span class='v'>").append(money(r.ownPrice)).append("</span></div><div>Najniższa NOWY<br><span class='v'>").append(money(r.lowestNew)).append("</span></div><div>Inni sprzedawcy/oferty<br><span class='v'>").append(r.otherSellers).append("</span></div><div>Różnica<br><span class='v ").append(cl).append("'>").append(moneySigned(r.diffPln)).append("</span></div></div><div class='p'>").append(esc(r.status)).append(" · ").append(esc(r.method)).append("</div>");if(r.note!=null&&!r.note.isEmpty())h.append("<div class='m' style='margin-top:6px'>").append(esc(r.note)).append("</div>");h.append("</div>");}h.append("</body></html>");webView.loadDataWithBaseURL("https://local.allcomp/",h.toString(),"text/html","UTF-8",null);}

    private void exportCsv(){if(results.isEmpty()){Toast.makeText(this,"Najpierw wykonaj skanowanie.",Toast.LENGTH_SHORT).show();return;}pendingCsv=csvText();Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("text/csv");i.putExtra(Intent.EXTRA_TITLE,"allegro_radar.csv");startActivityForResult(i,CREATE_CSV);}
    @Override protected void onActivityResult(int req,int res,Intent data){super.onActivityResult(req,res,data);if(req==CREATE_CSV&&res==RESULT_OK&&data!=null&&data.getData()!=null){try(OutputStream os=getContentResolver().openOutputStream(data.getData())){if(os!=null){os.write(new byte[]{(byte)0xEF,(byte)0xBB,(byte)0xBF});os.write(pendingCsv.getBytes(StandardCharsets.UTF_8));Toast.makeText(this,"CSV zapisany.",Toast.LENGTH_SHORT).show();}}catch(Exception e){Toast.makeText(this,"Błąd zapisu: "+e.getMessage(),Toast.LENGTH_LONG).show();}}}
    private String csvText(){StringBuilder s=new StringBuilder("ID oferty;Tytuł;EAN;Moja cena;Oferty produktu;Inni sprzedawcy/oferty;Najniższa cena NOWY;Różnica PLN;Różnica %;Status;Metoda;Uwagi\r\n");for(ResultRow r:results)s.append(q(r.offerId)).append(';').append(q(r.title)).append(';').append(q(r.ean)).append(';').append(num(r.ownPrice)).append(';').append(r.totalOffers).append(';').append(r.otherSellers).append(';').append(num(r.lowestNew)).append(';').append(num(r.diffPln)).append(';').append(num(r.diffPct)).append(';').append(q(r.status)).append(';').append(q(r.method)).append(';').append(q(r.note)).append("\r\n");return s.toString();}
    private String q(String s){return "\""+(s==null?"":s.replace("\"","\"\""))+"\"";} private String num(double d){return Double.isNaN(d)?"":String.format(Locale.US,"%.2f",d).replace('.',',');}

    private void acceptCookies(){webView.evaluateJavascript("(function(){for(const e of document.querySelectorAll('button,a')){const t=(e.innerText||'').toLowerCase();if(t.includes('zgadzam się')||t.includes('akceptuj')||t.includes('przejdź do serwisu')){try{e.click();return true}catch(x){}}}return false;})()",null);}
    private void stopScan(boolean say){cancelled=true;scanningSales=false;scanningMarket=false;waitingMarketPage=false;handler.removeCallbacksAndMessages(null);if(say)setStatus("Skanowanie zatrzymane.");}
    @Override public void onBackPressed(){if(scanningSales||scanningMarket){new AlertDialog.Builder(this).setTitle("Skanowanie trwa").setMessage("Zatrzymać skanowanie?").setPositiveButton("Tak",(d,w)->stopScan(true)).setNegativeButton("Nie",null).show();return;}if(webView.canGoBack())webView.goBack();else super.onBackPressed();}
    private String decode(String v){if(v==null||"null".equals(v))return null;try{return new JSONArray("["+v+"]").getString(0);}catch(Exception e){return null;}}
    private void setStatus(String s){status.setText(s);} private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);} private static double round2(double v){return Math.round(v*100.0)/100.0;}
    private static String shortText(String s,int n){if(s==null)return "";return s.length()<=n?s:s.substring(0,n)+"…";} private static String esc(String s){if(s==null)return "";return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
    private static String money(double d){return Double.isNaN(d)?"—":String.format(Locale.forLanguageTag("pl-PL"),"%.2f zł",d);} private static String moneySigned(double d){return Double.isNaN(d)?"—":(d>0?"+":"")+String.format(Locale.forLanguageTag("pl-PL"),"%.2f zł",d);}

    private static class Offer{String id="",title="",ean="",url="";double price=Double.NaN;}
    private static class MarketCard{String offerId="",text="",seller="";}
    private static class GroupCandidate{int count;String text="";}
    private static class ResultRow{String offerId="",title="",ean="",status="",method="",note="";double ownPrice=Double.NaN,lowestNew=Double.NaN,diffPln=Double.NaN,diffPct=Double.NaN;int totalOffers,otherSellers;static ResultRow from(Offer o){ResultRow r=new ResultRow();r.offerId=o.id;r.title=o.title;r.ean=o.ean;r.ownPrice=o.price;return r;}}

    private static final String JS_SCROLL_SALES="(function(){try{const a=[document.scrollingElement,...document.querySelectorAll('*')].filter(Boolean).filter(e=>{try{const s=getComputedStyle(e);return /(auto|scroll)/.test(s.overflowY)&&e.scrollHeight>e.clientHeight+120}catch(x){return false}}).sort((a,b)=>(b.scrollHeight-b.clientHeight)-(a.scrollHeight-a.clientHeight));let m=false;for(const e of a.slice(0,6)){const o=e.scrollTop;e.scrollTop=Math.min(e.scrollTop+Math.max(520,e.clientHeight*.82),e.scrollHeight);if(e.scrollTop>o+2)m=true}return m}catch(e){return false}})()";

    private static final String JS_SCAN_SALES="(function(){try{const roots=[document],rr=new Set(),hits=[],seen=new Set();for(let i=0;i<roots.length;i++){const r=roots[i];if(!r||rr.has(r))continue;rr.add(r);try{for(const e of r.querySelectorAll('*'))if(e.shadowRoot)roots.push(e.shadowRoot)}catch(x){}}function price(t){const m=t.match(/(?<!\\d)(\\d{1,3}(?:[ \\u00a0\\u202f]\\d{3})*(?:[,.]\\d{2}))\\s*zł/i);if(!m)return null;const n=parseFloat(m[1].replace(/[ \\u00a0\\u202f]/g,'').replace(',','.'));return isNaN(n)?null:n}function title(el,t,id){let b='';try{for(const a of el.querySelectorAll('a[href],h1,h2,h3,strong')){const x=(a.innerText||'').replace(/\\s+/g,' ').trim(),l=x.toLowerCase();if(x.length<5||x.length>260)continue;if(/\\d+[,.]\\d{2}\\s*zł/i.test(x)||l.includes('szczegóły')||l.includes('obserwujący')||l.includes('sprzedano')||l.includes('status oferty'))continue;if(new RegExp('\\bnr\\s*[:：]?\\s*'+id+'\\b','i').test(x))continue;if(x.length>b.length)b=x}}catch(e){}if(b)return b;const lines=t.split(/\\n+/).map(x=>x.replace(/\\s+/g,' ').trim()).filter(Boolean),idx=lines.findIndex(x=>new RegExp('\\bnr\\s*[:：]?\\s*'+id+'\\b','i').test(x)),noise=['liczba sztuk','status','katalog allegro','obserwujący','szczegóły','aktywna','połączono','więcej','wizyty','sprzedano','rynki','smart','business','cechy','statystyki','oferta','cena'];if(idx<0)return '';const p=[];for(let i=idx-1;i>=0&&i>=idx-7;i--){const x=lines[i],l=x.toLowerCase();if(/\\d+[,.]\\d{2}\\s*zł/i.test(x)||noise.some(n=>l.includes(n))||/^\\d+$/.test(x)){if(p.length)break;continue}p.unshift(x);if(p.join(' ').length>180||p.length>=3)break}return p.join(' ').trim()}for(const root of roots){let w;try{w=document.createTreeWalker(root,NodeFilter.SHOW_TEXT)}catch(e){continue}let n;while((n=w.nextNode())){const s=(n.nodeValue||'').trim(),m=s.match(/\\bnr\\s*[:：]?\\s*(\\d{8,16})\\b/i);if(!m)continue;const id=m[1];if(seen.has(id))continue;let el=n.parentElement,b=null,bt='';for(let i=0;i<13&&el;i++,el=el.parentElement){const t=(el.innerText||'').trim();if(!t||t.length>5500)continue;const hi=new RegExp('\\bnr\\s*[:：]?\\s*'+id+'\\b','i').test(t),hp=/\\d[\\d \\u00a0\\u202f]*[,.]\\d{2}\\s*zł/i.test(t),he=/EAN\\s*\\(?(?:GTIN)?\\)?\\s*[:：]?\\s*\\d{8,14}/i.test(t);if(hi&&hp&&(he||t.length>50)){if(!bt||t.length<bt.length){bt=t;b=el}}}if(!b)continue;const em=bt.match(/EAN\\s*\\(?(?:GTIN)?\\)?\\s*[:：]?\\s*(\\d{8,14})/i);let u='';try{for(const a of b.querySelectorAll('a[href]')){const h=a.href||'';if(h.includes('allegro.pl/oferta/')&&h.includes(id)){u=h;break}}}catch(e){}hits.push({id:id,title:title(b,bt,id),ean:em?em[1]:'',price:price(bt),url:u});seen.add(id)}}return JSON.stringify(hits)}catch(e){return JSON.stringify([])}})()";

    private static final String JS_SCAN_MARKET="(function(){try{const cards=[],groups=[],seen=new Set();for(const a of document.querySelectorAll('a[href*=\\\"/oferta/\\\"]')){const href=a.href||'',m=href.match(/(\\d{8,16})(?:[?#]|$)/),id=m?m[1]:'';if(!id||seen.has(id))continue;let e=a,b='',be=null;for(let i=0;i<9&&e;i++,e=e.parentElement){const t=(e.innerText||'').trim();if(t.length>=15&&t.length<=2800&&/\\d[\\d \\u00a0\\u202f]*[,.]\\d{2}\\s*zł/i.test(t)){if(!b||t.length<b.length){b=t;be=e}}}if(!b)continue;let s='';try{const x=be.querySelector('a[href*=\\\"/uzytkownik/\\\"]');if(x)s=(x.innerText||'').trim()||(x.getAttribute('href')||'').split('/uzytkownik/')[1].split(/[?#/]/)[0]}catch(z){}cards.push({offerId:id,text:b,seller:s});seen.add(id)}const gs=new Set();for(const e of document.querySelectorAll('body *')){const o=(e.innerText||'').trim(),m=o.match(/(?:zobacz\\s+)?(\\d+)\\s+ofert(?:a|y)?(?:\\s+tego\\s+produktu)?/i);if(!m)continue;let x=e,b='';for(let i=0;i<7&&x;i++,x=x.parentElement){const t=(x.innerText||'').trim();if(t.length>20&&t.length<2600&&/\\d[\\d \\u00a0\\u202f.,]*\\s*zł/i.test(t)){if(!b||t.length<b.length)b=t}}if(b){const k=m[1]+'|'+b.slice(0,400);if(!gs.has(k)){gs.add(k);groups.push({count:parseInt(m[1]),text:b})}}}return JSON.stringify({cards:cards,groups:groups})}catch(e){return JSON.stringify({cards:[],groups:[]})}})()";
}
