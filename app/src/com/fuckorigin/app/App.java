package com.fuckorigin.app;

import android.app.Application;
import android.content.Context;

/** 只为拿一个全局 Context（Pem 需要注册电池广播）。 */
public class App extends Application {
    private static App sInstance;
    @Override public void onCreate() { super.onCreate(); sInstance = this; }
    public static Context get() { return sInstance; }
}
