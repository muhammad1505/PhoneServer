package dev.pocketshare.data.settings

import dev.pocketshare.core.model.AppLanguage
import dev.pocketshare.core.model.ShareExpiry
import dev.pocketshare.core.model.TunnelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PortableSettingsTest {
    @Test
    fun `round trip keeps every portable field and the hostname`() {
        val settings =
            AppSettings(
                tunnelMode = TunnelMode.NAMED,
                port = 8_080,
                wifiOnly = true,
                autoStopMinutes = 30,
                defaultExpiry = ShareExpiry.DAYS_7,
                language = AppLanguage.INDONESIAN,
                allowLanAccess = true,
            )

        val encoded = PortableSettingsCodec.encode(settings, " files.example.com ")
        val decoded = PortableSettingsCodec.decode(encoded)

        assertEquals(settings, decoded?.merge(AppSettings()))
        assertEquals("files.example.com", decoded?.namedTunnelHostname)
    }

    @Test
    fun `schema 1 file without a hostname still imports`() {
        val raw =
            """
            {
              "app": "pocketshare",
              "schema": 1,
              "tunnelMode": "QUICK",
              "port": 45678,
              "wifiOnly": false,
              "autoStopMinutes": 0,
              "defaultExpiry": "HOURS_24",
              "language": "ENGLISH",
              "allowLanAccess": false
            }
            """.trimIndent()

        val decoded = PortableSettingsCodec.decode(raw)

        assertEquals(TunnelMode.QUICK, decoded?.tunnelMode)
        assertEquals(45_678, decoded?.port)
        assertEquals(AppLanguage.ENGLISH, decoded?.language)
        assertNull(decoded?.namedTunnelHostname)
    }

    @Test
    fun `unknown fields and a newer schema do not break the import`() {
        val raw =
            """
            {
              "app": "pocketshare",
              "schema": 99,
              "tunnelMode": "NAMED",
              "port": 8080,
              "namedTunnelHostname": "files.example.com",
              "futureSetting": true
            }
            """.trimIndent()

        val decoded = PortableSettingsCodec.decode(raw)

        assertEquals(TunnelMode.NAMED, decoded?.tunnelMode)
        assertEquals("files.example.com", decoded?.namedTunnelHostname)
    }

    @Test
    fun `merge keeps current values that the file omits`() {
        val current =
            AppSettings(
                tunnelMode = TunnelMode.NAMED,
                port = 9_999,
                wifiOnly = true,
                autoStopMinutes = 15,
                defaultExpiry = ShareExpiry.HOUR_1,
                language = AppLanguage.INDONESIAN,
                allowLanAccess = true,
            )
        val imported = PortableSettings(port = 8_080)

        val merged = imported.merge(current)

        assertEquals(8_080, merged.port)
        assertEquals(TunnelMode.NAMED, merged.tunnelMode)
        assertEquals(true, merged.wifiOnly)
        assertEquals(15, merged.autoStopMinutes)
        assertEquals(ShareExpiry.HOUR_1, merged.defaultExpiry)
        assertEquals(AppLanguage.INDONESIAN, merged.language)
        assertEquals(true, merged.allowLanAccess)
    }

    @Test
    fun `wrong app and malformed files are rejected`() {
        assertNull(PortableSettingsCodec.decode("""{"app":"other","schema":1}"""))
        assertNull(PortableSettingsCodec.decode("not json"))
        assertNull(PortableSettingsCodec.decode(""))
    }

    @Test
    fun `byte order mark is ignored`() {
        val raw = "\uFEFF" + """{"app":"pocketshare","schema":1,"language":"INDONESIAN"}"""

        assertEquals(AppLanguage.INDONESIAN, PortableSettingsCodec.decode(raw)?.language)
    }
}
