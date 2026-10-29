package dev.opencode.mobile

import android.content.Intent
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaunchSmokeTest {
    @Test fun launcherInstallsStartsAndSurvivesRecreation() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        assertNotNull("No launcher activity", intent)
        intent!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
            scenario.onActivity { activity ->
                val content = activity.findViewById<ViewGroup>(android.R.id.content)
                assertTrue("Compose content is missing", content.childCount > 0)
                assertTrue("Activity is finishing", !activity.isFinishing)
            }
            scenario.recreate()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }
}
