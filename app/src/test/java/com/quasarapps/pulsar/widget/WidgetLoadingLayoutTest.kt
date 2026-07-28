package com.quasarapps.pulsar.widget

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import androidx.test.core.app.ApplicationProvider
import com.quasarapps.pulsar.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards the widget's pre-render placeholder against regressing to an invisible state.
 *
 * The launcher shows `R.layout.widget_loading` from placement until Glance posts the first real
 * render — and because Glance renders inside a WorkManager job, "until" can be forever when that job
 * is deferred or blocked (OEM battery management, a broken WorkManager). Glance posts no error layout
 * in that state; whatever this layout shows IS the widget. It shipped once as an empty transparent
 * FrameLayout, which turned exactly that failure state into fully invisible widgets on a user's
 * device. These assertions pin the two properties that make the pending state visible: an opaque
 * background and a visible child.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetLoadingLayoutTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun inflate(): ViewGroup =
        LayoutInflater.from(context).inflate(R.layout.widget_loading, null) as ViewGroup

    @Test
    fun placeholder_hasAnOpaqueBackground() {
        val background = inflate().background
        assertNotNull("the placeholder must draw a background", background)
        assertTrue(
            "expected the shape drawable, was ${background!!.javaClass.simpleName}",
            background is GradientDrawable,
        )
        val solid = (background as GradientDrawable).color
        assertNotNull("the background must have a solid fill", solid)
        assertEquals(
            "the fill must be fully opaque",
            0xFF,
            solid!!.defaultColor ushr 24,
        )
    }

    @Test
    fun placeholder_showsAVisibleSpinner() {
        val root = inflate()
        assertTrue("the placeholder must not be an empty frame", root.childCount > 0)
        val spinner = (0 until root.childCount)
            .map(root::getChildAt)
            .filterIsInstance<ProgressBar>()
            .firstOrNull()
        assertNotNull("the placeholder must contain a progress spinner", spinner)
        assertEquals(View.VISIBLE, spinner!!.visibility)
        assertTrue("the spinner must be indeterminate", spinner.isIndeterminate)
    }
}
