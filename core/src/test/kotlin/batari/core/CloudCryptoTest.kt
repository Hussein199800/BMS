package batari.core

import org.junit.Assert.*
import org.junit.Test

class CloudCryptoTest {
    @Test fun passwordIsEncryptedToOneRsa512Block() {
        val a = CloudCrypto.encryptPassword("secret-123")
        assertEquals(64, java.util.Base64.getDecoder().decode(a).size)
        assertFalse(a.contains("secret"))
        assertNotEquals(a, CloudCrypto.encryptPassword("secret-123")) // حشو عشوائي
    }

    @Test fun onlyLoginAndReadRoutesAllowed() {
        assertTrue(CloudCrypto.isAllowed("GET", "bluetooth/server/ess/baseInfo?macAddr=x&addrCode=1"))
        assertTrue(CloudCrypto.isAllowed("post", "bluetooth/server/ess/readBaseInfo"))
        assertTrue(CloudCrypto.isAllowed("POST", "api/auth/appLogin"))
        for (p in listOf("bluetooth/server/ess/writeOneParam", "bluetooth/server/device/factorySet", "bluetooth/server/device/unBind",
            "bluetooth/ota/upgrade/execute", "bluetooth/server/device/closeWifi", "bluetooth/server/cmd/activation", "bluetooth/server/hv/writeOneParam"))
            assertFalse(p, CloudCrypto.isAllowed("POST", p))
        assertFalse(CloudCrypto.isAllowed("POST", "bluetooth/server/ess/baseInfo")) // الطريقة جزء من القاعدة
    }

    @Test fun maskingHidesIdentifiersButKeepsMeasurements() {
        val m = CloudCrypto.maskJson("""{"macAddr":"AA:C2:37:0B:47:D7","soc":85,"deviceName":"5YBOP026042048200028","totalVol":52.52,"userId":123456,"token":"abcdefghijkl"}""")
        assertFalse(m.contains("37:0B")); assertFalse(m.contains("026042048")); assertFalse(m.contains("123456")); assertFalse(m.contains("cdefghij"))
        assertTrue(m.contains("\"soc\":85")); assertTrue(m.contains("\"totalVol\":52.52"))
    }
}
