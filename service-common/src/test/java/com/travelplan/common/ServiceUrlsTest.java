package com.travelplan.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceUrlsTest {
    @Test void removesEveryTrailingSlashAndNothingElse() {
        assertThat(ServiceUrls.withoutTrailingSlashes("http://travel-service:8083/")).isEqualTo("http://travel-service:8083");
        assertThat(ServiceUrls.withoutTrailingSlashes("http://travel-service:8083///")).isEqualTo("http://travel-service:8083");
        assertThat(ServiceUrls.withoutTrailingSlashes("http://travel-service:8083")).isEqualTo("http://travel-service:8083");
        assertThat(ServiceUrls.withoutTrailingSlashes("http://host/base/path/")).isEqualTo("http://host/base/path");
        assertThat(ServiceUrls.withoutTrailingSlashes("///")).isEmpty();
        assertThat(ServiceUrls.withoutTrailingSlashes("")).isEmpty();
    }

    @Test void handlesHugeInputsInLinearTime() {
        String hostile = "/".repeat(1_000_000) + "x" + "/".repeat(1_000_000);
        assertThat(ServiceUrls.withoutTrailingSlashes(hostile)).hasSize(1_000_001);
    }
}
