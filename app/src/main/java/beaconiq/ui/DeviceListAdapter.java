package beaconiq.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import beaconiq.R;
import beaconiq.scan.BleDevice;

import org.altbeacon.beacon.Beacon;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DeviceListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VIEW_TYPE_HEADER = 0;
    private static final int VIEW_TYPE_IBEACON = 1;
    private static final int VIEW_TYPE_GENERIC = 2;

    private final List<Beacon> beacons = new ArrayList<>();
    private final Map<String, Long> beaconLastSeen = new HashMap<>();
    private final List<BleDevice> genericDevices = new ArrayList<>();

    private double pathLossN = 2.0;
    private int defaultTxPower = -59;
    private int rssiGoodThreshold = -60;
    private int rssiPoorThreshold = -80;

    public void setPathLossN(double n) {
        this.pathLossN = n;
        notifyDataSetChanged();
    }

    public void setDefaultTxPower(int txPower) {
        this.defaultTxPower = txPower;
        notifyDataSetChanged();
    }

    public void setRssiThresholds(int good, int poor) {
        this.rssiGoodThreshold = good;
        this.rssiPoorThreshold = poor;
        notifyDataSetChanged();
    }

    public List<Beacon> getBeacons() {
        return new ArrayList<>(beacons);
    }

    public void updateBeacon(Beacon beacon) {
        String id = beaconCompositeId(beacon);
        long now = System.currentTimeMillis();
        beaconLastSeen.put(id, now);
        for (int i = 0; i < beacons.size(); i++) {
            if (beaconCompositeId(beacons.get(i)).equals(id)) {
                beacons.set(i, beacon);
                sortAndNotify();
                return;
            }
        }
        beacons.add(beacon);
        sortAndNotify();
    }

    public void updateDevice(BleDevice device) {
        for (int i = 0; i < genericDevices.size(); i++) {
            if (genericDevices.get(i).getMacAddress().equals(device.getMacAddress())) {
                genericDevices.set(i, device);
                sortAndNotify();
                return;
            }
        }
        genericDevices.add(device);
        sortAndNotify();
    }

    public void removeStaleDevices(long maxAgeMs) {
        long cutoff = System.currentTimeMillis() - maxAgeMs;
        boolean removedBeacons = beacons.removeIf(b -> {
            Long lastSeen = beaconLastSeen.get(beaconCompositeId(b));
            return lastSeen != null && lastSeen < cutoff;
        });
        boolean removedGeneric = genericDevices.removeIf(d -> d.getLastSeenMs() < cutoff);
        if (removedBeacons || removedGeneric) {
            notifyDataSetChanged();
        }
    }

    private void sortAndNotify() {
        Collections.sort(beacons, (a, b) -> Integer.compare(b.getRssi(), a.getRssi()));
        Collections.sort(genericDevices, (a, b) -> Long.compare(b.getLastSeenMs(), a.getLastSeenMs()));
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return 2 + beacons.size() + genericDevices.size();
    }

    @Override
    public int getItemViewType(int position) {
        if (position == 0) return VIEW_TYPE_HEADER;
        if (position <= beacons.size()) return VIEW_TYPE_IBEACON;
        if (position == 1 + beacons.size()) return VIEW_TYPE_HEADER;
        return VIEW_TYPE_GENERIC;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case VIEW_TYPE_IBEACON: {
                View view = inflater.inflate(R.layout.item_ibeacon, parent, false);
                return new IBeaconViewHolder(view);
            }
            case VIEW_TYPE_GENERIC: {
                View view = inflater.inflate(R.layout.item_device, parent, false);
                return new DeviceViewHolder(view);
            }
            default: {
                View view = inflater.inflate(R.layout.item_section_header, parent, false);
                return new HeaderViewHolder(view);
            }
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (position == 0) {
            ((HeaderViewHolder) holder).text.setText(
                    "iBeacons (" + beacons.size() + ")");
            return;
        }
        if (position <= beacons.size()) {
            Beacon b = beacons.get(position - 1);
            IBeaconViewHolder h = (IBeaconViewHolder) holder;

            int major = b.getIdentifiers().size() >= 2 ? b.getId2().toInt() : 0;
            int minor = b.getIdentifiers().size() >= 3 ? b.getId3().toInt() : 0;
            h.summary.setText("iBeacon · major=" + major + " minor=" + minor);
            h.uuid.setText(b.getId1().toString());

            String id = beaconCompositeId(b);
            Long lastSeen = beaconLastSeen.get(id);
            long ageSeconds = lastSeen != null
                    ? (System.currentTimeMillis() - lastSeen) / 1000 : 0;
            String timeAgo = (ageSeconds < 2) ? "just now" : (ageSeconds + "s ago");

            double dist = Math.pow(10.0,
                    (b.getTxPower() - b.getRssi()) / (10.0 * pathLossN));
            h.signal.setText(b.getRssi() + " dBm · TxP " + b.getTxPower()
                    + " · ~" + String.format("%.1f", dist) + "m · " + timeAgo);
            h.signal.setTextColor(rssiColor(h.itemView, b.getRssi()));
            return;
        }
        if (position == 1 + beacons.size()) {
            ((HeaderViewHolder) holder).text.setText(
                    "Other BLE devices (" + genericDevices.size() + ")");
            return;
        }
        BleDevice device = genericDevices.get(position - 2 - beacons.size());
        DeviceViewHolder h = (DeviceViewHolder) holder;
        String displayName = device.getName() != null
                ? device.getName()
                : h.name.getContext().getString(R.string.device_unknown);
        h.name.setText(displayName);
        h.mac.setText(device.getMacAddress());
        long ageSeconds = (System.currentTimeMillis() - device.getLastSeenMs()) / 1000;
        String timeAgo = (ageSeconds < 2) ? "just now" : (ageSeconds + "s ago");
        double dist = Math.pow(10.0, (defaultTxPower - device.getRssi()) / (10.0 * pathLossN));
        h.rssi.setText(device.getRssi() + " dBm · ~" + String.format("%.1f", dist)
                + "m (est) · " + timeAgo);
        h.rssi.setTextColor(rssiColor(h.itemView, device.getRssi()));
    }

    private int rssiColor(View view, int rssi) {
        int colorRes;
        if (rssi >= rssiGoodThreshold) {
            colorRes = R.color.status_ok;
        } else if (rssi >= rssiPoorThreshold) {
            colorRes = R.color.status_warn;
        } else {
            colorRes = R.color.status_alert;
        }
        return ContextCompat.getColor(view.getContext(), colorRes);
    }

    private static String beaconCompositeId(Beacon b) {
        StringBuilder sb = new StringBuilder(b.getId1().toString());
        if (b.getIdentifiers().size() >= 2) sb.append(":").append(b.getId2());
        if (b.getIdentifiers().size() >= 3) sb.append(":").append(b.getId3());
        return sb.toString();
    }

    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        final TextView text;

        HeaderViewHolder(View itemView) {
            super(itemView);
            text = itemView.findViewById(R.id.section_header_text);
        }
    }

    static class IBeaconViewHolder extends RecyclerView.ViewHolder {
        final TextView summary;
        final TextView uuid;
        final TextView signal;

        IBeaconViewHolder(View itemView) {
            super(itemView);
            summary = itemView.findViewById(R.id.ibeacon_summary);
            uuid = itemView.findViewById(R.id.ibeacon_uuid);
            signal = itemView.findViewById(R.id.ibeacon_signal);
        }
    }

    static class DeviceViewHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView mac;
        final TextView rssi;

        DeviceViewHolder(View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.device_name);
            mac = itemView.findViewById(R.id.device_mac);
            rssi = itemView.findViewById(R.id.device_rssi);
        }
    }
}
