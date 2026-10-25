package com.fip.plugin.sdk;

public interface FipPlugin {
    String getName();

    String getVersion();

    String execute(String input);
}
