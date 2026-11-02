package com.dsh.tvdesk.stock;

import android.support.v4.app.Fragment;

import com.stv.launcher.sdk.plugin.api.ILePluginCreateApi;

/** 桌面通过 manifest 的 meta-data "ILePluginCreateApi" 反射实例化。 */
public class CreateApi implements ILePluginCreateApi {
    @Override
    public Fragment createFragment() {
        return new StockFragment();
    }
}
