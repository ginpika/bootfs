package cc.ginpika.bootfs.metrics;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class TrafficStatsFilterTest {
    @Test void recordsConnectionIpAndDoesNotTrustAnArbitraryForwardedHeader() throws Exception {
        RequestStatsService service = new RequestStatsService();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/f/test");
        request.setRemoteAddr("192.0.2.42");
        request.addHeader("X-Forwarded-For", "203.0.113.99");
        MockHttpServletResponse response = new MockHttpServletResponse();
        new TrafficStatsFilter(service).doFilter(request, response, (req, resp) -> {
            ((javax.servlet.http.HttpServletResponse) resp).setStatus(206);
            resp.getOutputStream().write(new byte[]{1, 2, 3});
        });
        JSONObject stats = JSON.parseObject(JSON.toJSONString(service.buildStats()));
        JSONObject recent = stats.getJSONObject("requests").getJSONArray("recent").getJSONObject(0);
        assertEquals("192.0.2.42", recent.getString("clientIp"));
        assertEquals("/f/test", recent.getString("uri"));
        assertEquals(206, recent.getIntValue("status"));
        assertEquals(3, recent.getLongValue("bytes"));
        assertEquals(1, stats.getJSONObject("traffic").getLongValue("totalRequests"));
    }

    @Test void preservesIpv6AndBoundsRecentEntriesWhileKeepingTotals() throws Exception {
        RequestStatsService service = new RequestStatsService();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test");
        request.setRemoteAddr("2001:db8::42");
        TrafficStatsFilter filter = new TrafficStatsFilter(service);
        for (int i = 0; i < 55; i++) {
            filter.doFilter(request, new MockHttpServletResponse(), (req, resp) -> {});
        }
        JSONObject stats = JSON.parseObject(JSON.toJSONString(service.buildStats()));
        var recent = stats.getJSONObject("requests").getJSONArray("recent");
        assertEquals(50, recent.size());
        assertEquals("2001:db8::42", recent.getJSONObject(0).getString("clientIp"));
        assertEquals(55, stats.getJSONObject("requests").getIntValue("total24h"));
    }
}
