package org.netno;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

public class AssetDataWrapper {

    private final Map<String, TradeInfo> currentAssets;

    @JsonCreator
    public AssetDataWrapper(
            @JsonProperty("currentAssets") Map<String, TradeInfo> currentAssets) {
        this.currentAssets = currentAssets;
    }

    public Map<String, TradeInfo> getCurrentAssets() {
        return currentAssets;
    }
}