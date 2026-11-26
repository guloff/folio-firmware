package com.rustamguloff.foliolink.ui.dashboard

import com.rustamguloff.foliolink.app.AppModel
import com.rustamguloff.foliolink.ui.dashboard.DashboardLogic.Battery
import com.rustamguloff.foliolink.ui.dashboard.DashboardLogic.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardLogicTest {
    @Test fun batteryBucketsMatchIos() {
        assertEquals(Battery.EMPTY, DashboardLogic.battery(12, false))
        assertEquals(Battery.QUARTER, DashboardLogic.battery(13, false))
        assertEquals(Battery.HALF, DashboardLogic.battery(38, false))
        assertEquals(Battery.THREE_QUARTERS, DashboardLogic.battery(87, false))
        assertEquals(Battery.FULL, DashboardLogic.battery(88, false))
        assertEquals(Battery.CHARGING, DashboardLogic.battery(5, true))
    }

    @Test fun statusLine() {
        assertEquals(Status.DEMO, DashboardLogic.status(AppModel.Connection.Connected, isDemo = true, hasKnownDevice = true))
        assertEquals(Status.CONNECTED, DashboardLogic.status(AppModel.Connection.Connected, false, true))
        assertEquals(Status.FAILED, DashboardLogic.status(AppModel.Connection.Failed("x"), false, true))
        assertEquals(Status.OFFLINE, DashboardLogic.status(AppModel.Connection.Disconnected, false, true))
        assertEquals(Status.NOT_CONNECTED, DashboardLogic.status(AppModel.Connection.Disconnected, false, false))
        assertEquals(Status.CONNECTING, DashboardLogic.status(AppModel.Connection.Connecting, false, false))
    }

    @Test fun welcomeOnlyOnFirstRun() {
        assertTrue(DashboardLogic.showWelcome(hasKnownDevice = false, connected = false))
        assertFalse(DashboardLogic.showWelcome(hasKnownDevice = true, connected = false))
        assertFalse(DashboardLogic.showWelcome(hasKnownDevice = false, connected = true))
    }

    @Test fun outboxPreview() {
        assertEquals(2 to 0, DashboardLogic.outboxPreview(2))
        assertEquals(3 to 4, DashboardLogic.outboxPreview(7))
    }

    @Test fun sdUsage() {
        assertNull(DashboardLogic.sdUsed(0, 0))
        assertEquals(0.75f, DashboardLogic.sdUsed(1000, 250)!!, 1e-6f)
        assertEquals(0f, DashboardLogic.sdUsed(1000, 2000)!!, 1e-6f)
    }
}
