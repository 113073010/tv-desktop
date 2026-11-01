package com.dsh.tvdesk.douban;

import android.support.v4.app.Fragment;

import com.stv.launcher.sdk.plugin.api.ILePluginCreateApi;

/** 桌面通过 manifest 里的 meta-data "ILePluginCreateApi" 反射实例化这个类。 */
public class CreateApi implements ILePluginCreateApi {
    @Override
    public Fragment createFragment() {
        return new DoubanFragment();
    }
}
