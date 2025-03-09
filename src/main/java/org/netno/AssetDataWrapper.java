package org.netno;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

public class AssetDataWrapper {

    private final Map<String, TradeInfo> currentAssets;
    private final boolean stopLossMarker;

    @JsonCreator
    public AssetDataWrapper(
            @JsonProperty("currentAssets") Map<String, TradeInfo> currentAssets,
            @JsonProperty("stopLossMarker") boolean stopLossMarker) {
        this.currentAssets = currentAssets;
        this.stopLossMarker = stopLossMarker;
    }

    public Map<String, TradeInfo> getCurrentAssets() {
        return currentAssets;
    }

    public boolean isStopLossMarker() {
        return stopLossMarker;
    }
}