package pl.allcomp.allegro;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Allegro Radar v6
 *
 * Fixes false minimum prices for offers without EAN/GTIN.
 * Older logic could accept a completely different product when only one weak
 * title token matched (for example just "80cm"). v6 re-validates every no-EAN
 * result against the market cards that were actually collected for that scan.
 *
 * Rules:
 * - EAN offers keep the existing EAN-based path.
 * - No-EAN offers require several meaningful title tokens to match.
 * - If a numeric/model token is present (e.g. LEGO 31171), it must match.
 * - If no sufficiently strong candidate exists, lowest price is cleared rather
 *   than showing a made-up value.
 *
 * v5 range selection, v4 exact product-offer count and v3 KEEP_SCREEN_ON stay.
 */
public class RadarMainActivityV6 extends RadarMainActivityV5 {
    private final Handler v6Handler = new Handler(Looper.getMainLooper());
    private int patchedRows = 0;

    private static final Pattern PRICE_RE = Pattern.compile(
            "(?<!\\d)(\\d{1,3}(?:[ \\u00a0\\u202f]\\d{3})*(?:[,.]\\d{2}))\\s*zł",
            Pattern.CASE_INSENSITIVE);

    private static final Set<String> STOP = new HashSet<>(Arrays.asList(
            "allegro","oferta","oferty","produkt","produktu","nowy","nowa","nowe",
            "cena","kup","zaplac","koszyka","dostawa","smart","stan","sprzedawca",
            "sztuka","sztuki","sztuk","prezent","zabawka","oryginalny","oryginalna",
            "czarny","czarna","black","bialy","biala","white","kolor","marka",
            "pluszak","maskotka","zestaw","dla","oraz","jest","tego","ten","ta"
    ));

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        updateVersion(findViewById(android.R.id.content));
        v6Handler.postDelayed(this::watchRows, 80);
    }

    @Override
    protected void onDestroy() {
        v6Handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void watchRows() {
        try {
            List<?> rows = getListField("results");
            while (patchedRows < rows.size()) {
                Object row = rows.get(patchedRows);
                String ean = str(field(row,"ean"));
                if (ean == null || ean.trim().isEmpty()) {
                    revalidateNoEanRow(row);
                }
                patchedRows++;
            }

            // A new range scan clears results. Reset our row pointer as well.
            if (rows.size() < patchedRows) patchedRows = rows.size();
        } catch (Exception ignored) {
        }
        v6Handler.postDelayed(this::watchRows, 80);
    }

    private void revalidateNoEanRow(Object row) {
        try {
            String title = str(field(row,"title"));
            String ownId = str(field(row,"offerId"));
            double ownPrice = dbl(field(row,"ownPrice"));
            if (title == null || title.trim().isEmpty()) {
                clearUncertain(row,"Brak EAN i brak tytułu pozwalającego pewnie dopasować produkt.");
                return;
            }

            List<String> titleTokens = significantTokens(title);
            List<String> modelTokens = modelTokens(titleTokens);
            if (titleTokens.size() < 2) {
                clearUncertain(row,"Brak EAN. Tytuł ma zbyt mało cech do pewnego dopasowania ceny.");
                return;
            }

            Object cardsObj = getFieldFromMobile("marketCards");
            if (!(cardsObj instanceof java.util.Map)) {
                clearUncertain(row,"Nie udało się potwierdzić ceny na kartach tego produktu.");
                return;
            }

            java.util.Map<?,?> cards = (java.util.Map<?,?>) cardsObj;
            double lowest = Double.NaN;
            int matched = 0;

            for (Object card : cards.values()) {
                String offerId = str(field(card,"offerId"));
                if (ownId != null && ownId.equals(offerId)) continue;
                String text = str(field(card,"text"));
                if (!strictSameProduct(titleTokens, modelTokens, text)) continue;
                double price = currentPrice(text);
                if (Double.isNaN(price)) continue;
                matched++;
                if (Double.isNaN(lowest) || price < lowest) lowest = price;
            }

            if (matched == 0 || Double.isNaN(lowest)) {
                clearUncertain(row,
                        "Brak EAN. Nie znalazłem wystarczająco pewnego dopasowania tego samego produktu, więc nie podaję ceny zamiast zgadywać.");
                return;
            }

            setField(row,"lowestNew",lowest);
            if (!Double.isNaN(ownPrice)) {
                double diff = round2(ownPrice - lowest);
                setField(row,"diffPln",diff);
                setField(row,"diffPct",lowest > 0 ? round2(diff / lowest * 100.0) : Double.NaN);
            }
            setField(row,"status","OK");
            setField(row,"method","ścisłe dopasowanie produktu bez EAN");
            setField(row,"note","Brak EAN: cena potwierdzona przez zgodność wielu cech tytułu; zgodnych ofert: " + matched + ".");
        } catch (Exception e) {
            try {
                clearUncertain(row,"Nie udało się bezpiecznie potwierdzić ceny produktu bez EAN.");
            } catch (Exception ignored) {}
        }
    }

    private boolean strictSameProduct(List<String> titleTokens, List<String> models, String candidate) {
        if (candidate == null || candidate.trim().isEmpty()) return false;
        String norm = normalize(candidate);

        // Model / set number is a strong identity feature and must agree when present.
        if (!models.isEmpty()) {
            boolean modelMatch = false;
            for (String m : models) if (containsToken(norm,m)) { modelMatch = true; break; }
            if (!modelMatch) return false;
        }

        int matches = 0;
        int alphaMatches = 0;
        for (String t : titleTokens) {
            if (containsToken(norm,t)) {
                matches++;
                if (!containsDigit(t)) alphaMatches++;
            }
        }

        int total = titleTokens.size();
        double ratio = total == 0 ? 0 : (double)matches / total;

        // Require multiple independent signals. One word such as "80cm" can never pass.
        if (!models.isEmpty()) {
            return matches >= Math.min(3,total) && (alphaMatches >= 1 || matches >= 3);
        }
        if (total >= 6) return matches >= 3 && ratio >= 0.42;
        if (total >= 4) return matches >= 3;
        return matches >= 2 && ratio >= 0.66;
    }

    private List<String> significantTokens(String title) {
        String n = normalize(title);
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String raw : n.split("[^a-z0-9]+")) {
            if (raw == null || raw.isEmpty()) continue;
            if (STOP.contains(raw)) continue;
            if (raw.length() < 4 && !containsDigit(raw)) continue;
            if (raw.matches("\\d{8,14}")) continue; // EAN-like noise
            out.add(raw);
            if (out.size() >= 12) break;
        }
        return new ArrayList<>(out);
    }

    private List<String> modelTokens(List<String> tokens) {
        List<String> out = new ArrayList<>();
        for (String t : tokens) {
            if (containsDigit(t) && t.length() >= 4 && !t.matches("\\d{1,3}(cm|mm|kg|ml|gb|tb)?")) out.add(t);
        }
        return out;
    }

    private boolean containsToken(String normalizedText, String token) {
        return Pattern.compile("(^|[^a-z0-9])" + Pattern.quote(token) + "([^a-z0-9]|$)")
                .matcher(normalizedText).find();
    }

    private boolean containsDigit(String s) {
        for (int i=0;i<s.length();i++) if (Character.isDigit(s.charAt(i))) return true;
        return false;
    }

    private String normalize(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+","");
        return n.toLowerCase(Locale.ROOT).replace('ł','l').replace('Ł','l');
    }

    private double currentPrice(String text) {
        if (text == null) return Double.NaN;
        for (String line : text.split("\\n")) {
            String l = normalize(line);
            if (l.contains("cena z 30 dni") || l.contains("dostaw") || l.contains("rata") ||
                    l.contains("mies") || l.contains("/szt") || l.contains("/kg") || l.contains("/100")) continue;
            Matcher m = PRICE_RE.matcher(line);
            double best = Double.NaN;
            while (m.find()) {
                double p = parsePrice(m.group(1));
                if (!Double.isNaN(p) && (Double.isNaN(best) || p < best)) best = p;
            }
            if (!Double.isNaN(best)) return best;
        }
        return Double.NaN;
    }

    private double parsePrice(String s) {
        try {
            return Double.parseDouble(s.replace("\u00a0","").replace("\u202f","").replace(" ","").replace(',','.'));
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private void clearUncertain(Object row, String note) throws Exception {
        setField(row,"lowestNew",Double.NaN);
        setField(row,"diffPln",Double.NaN);
        setField(row,"diffPct",Double.NaN);
        setField(row,"status","BRAK PEWNEGO DOPASOWANIA");
        setField(row,"method","bez zgadywania po tytule");
        setField(row,"note",note);
    }

    @SuppressWarnings("unchecked")
    private List<?> getListField(String name) throws Exception {
        Field f = MobileMainActivity.class.getDeclaredField(name);
        f.setAccessible(true);
        Object x = f.get(this);
        return x instanceof List ? (List<?>)x : new ArrayList<>();
    }

    private Object getFieldFromMobile(String name) throws Exception {
        Field f = MobileMainActivity.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(this);
    }

    private Object field(Object obj,String name) throws Exception {
        Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(obj);
    }

    private void setField(Object obj,String name,Object value) throws Exception {
        Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        if (f.getType() == double.class && value instanceof Number) f.setDouble(obj,((Number)value).doubleValue());
        else if (f.getType() == int.class && value instanceof Number) f.setInt(obj,((Number)value).intValue());
        else f.set(obj,value);
    }

    private double dbl(Object o) { return o instanceof Number ? ((Number)o).doubleValue() : Double.NaN; }
    private String str(Object o) { return o == null ? "" : String.valueOf(o); }
    private double round2(double x) { return Math.round(x * 100.0) / 100.0; }

    private void updateVersion(View view) {
        if (view instanceof TextView && !(view instanceof android.widget.Button)) {
            TextView t=(TextView)view;
            String s=String.valueOf(t.getText());
            if(s.contains("Allegro Radar")||s.contains("Radar v"))
                t.setText(s.replace("v2","v6").replace("v3","v6").replace("v4","v6").replace("v5","v6"));
        }
        if(view instanceof ViewGroup){
            ViewGroup g=(ViewGroup)view;
            for(int i=0;i<g.getChildCount();i++) updateVersion(g.getChildAt(i));
        }
    }
}
