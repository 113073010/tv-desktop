package android.support.v4.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;

/**
 * 独立 APK 用的极简 Fragment 垫片。
 *
 * 插件里的页面之所以继承 Fragment，是因为乐视桌面用 support-v4 的 Fragment 承载插件。
 * 独立应用没有桌面提供的 support-v4，这里只实现页面实际用到的那几个方法：
 * getActivity / onAttach / onDetach / onDestroyView。
 */
public class Fragment {

    private Activity mActivity;

    public Activity getActivity() { return mActivity; }

    public void onAttach(Activity activity) { mActivity = activity; }

    public void onDetach() { mActivity = null; }

    public View onCreateView(android.view.LayoutInflater inflater, android.view.ViewGroup container,
                             Bundle savedInstanceState) {
        return null;
    }

    public void onViewCreated(View view, Bundle savedInstanceState) { }

    public void onDestroyView() { }

    public final Bundle getArguments() { return null; }

    public final void setArguments(Bundle args) { }

    public final Object getTag() { return null; }
}
