package com.immediate.shop;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.TimeUnit;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private SwipeRefreshLayout swipeRefresh;
    private ProgressBar progressBar;
    private LinearLayout offlineLayout;

    private ValueCallback<Uri[]> filePathCallback;
    private static final int FILE_CHOOSER_REQUEST_CODE = 51426;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 8801;

    // GitHub-এ রাখা version.json ফাইলের raw লিংক — নতুন ভার্সন রিলিজ দিলে এই ফাইল আপডেট করবেন
    private static final String VERSION_CHECK_URL =
            "https://raw.githubusercontent.com/telekitbd/immediate-shop-app/main/version.json";

    // এখানে আপনার সাইটের ডোমেইন বসান, যাতে শুধু এই সাইটের লিংক অ্যাপের ভিতরে খোলে
    private static final String SITE_HOST = "immediate.rf.gd";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        webView.setBackgroundColor(ContextCompat.getColor(this, R.color.white));
        swipeRefresh = findViewById(R.id.swipeRefresh);
        progressBar = findViewById(R.id.progressBar);
        offlineLayout = findViewById(R.id.offlineLayout);
        Button retryButton = findViewById(R.id.retryButton);

        setupWebView();

        retryButton.setOnClickListener(v -> loadSite());
        swipeRefresh.setOnRefreshListener(this::loadSite);

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            loadSite();
        }

        requestNotificationPermissionIfNeeded();
        subscribeToNotificationTopic();
        scheduleNewProductCheck();
        checkForAppUpdate();
    }

    // সব ইউজারকে "all_users" টপিকে সাবস্ক্রাইব করা হচ্ছে,
    // যাতে সেন্ডার অ্যাপ থেকে এই টপিকে পাঠানো নোটিফিকেশন সবাই পায়
    private void subscribeToNotificationTopic() {
        com.google.firebase.messaging.FirebaseMessaging.getInstance()
                .subscribeToTopic("all_users");
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                        NOTIFICATION_PERMISSION_REQUEST);
            }
        }
    }

    // প্রতি ৬ ঘণ্টায় ব্যাকগ্রাউন্ডে সাইট চেক করে নতুন প্রোডাক্ট/কনটেন্ট এলে notification দেখাবে
    private void scheduleNewProductCheck() {
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                NewProductWorker.class, 6, TimeUnit.HOURS)
                .build();
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "new_product_check", ExistingPeriodicWorkPolicy.KEEP, request);
    }

    // নতুন অ্যাপ ভার্সন এসেছে কিনা চেক করে (version.json থেকে)
    private void checkForAppUpdate() {
        new Thread(() -> {
            try {
                URL url = new URL(VERSION_CHECK_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder content = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line);
                }
                reader.close();
                conn.disconnect();

                JSONObject json = new JSONObject(content.toString());
                int latestVersionCode = json.getInt("versionCode");
                String versionName = json.optString("versionName", "");
                String apkUrl = json.optString("apkUrl", "");
                String message = json.optString("message", "নতুন ভার্সন পাওয়া গেছে!");
                boolean forceUpdate = json.optBoolean("forceUpdate", false);
                int minSupportedVersionCode = json.optInt("minSupportedVersionCode", 0);

                int currentVersionCode = getCurrentVersionCode();

                boolean mustUpdate = forceUpdate && currentVersionCode < minSupportedVersionCode;

                if (mustUpdate) {
                    runOnUiThread(() -> showUpdateDialog(versionName, message, apkUrl, true));
                } else if (latestVersionCode > currentVersionCode) {
                    runOnUiThread(() -> showUpdateDialog(versionName, message, apkUrl, false));
                }
            } catch (Exception ignored) {
                // ইন্টারনেট না থাকলে বা চেক ব্যর্থ হলে চুপচাপ স্কিপ করবে
            }
        }).start();
    }

    private int getCurrentVersionCode() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    private void showUpdateDialog(String versionName, String message, String apkUrl, boolean forceUpdate) {
        String title = versionName.isEmpty()
                ? "নতুন ভার্সন উপলব্ধ"
                : "নতুন ভার্সন উপলব্ধ (" + versionName + ")";

        if (forceUpdate) {
            title = "আপডেট বাধ্যতামূলক";
            String finalMessage = message.isEmpty()
                    ? "এই অ্যাপ ব্যবহার করতে হলে নতুন ভার্সনে আপডেট করা আবশ্যক।"
                    : message + "\n\nএই অ্যাপ ব্যবহার করতে হলে আপডেট করা আবশ্যক।";

            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setMessage(finalMessage)
                    .setCancelable(false)
                    .setPositiveButton("এখনই আপডেট করুন", null)
                    .create();

            dialog.setOnShowListener(d -> {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    if (!apkUrl.isEmpty()) {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl));
                        startActivity(intent);
                    }
                    // ডায়ালগ বন্ধ হবে না, যতক্ষণ না আপডেট করা হয়
                });
            });

            dialog.show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(true);

        if (!apkUrl.isEmpty()) {
            builder.setPositiveButton("ডাউনলোড করুন", (dialog, which) -> {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl));
                startActivity(intent);
            });
            builder.setNegativeButton("পরে", (dialog, which) -> dialog.dismiss());
        } else {
            builder.setPositiveButton("ঠিক আছে", (dialog, which) -> dialog.dismiss());
        }

        builder.show();
    }

    private void loadSite() {
        if (isNetworkAvailable()) {
            offlineLayout.setVisibility(View.GONE);
            webView.setVisibility(View.VISIBLE);
            webView.loadUrl(getString(R.string.site_url));
        } else {
            swipeRefresh.setRefreshing(false);
            offlineLayout.setVisibility(View.VISIBLE);
            webView.setVisibility(View.GONE);
            Toast.makeText(this, "ইন্টারনেট সংযোগ নেই", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo info = cm != null ? cm.getActiveNetworkInfo() : null;
        return info != null && info.isConnected();
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setAllowFileAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url == null) return false;

                // Non-http(s) links (tel:, mailto:, whatsapp, upi payment apps ইত্যাদি) বাইরের অ্যাপে খুলবে
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(MainActivity.this, "এই লিংকটি খোলার মতো কোনো অ্যাপ নেই", Toast.LENGTH_SHORT).show();
                    }
                    return true;
                }
                // নিজের সাইটের সব লিংক (অ্যাডমিন প্যানেল সহ) অ্যাপের ভিতরেই লোড হবে
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                progressBar.setVisibility(View.GONE);
                swipeRefresh.setRefreshing(false);
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                if (!isNetworkAvailable()) {
                    offlineLayout.setVisibility(View.VISIBLE);
                    webView.setVisibility(View.GONE);
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                if (newProgress >= 100) {
                    progressBar.setVisibility(View.GONE);
                }
            }

            // ছবি/ফাইল আপলোড সাপোর্ট — ই-কমার্স অ্যাডমিন প্যানেলে প্রোডাক্ট ছবি আপলোডের জন্য দরকার
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;

                Intent intent = params.createIntent();
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE);
                } catch (Exception e) {
                    filePathCallback = null;
                    Toast.makeText(MainActivity.this, "ফাইল বাছাই করা যায়নি", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }

            // ক্যামেরা পারমিশন প্রম্পট (QR স্ক্যান/ছবি তোলার ফিচার থাকলে)
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }
        });

        // সাইট থেকে ফাইল ডাউনলোড করলে (ইনভয়েস/রিসিট পিডিএফ ইত্যাদি) ব্রাউজারে/ডাউনলোড ম্যানেজারে পাঠানো হবে
        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(this, "ডাউনলোড শুরু করা যায়নি", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (filePathCallback == null) {
                super.onActivityResult(requestCode, resultCode, data);
                return;
            }
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                String dataString = data.getDataString();
                if (dataString != null) {
                    results = new Uri[]{Uri.parse(dataString)};
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("অ্যাপ থেকে বের হবেন?")
                .setMessage("আপনি কি নিশ্চিতভাবে Immediate অ্যাপ থেকে বের হতে চান?")
                .setPositiveButton("হ্যাঁ", (dialog, which) -> {
                    dialog.dismiss();
                    finish();
                })
                .setNegativeButton("না", (dialog, which) -> dialog.dismiss())
                .setCancelable(true)
                .show();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }
}
