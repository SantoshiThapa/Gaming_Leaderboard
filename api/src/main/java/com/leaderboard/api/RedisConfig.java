package com.leaderboard.api;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.nio.charset.StandardCharsets;

@Configuration
public class RedisConfig {

    /** Every API instance listens on the Pub/Sub channel, so any write on any node reaches every WebSocket client. */
    @Bean
    RedisMessageListenerContainer redisListenerContainer(RedisConnectionFactory factory,
                                                         LeaderboardSocketHandler handler) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(factory);
        container.addMessageListener(
                (message, pattern) -> handler.markDirty(new String(message.getBody(), StandardCharsets.UTF_8)),
                new ChannelTopic(ScoreService.CHANNEL));
        return container;
    }
}
