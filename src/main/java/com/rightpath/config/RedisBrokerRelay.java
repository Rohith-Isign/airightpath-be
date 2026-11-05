package com.rightpath.config;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.support.AbstractSubscribableChannel;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.util.MimeType;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;

/**
 * Lets STOMP messages cross pods.
 *
 * <p>The in-memory simple broker only knows the sessions connected to its own pod, so with more than
 * one replica a message published on pod A never reaches a subscriber on pod B (for example the
 * candidate's desktop and phone landing on different pods). Redis cannot act as a STOMP broker, so
 * the simple broker stays and this relay stitches the pods together: every message the application
 * sends to a {@code /topic}, {@code /queue} or {@code /user} destination is also published on a Redis
 * channel, and every other pod feeds it into its own local broker. Pods that hold no matching
 * subscription simply drop it.</p>
 *
 * <p>Off unless {@code app.websocket.redis-relay.enabled=true}, so a single replica needs no Redis.</p>
 */
@Configuration
@ConditionalOnProperty(name = "app.websocket.redis-relay.enabled", havingValue = "true")
public class RedisBrokerRelay {

    private static final Logger log = LoggerFactory.getLogger(RedisBrokerRelay.class);

    /** Header set on messages injected from another pod, so they are not published a second time. */
    static final String RELAYED_HEADER = "rightpathRelayedFrom";

    /** This pod's identity; a pod ignores its own messages when they come back from Redis. */
    private final String podId = UUID.randomUUID().toString();

    private final ObjectMapper mapper = new ObjectMapper();

    private final AbstractSubscribableChannel brokerChannel;
    private final StringRedisTemplate redis;
    private final String channel;

    public RedisBrokerRelay(@Lazy @Qualifier("brokerChannel") AbstractSubscribableChannel brokerChannel,
            StringRedisTemplate redis,
            @Value("${app.websocket.redis-relay.channel:rightpath:ws-relay}") String channel) {
        this.brokerChannel = brokerChannel;
        this.redis = redis;
        this.channel = channel;
    }

    @PostConstruct
    void publishOutgoing() {
        brokerChannel.addInterceptor(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel ch) {
                try {
                    publish(message);
                } catch (Exception e) {
                    // Local delivery must not depend on Redis being up.
                    log.warn("Could not relay websocket message through Redis", e);
                }
                return message;
            }
        });
        log.info("Redis websocket relay enabled on channel {} (pod {})", channel, podId);
    }

    @Bean
    RedisMessageListenerContainer wsRelayListenerContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((msg, pattern) -> {
            try {
                deliverLocally(new String(msg.getBody(), StandardCharsets.UTF_8));
            } catch (Exception e) {
                log.warn("Could not deliver relayed websocket message", e);
            }
        }, new ChannelTopic(channel));
        return container;
    }

    private void publish(Message<?> message) throws Exception {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.wrap(message);
        String destination = accessor.getDestination();
        if (accessor.getMessageType() != SimpMessageType.MESSAGE
                || destination == null
                || accessor.getHeader(RELAYED_HEADER) != null
                // The second hop of a /user message, already resolved to this pod's own sessions.
                || accessor.getHeader(SimpMessageHeaderAccessor.ORIGINAL_DESTINATION) != null
                || !(destination.startsWith("/topic") || destination.startsWith("/queue")
                        || destination.startsWith("/user"))
                || !(message.getPayload() instanceof byte[] payload)) {
            return;
        }
        MimeType contentType = accessor.getContentType();
        String json = mapper.writeValueAsString(Map.of(
                "origin", podId,
                "destination", destination,
                "contentType", contentType == null ? "" : contentType.toString(),
                "payload", Base64.getEncoder().encodeToString(payload)));
        redis.convertAndSend(channel, json);
    }

    @SuppressWarnings("unchecked")
    private void deliverLocally(String json) throws Exception {
        Map<String, String> envelope = mapper.readValue(json, Map.class);
        if (podId.equals(envelope.get("origin"))) {
            return;
        }
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setDestination(envelope.get("destination"));
        String contentType = envelope.get("contentType");
        if (contentType != null && !contentType.isEmpty()) {
            accessor.setContentType(MimeType.valueOf(contentType));
        }
        accessor.setHeader(RELAYED_HEADER, envelope.get("origin"));
        accessor.setLeaveMutable(true);
        byte[] payload = Base64.getDecoder().decode(envelope.get("payload"));
        brokerChannel.send(MessageBuilder.createMessage(payload, accessor.getMessageHeaders()));
    }
}
