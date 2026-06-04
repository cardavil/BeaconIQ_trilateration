package beaconiq;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import android.widget.TextView;

import com.google.android.material.appbar.MaterialToolbar;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private MaterialToolbar toolbar;
    private TextView toolbarScreen;
    private ScanFragment scanFragment;
    private PhaseTwoTestFragment testFragmentP2;
    private SettingsFragment settingsFragment;
    private Fragment activeFragment;

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(),
                    this::onPermissionsResult);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, 0);
            return insets;
        });

        toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle("");
        toolbarScreen = findViewById(R.id.toolbar_screen);

        if (savedInstanceState == null) {
            scanFragment = new ScanFragment();

            testFragmentP2 = new PhaseTwoTestFragment();

            settingsFragment = new SettingsFragment();

            getSupportFragmentManager().beginTransaction()
                    .add(R.id.fragment_container, settingsFragment, "settings")
                    .hide(settingsFragment)
                    .add(R.id.fragment_container, testFragmentP2, "test_p2")
                    .hide(testFragmentP2)
                    .add(R.id.fragment_container, scanFragment, "scan")
                    .commit();

            activeFragment = scanFragment;
            setScreenName("Explore");
        } else {
            FragmentManager fm = getSupportFragmentManager();
            scanFragment = (ScanFragment) fm.findFragmentByTag("scan");
            testFragmentP2 = (PhaseTwoTestFragment) fm.findFragmentByTag("test_p2");
            settingsFragment = (SettingsFragment) fm.findFragmentByTag("settings");
            activeFragment = scanFragment;
            setScreenName("Explore");
        }

        toolbar.setOnMenuItemClickListener(item -> {
            Fragment target;
            String screen;
            int id = item.getItemId();
            if (id == R.id.nav_explore) {
                target = scanFragment;
                screen = "Explore";
            } else if (id == R.id.nav_recording_p2) {
                target = testFragmentP2;
                screen = "Signal Recording";
            } else if (id == R.id.nav_permissions) {
                target = settingsFragment;
                screen = "Permissions";
            } else {
                return false;
            }

            if (target != activeFragment) {
                getSupportFragmentManager().beginTransaction()
                        .hide(activeFragment)
                        .show(target)
                        .commit();
                activeFragment = target;
            }
            setScreenName(screen);
            return true;
        });

        checkAndRequestPermissions();
    }

    private void checkAndRequestPermissions() {
        String[] required = getRequiredPermissions();
        List<String> missing = new ArrayList<>();
        for (String perm : required) {
            if (ContextCompat.checkSelfPermission(this, perm)
                    != PackageManager.PERMISSION_GRANTED) {
                missing.add(perm);
            }
        }
        if (!missing.isEmpty()) {
            permissionLauncher.launch(missing.toArray(new String[0]));
        }
    }

    private void onPermissionsResult(Map<String, Boolean> results) {
        boolean allGranted = true;
        for (Boolean granted : results.values()) {
            if (!granted) {
                allGranted = false;
                break;
            }
        }
        if (scanFragment != null) scanFragment.onPermissionsResult(allGranted);
        if (settingsFragment != null) settingsFragment.refreshPermissions();
    }

    static String[] getRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
            };
        } else {
            return new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION
            };
        }
    }

    private void setScreenName(String name) {
        toolbarScreen.setText(" | " + name);
    }

    public void stopScannerForRecording() {
        if (scanFragment != null) scanFragment.stopScanForRecording();
    }

    public void resumeScannerAfterRecording() {
        if (scanFragment != null) scanFragment.resumeAfterRecording();
    }

    public void requestBlePermissions() {
        checkAndRequestPermissions();
    }
}
