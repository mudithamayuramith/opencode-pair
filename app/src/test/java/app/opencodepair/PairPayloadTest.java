package app.opencodepair;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

/** Covers every shape the pairing input can take: QR JSON, printed links, pasted text. */
public class PairPayloadTest {

    private static final String CODE = "qcW5CRLJdHE5VGOKkxsWvA";
    private static final String LAN = "http://192.168.8.102:45278";
    private static final String TUNNEL = "https://38721ba75f5db94b.ba4h4ug6wkhd.opentunnel.xyz";
    private static final String LOOPBACK = "http://127.0.0.1:45278";

    /** Exactly what the QR code contains: base URLs, no /auth/connect path. */
    @Test public void readsQrJsonWithBaseUrls() {
        String qr = "{\"code\":\"" + CODE + "\",\"urls\":[\"" + LAN + "\",\"" + TUNNEL + "\"]}";

        List<String> urls = PairPayload.parse(qr);

        assertEquals(2, urls.size());
        assertEquals(LAN + "/auth/connect/" + CODE, urls.get(0));
        assertEquals(TUNNEL + "/auth/connect/" + CODE, urls.get(1));
    }

    /** The three links printed under the QR are already complete. */
    @Test public void passesThroughPrintedLinks() {
        String lan = LAN + "/auth/connect/" + CODE;
        String tunnel = TUNNEL + "/auth/connect/" + CODE;
        String loopback = LOOPBACK + "/auth/connect/" + CODE;

        assertEquals(Collections.singletonList(lan), PairPayload.parse(lan));
        assertEquals(Collections.singletonList(tunnel), PairPayload.parse(tunnel));
        assertEquals(Collections.singletonList(loopback), PairPayload.parse(loopback));
    }

    @Test public void acceptsJsonThatAlreadyHasFullLinks() {
        String qr = "{\"urls\":[\"" + LAN + "/auth/connect/" + CODE + "\"]}";

        assertEquals(Collections.singletonList(LAN + "/auth/connect/" + CODE), PairPayload.parse(qr));
    }

    @Test public void acceptsSingleUrlFieldAndTrailingSlash() {
        String qr = "{\"code\":\"" + CODE + "\",\"url\":\"" + LAN + "/\"}";

        assertEquals(Collections.singletonList(LAN + "/auth/connect/" + CODE), PairPayload.parse(qr));
    }

    /** 127.0.0.1 can only work on the PC, so it is tried after the reachable addresses. */
    @Test public void movesLoopbackLast() {
        String qr = "{\"code\":\"" + CODE + "\",\"urls\":[\"" + LOOPBACK + "\",\"" + LAN + "\"]}";

        List<String> urls = PairPayload.parse(qr);

        assertEquals(2, urls.size());
        assertEquals(LAN + "/auth/connect/" + CODE, urls.get(0));
        assertEquals(LOOPBACK + "/auth/connect/" + CODE, urls.get(1));
    }

    @Test public void findsLinkInsideSurroundingText() {
        String text = "Open a link to connect: " + LAN + "/auth/connect/" + CODE + ", expires in 5 minutes.";

        assertEquals(Collections.singletonList(LAN + "/auth/connect/" + CODE), PairPayload.parse(text));
    }

    @Test public void rejectsGarbage() {
        assertTrue(PairPayload.parse("").isEmpty());
        assertTrue(PairPayload.parse("   ").isEmpty());
        assertTrue(PairPayload.parse("hello world").isEmpty());
        assertTrue(PairPayload.parse("{\"foo\":1}").isEmpty());
        assertTrue(PairPayload.parse(null).isEmpty());
    }

    @Test public void recognisesLoopback() {
        assertTrue(PairPayload.isLoopback(LOOPBACK + "/auth/connect/" + CODE));
        assertTrue(PairPayload.isLoopback("http://localhost:45278"));
        assertTrue(PairPayload.isLoopback("http://127.5.5.5/"));
        assertFalse(PairPayload.isLoopback(LAN));
        assertFalse(PairPayload.isLoopback(TUNNEL));
        assertFalse(PairPayload.isLoopback("http://10.0.0.2:45278"));
    }

    @Test public void recognisesPairingLinks() {
        assertTrue(PairPayload.isPairingLink(LAN + "/auth/connect/" + CODE));
        assertFalse(PairPayload.isPairingLink(LAN + "/"));
    }
}
