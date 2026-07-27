package com.neocopier;

import com.neocopier.service.UpstoxService;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

public class UpstoxServiceTest {

    @Test
    public void testUpstoxCandles() {
        UpstoxService upstoxService = new UpstoxService();
        upstoxService.init();
        upstoxService.downloadAndIndexUpstoxInstrumentsAsync();

        try {
            Thread.sleep(5000); // Wait for indexer to complete
        } catch (InterruptedException ignored) {}

        String key1 = "NSE_INDEX|Nifty 50";
        List<Map<String, Object>> candles1 = upstoxService.fetchHistoricalCandles(key1, "1m");
        System.out.println("[TEST] Candles for " + key1 + ": " + candles1.size());

        String key2 = upstoxService.resolveInstrumentKey("63939", null);
        System.out.println("[TEST] Resolved key for 63939: " + key2);

        Map<String, Object> testRes = upstoxService.testHistoricalCandles(key1);
        System.out.println("[TEST] Test candles response status: " + testRes.get("statusCode"));
    }
}
