package com.monitor.m1l;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Enumeration;
import java.util.List;

public class MainActivity extends Activity {
    private TextView infoText;
    private View blackOverlay;
    private long lastTapTime = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.parseColor("#121212"));
        root.setPadding(50, 50, 50, 50);

        infoText = new TextView(this);
        infoText.setTextColor(Color.WHITE);
        infoText.setTextSize(18);
        infoText.setGravity(Gravity.CENTER);
        updateIpDisplay();

        Button startBtn = new Button(this);
        startBtn.setText("1. 启动 / 刷新监控服务");
        startBtn.setTextSize(16);
        startBtn.setOnClickListener(v -> checkPermAndStart());

        Button blackBtn = new Button(this);
        blackBtn.setText("2. 开启纯黑伪装 (防息屏断流备选)");
        blackBtn.setTextSize(16);
        blackBtn.setOnClickListener(v -> enableBlackMode());

        Button stopBtn = new Button(this);
        stopBtn.setText("彻底关闭监控退出");
        stopBtn.setTextSize(16);
        stopBtn.setOnClickListener(v -> {
            stopService(new Intent(this, CameraService.class));
            finish();
        });

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 25, 0, 25);

        root.addView(infoText, lp);
        root.addView(startBtn, lp);
        root.addView(blackBtn, lp);
        root.addView(stopBtn, lp);

        // 纯黑伪装层
        blackOverlay = new View(this);
        blackOverlay.setBackgroundColor(Color.BLACK);
        blackOverlay.setVisibility(View.GONE);
        blackOverlay.setOnClickListener(v -> {
            long now = System.currentTimeMillis();
            if (now - lastTapTime < 500) {
                disableBlackMode();
            }
            lastTapTime = now;
        });

        addContentView(root, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        addContentView(blackOverlay, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));

        checkPermAndStart();
    }

    private void checkPermAndStart() {
        if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.CAMERA}, 100);
                return;
            }
        }
        startMonitorService();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == 100 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startMonitorService();
        }
    }

    private void startMonitorService() {
        Intent intent = new Intent(this, CameraService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        updateIpDisplay();
    }

    private void updateIpDisplay() {
        String ip = getLocalIp();
        infoText.setText("【监控服务已就绪】\n\n请在另一台手机浏览器输入网址：\n\nhttp://" + ip + ":8080\n\n" +
                "提示：现在可以直接按【电源键息屏】放进书房。\n如果发现锤子系统锁屏几分钟后画面卡住，请点击下方【开启纯黑伪装】，屏幕会全黑且背光归零（双击屏幕任意位置恢复）。");
    }

    private void enableBlackMode() {
        blackOverlay.setVisibility(View.VISIBLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.screenBrightness = 0.01f;
        getWindow().setAttributes(params);
    }

    private void disableBlackMode() {
        blackOverlay.setVisibility(View.GONE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(params);
    }

    private String getLocalIp() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                WifiInfo wi = wm.getConnectionInfo();
                int ipInt = wi.getIpAddress();
                if (ipInt != 0) {
                    return String.format("%d.%d.%d.%d",
                            (ipInt & 0xff), (ipInt >> 8 & 0xff), (ipInt >> 16 & 0xff), (ipInt >> 24 & 0xff));
                }
            }
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements(); ) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "请先连接Wi-Fi后点击启动按钮";
    }
}
