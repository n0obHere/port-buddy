package tech.amak.portbuddy.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;
import tech.amak.portbuddy.common.tunnel.HttpTunnelMessage;

@Slf4j
@RequiredArgsConstructor
public class HttpTunnelClient {

  private final String serverUrl; // e.g. https://api.port-buddy.com
  private final String tunnelId;
  private final String localHost;
  private final int localPort;

  private final OkHttpClient http = new OkHttpClient.Builder()
      .readTimeout(0, TimeUnit.MILLISECONDS) // keep-alive for WS
      .build();
  private final ObjectMapper mapper = new ObjectMapper();

  private WebSocket ws;
  private final CountDownLatch closeLatch = new CountDownLatch(1);

  public void runBlocking() {
    final var wsUrl = toWebSocketUrl(serverUrl, "/api/tunnel/" + tunnelId);
    final var req = new Request.Builder().url(wsUrl).build();
    ws = http.newWebSocket(req, new Listener());

    try {
      closeLatch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private String toWebSocketUrl(String base, String path) {
    var u = URI.create(base);
    var scheme = u.getScheme();
    if ("https".equalsIgnoreCase(scheme)) {
      scheme = "wss";
    } else if ("http".equalsIgnoreCase(scheme)) {
      scheme = "ws";
    }
    final var hostPort = (u.getPort() == -1) ? u.getHost() : (u.getHost() + ":" + u.getPort());
    return scheme + "://" + hostPort + path;
  }

  private class Listener extends WebSocketListener {
    @Override
    public void onOpen(WebSocket webSocket, Response response) {
      log.info("Tunnel connected to server");
    }

    @Override
    public void onMessage(WebSocket webSocket, String text) {
      try {
        final var msg = mapper.readValue(text, HttpTunnelMessage.class);
        if (msg.getType() == HttpTunnelMessage.Type.REQUEST) {
          final var resp = handleRequest(msg);
          final var json = mapper.writeValueAsString(resp);
          webSocket.send(json);
        } else {
          log.debug("Ignoring non-REQUEST msg");
        }
      } catch (Exception e) {
        log.warn("Failed to process WS message: {}", e.toString());
      }
    }

    @Override
    public void onClosed(WebSocket webSocket, int code, String reason) {
      log.info("Tunnel closed: {} {}", code, reason);
      closeLatch.countDown();
    }

    @Override
    public void onFailure(WebSocket webSocket, Throwable t, Response response) {
      log.warn("Tunnel failure: {}", t.toString());
      closeLatch.countDown();
    }
  }

  private HttpTunnelMessage handleRequest(HttpTunnelMessage reqMsg) {
    final var method = reqMsg.getMethod();
    var url = "http://" + localHost + ":" + localPort + reqMsg.getPath();
    if (reqMsg.getQuery() != null && !reqMsg.getQuery().isBlank()) {
      url += "?" + reqMsg.getQuery();
    }

    final var rb = new Request.Builder().url(url).method(method, buildBody(method, reqMsg.getBodyB64()));

    if (reqMsg.getHeaders() != null) {
      for (var e : reqMsg.getHeaders().entrySet()) {
        final var name = e.getKey();
        final var value = e.getValue();
        if (name == null || value == null) continue;
        if (name.equalsIgnoreCase("Host")) continue; // Host will be set by client
        rb.addHeader(name, value);
      }
    }

    try (var resp = http.newCall(rb.build()).execute()) {
      final var respMsg = new HttpTunnelMessage();
      respMsg.setId(reqMsg.getId());
      respMsg.setType(HttpTunnelMessage.Type.RESPONSE);
      respMsg.setStatus(resp.code());
      respMsg.setRespHeaders(extractHeaders(resp));
      final var body = resp.body();
      if (body != null) {
        final var bytes = body.bytes();
        if (bytes.length > 0) {
          respMsg.setRespBodyB64(Base64.getEncoder().encodeToString(bytes));
        }
      }
      return respMsg;
    } catch (IOException e) {
      final var err = new HttpTunnelMessage();
      err.setId(reqMsg.getId());
      err.setType(HttpTunnelMessage.Type.RESPONSE);
      err.setStatus(502);
      final var headers = new HashMap<String, String>();
      headers.put("Content-Type", "text/plain; charset=utf-8");
      err.setRespHeaders(headers);
      err.setRespBodyB64(Base64.getEncoder().encodeToString(("Bad Gateway: " + e.getMessage()).getBytes(StandardCharsets.UTF_8)));
      return err;
    }
  }

  private RequestBody buildBody(String method, String bodyB64) {
    // Methods that usually don't have body
    if (bodyB64 == null) return methodSupportsBody(method) ? RequestBody.create(new byte[0], null) : null;
    final var bytes = Base64.getDecoder().decode(bodyB64);
    return RequestBody.create(bytes, MediaType.parse("application/octet-stream"));
  }

  private boolean methodSupportsBody(String method) {
    if (method == null) return false;
    return switch (method.toUpperCase()) {
      case "POST", "PUT", "PATCH" -> true;
      default -> false;
    };
  }

  private Map<String, String> extractHeaders(Response resp) {
    final var map = new HashMap<String, String>();
    for (var name : resp.headers().names()) {
      map.put(name, resp.header(name));
    }
    return map;
  }
}
