package com.echomind.evaluation;

import com.echomind.api.dto.EvalRunRequest;
import com.echomind.intent.IntentCategory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class EvaluationDatasetContractTest {

    @Test
    void reproducibleDatasetMatchesApiContractAndKnownIntentLabels() throws Exception {
        Path dataset = Path.of("evaluation", "eval-dataset.json");
        assertThat(dataset).exists();

        ObjectMapper mapper = new ObjectMapper();
        mapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        EvalRunRequest request = mapper.readValue(Files.readString(dataset), EvalRunRequest.class);

        Set<String> knownLabels = java.util.Arrays.stream(IntentCategory.values())
                .map(value -> value.name().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        assertThat(request.intentCases()).hasSizeGreaterThanOrEqualTo(10);
        assertThat(request.intentCases())
                .allSatisfy(item -> {
                    assertThat(item.message()).isNotBlank();
                    assertThat(item.expectedIntent()).isIn(knownLabels);
                });
        assertThat(request.dialogCases()).isNotEmpty();
    }

    @Test
    void candidateSetCoversAllNineteenLabelsWithoutReusingExistingExamples() throws Exception {
        ObjectMapper mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        EvalRunRequest candidate = mapper.readValue(Files.readString(
                Path.of("evaluation", "intent-holdout-candidate-v1.json")), EvalRunRequest.class);
        EvalRunRequest legacy = mapper.readValue(Files.readString(
                Path.of("evaluation", "legacy-smoke-v1.json")), EvalRunRequest.class);
        assertThat(candidate.intentCases()).hasSize(95);
        assertThat(candidate.dialogCases()).isEmpty();
        assertThat(candidate.saveAsBaseline()).isFalse();
        Map<String, Long> counts = candidate.intentCases().stream().collect(Collectors.groupingBy(
                EvalRunRequest.IntentCase::expectedIntent, Collectors.counting()));
        Set<String> knownLabels = java.util.Arrays.stream(IntentCategory.values())
                .map(i -> i.name().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        assertThat(counts.keySet()).isEqualTo(knownLabels);
        assertThat(counts.values()).allMatch(count -> count == 5L);
        List<String> messages = candidate.intentCases().stream().map(c -> normalize(c.message())).toList();
        assertThat(messages).doesNotHaveDuplicates().doesNotContain("");
        assertThat(messages).doesNotContainAnyElementsOf(legacy.intentCases().stream()
                .map(c -> normalize(c.message())).toList());
        String source = Files.readString(Path.of("src/main/java/com/echomind/intent/IntentRecognizer.java"));
        Set<String> literals = Pattern.compile("\"([^\"\\r\\n]*)\"").matcher(source).results()
                .map(match -> normalize(match.group(1))).collect(Collectors.toSet());
        assertThat(messages).doesNotContainAnyElementsOf(literals);
    }

    @Test
    void legacyCopyPreservesOriginalTwelveCasesAndFiveDialogTurns() throws Exception {
        ObjectMapper mapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        var original = mapper.readTree(Files.readString(Path.of("evaluation", "eval-dataset.json")));
        var legacyTree = mapper.readTree(Files.readString(Path.of("evaluation", "legacy-smoke-v1.json")));
        assertThat(legacyTree).isEqualTo(original);
        EvalRunRequest legacy = mapper.treeToValue(legacyTree, EvalRunRequest.class);
        assertThat(legacy.intentCases()).hasSize(12);
        assertThat(legacy.dialogCases().stream().mapToInt(c -> c.turns() == null ? 1 : c.turns().size()).sum())
                .isEqualTo(5);
    }

    private String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{Z}\\s]+", "");
    }
}
