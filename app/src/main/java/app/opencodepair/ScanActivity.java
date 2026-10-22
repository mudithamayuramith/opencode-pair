package app.opencodepair;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Camera;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Full-screen QR reader. Uses the platform camera (no AndroidX / CameraX) and the pure-Java
 * ZXing decoder, so the app keeps its tiny dependency footprint.
 */
public class ScanActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {

    public static final String EXTRA_TEXT = "text";

    private static final int REQ_CAMERA = 10;

    private FrameLayout overlay;
    private SurfaceView surface;
    private SurfaceHolder holder;
    private TextView status;
    private TextView settingsBtn;

    private Camera camera;
    private int previewWidth, previewHeight;
    private boolean surfaceReady;

    private HandlerThread decodeThread;
    private Handler decodeHandler;
    private final MultiFormatReader reader = new MultiFormatReader();

    private volatile boolean decoding;
    private volatile boolean done;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();

        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        // A light-themed terminal prints the code inverted (dark glyphs on light).
        hints.put(DecodeHintType.ALSO_INVERTED, Boolean.TRUE);
        reader.setHints(hints);

        decodeThread = new HandlerThread("qr-decode");
        decodeThread.start();
        decodeHandler = new Handler(decodeThread.getLooper());

        if (hasCameraPermission()) openCamera();
        else requestPermissions(new String[] {Manifest.permission.CAMERA}, REQ_CAMERA);
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    // ---------- UI ----------

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        surface = new SurfaceView(this);
        holder = surface.getHolder();
        holder.addCallback(this);
        root.addView(surface, new FrameLayout.LayoutParams(-1, -1));

        overlay = new FrameLayout(this);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        applyInsets();

