package dz.fahis.fawatir;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Point;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.FileProvider;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_PICK = 7;
    private WebView web;
    private ValueCallback<Uri[]> pending;
    private Uri camUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor(night ? "#0E141F" : "#F1F4F8"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setTextZoom(100);
        web.addJavascriptInterface(new Ocr(), "AndroidOcr");
        web.addJavascriptInterface(new Gemini(), "AndroidGemini");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String sc = u.getScheme();
                if ("http".equals(sc) || "https".equals(sc)) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                    return true;
                }
                return false;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (pending != null) pending.onReceiveValue(null);
                pending = cb;
                camUri = null;
                Intent i;
                if (p.isCaptureEnabled()) {
                    try {
                        File dir = new File(getCacheDir(), "cam");
                        dir.mkdirs();
                        File f = new File(dir, "invoice_" + System.currentTimeMillis() + ".jpg");
                        camUri = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".files", f);
                        i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                        i.putExtra(MediaStore.EXTRA_OUTPUT, camUri);
                        i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Exception e) {
                        camUri = null;
                        i = p.createIntent();
                    }
                } else {
                    i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("image/*");
                }
                try {
                    startActivityForResult(i, REQ_PICK);
                } catch (Exception e) {
                    pending.onReceiveValue(null);
                    pending = null;
                    return false;
                }
                return true;
            }
        });
        setContentView(web);
        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || pending == null) return;
        Uri[] r = null;
        if (res == RESULT_OK) {
            if (camUri != null) r = new Uri[]{camUri};
            else if (data != null && data.getData() != null) r = new Uri[]{data.getData()};
        }
        pending.onReceiveValue(r);
        pending = null;
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    private void reply(String id, String text, String err) {
        final String js = "window.__ocrDone && window.__ocrDone(" + JSONObject.quote(id) + "," + JSONObject.quote(text == null ? "" : text) + "," + JSONObject.quote(err == null ? "" : err) + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    private static final String[] KEYS = {"FACTURE", "TOTAL", "NIF", "N.I.F", "NIS", "R.C", "RC ", "TVA", "T.V.A", "DATE", "SOMME", "DESIGNATION", "QUANTITE", "PRIX", "MONTANT", "TTC", "H.T", "ARTICLE", "CLIENT", "DOIT"};

    private static int score(String s) {
        String u = s.toUpperCase(Locale.ROOT);
        int sc = 0;
        for (String k : KEYS) if (u.contains(k)) sc += 100;
        int letters = 0;
        for (int i = 0; i < u.length(); i++) { char c = u.charAt(i); if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) letters++; }
        return sc + letters / 10;
    }

    private static class Ln {
        double along, across, h;
        String text;
    }

    // Rebuild the page row by row, as printed, from the recognised lines and their positions.
    private static String rows(Text t) {
        List<Ln> ls = new ArrayList<>();
        double ux = 0, uy = 0;
        for (Text.TextBlock b : t.getTextBlocks()) {
            for (Text.Line l : b.getLines()) {
                Point[] c = l.getCornerPoints();
                if (c == null || c.length < 4) continue;
                double dx = c[1].x - c[0].x, dy = c[1].y - c[0].y;
                ux += dx; uy += dy;
            }
        }
        double n = Math.hypot(ux, uy);
        if (n < 1) return t.getText();
        ux /= n; uy /= n;
        double nx = -uy, ny = ux;
        List<Double> hs = new ArrayList<>();
        for (Text.TextBlock b : t.getTextBlocks()) {
            for (Text.Line l : b.getLines()) {
                Point[] c = l.getCornerPoints();
                if (c == null || c.length < 4) continue;
                Ln x = new Ln();
                double cx = (c[0].x + c[1].x + c[2].x + c[3].x) / 4.0, cy = (c[0].y + c[1].y + c[2].y + c[3].y) / 4.0;
                x.along = cx * ux + cy * uy;
                x.across = cx * nx + cy * ny;
                x.h = Math.hypot(c[3].x - c[0].x, c[3].y - c[0].y);
                x.text = l.getText();
                ls.add(x);
                hs.add(x.h);
            }
        }
        if (ls.isEmpty()) return "";
        Collections.sort(hs);
        double mh = Math.max(4, hs.get(hs.size() / 2));
        ls.sort((a, b) -> Double.compare(a.across, b.across));
        StringBuilder out = new StringBuilder();
        List<Ln> row = new ArrayList<>();
        double start = ls.get(0).across;
        for (Ln x : ls) {
            if (!row.isEmpty() && x.across - start > mh * 0.6) {
                flush(row, out);
                row.clear();
                start = x.across;
            }
            if (row.isEmpty()) start = x.across;
            row.add(x);
        }
        flush(row, out);
        return out.toString();
    }

    private static void flush(List<Ln> row, StringBuilder out) {
        if (row.isEmpty()) return;
        row.sort((a, b) -> Double.compare(a.along, b.along));
        StringBuilder r = new StringBuilder();
        for (Ln x : row) { if (r.length() > 0) r.append(' '); r.append(x.text); }
        out.append(r).append('\n');
    }

    private static Bitmap decode(byte[] bytes) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        int max = Math.max(o.outWidth, o.outHeight), sample = 1;
        while (max / sample > 3200) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o2);
    }

    private class Ocr {
        @JavascriptInterface
        public void recognize(final String b64, final String id) {
            new Thread(() -> {
                TextRecognizer rec = null;
                try {
                    byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                    Bitmap bm = decode(bytes);
                    if (bm == null) { reply(id, null, "decode"); return; }
                    rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
                    String best = "";
                    int bestScore = -1;
                    // The photo may be rotated or upside down: try the four orientations, keep the best reading.
                    for (int rot : new int[]{0, 90, 270, 180}) {
                        Text t = Tasks.await(rec.process(InputImage.fromBitmap(bm, rot)));
                        String s = rows(t);
                        int sc = score(s);
                        if (sc > bestScore) { bestScore = sc; best = s; }
                        if (sc >= 800) break;
                    }
                    reply(id, best, null);
                } catch (Throwable e) {
                    reply(id, null, String.valueOf(e));
                } finally {
                    if (rec != null) rec.close();
                }
            }).start();
        }
    }

    // Online reading with Google Gemini, using the user's own free API key.
    private static final String API = "https://generativelanguage.googleapis.com/v1beta/";

    private static String http(String method, String url, String body, int[] code) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(20000);
        c.setReadTimeout(120000);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
        }
        code[0] = c.getResponseCode();
        InputStream in = code[0] >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) return "";
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toString("UTF-8");
    }

    // Newest "gemini-X-flash" models first, then known fallbacks.
    private static List<String> models(String key) {
        List<String> out = new ArrayList<>();
        try {
            int[] code = new int[1];
            String r = http("GET", API + "models?pageSize=200&key=" + key, null, code);
            if (code[0] == 200) {
                JSONArray a = new JSONObject(r).optJSONArray("models");
                Pattern p = Pattern.compile("^models/(gemini-(\\d+(?:\\.\\d+)?)-flash)$");
                List<double[]> vs = new ArrayList<>();
                List<String> names = new ArrayList<>();
                for (int i = 0; a != null && i < a.length(); i++) {
                    JSONObject m = a.getJSONObject(i);
                    String nm = m.optString("name");
                    Matcher mt = p.matcher(nm);
                    if (!mt.matches()) continue;
                    if (!m.optString("supportedGenerationMethods").contains("generateContent")) continue;
                    names.add(mt.group(1));
                    vs.add(new double[]{Double.parseDouble(mt.group(2)), names.size() - 1});
                }
                vs.sort((x, y) -> Double.compare(y[0], x[0]));
                for (double[] v : vs) out.add(names.get((int) v[1]));
            }
        } catch (Exception ignored) {}
        for (String f : new String[]{"gemini-flash-latest", "gemini-2.5-flash"}) if (!out.contains(f)) out.add(f);
        return out;
    }

    private void geminiReply(String id, String text, String err) {
        final String js = "window.__geminiDone && window.__geminiDone(" + JSONObject.quote(id) + "," + JSONObject.quote(text == null ? "" : text) + "," + JSONObject.quote(err == null ? "" : err) + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    private class Gemini {
        @JavascriptInterface
        public void ask(final String key, final String b64, final String prompt, final String id) {
            new Thread(() -> {
                try {
                    JSONObject img = new JSONObject().put("inline_data", new JSONObject().put("mime_type", "image/jpeg").put("data", b64));
                    JSONObject txt = new JSONObject().put("text", prompt);
                    JSONObject body = new JSONObject()
                        .put("contents", new JSONArray().put(new JSONObject().put("role", "user").put("parts", new JSONArray().put(img).put(txt))))
                        .put("generationConfig", new JSONObject().put("temperature", 0).put("responseMimeType", "application/json"));
                    String last = "no_model";
                    for (String m : models(key)) {
                        int[] code = new int[1];
                        String r = http("POST", API + "models/" + m + ":generateContent?key=" + key, body.toString(), code);
                        if (code[0] == 200) {
                            JSONObject o = new JSONObject(r);
                            JSONArray cands = o.optJSONArray("candidates");
                            StringBuilder sb = new StringBuilder();
                            if (cands != null && cands.length() > 0) {
                                JSONArray parts = cands.getJSONObject(0).optJSONObject("content") == null ? null : cands.getJSONObject(0).getJSONObject("content").optJSONArray("parts");
                                for (int i = 0; parts != null && i < parts.length(); i++) sb.append(parts.getJSONObject(i).optString("text"));
                            }
                            if (sb.length() > 0) { geminiReply(id, sb.toString(), null); return; }
                            last = "empty";
                            continue;
                        }
                        String msg = "";
                        try { msg = new JSONObject(r).getJSONObject("error").optString("message"); } catch (Exception ignored) {}
                        last = code[0] + " " + msg;
                        if (code[0] == 400 && msg.toLowerCase(Locale.ROOT).contains("api key")) break;
                        if (code[0] == 401 || code[0] == 403) break;
                        // 404 (model gone) or 429 (quota of this model): try the next model.
                    }
                    geminiReply(id, null, last);
                } catch (Throwable e) {
                    geminiReply(id, null, "net " + e);
                }
            }).start();
        }
    }
}
