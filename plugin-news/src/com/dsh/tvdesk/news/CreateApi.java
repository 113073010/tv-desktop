package com.dsh.tvdesk.news;

import android.support.v4.app.Fragment;

import com.stv.launcher.sdk.plugin.api.ILePluginCreateApi;

public class CreateApi implements ILePluginCreateApi {
    @Override
    public Fragment createFragment() {
        return new NewsFragment();
    }
}
