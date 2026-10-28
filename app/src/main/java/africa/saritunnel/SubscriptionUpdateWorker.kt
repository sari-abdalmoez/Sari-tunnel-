package africa.saritunnel

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Refreshes the Blogger VLESS feed once every 24 hours. A failed refresh keeps the cached servers. */
class SubscriptionUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return when (VlessSubscriptionManager(applicationContext).refresh()) {
            is VlessSubscriptionManager.RefreshResult.Ok -> Result.success()
            is VlessSubscriptionManager.RefreshResult.Error -> Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "vless_subscription_update"

        fun schedulePeriodicUpdates(context: Context) {
            val request = PeriodicWorkRequestBuilder<SubscriptionUpdateWorker>(24, TimeUnit.HOURS, 2, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            // UPDATE replaces the 8-hour schedule created by earlier builds.
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request
            )
        }

        fun cancelUpdates(context: Context) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK_NAME)
        }
    }
}
