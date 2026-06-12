package beaconiq.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import beaconiq.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class BeaconCardAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    /** Notified when a card is tapped, so the host can open that beacon for review. */
    public interface OnBeaconCardTap {
        void onTap(String compositeId);
    }

    private static final int VIEW_TYPE_CLOSEST = 0;
    private static final int VIEW_TYPE_NORMAL = 1;

    private final List<BeaconCardItem> items = new ArrayList<>();
    private OnBeaconCardTap tapListener;

    public void setOnBeaconCardTap(OnBeaconCardTap listener) {
        this.tapListener = listener;
    }

    public void updateItems(List<BeaconCardItem> newItems) {
        items.clear();
        items.addAll(newItems);
        Collections.sort(items);
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return (position == 0 && !items.isEmpty() && items.get(0).isClosest())
                ? VIEW_TYPE_CLOSEST : VIEW_TYPE_NORMAL;
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        int layout = viewType == VIEW_TYPE_CLOSEST
                ? R.layout.item_beacon_card_closest
                : R.layout.item_beacon_card_normal;
        return new CardViewHolder(inflater.inflate(layout, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        BeaconCardItem item = items.get(position);
        ((CardViewHolder) holder).bind(item);
        holder.itemView.setOnClickListener(v -> {
            if (tapListener != null) tapListener.onTap(item.getCompositeId());
        });
    }

    private static int rssiColor(View view, int rssi) {
        int colorRes;
        if (rssi >= -60) colorRes = R.color.status_ok;
        else if (rssi >= -80) colorRes = R.color.status_warn;
        else colorRes = R.color.status_alert;
        return ContextCompat.getColor(view.getContext(), colorRes);
    }

    /** Single holder for both the closest and normal card layouts — they bind
     * the same four views; only the inflated layout (style) differs. */
    static class CardViewHolder extends RecyclerView.ViewHolder {
        final TextView label, distance, rssi, uuid;

        CardViewHolder(View v) {
            super(v);
            label = v.findViewById(R.id.beacon_card_label);
            distance = v.findViewById(R.id.beacon_card_distance);
            rssi = v.findViewById(R.id.beacon_card_rssi);
            uuid = v.findViewById(R.id.beacon_card_uuid);
        }

        void bind(BeaconCardItem item) {
            label.setText(itemView.getContext().getString(R.string.beacon_card_label, item.getLabel()));
            distance.setText(itemView.getContext()
                    .getString(R.string.beacon_card_distance, item.getFilteredDistanceMeters()));
            rssi.setText(itemView.getContext()
                    .getString(R.string.beacon_card_rssi, item.getLastRawRssi()));
            rssi.setTextColor(rssiColor(itemView, item.getLastRawRssi()));
            uuid.setText(item.getUuid());
        }
    }
}
