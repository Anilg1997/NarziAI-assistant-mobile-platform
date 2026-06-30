package com.narzoai.assistant

import android.Manifest
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.assertion.ViewAssertions
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityUITest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA
    )

    @Test
    fun `activity should launch successfully`() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            assertNotNull(activity)
        }
    }

    @Test
    fun `chat recycler view should be visible`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            Espresso.onView(ViewMatchers.withId(R.id.chat_recycler_view))
                .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        }
    }

    @Test
    fun `mic button should be visible`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            Espresso.onView(ViewMatchers.withId(R.id.mic_button))
                .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        }
    }

    @Test
    fun `status indicator should be visible`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            Espresso.onView(ViewMatchers.withId(R.id.status_indicator))
                .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        }
    }

    @Test
    fun `toolbar should be visible`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            Espresso.onView(ViewMatchers.withId(R.id.toolbar))
                .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        }
    }

    @Test
    fun `welcome message should be displayed on launch`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            Espresso.onView(ViewMatchers.withText("NarzoAI"))
                .check(ViewAssertions.matches(ViewMatchers.isDisplayed()))
        }
    }

    @Test
    fun `mic button should be clickable`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            Espresso.onView(ViewMatchers.withId(R.id.mic_button))
                .check(ViewAssertions.matches(ViewMatchers.isClickable()))
        }
    }

    @Test
    fun `settings menu should be accessible`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            Espresso.openActionBarOverflowOrOptionsMenu(
                androidx.test.core.app.ApplicationProvider.getApplicationContext()
            )
        }
    }
}
