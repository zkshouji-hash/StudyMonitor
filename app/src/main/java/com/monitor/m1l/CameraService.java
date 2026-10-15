package com.monitor.m1l;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;

public class CameraService extends Service implements Camera.PreviewCallback {
    private Camera camera;
    private SurfaceTexture surfaceTexture;
    private PowerManager.WakeLock wakeLock;
    private ServerSocket serverSocket;
    private volatile boolean isRunning = false;
    private volatile byte[] latestJpeg = null;
    private volatile int activeClients = 0;
    private long lastFrameTime = 0;

    private int previewWidth = 1280;
    private int previewHeight = 720;
    private int jpegQuality = 70;
    private int maxZoom = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundNotification();
        acquireWakeLock();
        startCamera(1280, 720);
        startHttpServer();
    }

    private void startForegroundNotification() {
        String channelId = "monitor_channel";
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    channelId, "Monitor Service", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
            Notification notification = new Notification.Builder(this, channelId)
                    .setContentTitle("书房监控运行中")
                    .setContentText("保持后台摄像头与局域网服务常驻")
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .build();
            startForeground(1, notification);
        } else {
            Notification notification = new Notification.Builder(this)
                    .setContentTitle("书房监控运行中")
                    .setContentText("保持后台摄像头与局域网服务常驻")
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .build();
            startForeground(1, notification);
        }
    }

    private void acquireWakeLock() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StudyMonitor::CameraWakeLock");
            wakeLock.acquire();
        }
    }

    private synchronized void startCamera(int targetW, int targetH) {
        releaseCamera();
        try {
            camera = Camera.open(0);
            Camera.Parameters params = camera.getParameters();

            if (params.isZoomSupported()) {
                maxZoom = params.getMaxZoom();
            }

            List<Camera.Size> sizes = params.getSupportedPreviewSizes();
            Camera.Size bestSize = sizes.get(0);
            int minDiff = Integer.MAX_VALUE;
            for (Camera.Size s : sizes) {
                int diff = Math.abs(s.width - targetW) + Math.abs(s.height - targetH);
                if (diff < minDiff) {
                    minDiff = diff;
                    bestSize = s;
                }
            }
            previewWidth = bestSize.width;
            previewHeight = bestSize.height;
            params.setPreviewSize(previewWidth, previewHeight);
            params.setPreviewFormat(ImageFormat.NV21);

            List<String> focusModes = params.getSupportedFocusModes();
            if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            } else if (focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            }

            camera.setParameters(params);
            surfaceTexture = new SurfaceTexture(10);
            camera.setPreviewTexture(surfaceTexture);
            camera.setPreviewCallback(this);
            camera.startPreview();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private synchronized void setCameraZoom(int percent) {
        if (camera == null || maxZoom == 0) return;
        try {
            Camera.Parameters params = camera.getParameters();
            if (params.isZoomSupported()) {
                int zoomVal = (int) ((percent / 100.0f) * maxZoom);
                zoomVal = Math.max(0, Math.min(zoomVal, maxZoom));
                params.setZoom(zoomVal);
                camera.setParameters(params);
            }
        } catch (Exception ignored) {}
    }

    private synchronized void triggerAutoFocus() {
        if (camera == null) return;
        try {
            camera.cancelAutoFocus();
            Camera.Parameters params = camera.getParameters();
            if (params.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                camera.setParameters(params);
            }
            camera.autoFocus((success, cam) -> {
                try {
                    Camera.Parameters p = cam.getParameters();
                    if (p.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                        p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
                        cam.setParameters(p);
                    }
                } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera cam) {
        if (activeClients <= 0) return;

        long now = System.currentTimeMillis();
        if (now - lastFrameTime < 80) return;
        lastFrameTime = now;

        try {
            YuvImage yuv = new YuvImage(data, ImageFormat.NV21, previewWidth, previewHeight, null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuv.compressToJpeg(new Rect(0, 0, previewWidth, previewHeight), jpegQuality, out);
            latestJpeg = out.toByteArray();
        } catch (Exception ignored) {}
    }

    private void startHttpServer() {
        isRunning = true;
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(8080);
                while (isRunning) {
                    Socket client = serverSocket.accept();
                    new Thread(() -> handleClient(client)).start();
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private void handleClient(Socket client) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
            OutputStream out = client.getOutputStream();
            String requestLine = reader.readLine();
            if (requestLine == null) {
                client.close();
                return;
            }

            if (requestLine.startsWith("GET /stream")) {
                activeClients++;
                try {
                    String header = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: multipart/x-mixed-replace; boundary=frame\r\n" +
                            "Cache-Control: no-cache\r\n" +
                            "Connection: close\r\n\r\n";
                    out.write(header.getBytes());
                    byte[] lastSent = null;
                    while (isRunning && !client.isClosed()) {
                        byte[] frame = latestJpeg;
                        if (frame != null && frame != lastSent) {
                            lastSent = frame;
                            String partHeader = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: " + frame.length + "\r\n\r\n";
                            out.write(partHeader.getBytes());
                            out.write(frame);
                            out.write("\r\n".getBytes());
                            out.flush();
                        }
                        Thread.sleep(50);
                    }
                } finally {
                    activeClients = Math.max(0, activeClients - 1);
                }
            } else if (requestLine.startsWith("GET /zoom?v=")) {
                String valStr = requestLine.split(" ")[1].replace("/zoom?v=", "");
                int percent = Integer.parseInt(valStr);
                setCameraZoom(percent);
                sendSimpleOk(out);
            } else if (requestLine.startsWith("GET /focus")) {
                triggerAutoFocus();
                sendSimpleOk(out);
            } else if (requestLine.startsWith("GET /hd?v=")) {
                String mode = requestLine.split(" ")[1].replace("/hd?v=", "");
                if ("1".equals(mode)) {
                    jpegQuality = 88;
                    startCamera(1920, 1080);
                } else {
                    jpegQuality = 70;
                    startCamera(1280, 720);
                }
                sendSimpleOk(out);
            } else {
                String html = getHtmlPage();
                byte[] body = html.getBytes("UTF-8");
                String header = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\nContent-Length: " + body.length + "\r\n\r\n";
                out.write(header.getBytes());
                out.write(body);
                out.flush();
            }
        } catch (Exception ignored) {
        } finally {
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    private void sendSimpleOk(OutputStream out) throws Exception {
        String resp = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK";
        out.write(resp.getBytes());
        out.flush();
    }

    private String getHtmlPage() {
        return "<!DOCTYPE html><html><head><meta charset='utf-8'>" +
                "<meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no'>" +
                "<title>书房实时看护</title>" +
                "<style>" +
                "body{margin:0;background:#000;color:#fff;font-family:sans-serif;overflow:hidden;display:flex;flex-direction:column;height:100vh;user-select:none;-webkit-user-select:none;}" +
                "#viewBox{flex:1;display:flex;align-items:center;justify-content:center;overflow:hidden;position:relative;touch-action:none;cursor:grab;}" +
                "#viewBox:active{cursor:grabbing;}" +
                "#cam{max-width:100%;max-height:100%;transform-origin:center center;will-change:transform;}" +
                ".tip{position:absolute;top:10px;left:50%;transform:translateX(-50%);background:rgba(0,0,0,0.6);padding:4px 12px;border-radius:20px;font-size:12px;color:#aaa;pointer-events:none;z-index:5;}" +
                ".bar{background:rgba(20,20,20,0.92);padding:10px 14px;z-index:10;border-top:1px solid #333;}" +
                ".row{display:flex;align-items:center;justify-content:space-between;margin-bottom:8px;gap:6px;}" +
                ".row:last-child{margin-bottom:0;}" +
                "input[type=range]{flex:1;margin:0 8px;accent-color:#00e676;height:22px;}" +
                "button{background:#2b2b2b;color:#fff;border:1px solid #555;padding:8px 10px;border-radius:6px;font-size:13px;flex:1;white-space:nowrap;}" +
                "button:active{background:#00e676;color:#000;}" +
                "</style></head><body>" +
                "<div id='viewBox'>" +
                "<div class='tip' id='tipText'>💡 双指任意位置放大 / 单指自由拖拽平移 (电脑可用滚轮)</div>" +
                "<img id='cam' src='/stream' draggable='false' />" +
                "</div>" +
                "<div class='bar'>" +
                "<div class='row'>" +
                "<span style='font-size:13px;'>画面放大: <b id='sv'>1.0x</b></span>" +
                "<input type='range' id='sRange' min='10' max='80' value='10' oninput='doSliderScale(this.value)' />" +
                "<span style='font-size:13px;'>镜头光学: <b id='zv'>0%</b></span>" +
                "<input type='range' id='zRange' min='0' max='100' value='0' oninput='doZoom(this.value)' />" +
                "</div>" +
                "<div class='row'>" +
                "<button onclick='resetView()'>Reset 居中复位</button>" +
                "<button onclick='doRotate()'>🔄 旋转</button>" +
                "<button onclick='doFocus()'>🎯 对焦</button>" +
                "<button id='hdbtn' onclick='toggleHd()'>⚡ 1080P超清</button>" +
                "</div></div>" +
                "<script>" +
                "const box=document.getElementById('viewBox'), img=document.getElementById('cam');" +
                "let scale=1, pointX=0, pointY=0, startX=0, startY=0, deg=0, isHd=0;" +
                "let isDragging=false, initialPinchDist=0, initialScale=1;" +
                "setTimeout(()=>{document.getElementById('tipText').style.display='none';}, 5000);" +
                "function updateTransform(){" +
                "  img.style.transform=`translate(${pointX}px, ${pointY}px) scale(${scale}) rotate(${deg}deg)`;" +
                "  document.getElementById('sv').innerText=scale.toFixed(1)+'x';" +
                "  document.getElementById('sRange').value=Math.round(scale*10);" +
                "}" +
                "function doSliderScale(v){ scale=v/10.0; updateTransform(); }" +
                "function resetView(){ scale=1; pointX=0; pointY=0; updateTransform(); }" +
                "function doRotate(){ deg=(deg+90)%360; updateTransform(); }" +
                "function doZoom(v){ document.getElementById('zv').innerText=v+'%'; fetch('/zoom?v='+v); }" +
                "function doFocus(){ fetch('/focus'); }" +
                "function toggleHd(){" +
                "  isHd=isHd?0:1; fetch('/hd?v='+isHd);" +
                "  document.getElementById('hdbtn').innerText=isHd?'✅ 已开1080P':'⚡ 1080P超清';" +
                "  document.getElementById('hdbtn').style.borderColor=isHd?'#00e676':'#555';" +
                "}" +
                // 手机触摸：单指任意拖拽 + 双指自由缩放
                "box.addEventListener('touchstart', e=>{" +
                "  if(e.touches.length===1){" +
                "    isDragging=true; startX=e.touches[0].clientX-pointX; startY=e.touches[0].clientY-pointY;" +
                "  }else if(e.touches.length===2){" +
                "    isDragging=false;" +
                "    initialPinchDist=Math.hypot(e.touches[0].clientX-e.touches[1].clientX, e.touches[0].clientY-e.touches[1].clientY);" +
                "    initialScale=scale;" +
                "  }" +
                "});" +
                "box.addEventListener('touchmove', e=>{" +
                "  e.preventDefault();" +
                "  if(e.touches.length===1 && isDragging){" +
                "    pointX=e.touches[0].clientX-startX; pointY=e.touches[0].clientY-startY;" +
                "    updateTransform();" +
                "  }else if(e.touches.length===2 && initialPinchDist>0){" +
                "    const dist=Math.hypot(e.touches[0].clientX-e.touches[1].clientX, e.touches[0].clientY-e.touches[1].clientY);" +
                "    scale=Math.min(Math.max(1, initialScale*(dist/initialPinchDist)), 8);" +
                "    updateTransform();" +
                "  }" +
                "}, {passive:false});" +
                "box.addEventListener('touchend', e=>{ if(e.touches.length<2) initialPinchDist=0; if(e.touches.length===0) isDragging=false; });" +
                // 电脑鼠标：滚轮缩放 + 左键按住拖拽
                "box.addEventListener('mousedown', e=>{ isDragging=true; startX=e.clientX-pointX; startY=e.clientY-pointY; });" +
                "window.addEventListener('mousemove', e=>{ if(!isDragging)return; pointX=e.clientX-startX; pointY=e.clientY-startY; updateTransform(); });" +
                "window.addEventListener('mouseup', ()=>{ isDragging=false; });" +
                "box.addEventListener('wheel', e=>{" +
                "  e.preventDefault();" +
                "  const delta=e.deltaY>0?-0.3:0.3;" +
                "  scale=Math.min(Math.max(1, scale+delta), 8);" +
                "  updateTransform();" +
                "}, {passive:false});" +
                "</script></body></html>";
    }

    private synchronized void releaseCamera() {
        if (camera != null) {
            try {
                camera.setPreviewCallback(null);
                camera.stopPreview();
                camera.release();
            } catch (Exception ignored) {}
            camera = null;
        }
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        releaseCamera();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
