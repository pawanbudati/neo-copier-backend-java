package com.neocopier;

import com.neocopier.model.Scrip;
import com.neocopier.service.UpstoxService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class UpstoxServiceTest {

    @Test
    public void testScripRefKeyStrikeExtraction() {
        UpstoxService upstoxService = new UpstoxService();

        // Test 1: BankNifty Option
        Scrip s1 = new Scrip("35177", "BANKNIFTY26AUG39700PE", "BANKNIFTY25AUG2639700.00PE", "OPTIDX", "NFO", "PE", 0.0, null, 30);
        String key1 = upstoxService.resolveInstrumentKey("35177", s1);
        assertNotNull(key1);
        assertTrue(key1.startsWith("NSE_FO|"));

        // Test 2: Nifty Option
        Scrip s2 = new Scrip("45347", "NIFTY2681828750CE", "NIFTY18AUG2628750.00CE", "OPTIDX", "NFO", "CE", 0.0, null, 65);
        String key2 = upstoxService.resolveInstrumentKey("45347", s2);
        assertNotNull(key2);
        assertTrue(key2.startsWith("NSE_FO|"));

        // Test 3: Sensex Option
        Scrip s3 = new Scrip("99999", "SENSEX26JUL77000PE", "SENSEX26JUL2477000.00PE", "OPTIDX", "BFO", "PE", 0.0, null, 10);
        String key3 = upstoxService.resolveInstrumentKey("99999", s3);
        assertNotNull(key3);

        // Test 4: Sensex Index
        Scrip s4 = new Scrip("1", "SENSEX", "SENSEX", "INDEX", "BSE", "INDEX", 0.0, null, 1);
        String key4 = upstoxService.resolveInstrumentKey("1", s4);
        assertNotNull(key4);
        assertTrue(key4.contains("SENSEX"));
    }
}
