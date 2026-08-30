package com.immediate.shop;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class NewProductWorker extends Worker {

    private static final String CHANNEL_ID = "immediate_updates";
    private static final String PREFS = "immediate_prefs";
    private static final String KEY_LAST_HASH = "last_page_hash";
    private static final String CHECK_URL = "https://immediate.rf.gd/";

    public NewProductWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            URL url = new URL(CHECK_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (ImmediateShopApp)");

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                content.append(line);
            }
            reader.close();
            conn.disconnect();

            String newHash = String.valueOf(content.toString().hashCode());

            SharedPreferences prefs = getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String oldHash = prefs.getString(KEY_LAST_HASH, null);

            if (oldHash != null && !oldHash.equals(newHash)) {
                showNotification();
            }

            prefs.edit().putString(KEY_LAST_HASH, newHash).apply();
            return Result.success();
        } catch (Exception e) {
            return Result.retry();
        }
    }

    private void showNotification() {
        Context context = getApplicationContext();
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "নতুন আপডেট", NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("নতুন প্রোডাক্ট বা কনটেন্ট আপডেটের নোটিফিকেশন");
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }

        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, flags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle("Immediate — নতুন কিছু যুক্ত হয়েছে!")
                .setContentText("দোকানে নতুন প্রোডাক্ট বা আপডেট দেখতে অ্যাপ খুলুন")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent);

        if (manager != null) {
            manager.notify(2001, builder.build());
        }
    }
}
