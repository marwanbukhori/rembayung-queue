package dev.marwan.console.metrics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromJsonTest {

    @Test
    void aMatrixBecomesOneSeriesPerLabelValue() {
        String json = """
            {"status":"success","data":{"resultType":"matrix","result":[
              {"metric":{"pod":"booking-a"},"values":[[1790342100,"1"],[1790342115,"3"]]},
              {"metric":{"pod":"booking-b"},"values":[[1790342115,"5"]]}]}}""";

        List<Series> series = PromJson.matrix(json, "pod");

        assertThat(series).extracting(Series::label).containsExactly("booking-a", "booking-b");
        assertThat(series.getFirst().points()).hasSize(2);
        assertThat(series.getFirst().points().get(1)).containsExactly(1790342115, 3);
    }

    /** Review focus 5: histogram_quantile over empty buckets yields NaN; it is dropped, not drawn at 0. */
    @Test
    void notANumberAndInfinityAreDropped() {
        String json = """
            {"status":"success","data":{"resultType":"matrix","result":[
              {"metric":{"job":"queue-gate"},"values":[[1,"NaN"],[2,"+Inf"],[3,"0.25"]]}]}}""";

        assertThat(PromJson.matrix(json, "job").getFirst().points()).hasSize(1);
    }

    @Test
    void aSeriesWithNoUsablePointsIsLeftOut() {
        String json = """
            {"status":"success","data":{"resultType":"matrix","result":[
              {"metric":{"job":"queue-gate"},"values":[[1,"NaN"]]}]}}""";

        assertThat(PromJson.matrix(json, "job")).isEmpty();
    }

    @Test
    void anErrorAnswerIsAnException() {
        assertThatThrownBy(() -> PromJson.matrix("{\"status\":\"error\",\"error\":\"bad query\"}", "job"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("bad query");
    }
}
