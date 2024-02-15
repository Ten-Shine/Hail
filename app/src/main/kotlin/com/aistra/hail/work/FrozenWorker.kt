package com.aistra.hail.work

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.aistra.hail.app.AppManager
import com.aistra.hail.app.HailData

class FrozenWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        inputData.getString(HailData.KEY_PACKAGE)?.let {
            val workingMode = HailData.checkedList.find { appInfo -> appInfo.packageName == it }
                ?.workingMode.takeUnless { it == HailData.MODE_DEFAULT } ?: HailData.workingMode
            AppManager.setAppFrozen(
                it,
                inputData.getBoolean(HailData.KEY_FROZEN, true),
                workingMode
            )
            return Result.success()
        }
        return Result.failure()
    }
}