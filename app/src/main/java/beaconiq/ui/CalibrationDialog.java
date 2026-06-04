package beaconiq.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import beaconiq.R;
import beaconiq.model.Beacon;
import beaconiq.positioning.phase2.P2BeaconSample;
import beaconiq.storage.CalibrationStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Per-beacon calibration dialog: edit X/Y coordinates and sample live RSSI to
 * derive a calibrated TX power. Shared by ScanFragment (Explore) and
 * PhaseTwoTestFragment, which previously each carried an identical copy.
 */
public final class CalibrationDialog {

    /** Notified after a beacon is saved or its calibration is removed. */
    public interface OnChanged {
        void onChanged();
    }

    private CalibrationDialog() {}

    public static void show(Context ctx,
                            Handler handler,
                            String compositeId,
                            double currentX, double currentY,
                            int defaultTxPower,
                            double pathLossN,
                            CalibrationStore calibrationStore,
                            Map<String, P2BeaconSample> beaconMap,
                            OnChanged onChanged) {

        String[] parts = compositeId.split(":");
        String uuid = parts.length >= 1 ? parts[0] : "";
        String major = parts.length >= 2 ? parts[1] : "?";
        String minor = parts.length >= 3 ? parts[2] : "?";

        Beacon saved = calibrationStore.getBeacon(compositeId);
        double startX = saved != null ? saved.getX() : currentX;
        double startY = saved != null ? saved.getY() : currentY;
        final double[] calibratedTxPower = {saved != null ? saved.getTxPower() : defaultTxPower};

        LinearLayout layout = new LinearLayout(ctx);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * ctx.getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad / 2, pad, 0);

        TextView subtitle = new TextView(ctx);
        subtitle.setText(ctx.getString(R.string.calib_uuid,
                uuid.substring(0, Math.min(uuid.length(), 18))));
        subtitle.setTextColor(ContextCompat.getColor(ctx, R.color.grey_mid));
        subtitle.setTextSize(12f);
        layout.addView(subtitle);

        TextView labelX = new TextView(ctx);
        labelX.setText(R.string.calib_label_x);
        labelX.setTextColor(ContextCompat.getColor(ctx, R.color.grey_pale));
        labelX.setPadding(0, pad / 2, 0, 4);
        layout.addView(labelX);

        EditText editX = new EditText(ctx);
        editX.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        editX.setText(String.format(Locale.US, "%.2f", startX));
        editX.setTextColor(ContextCompat.getColor(ctx, R.color.white));
        editX.setBackgroundColor(ContextCompat.getColor(ctx, R.color.grey_dark));
        editX.setPadding(16, 12, 16, 12);
        layout.addView(editX);

        TextView labelY = new TextView(ctx);
        labelY.setText(R.string.calib_label_y);
        labelY.setTextColor(ContextCompat.getColor(ctx, R.color.grey_pale));
        labelY.setPadding(0, pad / 2, 0, 4);
        layout.addView(labelY);

        EditText editY = new EditText(ctx);
        editY.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        editY.setText(String.format(Locale.US, "%.2f", startY));
        editY.setTextColor(ContextCompat.getColor(ctx, R.color.white));
        editY.setBackgroundColor(ContextCompat.getColor(ctx, R.color.grey_dark));
        editY.setPadding(16, 12, 16, 12);
        layout.addView(editY);

        TextView tvTxPower = new TextView(ctx);
        tvTxPower.setText(ctx.getString(R.string.calib_tx_power, (int) calibratedTxPower[0]));
        tvTxPower.setTextColor(ContextCompat.getColor(ctx, R.color.grey_pale));
        tvTxPower.setPadding(0, pad, 0, 4);
        layout.addView(tvTxPower);

        // Held at method scope so the dialog's dismiss listener can cancel any
        // pending sampling callbacks (otherwise they keep firing for up to 5s
        // and touch views of an already-closed dialog).
        final Runnable[] sampleRunnable = new Runnable[1];

        P2BeaconSample sample = beaconMap.get(compositeId);

