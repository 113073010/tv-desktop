package android.support.v4.app;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;

/** 仅用于编译，不会打进 dex。运行时解析到桌面自带的 support-v4。 */
public class Fragment {
    public Activity getActivity() { return null; }
    public void onAttach(Activity activity) { }
    public void onDetach() { }
    public View onCreateView(android.view.LayoutInflater inflater, android.view.ViewGroup container, Bundle savedInstanceState) { return null; }
    public void onViewCreated(View view, Bundle savedInstanceState) { }
    public void onDestroyView() { }
    public final Bundle getArguments() { return null; }
    public final void setArguments(Bundle args) { }
    public final Object getTag() { return null; }
}
