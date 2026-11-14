package com.stv.launcher.sdk.plugin;

/** 独立 APK 里只需要类型存在；桌面插件运行时用的是 launcher 自带的同名接口。 */
public interface ActivityActionHandler {
    Object onActivityAction(int actionId, Object obj);
}
