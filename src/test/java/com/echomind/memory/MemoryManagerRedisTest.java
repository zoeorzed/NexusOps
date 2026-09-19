package com.echomind.memory;

import com.echomind.config.EchoMindProperties;
import com.echomind.llm.LlmGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named="redis.test.port", matches="\\d+")
class MemoryManagerRedisTest {
    @TempDir Path directory;
    LettuceConnectionFactory factory;
    StringRedisTemplate redis;
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    String user = "audit-" + UUID.randomUUID();
    @BeforeEach void connect() {
        factory = new LettuceConnectionFactory("127.0.0.1",Integer.parseInt(System.getProperty("redis.test.port")));
        factory.afterPropertiesSet(); factory.start();
        redis = new StringRedisTemplate(factory);
    }
    @AfterEach void close() {
        redis.delete(List.of("wm:"+user+":c", "summary:"+user+":c", "wm:"+user+":other"));
        factory.destroy();
    }
    MemoryManager manager(LlmGateway llm) {
        EchoMindProperties props = new EchoMindProperties();
        props.getMemory().setCompressAt(10);
        props.getStorage().setMemoryPath(directory.resolve("memory.json").toString());
        return new MemoryManager(redis,mapper,props,llm);
    }
    void fill(MemoryManager memory) {
        for(int i=1;i<=10;i++) memory.addMessage(user,"c",MessageRole.USER,"message-"+i);
    }
    @Test void compressionPreservesChronologyAndSummaryTtl() {
        MemoryManager memory = manager((s,p,t,m) -> "历史摘要");
        fill(memory);
        assertThat(memory.getWorkingMemory(user,"c")).extracting(ConversationMessage::content)
                .containsExactly("message-6","message-7","message-8","message-9","message-10");
        assertThat(redis.opsForValue().get("summary:"+user+":c")).isEqualTo("历史摘要");
        assertThat(redis.getExpire("summary:"+user+":c")).isPositive();
    }
    @Test void appendDuringModelCallAbortsStaleCompressionWithoutLosingMessages() {
        MemoryManager memory = manager((s,p,t,m) -> {
            try {
                redis.opsForList().leftPush("wm:"+user+":c",mapper.writeValueAsString(
                        new ConversationMessage(MessageRole.USER,"concurrent",Instant.now(),Map.of())));
            } catch(Exception e) { throw new RuntimeException(e); }
            return "stale summary";
        });
        fill(memory);
        assertThat(memory.getWorkingMemory(user,"c")).hasSize(11)
                .extracting(ConversationMessage::content).endsWith("message-10","concurrent");
        assertThat(redis.hasKey("summary:"+user+":c")).isFalse();
    }
    @Test void failedSummaryLeavesOriginalMessagesIntact() {
        MemoryManager memory = manager((s,p,t,m) -> {throw new IllegalStateException("model offline");});
        fill(memory);
        assertThat(memory.getWorkingMemory(user,"c")).hasSize(10);
        assertThat(redis.hasKey("summary:"+user+":c")).isFalse();
    }
    @Test void recentMessagesAreSeparatedByConversation() {
        MemoryManager memory = manager((s,p,t,m) -> "summary");
        memory.addMessage(user,"c",MessageRole.USER,"订单A");
        memory.addMessage(user,"other",MessageRole.USER,"订单B");
        assertThat(memory.getWorkingMemory(user,"c")).extracting(ConversationMessage::content).containsExactly("订单A");
        assertThat(memory.getWorkingMemory("another-"+user,"c")).isEmpty();
    }
}
