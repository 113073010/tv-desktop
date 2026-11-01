package com.stv.launcher.sdk.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.stv.launcher.sdk.plugin.ActivityActionHandler;

/** 仅用于编译的桩：签名与桌面里的 BaseFragment 保持一致，运行时用桌面的真实类。 */
public abstract class BaseFragment extends android.support.v4.app.Fragment implements ActivityActionHandler {

    public View mContainer;
    public String pluginPkgName;
    public String tag;

    public abstract boolean onFocusRequested(int direction);

    public abstract void onFragmentSeletedPre(boolean selected);

    public abstract void onFragmentShowChanged(boolean shown);

    public abstract boolean onHomeKeyEventHandled();

    public abstract View onInflaterContent(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState);

    public abstract void setHoverListener(View.OnHoverListener listener);

    @Override
    public Object onActivityAction(int actionId, Object obj) { return null; }

    public void setContentDescendantFocusability(int focusability) { }

    public View getContainer() { return mContainer; }
}
