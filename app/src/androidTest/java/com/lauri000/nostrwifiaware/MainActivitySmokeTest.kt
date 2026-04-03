package com.lauri000.nostrwifiaware

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class MainActivitySmokeTest {
    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun localGramHeaderAndPageToggleRender() {
        activityRule.scenario.onActivity { activity ->
            val root = activity.window.decorView
            assertNotNull(findTextView(root, "LocalGram"))
            assertTrue(findTextView(root, "Take Photo")?.isShown == true)
            assertTrue(
                listOf("Connect", "Disconnect", "Connecting...").any { label ->
                    findTextView(root, label)?.isShown == true
                },
            )
            findTextView(root, "Settings")?.performClick()
        }

        Thread.sleep(200)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        activityRule.scenario.onActivity { activity ->
            val root = activity.window.decorView
            assertTrue(findTextView(root, "Transport Log")?.isShown == true)
            findTextView(root, "Feed")?.performClick()
        }

        Thread.sleep(200)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        activityRule.scenario.onActivity { activity ->
            val root = activity.window.decorView
            assertTrue(findTextView(root, "Take Photo")?.isShown == true)
        }
    }

    private fun findTextView(
        root: View,
        text: String,
    ): TextView? {
        if (root is TextView && root.text?.toString() == text) {
            return root
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                val match = findTextView(root.getChildAt(index), text)
                if (match != null) {
                    return match
                }
            }
        }
        return null
    }
}
