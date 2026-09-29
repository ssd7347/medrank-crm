package com.mbbscrm.crm.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PhonesTest {

    @Test
    void normalisesCommonIndianFormats() {
        assertThat(Phones.normalize("+91 98765 43210")).isEqualTo("9876543210");
        assertThat(Phones.normalize("919876543210")).isEqualTo("9876543210");
        assertThat(Phones.normalize("09876543210")).isEqualTo("9876543210");
        assertThat(Phones.normalize("98765-43210")).isEqualTo("9876543210");
        assertThat(Phones.normalize(null)).isNull();
    }
}
