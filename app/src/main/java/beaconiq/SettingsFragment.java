package beaconiq;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

public class SettingsFragment extends Fragment {

    private TextView permStatusNearby, permStatusLocation;
    private Button btnRequestPermissions;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        permStatusNearby = view.findViewById(R.id.perm_status_nearby);
        permStatusLocation = view.findViewById(R.id.perm_status_location);
        btnRequestPermissions = view.findViewById(R.id.btn_request_permissions);

        btnRequestPermissions.setOnClickListener(v ->
                ((MainActivity) requireActivity()).requestBlePermissions());

        try {
            PackageInfo pInfo = requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0);
            ((TextView) view.findViewById(R.id.settings_version))
                    .setText("BeaconIQ v" + pInfo.versionName);
        } catch (PackageManager.NameNotFoundException ignored) {
        }

        view.findViewById(R.id.settings_github_link).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/cardavil/BeaconIQ_trilateration_model"));
            startActivity(intent);
        });

        updatePermissionStatus();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) updatePermissionStatus();
    }

    @Override
    public void onResume() {
        super.onResume();
        updatePermissionStatus();
    }

    public void refreshPermissions() {
        if (getView() != null) updatePermissionStatus();
    }

    private void updatePermissionStatus() {
        boolean allGranted = true;

        int colorOk = ContextCompat.getColor(requireContext(), R.color.status_ok);
        int colorAlert = ContextCompat.getColor(requireContext(), R.color.status_alert);

        if (Build.VERSION.SDK_INT >= 31) {
            boolean nearby = hasPerm(Manifest.permission.BLUETOOTH_SCAN)
                    && hasPerm(Manifest.permission.BLUETOOTH_CONNECT);
            permStatusNearby.setText(nearby
                    ? "Nearby Devices: ✓ Granted"
                    : "Nearby Devices: ✗ Not granted");
            permStatusNearby.setTextColor(nearby ? colorOk : colorAlert);
            if (!nearby) allGranted = false;
        } else {
            permStatusNearby.setText("Nearby Devices: ✓ Granted");
            permStatusNearby.setTextColor(colorOk);
        }

        boolean location = hasPerm(Manifest.permission.ACCESS_FINE_LOCATION);
        permStatusLocation.setText(location
                ? "Location: ✓ Granted"
                : "Location: ✗ Not granted");
        permStatusLocation.setTextColor(location ? colorOk : colorAlert);
        if (!location) allGranted = false;

        btnRequestPermissions.setVisibility(allGranted ? View.GONE : View.VISIBLE);
    }

    private boolean hasPerm(String permission) {
        return ContextCompat.checkSelfPermission(requireContext(), permission)
                == PackageManager.PERMISSION_GRANTED;
    }
}
