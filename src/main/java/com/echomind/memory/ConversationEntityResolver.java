package com.echomind.memory;

import com.echomind.intent.IntentRecognizer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ConversationEntityResolver {

    private final IntentRecognizer intentRecognizer;

    public ConversationEntityResolver(IntentRecognizer intentRecognizer) {
        this.intentRecognizer = intentRecognizer;
    }

    public Map<String, List<String>> resolve(
            Map<String, List<String>> currentEntities,
            List<ConversationMessage> recentMessages
    ) {
        Map<String, List<String>> resolved = copyEntities(currentEntities);
        if (recentMessages == null || recentMessages.isEmpty()) {
            return resolved;
        }

        for (int index = recentMessages.size() - 1; index >= 0; index--) {
            ConversationMessage message = recentMessages.get(index);
            if (message == null || message.role() != MessageRole.USER || message.content() == null) {
                continue;
            }
            Map<String, List<String>> historical = intentRecognizer.extractEntities(message.content());
            historical.forEach((name, values) -> {
                List<String> current = resolved.getOrDefault(name, List.of());
                if (current.isEmpty() && values != null && !values.isEmpty()) {
                    resolved.put(name, new ArrayList<>(values));
                }
            });
        }
        return resolved;
    }

    private Map<String, List<String>> copyEntities(Map<String, List<String>> entities) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        if (entities != null) {
            entities.forEach((name, values) -> copy.put(
                    name,
                    values == null ? new ArrayList<>() : new ArrayList<>(values)
            ));
        }
        return copy;
    }
}
