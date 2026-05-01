package com.izzy2lost.nin64

import android.app.Activity
import android.app.Application
import android.os.Bundle

class Nin64Application : Application(), Application.ActivityLifecycleCallbacks {
    private var currentActivity: Activity? = null
    private var startedActivityCount = 0

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(this)
        AppOpenAdManager.loadAd(this)
    }

    override fun onActivityStarted(activity: Activity) {
        val appWasBackgrounded = startedActivityCount == 0
        startedActivityCount += 1

        if (!AppOpenAdManager.isShowingAd()) {
            currentActivity = activity
        }

        if (appWasBackgrounded) {
            showAppOpenAdIfAppropriate(activity)
        }
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (currentActivity === activity) {
            currentActivity = null
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private fun showAppOpenAdIfAppropriate(activity: Activity) {
        if (activity is GameActivity) {
            AppOpenAdManager.loadAd(activity)
            return
        }

        AppOpenAdManager.showAdIfAvailable(activity) {
            AppOpenAdManager.loadAd(activity)
        }
    }
}
