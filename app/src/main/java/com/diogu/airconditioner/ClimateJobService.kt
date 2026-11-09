package com.diogu.airconditioner

import android.app.job.JobParameters
import android.app.job.JobService

/** On-demand work for widget actions. No foreground service or periodic background polling. */
class ClimateJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        ClimateController.get(this).processDue { jobFinished(params,false) }
        return true
    }
    // Unsent drafts remain in preferences. Never automatically replay a PUT already attempted.
    override fun onStopJob(params: JobParameters) = false
}
