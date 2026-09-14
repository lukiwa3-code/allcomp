package pl.allcomp.allegro;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.TextView;

/**
 * Allegro Radar v9
 *
 * Allegro serves more than one mobile/web variant of the offer page.
 * Some variants show "PORÓWNAJ X OFERTY TEGO PRODUKTU", while WebView can
 * show only "Zobacz porównanie". V8 required the former text, so it missed
 * valid comparison buttons.
 *
 * V9 keeps the exact V8 flow but installs small off-screen proxy controls in
 * the light DOM for controls that may live under a different label, shadow
 * root or same-origin iframe. V8 then clicks those proxies; the proxy forwards
 * the click to Allegro's real control. The real product offer count is still
 * read from the comparison screen, e.g. "(2 oferty)". The temporary proxy
 * count is never intended to be the final source of truth.
 */
public class RadarMainActivityV9 extends RadarMainActivityV8 {
    private final Handler bridgeHandler = new Handler(Looper.getMainLooper());
    private WebView bridgeWeb;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        bridgeWeb = findWebView(findViewById(android.R.id.content));
        updateVersion(findViewById(android.R.id.content));
        bridgeHandler.post(bridgeTick);
    }

    @Override
    protected void onDestroy() {
        bridgeHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private final Runnable bridgeTick = new Runnable() {
        @Override public void run() {
            try {
                if (bridgeWeb != null) bridgeWeb.evaluateJavascript(BRIDGE_JS, null);
            } catch (Exception ignored) {}
            bridgeHandler.postDelayed(this, 180);
        }
    };

    /*
     * Important design point: V8 searches document.querySelectorAll(...), which
     * does not enter shadow roots. We therefore recursively find controls in
     * document/shadow roots/iframes and expose an off-screen BUTTON in the main
     * document. Clicking that button forwards the click to the real Allegro
     * control. This does not alter prices or offers; it only navigates/filters.
     */
    private static final String BRIDGE_JS =
        "(function(){try{" +
        "function norm(s){return (s||'').replace(/\\s+/g,' ').trim().toLowerCase()}" +
        "function roots(){const out=[document],seen=new Set();for(let i=0;i<out.length;i++){const r=out[i];if(!r||seen.has(r))continue;seen.add(r);let es=[];try{es=[...r.querySelectorAll('*')]}catch(e){}for(const e of es){try{if(e.shadowRoot&&!seen.has(e.shadowRoot))out.push(e.shadowRoot)}catch(x){}try{if(e.tagName==='IFRAME'&&e.contentDocument&&!seen.has(e.contentDocument))out.push(e.contentDocument)}catch(x){}}}return [...seen]}" +
        "function deepFind(test){let best=null,bestLen=1e9;for(const r of roots()){let es=[];try{es=[...r.querySelectorAll('button,a,[role=button],[role=option],[role=checkbox],label,div,span')]}catch(e){}for(const e of es){let t='';try{t=(e.innerText||'').trim()}catch(x){}if(!t||t.length>260)continue;if(test(t,e)&&t.length<bestLen){best=e;bestLen=t.length}}}return best}" +
        "function realClick(el){if(!el)return false;let x=el;try{x=el.closest('button,a,[role=button],[role=option],[role=checkbox],label')||el}catch(e){}try{x.scrollIntoView({block:'center'});}catch(e){}try{x.click();return true}catch(e){}try{x.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,view:window}));return true}catch(e){}return false}" +
        "function proxy(id,text,target){if(document.getElementById(id)||!target)return;const b=document.createElement('button');b.id=id;b.type='button';b.textContent=text;b.style.cssText='position:fixed!important;left:-10000px!important;top:1px!important;width:2px!important;height:2px!important;overflow:hidden!important;opacity:.01!important;z-index:2147483647!important';b.addEventListener('click',function(ev){ev.preventDefault();ev.stopPropagation();realClick(target)});(document.body||document.documentElement).appendChild(b)}" +
        "const body=((document.body&&document.body.innerText)||'');" +
        // Offer-page compare. If the numbered version already exists, V8 handles it itself.
        "if(!/POR[ÓO]WNAJ\\s+[\\d\\s\\u00a0\\u202f]+\\s+OFERT(?:A|Y)?\\s+TEGO\\s+PRODUKTU/i.test(body)){const c=deepFind((t,e)=>{const n=norm(t);return n==='zobacz porównanie'||n==='zobacz porownanie'||(n.includes('zobacz')&&n.includes('porównanie'))||(n.includes('zobacz')&&n.includes('porownanie'))});if(c)proxy('radar-v9-compare-proxy','PORÓWNAJ 1 OFERTY TEGO PRODUKTU',c)}" +
        // Comparison screen: expose Najtaniej when hidden under shadow DOM.
        "const cheap=deepFind((t,e)=>norm(t)==='najtaniej');if(cheap)proxy('radar-v9-cheap-proxy','Najtaniej',cheap);" +
        // State selector/value proxies. V8 needs literal text in light DOM.
        "const stan=deepFind((t,e)=>norm(t)==='stan');if(stan)proxy('radar-v9-stan-proxy','Stan',stan);" +
        "const nowe=deepFind((t,e)=>norm(t)==='nowe');if(nowe)proxy('radar-v9-nowe-proxy','Nowe',nowe);" +
        // If comparison header count is only in shadow DOM, mirror the literal text.
        "const cnt=deepFind((t,e)=>/^\\(\\s*\\d+\\s+ofert(?:a|y)?\\s*\\)$/i.test(t.trim()));if(cnt&&!/\\(\\s*\\d+\\s+ofert(?:a|y)?\\s*\\)/i.test(body)){let old=document.getElementById('radar-v9-count-proxy');if(!old){old=document.createElement('div');old.id='radar-v9-count-proxy';old.style.cssText='position:fixed;left:-10000px;top:1px;width:2px;height:2px;overflow:hidden;opacity:.01';(document.body||document.documentElement).appendChild(old)}old.textContent=(cnt.innerText||'').trim()}" +
        "return true}catch(e){return false}})()";

    private WebView findWebView(View v) {
        if (v instanceof WebView) return (WebView)v;
        if (v instanceof ViewGroup) {
            ViewGroup g=(ViewGroup)v;
            for(int i=0;i<g.getChildCount();i++) {
                WebView w=findWebView(g.getChildAt(i));
                if(w!=null)return w;
            }
        }
        return null;
    }

    private void updateVersion(View v) {
        if(v instanceof TextView && !(v instanceof Button)) {
            TextView t=(TextView)v;
            String s=String.valueOf(t.getText());
            if(s.contains("Allegro Radar")||s.contains("Radar v")) {
                t.setText(s.replace("v2","v9").replace("v3","v9").replace("v4","v9").replace("v5","v9").replace("v6","v9").replace("v7","v9").replace("v8","v9"));
            }
        }
        if(v instanceof ViewGroup) {
            ViewGroup g=(ViewGroup)v;
            for(int i=0;i<g.getChildCount();i++)updateVersion(g.getChildAt(i));
        }
    }
}
