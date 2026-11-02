package cc.ginpika.bootfs.metrics;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class TrafficStatsFilterTest {
    @Test void bufferedXmlAndJsonErrorsRetainTheirBodyAndExactUtf8ByteCount() throws Exception {
        for (String body : new String[]{"<Error><Code>AccessDenied</Code><Message>拒绝访问</Message></Error>",
                "{\"error\":\"未获授权 🔒\"}"}) {
            RequestStatsService service = new RequestStatsService();
            MockHttpServletResponse response = new MockHttpServletResponse();
            new TrafficStatsFilter(service).doFilter(new MockHttpServletRequest("GET", "/s3"), response, (req, resp) -> {
                ((HttpServletResponse) resp).setStatus(403);
                resp.setCharacterEncoding("UTF-8");
                resp.getWriter().write(body);
            });
            assertEquals(403, response.getStatus());
            assertArrayEquals(body.getBytes(StandardCharsets.UTF_8), response.getContentAsByteArray());
            JSONObject stats = JSON.parseObject(JSON.toJSONString(service.buildStats()));
            assertEquals(body.getBytes(StandardCharsets.UTF_8).length,
                    stats.getJSONObject("requests").getJSONArray("recent").getJSONObject(0).getLongValue("bytes"));
        }
    }

    @Test void explicitFlushBufferEmitsPendingCharactersBeforeReturningToTheHandler() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new TrafficStatsFilter(new RequestStatsService()).doFilter(new MockHttpServletRequest(), response, (req, resp) -> {
            resp.setCharacterEncoding("UTF-8");
            resp.getWriter().write("已完成");
            resp.flushBuffer();
            assertEquals("已完成", response.getContentAsString());
            assertTrue(response.isCommitted());
        });
    }

    @Test void failedOrAsyncRequestsAreNotCommittedWhenTheInitialFilterInvocationEnds() throws Exception {
        MockHttpServletResponse failed = new MockHttpServletResponse();
        assertThrows(ServletException.class, () -> new TrafficStatsFilter(new RequestStatsService())
                .doFilter(new MockHttpServletRequest(), failed, (req, resp) -> {
                    resp.getWriter().write("partial response");
                    throw new ServletException("handler failed");
                }));
        assertFalse(failed.isCommitted());
        assertEquals(0, failed.getContentAsByteArray().length);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAsyncSupported(true);
        MockHttpServletResponse async = new MockHttpServletResponse();
        new TrafficStatsFilter(new RequestStatsService()).doFilter(request, async, (req, resp) -> {
            resp.getWriter().write("pending async response");
            req.startAsync(req, resp);
        });
        assertFalse(async.isCommitted());
        assertEquals(0, async.getContentAsByteArray().length);
    }

    @Test void resetBufferKeepsTheAcquiredWriterAndDiscardsPendingCharacters() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new TrafficStatsFilter(new RequestStatsService()).doFilter(new MockHttpServletRequest(), response, (req, resp) -> {
            var writer = resp.getWriter();
            writer.write("discard me");
            resp.resetBuffer();
            writer.write("replacement");
        });
        assertEquals("replacement", response.getContentAsString());
    }

    @Test void errorDispatchesAndRedirectsDiscardTheWrappersPendingBody() throws Exception {
        for (boolean redirect : new boolean[]{false, true}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            new TrafficStatsFilter(new RequestStatsService()).doFilter(new MockHttpServletRequest(), response, (req, resp) -> {
                resp.getWriter().write("discard me");
                if (redirect) ((HttpServletResponse) resp).sendRedirect("/login");
                else ((HttpServletResponse) resp).sendError(500, "handler failed");
            });
            assertEquals(redirect ? 302 : 500, response.getStatus());
            assertEquals(0, response.getContentAsByteArray().length);
        }
    }

    @Test void resettingTheResponseDiscardsTheOldWriterAndAllowsANewEncoding() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        new TrafficStatsFilter(new RequestStatsService()).doFilter(new MockHttpServletRequest(), response, (req, resp) -> {
            resp.getWriter().write("discard me");
            resp.reset();
            resp.setCharacterEncoding("UTF-8");
            resp.getWriter().write("新的响应");
        });
        assertArrayEquals("新的响应".getBytes(StandardCharsets.UTF_8), response.getContentAsByteArray());
    }

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
