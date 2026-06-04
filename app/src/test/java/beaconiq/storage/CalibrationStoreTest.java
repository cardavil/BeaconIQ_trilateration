package beaconiq.storage;

import static android.content.Context.MODE_PRIVATE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import beaconiq.model.Beacon;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class CalibrationStoreTest {

    private static final String TEST_UUID = "550e8400-e29b-41d4-a716-446655440000";

    private Context context;
    private CalibrationStore store;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        store = new CalibrationStore(context);
        store.clear();
    }

    @Test
    public void emptyStoreReturnsEmptyList() {
        List<Beacon> beacons = store.getAllBeacons();
        assertThat(beacons).isNotNull().isEmpty();
    }

    @Test
    public void saveBeaconReturnsTrueForNew() {
        Beacon b = new Beacon(TEST_UUID, 1, 1, 0.0, 0.0, -58.0, 2.4);
        assertThat(store.saveBeacon(b)).isTrue();
        assertThat(store.getAllBeacons()).hasSize(1);
        assertThat(store.getBeacon(b.getCompositeId())).isEqualTo(b);
    }

    @Test
    public void saveBeaconReturnsFalseForReplace() {
        Beacon b = new Beacon(TEST_UUID, 1, 1, 0.0, 0.0, -58.0, 2.4);
        assertThat(store.saveBeacon(b)).isTrue();
        assertThat(store.saveBeacon(b)).isFalse();
        assertThat(store.getAllBeacons()).hasSize(1);
    }

    @Test
    public void saveBeaconReplacesByCompositeId() {
        Beacon b1 = new Beacon(TEST_UUID, 1, 1, 0.0, 0.0, -58.0, 2.4);
        Beacon b1Updated = new Beacon(TEST_UUID, 1, 1, 5.0, 5.0, -58.0, 2.4);
        store.saveBeacon(b1);
        store.saveBeacon(b1Updated);
        assertThat(store.getAllBeacons()).hasSize(1);
        assertThat(store.getBeacon(b1.getCompositeId()).getX()).isEqualTo(5.0);
    }

    @Test
    public void multipleBeaconsPreserveInsertionOrder() {
        Beacon b1 = new Beacon(TEST_UUID, 1, 1, 0.0, 0.0, -58.0, 2.4);
        Beacon b2 = new Beacon(TEST_UUID, 2, 1, 1.0, 0.0, -60.0, 2.2);
        Beacon b3 = new Beacon(TEST_UUID, 3, 1, 0.0, 1.0, -55.0, 2.6);
        store.saveBeacon(b1);
        store.saveBeacon(b2);
        store.saveBeacon(b3);
        List<Beacon> all = store.getAllBeacons();
        assertThat(all).containsExactly(b1, b2, b3);
    }

    @Test
    public void removeBeaconReturnsTrueWhenPresent() {
        Beacon b1 = new Beacon(TEST_UUID, 1, 1, 0.0, 0.0, -58.0, 2.4);
        store.saveBeacon(b1);
        assertThat(store.removeBeacon(b1.getCompositeId())).isTrue();
        assertThat(store.getAllBeacons()).isEmpty();
    }

    @Test
    public void removeBeaconReturnsFalseWhenAbsent() {
        assertThat(store.removeBeacon("nonexistent:1:1")).isFalse();
    }

    @Test
    public void getBeaconByCompositeIdReturnsNullWhenAbsent() {
        assertThat(store.getBeacon("nonexistent:1:1")).isNull();
    }

    @Test
    public void getBeaconByUuidMajorMinorWorks() {
        Beacon b1 = new Beacon(TEST_UUID, 1, 5, 0.0, 0.0, -58.0, 2.4);
        store.saveBeacon(b1);
        assertThat(store.getBeacon(TEST_UUID, 1, 5)).isEqualTo(b1);
        assertThat(store.getBeacon(TEST_UUID, 1, 6)).isNull();
    }

    @Test
    public void clearRemovesAllBeacons() {
        store.saveBeacon(new Beacon(TEST_UUID, 1, 1, 0.0, 0.0, -58.0, 2.4));
        store.saveBeacon(new Beacon(TEST_UUID, 2, 1, 1.0, 0.0, -60.0, 2.2));
        store.saveBeacon(new Beacon(TEST_UUID, 3, 1, 0.0, 1.0, -55.0, 2.6));
        store.clear();
        assertThat(store.getAllBeacons()).isEmpty();
    }

    @Test
    public void dataPersistsAcrossInstances() {
        Beacon b1 = new Beacon(TEST_UUID, 1, 1, 0.0, 0.0, -58.0, 2.4);
        store.saveBeacon(b1);
        CalibrationStore store2 = new CalibrationStore(context);
        assertThat(store2.getAllBeacons()).containsExactly(b1);
    }

    @Test
    public void malformedJsonReturnsEmptyList() {
        context.getSharedPreferences(CalibrationStore.PREFS_NAME, MODE_PRIVATE)
                .edit().putString(CalibrationStore.KEY_BEACONS, "not valid json {[").commit();
        CalibrationStore freshStore = new CalibrationStore(context);
        assertThat(freshStore.getAllBeacons()).isEmpty();
    }

    @Test
    public void nullContextThrows() {
        assertThatThrownBy(() -> new CalibrationStore(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("context");
    }

    @Test
    public void nullBeaconInSaveThrows() {
        assertThatThrownBy(() -> store.saveBeacon(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("beacon");
    }

    @Test
    public void nullCompositeIdInRemoveThrows() {
        assertThatThrownBy(() -> store.removeBeacon(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    public void nullCompositeIdInGetThrows() {
        assertThatThrownBy(() -> store.getBeacon((String) null))
                .isInstanceOf(NullPointerException.class);
    }
}
