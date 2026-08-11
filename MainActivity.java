package icf.idcapture;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import java.io.File;

/**
 * ID Capture. One activity holding one page.
 *
 * The page is served over https://appassets.androidplatform.net/ rather than
 * file:/// so it keeps a real origin and localStorage behaves as in a browser.
 * Text recognition runs on the device through OcrBridge, exposed to the page
 * as window.AndroidOCR.
 */
public class MainActivity extends ComponentActivity {

    private static final String START_PAGE =
            "https://appassets.androidplatform.net/assets/www/id_capture_offline.html";
    private static final String AUTHORITY = "icf.idcapture.fileprovider";

    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private Uri cameraUri;
    private ActivityResultLauncher<Intent> picker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        picker = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                new ActivityResultCallback<ActivityResult>() {
                    @Override
                    public void onActivityResult(ActivityResult result) {
                        if (fileCallback == null) return;
                        Uri[] out = null;
                        if (result.getResultCode() == RESULT_OK) {
                            Intent data = result.getData();
                            if (data != null && data.getData() != null) {
                                out = new Uri[]{ data.getData() };        // came from the gallery
                            } else if (cameraUri != null) {
                                out = new Uri[]{ cameraUri };             // came from the camera
                            }
                        }
                        fileCallback.onReceiveValue(out);                  // null is required on cancel
                        fileCallback = null;
                        cameraUri = null;
                    }
                });

        webView = new WebView(this);
        webView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportZoom(false);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if ("appassets.androidplatform.net".equals(url.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, url));   // links go to the browser
                } catch (Exception ignored) { }
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                return openChooser(params);
            }
        });

        webView.addJavascriptInterface(new OcrBridge(this, webView), "AndroidOCR");

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack();
                else finish();
            }
        });

        if (savedInstanceState != null) webView.restoreState(savedInstanceState);
        else webView.loadUrl(START_PAGE);
    }

    /** Camera and gallery side by side, so one tap covers both. */
    private boolean openChooser(WebChromeClient.FileChooserParams params) {
        try {
            Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType("image/*");

            Intent chooser = Intent.createChooser(pick, getString(R.string.pick_title));

            Intent camera = cameraIntent();
            if (camera != null) {
                chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{ camera });
            }

            picker.launch(chooser);
            return true;
        } catch (Exception e) {
            if (fileCallback != null) { fileCallback.onReceiveValue(null); fileCallback = null; }
            Toast.makeText(this, "No camera or gallery app was found.", Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private Intent cameraIntent() {
        try {
            File dir = new File(getCacheDir(), "shots");
            if (!dir.exists() && !dir.mkdirs()) return null;
            File shot = new File(dir, "shot_" + System.currentTimeMillis() + ".jpg");
            cameraUri = FileProvider.getUriForFile(this, AUTHORITY, shot);

            Intent camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            camera.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
            camera.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            return camera;
        } catch (Exception e) {
            cameraUri = null;
            return null;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }
}
