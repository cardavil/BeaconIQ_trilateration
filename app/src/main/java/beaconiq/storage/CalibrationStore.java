package beaconiq.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import beaconiq.model.Beacon;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

public class CalibrationStore {

    public static final String PREFS_NAME = "beaconiq_calibration";
    public static final String KEY_BEACONS = "beacons_json";

    private static final String TAG = "CalibrationStore";

    private final Context appContext;

    public CalibrationStore(Context context) {
        Objects.requireNonNull(context, "context must not be null");
        this.appContext = context.getApplicationContext();
    }

    public List<Beacon> getAllBeacons() {
        String json = getPrefs().getString(KEY_BEACONS, null);
        if (json == null) {
            return new ArrayList<>();
        }
        try {
            JSONObject root = new JSONObject(json);
            JSONArray array = root.getJSONArray("beacons");
            List<Beacon> result = new ArrayList<>(array.length());
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                result.add(new Beacon(
                        obj.getString("uuid"),
                        obj.getInt("major"),
                        obj.getInt("minor"),
                        obj.getDouble("x"),
                        obj.getDouble("y"),
                        obj.getDouble("txPower"),
                        obj.getDouble("pathLossN")
                ));
            }
            return result;
        } catch (JSONException e) {
            Log.w(TAG, "Failed to parse stored beacons, returning empty list", e);
            return new ArrayList<>();
        }
    }

    public boolean saveBeacon(Beacon beacon) {
        Objects.requireNonNull(beacon, "beacon must not be null");
        LinkedHashMap<String, Beacon> map = toMap(getAllBeacons());
        boolean isNew = !map.containsKey(beacon.getCompositeId());
        map.put(beacon.getCompositeId(), beacon);
        persist(new ArrayList<>(map.values()));
        return isNew;
    }

    public boolean removeBeacon(String compositeId) {
        Objects.requireNonNull(compositeId, "compositeId must not be null");
        LinkedHashMap<String, Beacon> map = toMap(getAllBeacons());
        boolean removed = map.remove(compositeId) != null;
        if (removed) {
            persist(new ArrayList<>(map.values()));
        }
        return removed;
    }

    public Beacon getBeacon(String compositeId) {
        Objects.requireNonNull(compositeId, "compositeId must not be null");
        for (Beacon b : getAllBeacons()) {
            if (b.getCompositeId().equals(compositeId)) {
                return b;
            }
        }
        return null;
    }

    public Beacon getBeacon(String uuid, int major, int minor) {
        Beacon key = new Beacon(uuid, major, minor, 0, 0, -59, 2.0);
        return getBeacon(key.getCompositeId());
    }

    public void clear() {
        getPrefs().edit().remove(KEY_BEACONS).apply();
    }

    private SharedPreferences getPrefs() {
        return appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private void persist(List<Beacon> beacons) {
        try {
            JSONArray array = new JSONArray();
            for (Beacon b : beacons) {
                JSONObject obj = new JSONObject();
                obj.put("uuid", b.getUuid());
                obj.put("major", b.getMajor());
                obj.put("minor", b.getMinor());
                obj.put("x", b.getX());
                obj.put("y", b.getY());
                obj.put("txPower", b.getTxPower());
                obj.put("pathLossN", b.getPathLossN());
                array.put(obj);
            }
            JSONObject root = new JSONObject();
            root.put("beacons", array);
            getPrefs().edit().putString(KEY_BEACONS, root.toString()).apply();
        } catch (JSONException e) {
            Log.w(TAG, "Failed to serialize beacons", e);
        }
    }

    private static LinkedHashMap<String, Beacon> toMap(List<Beacon> beacons) {
        LinkedHashMap<String, Beacon> map = new LinkedHashMap<>();
        for (Beacon b : beacons) {
            map.put(b.getCompositeId(), b);
        }
        return map;
    }
}
