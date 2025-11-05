package tech.amak.portbuddy.server.tunnel;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tech.amak.portbuddy.common.tunnel.HttpTunnelMessage;

@Slf4j
@Component
@RequiredArgsConstructor
public class TunnelWebSocketHandler extends TextWebSocketHandler {

  private final TunnelRegistry registry;
  private final ObjectMapper mapper = new ObjectMapper();

  @Override
  public void afterConnectionEstablished(WebSocketSession session) throws Exception {
    final var uri = session.getUri();
    final var tunnelId = extractTunnelId(uri);
    if (tunnelId == null || !registry.attachSession(tunnelId, session)) {
      log.warn("Tunnel not found for id={}", tunnelId);
      session.close(CloseStatus.NORMAL);
      return;
    }
    log.info("Tunnel session established: {}", tunnelId);
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
    final var uri = session.getUri();
    final var tunnelId = extractTunnelId(uri);
    final var msg = mapper.readValue(message.getPayload(), HttpTunnelMessage.class);
    if (msg.getType() == HttpTunnelMessage.Type.RESPONSE) {
      registry.onResponse(tunnelId, msg);
    } else {
      log.debug("Ignoring unexpected message type from client: {}", msg.getType());
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
    final var uri = session.getUri();
    final var tunnelId = extractTunnelId(uri);
    final var t = registry.getByTunnelId(tunnelId);
    if (t != null) {
      t.setSession(null);
      log.info("Tunnel session closed: {}", tunnelId);
    }
  }

  private String extractTunnelId(URI uri) {
    if (uri == null) return null;
    final var path = uri.getPath();
    if (path == null) return null;
    final var parts = path.split("/");
    return parts.length > 0 ? parts[parts.length - 1] : null;
  }
}
