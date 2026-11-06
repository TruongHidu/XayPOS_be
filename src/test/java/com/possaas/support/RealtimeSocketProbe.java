package com.possaas.support;

import static org.assertj.core.api.Assertions.*;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.io.ByteArrayOutputStream;

/** Native TCP/STOMP test client. No mock sender or in-process broker shortcuts. */
public class RealtimeSocketProbe implements WebSocket.Listener, AutoCloseable {
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final BlockingQueue<Frame> frames=new LinkedBlockingQueue<>();
    private final StringBuilder partial=new StringBuilder(), pending=new StringBuilder();
    private final ByteArrayOutputStream binary=new ByteArrayOutputStream();
    private WebSocket socket;
    public RealtimeSocketProbe(int port,String token) throws Exception {
        socket=client.newWebSocketBuilder().subprotocols("v12.stomp")
            .buildAsync(URI.create("ws://localhost:"+port+"/ws"),this).get(10,TimeUnit.SECONDS);
        send("CONNECT\naccept-version:1.2\nhost:localhost\nheart-beat:0,0\nAuthorization:Bearer "+token+"\n\n\0");
        assertThat(next().command()).isEqualTo("CONNECTED");
    }
    public void subscribe(String id,String destination) { send("SUBSCRIBE\nid:"+id+"\ndestination:"+destination+"\nack:auto\n\n\0"); }
    public void unsubscribe(String id) { send("UNSUBSCRIBE\nid:"+id+"\n\n\0"); }
    public void send(String value) { socket.sendText(value,true).join(); }
    public Frame next() throws Exception {
        var frame=frames.poll(10,TimeUnit.SECONDS); assertThat(frame).as("Expected a real STOMP frame").isNotNull(); return frame;
    }
    public void noFrame() throws Exception { assertThat(frames.poll(300,TimeUnit.MILLISECONDS)).isNull(); }
    @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }
    @Override public CompletionStage<?> onText(WebSocket webSocket,CharSequence data,boolean last) {
        partial.append(data); if(last) { accept(partial.toString()); partial.setLength(0); }
        webSocket.request(1); return CompletableFuture.completedFuture(null);
    }
    @Override public CompletionStage<?> onBinary(WebSocket webSocket,ByteBuffer data,boolean last) {
        var bytes=new byte[data.remaining()]; data.get(bytes); binary.writeBytes(bytes);
        if(last) { accept(binary.toString(StandardCharsets.UTF_8)); binary.reset(); }
        webSocket.request(1); return CompletableFuture.completedFuture(null);
    }
    private void accept(String text) {
        pending.append(text.replace("\r\n","\n")); int end;
        while((end=pending.indexOf("\0"))>=0) {
            String frame=pending.substring(0,end).stripLeading(); pending.delete(0,end+1);
            int split=frame.indexOf("\n\n"); if(split<0) continue;
            var lines=frame.substring(0,split).split("\n"); Map<String,String> headers=new LinkedHashMap<>();
            for(int i=1;i<lines.length;i++) { int colon=lines[i].indexOf(':'); if(colon>0) headers.put(lines[i].substring(0,colon),lines[i].substring(colon+1)); }
            frames.add(new Frame(lines[0],Map.copyOf(headers),frame.substring(split+2)));
        }
    }
    @Override public void close() { if(socket!=null) socket.abort(); client.close(); }
    public record Frame(String command,Map<String,String> headers,String body) {}
}
