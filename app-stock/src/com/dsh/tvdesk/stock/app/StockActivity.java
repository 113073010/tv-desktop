package com.dsh.tvdesk.stock.app;

import com.dsh.tvdesk.stock.StockFragment;
import com.stv.launcher.sdk.fragment.BaseFragment;

/** 独立的「股票」应用：复用原桌面插件的页面，只是把宿主从 launcher Tab 换成普通 Activity。 */
public class StockActivity extends com.dsh.tvdesk.app.HostActivity implements StockFragment.Host {

    @Override
    protected BaseFragment createFragment() {
        return new StockFragment();
    }

    /** 页面里按返回键退到分类条后，再按一次就退出应用。 */
    @Override
    public void onHostBack() {
        finish();
    }
}
