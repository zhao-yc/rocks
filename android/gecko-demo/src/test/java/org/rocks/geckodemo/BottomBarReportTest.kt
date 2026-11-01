package org.rocks.geckodemo

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BottomBarReportTest {
    private fun message() = JSONObject("""{"documentId":"document-1","viewport":{"left":0,"top":0,"width":400,"height":800},
        "bars":[{"left":0,"top":680,"right":400,"bottom":720,"color":[20,30,40]}]}""")

    @Test fun bottomMustMatchActualClippedEdgeRatherThanLayoutViewport() {
        val report = BottomBarReport.parse(message())!!
        assertEquals(0xFF141E28.toInt(), report.colorAt(720.0))
        assertEquals(report.colorAt(720.0), report.colorAt(724.0))
        assertNull(report.colorAt(725.0)); assertNull(report.colorAt(800.0))
        assertNull(report.colorAt(Double.NaN))
    }

    @Test fun rejectsNarrowFloatingButtonsAndNonVisibleGeometry() {
        val json = message()
        json.getJSONArray("bars").getJSONObject(0).put("left", 81)
        assertNull(BottomBarReport.parse(json)!!.colorAt(720.0))
        json.getJSONArray("bars").getJSONObject(0).put("left", 80)
        assertNotNull(BottomBarReport.parse(json)!!.colorAt(720.0))
        json.getJSONArray("bars").getJSONObject(0).put("top", 721)
        assertNull(BottomBarReport.parse(json)!!.colorAt(720.0))
    }

    @Test fun rejectsInvalidMessagesWithoutThrowing() {
        assertNull(BottomBarReport.parse("{}"))
        assertNull(BottomBarReport.parse(message().put("documentId", "")))
        val geometry = message(); geometry.getJSONObject("viewport").put("width", "400")
        assertNull(BottomBarReport.parse(geometry))
        val color = message(); color.getJSONArray("bars").getJSONObject(0).getJSONArray("color").put(0, 256)
        assertNull(BottomBarReport.parse(color))
        val fractional = message(); fractional.getJSONArray("bars").getJSONObject(0).getJSONArray("color").put(0, 1.5)
        assertNull(BottomBarReport.parse(fractional))
        val oversized = message(); repeat(33) { oversized.getJSONArray("bars").put(message().getJSONArray("bars").get(0)) }
        assertNull(BottomBarReport.parse(oversized))
    }

    @Test fun oldDocumentsSameUrlReloadsAndBackgroundTabsCannotRestoreAColor() {
        val gate = BottomBarDocumentGate()
        val first = BrowserPageScope(1, 1, "https://example.org")
        val reload = first.copy(revision = 2)
        gate.reset(first)
        assertTrue(gate.accepts(first, first, "document-1"))
        assertFalse(gate.accepts(first, first, "document-2"))
        assertFalse(gate.accepts(reload, first, "document-1"))
        assertFalse(gate.accepts(first.copy(tabId = 2), first, "document-1"))
        assertFalse(gate.accepts(null, first, "document-1"))
        gate.reset(reload)
        assertFalse(gate.accepts(reload, first, "document-1"))
        assertTrue(gate.accepts(reload, reload, "document-2"))
        gate.reset(null)
        assertFalse(gate.accepts(reload, reload, "document-2"))
    }
}
