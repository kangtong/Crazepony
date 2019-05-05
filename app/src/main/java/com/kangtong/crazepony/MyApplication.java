package com.kangtong.crazepony;

import android.app.Application;

import com.tencent.bugly.Bugly;

public class MyApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Bugly.init(getApplicationContext(), "903b235f2f", false);

    }
}
