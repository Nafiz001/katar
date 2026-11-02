package dev.nafiz.katar.web;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * A token bucket per client address on the endpoints a bot would hammer:
 * joining, holding and booking. Ten requests a second sustained, bursts of
 * twenty. In memory, which is enough for one instance; several instances
 * would share buckets in Redis instead.
 */
@Component
public class RateLimiter implements HandlerInterceptor {

    private static final double PER_SECOND = 10;
    private static final double BURST = 20;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        if (!"POST".equals(req.getMethod()) && !"DELETE".equals(req.getMethod())) {
            return true;
        }
        if (buckets.size() > 100_000) {
            buckets.clear(); // crude bound on memory under a flood of addresses
        }
        Bucket b = buckets.computeIfAbsent(client(req), k -> new Bucket());
        if (b.take(System.nanoTime())) {
            return true;
        }
        res.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        res.setHeader("Retry-After", "1");
        res.setContentType("application/json");
        res.getWriter().write("{\"error\":{\"code\":\"rate_limited\",\"message\":\"too many requests; slow down\"}}");
        return false;
    }

    /**
     * The client's address. Behind the reverse proxy (Caddy, on a private
     * address) the real one is the first X-Forwarded-For entry; a request
     * straight from the internet cannot pick its own bucket that way.
     */
    static String client(HttpServletRequest req) {
        String remote = req.getRemoteAddr();
        String forwarded = req.getHeader("X-Forwarded-For");
        if (forwarded != null && isPrivate(remote)) {
            return forwarded.split(",")[0].trim();
        }
        return remote;
    }

    private static boolean isPrivate(String ip) {
        return ip.startsWith("10.") || ip.startsWith("192.168.") || ip.startsWith("127.") || ip.equals("0:0:0:0:0:0:0:1")
                || ip.matches("^172\\.(1[6-9]|2\\d|3[01])\\..*");
    }

    private static final class Bucket {
        private double tokens = BURST;
        private long last = System.nanoTime();

        synchronized boolean take(long now) {
            tokens = Math.min(BURST, tokens + (now - last) / 1e9 * PER_SECOND);
            last = now;
            if (tokens >= 1) {
                tokens -= 1;
                return true;
            }
            return false;
        }
    }
}
