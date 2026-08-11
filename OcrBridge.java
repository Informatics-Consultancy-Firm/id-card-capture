package icf.idcapture;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * On device text recognition for the WebView pages.
 *
 * Wiring, one line in MainActivity after the WebView exists:
 *     webView.addJavascriptInterface(new OcrBridge(this, webView), "AndroidOCR");
 *
 * And one line in app/build.gradle dependencies:
 *     implementation 'com.google.mlkit:text-recognition:16.0.1'
 *
 * The page calls:
 *     AndroidOCR.available()                -> true
 *     AndroidOCR.recognise(base64, token)   -> window.__ocrDone(token, payload)
 *
 * payload is {"ok":true,"lines":[...],"text":"..."} or {"ok":false,"error":"..."}.
 * Nothing leaves the phone. The Latin model is bundled in the APK, so this works
 * with the aeroplane mode on and with no Play Services download.
 */
public class OcrBridge {

    private final Activity activity;
    private final WebView web;
    private final TextRecognizer recognizer =
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

    public OcrBridge(Activity activity, WebView web) {
        this.activity = activity;
        this.web = web;
    }

    @JavascriptInterface
    public boolean available() {
        return true;
    }

    @JavascriptInterface
    public void recognise(final String base64, final String token) {
        // Runs on the JavaBridge thread, so the decode is fine here and every
        // reply is posted back onto the main thread before it touches the WebView.
        try {
            String data = base64;
            int comma = data.indexOf(',');
            if (data.startsWith("data:") && comma > -1) data = data.substring(comma + 1);

            byte[] bytes = Base64.decode(data, Base64.DEFAULT);
            Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bmp == null) {
                fail(token, "The photo could not be decoded on the device.");
                return;
            }

            InputImage image = InputImage.fromBitmap(bmp, 0);
            recognizer.process(image)
                    .addOnSuccessListener(new OnSuccessListener<Text>() {
                        @Override
                        public void onSuccess(Text result) {
                            try {
                                JSONArray lines = new JSONArray();
                                for (Text.TextBlock block : result.getTextBlocks()) {
                                    for (Text.Line line : block.getLines()) {
                                        String t = line.getText().trim();
                                        if (t.length() > 0) lines.put(t);
                                    }
                                }
                                JSONObject out = new JSONObject();
                                out.put("ok", true);
                                out.put("lines", lines);
                                out.put("text", result.getText());
                                send(token, out.toString());
                            } catch (Exception e) {
                                fail(token, "The result could not be packed: " + e.getMessage());
                            }
                        }
                    })
                    .addOnFailureListener(new OnFailureListener() {
                        @Override
                        public void onFailure(Exception e) {
                            fail(token, "Text recognition failed: " + e.getMessage());
                        }
                    });
        } catch (Exception e) {
            fail(token, "Could not start recognition: " + e.getMessage());
        }
    }

    private void fail(String token, String message) {
        try {
            JSONObject out = new JSONObject();
            out.put("ok", false);
            out.put("error", message);
            send(token, out.toString());
        } catch (Exception ignored) {
            send(token, "{\"ok\":false,\"error\":\"unknown\"}");
        }
    }

    private void send(final String token, final String payload) {
        final String js = "if(window.__ocrDone){window.__ocrDone('" + token + "'," + payload + ");}";
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                web.evaluateJavascript(js, null);
            }
        });
    }
}
