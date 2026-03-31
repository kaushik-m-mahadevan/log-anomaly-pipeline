package com.logpipeline.model;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

class LogLevelTest {

    @ParameterizedTest(name = "{0}.isError() == true")
    @EnumSource(value = LogLevel.class, names = {"ERROR", "FATAL"})
    void isError_returnsTrue_forErrorAndFatal(LogLevel level) {
        assertThat(level.isError()).isTrue();
    }

    @ParameterizedTest(name = "{0}.isError() == false")
    @EnumSource(value = LogLevel.class, names = {"DEBUG", "INFO", "WARN"})
    void isError_returnsFalse_forNonErrorLevels(LogLevel level) {
        assertThat(level.isError()).isFalse();
    }
}
