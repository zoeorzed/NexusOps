package com.echomind.evaluation;

import com.echomind.agent.AgentOrchestrator;
import com.echomind.api.dto.EvalRunRequest;
import com.echomind.config.EchoMindProperties;
import com.echomind.intent.IntentRecognizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class EndToEndEvaluatorTest {

    @TempDir
    Path tempDir;

    @Test
    void savesBaselineOnlyWhenExplicitlyRequested() {
        Path baseline = tempDir.resolve("baseline.json");
        EchoMindProperties properties = new EchoMindProperties();
        properties.getEval().setBaselinePath(baseline.toString());
        EndToEndEvaluator evaluator = new EndToEndEvaluator(
                mock(IntentRecognizer.class),
                mock(AgentOrchestrator.class),
                mock(LLMJudge.class),
                new ObjectMapper(),
                properties
        );

        evaluator.run(new EvalRunRequest(List.of(), List.of(), false));
        assertThat(baseline).doesNotExist();

        evaluator.run(new EvalRunRequest(List.of(), List.of(), true));
        assertThat(baseline).exists();
    }
}
