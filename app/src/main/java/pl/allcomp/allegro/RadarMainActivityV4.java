package pl.allcomp.allegro;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Allegro Radar v4
 *
 * Adds an exact product-offer count pass. For each Sales Center offer, before
 * the normal public listing scan starts, the app opens the offer page itself
 * and reads the value shown by Allegro in:
 *   "PORÓWNAJ X OFERT TEGO PRODUKTU"
 *
 * This gives a much more reliable product-offer count than counting rendered
 * cards from search results. The app then stores:
 *   totalOffers  = X
 *   otherSellers = X - 1  (used as "other offers" in v4 report)
 *
 * v3 behaviour is preserved, including KEEP_SCREEN_ON and forcing the Sales
 * Center list to the top before scanning.
 */
public class RadarMainActivityV4 extends RadarMainActivityV3 {
    private final Handler v4Handler = new Handler(Looper.getMainLooper());
    private final Map<String, Integer> exactProductOfferCounts = new LinkedHashMap<>();
    private final Set<String> lookupDone = new LinkedHashSet<>();

    private WebView radarWebView;
    private boolean intercepting = false;
    private String interceptedOfferId = "";
    private String savedListingUrl = "";
    private int extractTries = 0;
    private int previousResultCount = -1;
    private boolean finalV4ReportShown = false;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        radarWebView = findWebView(findViewById(android.R.id.content));
        replaceReportButton(findViewById(android.R.id.content));
        updateVersionText(findViewById(android.R.id.content));
        v4Handler.postDelayed(this::monitorScan, 300);
    }

    @Override
    protected void onDestroy() {
        v4Handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void monitorScan() {
        try {
            patchExistingResults();
            boolean scanningMarket = getPrivateBoolean("scanningMarket");

            if (scanningMarket && radarWebView != null) {
                finalV4ReportShown = false;
                String url = radarWebView.getUrl();

                if (!intercepting && url != null && url.contains("allegro.pl/listing")) {
                    String id = currentOfferId();
                    if (!id.isEmpty() && !lookupDone.contains(id)) {
                        beginExactCountLookup(id, url);
                    }
                } else if (intercepting && url != null && url.contains("allegro.pl/oferta/")) {
                    if (radarWebView.getProgress() >= 70) tryExtractExactCount();
                }
            } else if (!intercepting) {
                int resultCount = getResults().size();
                int offerCount = getOffers().size();
                if (resultCount > 0 && resultCount == offerCount && !finalV4ReportShown) {
                    patchExistingResults();
                    finalV4ReportShown = true;
                    v4Handler.postDelayed(this::showReportV4, 350);
                }
            }
        } catch (Exception ignored) {
        }
        v4Handler.postDelayed(this::monitorScan, 280);
    }

    private void beginExactCountLookup(String offerId, String listingUrl) {
        if (radarWebView == null) return;
        intercepting = true;
        interceptedOfferId = offerId;
        savedListingUrl = listingUrl;
        extractTries = 0;

        // Prevent the superclass from treating the temporary offer page as a
        // finished competitor-listing page.
        setPrivateBoolean("waitingMarketPage", false);
        radarWebView.stopLoading();
        radarWebView.loadUrl("https://allegro.pl/oferta/" + offerId);
    }

    private void tryExtractExactCount() {
        if (!intercepting || radarWebView == null) return;
        extractTries++;

        String js = "(function(){try{" +
                "const t=(document.body&&document.body.innerText)||'';" +
                "let m=t.match(/POR[ÓO]WNAJ\\s+([\\d\\s\\u00a0\\u202f]+)\\s+OFERT(?:A|Y)?\\s+TEGO\\s+PRODUKTU/i);" +
                "if(!m)m=t.match(/([\\d\\s\\u00a0\\u202f]+)\\s+OFERT(?:A|Y)?\\s+TEGO\\s+PRODUKTU/i);" +
                "if(!m)return 0;" +
                "const n=parseInt(m[1].replace(/\\D/g,''),10);" +
                "return isNaN(n)?0:n" +
                "}catch(e){return 0}})()";

        radarWebView.evaluateJavascript(js, value -> {
            int count = parseJsInt(value);
            if (count > 0) {
                exactProductOfferCounts.put(interceptedOfferId, count);
                lookupDone.add(interceptedOfferId);
                returnToListing();
                return;
            }

            if (extractTries >= 24) {
                // Do not guess. Keep the old fallback logic for this item.
                lookupDone.add(interceptedOfferId);
                returnToListing();
            } else {
                v4Handler.postDelayed(this::tryExtractExactCount, 220);
            }
        });
    }

    private void returnToListing() {
        final String url = savedListingUrl;
        intercepting = false;
        interceptedOfferId = "";
        savedListingUrl = "";
        extractTries = 0;

        if (radarWebView == null || url == null || url.isEmpty()) return;
        v4Handler.postDelayed(() -> {
            setPrivateBoolean("waitingMarketPage", true);
            radarWebView.loadUrl(url);
        }, 120);
    }

    private void patchExistingResults() {
        List<?> results = getResults();
        if (results.isEmpty()) return;

        for (Object row : results) {
            try {
                String offerId = String.valueOf(getObjectField(row, "offerId"));
                Integer total = exactProductOfferCounts.get(offerId);
                if (total == null || total <= 0) continue;

                setObjectField(row, "totalOffers", total);
                setObjectField(row, "otherSellers", Math.max(total - 1, 0));
                setObjectField(row, "method", "dokładnie z: PORÓWNAJ X OFERT TEGO PRODUKTU");
                setObjectField(row, "note", "Allegro pokazuje dokładnie " + total +
                        " ofert tego produktu. Innych ofert względem Twojej: " + Math.max(total - 1, 0) + ".");
            } catch (Exception ignored) {
            }
        }

        if (results.size() != previousResultCount) previousResultCount = results.size();
    }

    private void showReportV4() {
        patchExistingResults();
        List<?> rows = getResults();
        if (rows.isEmpty() || radarWebView == null) {
            Toast.makeText(this, "Nie ma jeszcze wyników.", Toast.LENGTH_SHORT).show();
            return;
        }

        StringBuilder h = new StringBuilder();
        h.append("<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>")
         .append("<style>body{font-family:Arial;background:#f4f5f7;margin:0;padding:12px;color:#202124}")
         .append(".c{background:#fff;border-radius:14px;padding:13px;margin:0 0 11px;box-shadow:0 1px 5px #0002}")
         .append(".t{font-weight:700;font-size:16px}.m{color:#666;font-size:12px;margin-top:3px}")
         .append(".g{display:grid;grid-template-columns:1fr 1fr;gap:9px;margin-top:11px}")
         .append(".k{font-size:12px;color:#666}.v{font-weight:700;font-size:16px;margin-top:2px}")
         .append(".good{color:#137333}.bad{color:#b3261e}.exact{color:#00695c;font-weight:700}")
         .append(".pill{display:inline-block;background:#eef3f2;border-radius:10px;padding:4px 8px;font-size:11px;margin-top:9px}")
         .append("</style></head><body><h2>Allegro Radar v4</h2>");

        for (Object row : rows) {
            try {
                String offerId = str(getObjectField(row, "offerId"));
                String title = str(getObjectField(row, "title"));
                String ean = str(getObjectField(row, "ean"));
                double own = dbl(getObjectField(row, "ownPrice"));
                double low = dbl(getObjectField(row, "lowestNew"));
                double diff = dbl(getObjectField(row, "diffPln"));
                int total = integer(getObjectField(row, "totalOffers"));
                int others = integer(getObjectField(row, "otherSellers"));
                String status = str(getObjectField(row, "status"));
                String method = str(getObjectField(row, "method"));
                boolean exact = exactProductOfferCounts.containsKey(offerId);

                h.append("<div class='c'><div class='t'>").append(esc(title)).append("</div>")
                 .append("<div class='m'>ID ").append(esc(offerId)).append(" · EAN ").append(esc(ean)).append("</div>")
                 .append("<div class='g'>")
                 .append(cell("Moja cena", money(own), ""))
                 .append(cell("Najniższa cena NOWY", money(low), ""))
                 .append(cell("Ofert produktu", total > 0 ? String.valueOf(total) : "—", exact ? "exact" : ""))
                 .append(cell("Inne oferty", total > 0 ? String.valueOf(others) : "—", exact ? "exact" : ""))
                 .append(cell("Różnica", moneySigned(diff), (!Double.isNaN(diff) && diff > 0) ? "bad" : "good"))
                 .append(cell("Status", esc(status), ""))
                 .append("</div>");

                if (exact) {
                    h.append("<div class='pill exact'>✓ liczba ofert odczytana bezpośrednio z „PORÓWNAJ X OFERT TEGO PRODUKTU”</div>");
                } else {
                    h.append("<div class='pill'>fallback: ").append(esc(method)).append("</div>");
                }
                h.append("</div>");
            } catch (Exception ignored) {
            }
        }

        h.append("</body></html>");
        radarWebView.loadDataWithBaseURL("https://local.allcomp/v4/", h.toString(), "text/html", "UTF-8", null);
    }

    private String cell(String key, String value, String css) {
        return "<div><div class='k'>" + key + "</div><div class='v " + css + "'>" + value + "</div></div>";
    }

    private void replaceReportButton(View view) {
        if (view instanceof Button) {
            Button b = (Button) view;
            if ("Raport".equalsIgnoreCase(String.valueOf(b.getText()).trim())) {
                b.setOnClickListener(v -> showReportV4());
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) replaceReportButton(g.getChildAt(i));
        }
    }

    private void updateVersionText(View view) {
        if (view instanceof TextView && !(view instanceof Button)) {
            TextView t = (TextView) view;
            String s = String.valueOf(t.getText());
            if (s.contains("Allegro Radar") || s.contains("Radar v2") || s.contains("Radar v3")) {
                t.setText(s.replace("v2", "v4").replace("v3", "v4"));
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) updateVersionText(g.getChildAt(i));
        }
    }

    private String currentOfferId() {
        try {
            Map<?,?> offers = getOffers();
            int index = getPrivateInt("marketIndex");
            if (index < 0 || index >= offers.size()) return "";
            Object offer = new ArrayList<>(offers.values()).get(index);
            return str(getObjectField(offer, "id"));
        } catch (Exception e) {
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<?,?> getOffers() {
        try {
            Field f = MobileMainActivity.class.getDeclaredField("offers");
            f.setAccessible(true);
            Object x = f.get(this);
            return x instanceof Map ? (Map<?,?>) x : new LinkedHashMap<>();
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    @SuppressWarnings("unchecked")
    private List<?> getResults() {
        try {
            Field f = MobileMainActivity.class.getDeclaredField("results");
            f.setAccessible(true);
            Object x = f.get(this);
            return x instanceof List ? (List<?>) x : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private boolean getPrivateBoolean(String name) {
        try {
            Field f = MobileMainActivity.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.getBoolean(this);
        } catch (Exception e) {
            return false;
        }
    }

    private int getPrivateInt(String name) {
        try {
            Field f = MobileMainActivity.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.getInt(this);
        } catch (Exception e) {
            return -1;
        }
    }

    private void setPrivateBoolean(String name, boolean value) {
        try {
            Field f = MobileMainActivity.class.getDeclaredField(name);
            f.setAccessible(true);
            f.setBoolean(this, value);
        } catch (Exception ignored) {
        }
    }

    private Object getObjectField(Object obj, String name) throws Exception {
        Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(obj);
    }

    private void setObjectField(Object obj, String name, Object value) throws Exception {
        Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        if (f.getType() == int.class && value instanceof Number) f.setInt(obj, ((Number) value).intValue());
        else f.set(obj, value);
    }

    private WebView findWebView(View view) {
        if (view instanceof WebView) return (WebView) view;
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                WebView found = findWebView(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private int parseJsInt(String value) {
        if (value == null) return 0;
        try {
            String d = value.replaceAll("[^0-9]", "");
            return d.isEmpty() ? 0 : Integer.parseInt(d);
        } catch (Exception e) {
            return 0;
        }
    }

    private static int integer(Object o) {
        return o instanceof Number ? ((Number)o).intValue() : 0;
    }

    private static double dbl(Object o) {
        return o instanceof Number ? ((Number)o).doubleValue() : Double.NaN;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String money(double d) {
        return Double.isNaN(d) ? "—" : String.format(Locale.forLanguageTag("pl-PL"), "%.2f zł", d);
    }

    private static String moneySigned(double d) {
        return Double.isNaN(d) ? "—" : (d > 0 ? "+" : "") + String.format(Locale.forLanguageTag("pl-PL"), "%.2f zł", d);
    }
}