        TextView close = new TextView(this);
        close.setText("\u2715");
        close.setTextColor(Color.WHITE);
        close.setTextSize(18);
        close.setGravity(Gravity.CENTER);
        GradientDrawable cb = new GradientDrawable();
        cb.setShape(GradientDrawable.OVAL);
        cb.setColor(0x66000000);
        close.setBackground(cb);
        close.setContentDescription(getString(R.string.scan_close));
        close.setOnClickListener(v -> finish());
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(40), dp(40));
        clp.gravity = Gravity.TOP | Gravity.START;
        clp.setMargins(dp(12), dp(12), 0, 0);
        overlay.addView(close, clp);

        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER_HORIZONTAL);

        int side = Math.min(dp(260), getResources().getDisplayMetrics().widthPixels - dp(64));
        View frame = new View(this);
        GradientDrawable fb = new GradientDrawable();
        fb.setColor(0x00000000);
        fb.setCornerRadius(dp(24));
        fb.setStroke(dp(2), 0xCCFFFFFF);
        frame.setBackground(fb);
        center.addView(frame, new LinearLayout.LayoutParams(side, side));

        status = new TextView(this);
        status.setText(R.string.scan_hint);
        status.setTextColor(0xFFE5E7EB);
        status.setTextSize(14);
        status.setGravity(Gravity.CENTER);
        status.setLineSpacing(0f, 1.25f);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(24);
        slp.leftMargin = dp(24);
        slp.rightMargin = dp(24);
        center.addView(status, slp);

        settingsBtn = new TextView(this);
        settingsBtn.setText(R.string.scan_open_settings);
        settingsBtn.setTextColor(0xFFA5B4FC);
        settingsBtn.setTextSize(14);
        settingsBtn.setTypeface(null, Typeface.BOLD);
        settingsBtn.setGravity(Gravity.CENTER);
        settingsBtn.setPadding(dp(16), dp(12), dp(16), dp(12));
        settingsBtn.setVisibility(View.GONE);
        settingsBtn.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", getPackageName(), null)));
            } catch (Exception ignored) { }
        });
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.topMargin = dp(14);
        center.addView(settingsBtn, olp);

        overlay.addView(center, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
    }

    private void applyInsets() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            overlay.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars =
                        insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        } else {
            overlay.setFitsSystemWindows(true);
        }
        overlay.requestApplyInsets();
    }

    private void setStatusText(int res) { if (status != null) status.setText(res); }

    private void showPermissionDenied() {
        setStatusText(R.string.scan_permission);
        if (settingsBtn != null) settingsBtn.setVisibility(View.VISIBLE);
    }

    // ---------- Permission ----------

    private boolean hasCameraPermission() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    @Override public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        if (req == REQ_CAMERA) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) openCamera();
            else showPermissionDenied();
            return;
        }
        super.onRequestPermissionsResult(req, perms, results);
    }

    // ---------- Camera ----------

    @Override public void surfaceCreated(SurfaceHolder h) {
        surfaceReady = true;
        if (hasCameraPermission()) openCamera();
    }

    @Override public void surfaceChanged(SurfaceHolder h, int format, int width, int height) { }

    @Override public void surfaceDestroyed(SurfaceHolder h) {
        surfaceReady = false;
        closeCamera();
    }

    private void openCamera() {
        if (camera != null || !surfaceReady || done) return;

        int id = findBackCamera();
        if (id < 0) { setStatusText(R.string.scan_no_camera); return; }

        Camera c;
        try {
            c = Camera.open(id);
        } catch (Exception e) {
            setStatusText(R.string.scan_camera_busy);
            return;
        }

        try {
            Camera.Parameters p = c.getParameters();

            Camera.Size size = choosePreviewSize(p.getSupportedPreviewSizes());
            if (size != null) p.setPreviewSize(size.width, size.height);

            String focus = chooseFocus(p.getSupportedFocusModes());
            if (focus != null) p.setFocusMode(focus);

            c.setParameters(p);

            // Read back the effective size: the camera may round the request.
            Camera.Size actual = c.getParameters().getPreviewSize();
            previewWidth = actual.width;
            previewHeight = actual.height;

            c.setDisplayOrientation(displayOrientation(id));
            c.setPreviewDisplay(holder);

            // One recycled buffer is enough and avoids allocating ~1.4 MB per preview frame.
            c.addCallbackBuffer(new byte[previewWidth * previewHeight * 3 / 2]);
            c.setPreviewCallbackWithBuffer(this);
            c.startPreview();
            camera = c;

            setStatusText(R.string.scan_hint);
            if (settingsBtn != null) settingsBtn.setVisibility(View.GONE);
        } catch (Exception e) {
            try { c.release(); } catch (Exception ignored) { }
            camera = null;
            setStatusText(R.string.scan_camera_busy);
        }
    }

    private void closeCamera() {
        Camera c = camera;
        camera = null;
        if (c == null) return;
        try { c.setPreviewCallback(null); } catch (Exception ignored) { }
        try { c.setPreviewCallbackWithBuffer(null); } catch (Exception ignored) { }
        try { c.stopPreview(); } catch (Exception ignored) { }
        try { c.release(); } catch (Exception ignored) { }
    }

    private int findBackCamera() {
        int count = Camera.getNumberOfCameras();
        Camera.CameraInfo info = new Camera.CameraInfo();
        for (int i = 0; i < count; i++) {
            Camera.getCameraInfo(i, info);
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) return i;
        }
        return count > 0 ? 0 : -1;
    }

    private int displayOrientation(int id) {
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(id, info);
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        int degrees = rotation == Surface.ROTATION_0 ? 0
                : rotation == Surface.ROTATION_90 ? 90
                : rotation == Surface.ROTATION_180 ? 180 : 270;
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            return (360 - (info.orientation + degrees) % 360) % 360;
        }
        return (info.orientation - degrees + 360) % 360;
    }

    /** Biggest preview up to 1280px wide; decoding gets slower and no more accurate above that. */
    private Camera.Size choosePreviewSize(List<Camera.Size> sizes) {
        if (sizes == null || sizes.isEmpty()) return null;
        Camera.Size best = null;
        for (Camera.Size s : sizes) {
            if (s.width > 1280 || s.height > 1280) continue;
            if (best == null || s.width * s.height > best.width * best.height) best = s;
        }
        if (best != null) return best;
        for (Camera.Size s : sizes) {
            if (best == null || s.width * s.height < best.width * best.height) best = s;
        }
        return best;
    }

    private String chooseFocus(List<String> modes) {
        if (modes == null) return null;
        if (modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
            return Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE;
        }
        if (modes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
            return Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO;
        }
        if (modes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) return Camera.Parameters.FOCUS_MODE_AUTO;
        return null;
    }

    // ---------- Decoding ----------

    @Override public void onPreviewFrame(byte[] data, Camera c) {
        if (done || decoding || data == null || previewWidth <= 0 || previewHeight <= 0) return;
        decoding = true;

        final byte[] frame = data;
        final int w = previewWidth, h = previewHeight;
        final Camera cam = c;

        decodeHandler.post(() -> {
            Result result = null;
            try {
                PlanarYUVLuminanceSource source =
                        new PlanarYUVLuminanceSource(frame, w, h, 0, 0, w, h, false);
                result = reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(source)));
            } catch (Exception ignored) {
                // NotFoundException is the normal case for most frames
            } finally {
                reader.reset();
            }

            if (result != null) {
                final String text = result.getText();
                runOnUiThread(() -> finishWith(text));
            } else {
                decoding = false;                       // hand the buffer back for the next frame
                runOnUiThread(() -> {
                    if (done || camera != cam) return;
                    try { cam.addCallbackBuffer(frame); } catch (Exception ignored) { }
                });
            }
        });
    }

    private void finishWith(String text) {
        if (done) return;
        done = true;
        closeCamera();
        Intent data = new Intent();
        data.putExtra(EXTRA_TEXT, text);
        setResult(RESULT_OK, data);
        finish();
    }

    // ---------- Lifecycle ----------

    @Override protected void onResume() {
        super.onResume();
        if (surfaceReady && hasCameraPermission()) openCamera();
    }

    @Override protected void onPause() {
        closeCamera();
        super.onPause();
    }

    @Override protected void onDestroy() {
        closeCamera();
        if (decodeThread != null) decodeThread.quitSafely();
        super.onDestroy();
    }
}
