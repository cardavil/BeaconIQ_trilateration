package beaconiq.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import beaconiq.R;
import beaconiq.model.Beacon;
import beaconiq.positioning.phase2.P2BeaconSample;
import beaconiq.storage.CalibrationStore;

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

                    // TX power is no longer calibrated per beacon (global param);
                    // store the default so the POJO stays valid — it is unused for TX.
                    Beacon beacon = new Beacon(uuid, majorInt, minorInt, newX, newY,
                            defaultTxPower, pathLossN);
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

        builder.create().show();
    }
}
