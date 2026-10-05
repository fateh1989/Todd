package com.todd

import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.todd.core.model.Project
import com.todd.core.tools.GitHubCredentialStore
import com.todd.data.local.ToddDatabase
import com.todd.data.repository.ToddRepository
import com.todd.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ToddDeviceSmokeTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun applicationLaunchesAndCoreAndroidServicesArePackaged() {
        assertEquals("com.todd", context.packageName)

        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val activity = instrumentation.startActivitySync(intent)
        assertNotNull(activity)
        activity.finish()

        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SERVICES
        )
        val serviceNames = info.services.orEmpty().map { it.name }.toSet()

        val expected = listOf(
            "com.todd.service.ime.ToddInputMethodService",
            "com.todd.service.accessibility.ToddAccessibilityService",
            "com.todd.service.overlay.FloatingToddService",
            "com.todd.service.screen.ScreenCaptureService",
            "com.todd.service.notification.ToddNotificationListenerService"
        )

        expected.forEach { serviceName ->
            assertTrue("Missing packaged Android service: $serviceName", serviceName in serviceNames)
        }
    }

    @Test
    fun githubCredentialStoreRoundTripsThroughAndroidKeystore() {
        val store = GitHubCredentialStore(context)
        store.clearToken()

        val marker = "github_pat_device_smoke_only"
        store.saveToken(marker)
        assertTrue(store.hasToken())
        assertEquals(marker, store.getToken())

        store.clearToken()
        assertTrue(!store.hasToken())
    }

    @Test
    fun roomProjectStatePersistsAcrossRepositoryInstances() = runBlocking {
        val database = ToddDatabase.getDatabase(context)
        val repository = ToddRepository(database)
        val id = "device-smoke-project"

        repository.saveProject(
            Project(
                id = id,
                name = "Device Smoke",
                description = "Instrumentation persistence check",
                repository = "fateh1989/Todd",
                branch = "main",
                currentGoal = "Verify Android runtime persistence"
            )
        )

        val reloadedRepository = ToddRepository(ToddDatabase.getDatabase(context))
        val project = reloadedRepository.getProjectById(id)

        assertNotNull(project)
        assertEquals("Device Smoke", project?.name)
        assertEquals("fateh1989/Todd", project?.repository)
    }
}