        TextView tvLiveRssi = new TextView(ctx);
        int liveRssi = sample != null ? sample.getLastRawRssi() : 0;
        tvLiveRssi.setText(ctx.getString(R.string.calib_current_rssi, liveRssi));
        tvLiveRssi.setTextColor(ContextCompat.getColor(ctx, R.color.grey_mid));
        tvLiveRssi.setTextSize(12f);
        layout.addView(tvLiveRssi);

        ProgressBar progressBar = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setVisibility(View.GONE);
        layout.addView(progressBar);

        Button btnCalibrate = new Button(ctx);
        btnCalibrate.setText(R.string.calib_btn_calibrate);
        btnCalibrate.setTextSize(13f);
        btnCalibrate.setAllCaps(false);

        btnCalibrate.setOnClickListener(v -> {
            btnCalibrate.setEnabled(false);
            btnCalibrate.setText(R.string.calib_btn_sampling);
            progressBar.setVisibility(View.VISIBLE);
            progressBar.setProgress(0);

            final List<Integer> rssiSamples = new ArrayList<>();
            final long sampleDurationMs = 5000;
            final long sampleIntervalMs = 200;
            final int totalSteps = (int) (sampleDurationMs / sampleIntervalMs);

            final int[] step = {0};
            sampleRunnable[0] = () -> {
                P2BeaconSample s = beaconMap.get(compositeId);
                if (s != null && s.getLastRawRssi() != 0) {
                    rssiSamples.add(s.getLastRawRssi());
                }
                step[0]++;
                progressBar.setProgress((step[0] * 100) / totalSteps);

                if (step[0] < totalSteps) {
                    handler.postDelayed(sampleRunnable[0], sampleIntervalMs);
                } else {
                    progressBar.setVisibility(View.GONE);
                    if (!rssiSamples.isEmpty()) {
                        int sum = 0;
                        for (int r : rssiSamples) sum += r;
                        calibratedTxPower[0] = (double) sum / rssiSamples.size();
                        tvTxPower.setText(ctx.getString(
                                R.string.calib_tx_power_calibrated, (int) calibratedTxPower[0]));
                    }
                    btnCalibrate.setEnabled(true);
                    btnCalibrate.setText(R.string.calib_btn_calibrate);
                }
            };
            handler.post(sampleRunnable[0]);
        });
        layout.addView(btnCalibrate);

        AlertDialog.Builder builder = new AlertDialog.Builder(ctx, android.R.style.Theme_DeviceDefault_Dialog)
                .setTitle(ctx.getString(R.string.calib_title, major, minor))
                .setView(layout)
                .setPositiveButton(R.string.calib_save, (dialog, which) -> {
                    double newX, newY;
                    try {
                        newX = Double.parseDouble(editX.getText().toString().trim());
                        newY = Double.parseDouble(editY.getText().toString().trim());
                    } catch (NumberFormatException e) {
                        Toast.makeText(ctx, R.string.calib_invalid_coords, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    int majorInt = 0, minorInt = 0;
                    try { majorInt = Integer.parseInt(major); } catch (NumberFormatException ignored) {}
                    try { minorInt = Integer.parseInt(minor); } catch (NumberFormatException ignored) {}

                    Beacon beacon = new Beacon(uuid, majorInt, minorInt, newX, newY,
                            calibratedTxPower[0], pathLossN);
                    calibrationStore.saveBeacon(beacon);

                    P2BeaconSample existing = beaconMap.get(compositeId);
                    if (existing != null) {
                        existing.setCoordinates(newX, newY);
                    }
                    if (onChanged != null) onChanged.onChanged();
                    Toast.makeText(ctx, ctx.getString(R.string.calib_saved, major, minor),
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.calib_cancel, null);

        if (saved != null) {
            builder.setNeutralButton(R.string.calib_delete, (dialog, which) -> {
                calibrationStore.removeBeacon(compositeId);
                if (onChanged != null) onChanged.onChanged();
                Toast.makeText(ctx, R.string.calib_removed, Toast.LENGTH_SHORT).show();
            });
        }

        AlertDialog dialog = builder.create();
        dialog.setOnDismissListener(d -> {
            if (sampleRunnable[0] != null) handler.removeCallbacks(sampleRunnable[0]);
        });
        dialog.show();
    }
}
