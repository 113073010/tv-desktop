package com.dsh.tvdesk.news.app;

import com.dsh.tvdesk.news.NewsFragment;
import com.stv.launcher.sdk.fragment.BaseFragment;

/** 独立的「新闻」应用：复用原桌面插件的页面，只是把宿主从 launcher Tab 换成普通 Activity。 */
public class NewsActivity extends com.dsh.tvdesk.app.HostActivity implements NewsFragment.Host {

    @Override
    protected BaseFragment createFragment() {
        return new NewsFragment();
    }

    /** 页面里按返回键退到分类条后，再按一次就退出应用。 */
    @Override
    public void onHostBack() {
        finish();
    }
}
