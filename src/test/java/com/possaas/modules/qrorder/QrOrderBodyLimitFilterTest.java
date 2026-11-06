package com.possaas.modules.qrorder;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.possaas.infrastructure.security.QrOrderBodyLimitFilter;
import com.possaas.infrastructure.security.QrOrderNoStoreFilter;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.*;
import java.time.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class QrOrderBodyLimitFilterTest {
    private final ObjectMapper json=new ObjectMapper();
    private final Clock clock=Clock.fixed(Instant.parse("2026-10-14T00:00:00Z"),ZoneOffset.UTC);
    private final QrOrderBodyLimitFilter filter=new QrOrderBodyLimitFilter(json,clock);

    @Test void rejectsKnownOversizeBeforeReadingAndKeepsNoStoreAndSanitizedClockTimestamp() throws Exception {
        var request=new CountingRequest(new byte[32769],32769); var response=new MockHttpServletResponse(); var continued=new AtomicBoolean();
        new QrOrderNoStoreFilter().doFilter(request,response,(req,res)->filter.doFilter(req,res,(a,b)->continued.set(true)));
        assertThat(response.getStatus()).isEqualTo(413); assertThat(continued.get()).isFalse(); assertThat(request.count).isZero();
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        var body=json.readTree(response.getContentAsString()); assertThat(body.get("code").asText()).isEqualTo("QR_REQUEST_BODY_TOO_LARGE");
        assertThat(body.get("timestamp").asText()).isEqualTo(clock.instant().toString());
        assertThat(response.getContentAsString()).doesNotContain("32769","java.","Exception");
    }
    @Test void boundsUnknownLengthChunkedReadBeforeRejecting() throws Exception {
        var request=new CountingRequest(new byte[90000],-1); var response=new MockHttpServletResponse(); var continued=new AtomicBoolean();
        filter.doFilter(request,response,(req,res)->continued.set(true));
        assertThat(response.getStatus()).isEqualTo(413); assertThat(continued.get()).isFalse(); assertThat(request.count).isEqualTo(32769);
    }
    @Test void exactlyBudgetIsForwardedIntactWithReusableInputStream() throws Exception {
        var bytes="a".repeat(32768).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var request=new CountingRequest(bytes,-1); var response=new MockHttpServletResponse(); var continued=new AtomicBoolean();
        filter.doFilter(request,response,(req,res)-> {
            continued.set(true); var wrapped=(HttpServletRequest)req;
            assertThat(wrapped.getInputStream().readAllBytes()).isEqualTo(bytes);
            assertThat(wrapped.getInputStream().readAllBytes()).isEqualTo(bytes);
        });
        assertThat(continued.get()).isTrue(); assertThat(request.count).isEqualTo(32768);
    }
    @Test void nonQrOrderEndpointAndGetRequestsAreNotReadOrLimited() throws Exception {
        for(String path:new String[]{"/api/v1/orders","/api/v1/public/menu/tables/token"}) {
            var request=new CountingRequest(new byte[90000],90000); request.setServletPath(path); request.setRequestURI(path);
            var response=new MockHttpServletResponse(); var continued=new AtomicBoolean(); filter.doFilter(request,response,(a,b)->continued.set(true));
            assertThat(continued.get()).isTrue(); assertThat(request.count).isZero();
        }
        var request=new CountingRequest(new byte[90000],90000); request.setMethod("GET"); var continued=new AtomicBoolean();
        filter.doFilter(request,new MockHttpServletResponse(),(a,b)->continued.set(true)); assertThat(continued.get()).isTrue(); assertThat(request.count).isZero();
    }
    private static final class CountingRequest extends MockHttpServletRequest {
        final byte[] bytes; final long length; int count;
        CountingRequest(byte[] bytes,long length) {
            super("POST","/api/v1/public/qr-order/requests"); this.bytes=bytes; this.length=length;
            setServletPath("/api/v1/public/qr-order/requests");
        }
        @Override public long getContentLengthLong() { return length; }
        @Override public int getContentLength() { return (int)length; }
        @Override public ServletInputStream getInputStream() {
            var buffer=new ByteArrayInputStream(bytes);
            return new ServletInputStream() {
                @Override public int read() { int next=buffer.read(); if(next>=0) count++; return next; }
                @Override public int read(byte[] target,int offset,int size) { int n=buffer.read(target,offset,size); if(n>0) count+=n; return n; }
                @Override public boolean isFinished() { return buffer.available()==0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
            };
        }
    }
}
