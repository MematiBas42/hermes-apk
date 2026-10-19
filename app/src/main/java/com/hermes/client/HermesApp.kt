package com.hermes.client

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.hermes.client.data.remote.NtfyPushController
import com.hermes.client.notifications.RunCompletionNotifier
import com.hermes.client.worker.WorkScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class HermesApp : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var notifier: RunCompletionNotifier
    @Inject lateinit var workScheduler: WorkScheduler
    @Inject lateinit var imageLoader: ImageLoader
    @Inject lateinit var appVisibility: AppVisibility
    @Inject lateinit var ntfyPush: NtfyPushController

    override fun newImageLoader(): ImageLoader = imageLoader

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        instance = this
        registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                val wasBackground = !appVisibility.isForeground
                appVisibility.onActivityStarted()
                if (wasBackground && appVisibility.isForeground) ntfyPush.setForeground(true)
            }
            override fun onActivityStopped(activity: Activity) {
                appVisibility.onActivityStopped()
                if (!appVisibility.isForeground) ntfyPush.setForeground(false)
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        })
        notifier.ensureChannel()
        // Foreground ntfy subscription for instant cron-job pushes (no-op until configured).
        runCatching { ntfyPush.start() }
        // Fallback polling so notifications still land while the app is killed.
        runCatching { workScheduler.ensurePeriodicReconcile() }
    }

    companion object {
        lateinit var instance: HermesApp
            private set

        fun string(@androidx.annotation.StringRes resId: Int, vararg formatArgs: Any?): String =
            if (::instance.isInitialized) {
                val ctx = ContextCompat.getContextForLanguage(instance)
                if (formatArgs.isEmpty()) ctx.getString(resId) else ctx.getString(resId, *formatArgs)
            } else ""
    }
}
